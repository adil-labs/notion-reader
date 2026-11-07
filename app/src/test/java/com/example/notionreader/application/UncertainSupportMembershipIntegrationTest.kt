package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One bounded Note scan can be uncertain about one canonical concept and establish verified
 * support for another, leaving a deliberately unchecked tail. Termination, support, uncertainty
 * and the tail must all survive checkpointing, restart, replay and later tail reconciliation.
 */
class UncertainSupportMembershipIntegrationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun uncertainSupportAndTailSurviveCheckpointRestartReplayAndTailReconciliation() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val queries = FileTopicRetrievalStore(root.resolve("queries"))
        val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        val generator = verifier.copy(prompt = "proposal")
        val policy = TopicRetrievalPolicy(.8f)
        val topicPolicy = TopicPolicy()

        suspend fun add(id: String, passages: List<Pair<String, List<Float>>>): SemanticRecord {
            val doc = ReaderDocument.fromChunkTexts(id, id, passages.map { it.first })
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val embedded = passages.mapIndexed { order, (text, vector) ->
                EmbeddedPassage(SemanticPassage("$id:p$order", id, "$id:s$order", "Section", "$id:b$order",
                    "$id:c$order", 0, text.length, text, text, order), vector)
            }
            return SemanticRecord(id, semanticRevision(doc), model, SemanticMetadata(id), embedded)
                .also { assertTrue(index.write(it)) }
        }
        val seedText = "Moisture and warmth help seeds germinate and begin growing."
        val chessA = "Calculate targets before considering candidate moves."
        val chessB = "Recheck the target after every candidate move."
        val seed = add("seed", listOf(seedText to listOf(1f, 0f)))
        val later = add("later", listOf(
            seedText to listOf(1f, 0f),
            chessA to listOf(0f, 1f),
            chessB to listOf(0f, 1f)))
        index.setPaused(false)
        val sources = mapOf(seed.pageId to seed.revision, later.pageId to later.revision)

        // Concept A: the seed Note supports it, so it is already verified. Concept B: the `later`
        // Note's first chess passage is the citation, and it has a second eligible chess passage
        // that support-first scanning deliberately leaves as an unchecked tail.
        val nameA = "Seed germination"
        val defA = "Moisture and warmth help seeds germinate."
        val nameB = "Chess calculation targets"
        val defB = "Identify targets before candidate moves."
        val evidenceA = TopicLabelEvidence(seed.passages.single().passage.id, "seed", seed.revision, seedText)
        val evidenceB = TopicLabelEvidence(later.passages[1].passage.id, "later", later.revision, chessA)
        suspend fun concept(proposalKey: String, name: String, definition: String,
            listening: String, evidence: TopicLabelEvidence): Pair<String, TopicSupportAssessment> {
            val request = DiscoveryProposalRequest(proposalKey, 0, listOf(evidence), generator)
            val response = DiscoveryProposalResponse.Concept(name, definition, listening, listOf(evidence.passageId))
            val key = (validateDiscoveryProposal(request, response, 0, sources)
                as DiscoveryProposalValidation.Accepted).workKey
            // Admission-time citations are assessed under the pre-admission identity. The catalog
            // record stores those exact citations; hydration re-validates them rather than trusting
            // a canonical copy.
            val supportRequest = TopicSupportRequest("pending:$key", key, name, definition, evidence, verifier, 0)
            val citation = (validateTopicSupport(supportRequest, SupportDecision.SUPPORTED, 0, key, sources)
                as TopicSupportValidation.Accepted).assessment
            assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(citation) { true })
            return key to citation
        }
        val (keyA, citationA) = concept(semanticHash("proposal-A"), nameA, defA, "Listen about seeds.", evidenceA)
        val (keyB, citationB) = concept(semanticHash("proposal-B"), nameB, defB,
            "Listen about chess calculation.", evidenceB)

        val queryA = TopicQueryEmbedding(TopicRetrievalQuery("topicA", keyA, "$nameA\n$defA", model, policy), listOf(1f, 0f))
        val queryB = TopicQueryEmbedding(TopicRetrievalQuery("topicB", keyB, "$nameB\n$defB", model, policy), listOf(0f, 1f))
        val seedPassage = seed.passages.single()
        val chessPassage = later.passages[1]
        val conceptA = CanonicalConceptEvidence(keyA, nameA, defA, "Listen about seeds.", generator, verifier,
            listOf(citationA.evidence), listOf(citationA), listOf(citationA.evidence), mapOf("seed" to seed.revision),
            matchingComplete = false, proposalKey = semanticHash("proposal-A"), queryEmbedding = queryA)
        val conceptB = CanonicalConceptEvidence(keyB, nameB, defB, "Listen about chess calculation.", generator, verifier,
            listOf(citationB.evidence), listOf(citationB), emptyList(), mapOf("seed" to seed.revision),
            matchingComplete = false, proposalKey = semanticHash("proposal-B"), queryEmbedding = queryB)
        val topicA = DiscoveredTopic("topicA", nameA, seedPassage.passage, seedPassage,
            listOf(TopicMembership("seed", seed.metadata, seedPassage.passage, 1f, seedPassage.passage, seed.revision)),
            1, canonicalConcept = conceptA,
            canonicalProjection = CanonicalTopicProjection(keyA, nameA, defA, "Listen about seeds.", emptyList(), null))
        val topicB = DiscoveredTopic("topicB", nameB, chessPassage.passage, chessPassage,
            listOf(TopicMembership("later", later.metadata, chessPassage.passage, 1f, chessPassage.passage, later.revision)),
            2, canonicalConcept = conceptB,
            canonicalProjection = CanonicalTopicProjection(keyB, nameB, defB, "Listen about chess calculation.", emptyList(), null))
        val backing = FileTopicStore(root.resolve("topics"))
        assertEquals(TopicWriteResult.Written, backing.writeDetailed(TopicSnapshot(
            topics = listOf(topicA, topicB), identity = model, canonicalCatalogReady = true,
            acceptedRevisions = sources, policyVersion = topicPolicy.version)))

        val retrievals = TopicRetrievalCoordinator(index, queries, { model }, policy, { listOf(1f, 0f) }, { true }, {}, Mutex())
        fun coordinator() = TopicCoordinator(cache, index, backing, null, { model }, topicPolicy, Mutex(), { true }, {}, {},
            retrievals = retrievals, proposalProgress = progress)
        var worker = coordinator()
        worker.invalidate(sources)
        var ticket = worker.captureCatalogTicket()!!
        var calls = 0
        val verify: suspend (TopicSupportRequest) -> TopicSupportRun = { request ->
            calls++
            when (request.conceptWorkKey) {
                keyB -> TopicSupportRun.Completed(SupportDecision.SUPPORTED)
                else -> TopicSupportRun.Completed(SupportDecision.UNCERTAIN)
            }
        }

        // Page 1: concept A is uncertain (no support). Page 2: concept B is supported early, so its
        // second chess passage is deliberately left unchecked.
        assertTrue(worker.advanceExistingConceptWork(ticket, later, sources, verifier, 8, verify = verify)
            is DurableConceptScanResult.Pending)
        val early = worker.advanceExistingConceptWork(ticket, later, sources, verifier, 8, verify = verify)
            as DurableConceptScanResult.AwaitingValidation
        val checkpoint = early.record
        assertEquals("Termination is early support, not uncertainty", ConceptScanCompletion.SUPPORTED_EARLY,
            checkpoint.completion)
        assertEquals(setOf(keyB), checkpoint.earlyConceptWorkKeys)
        assertEquals(setOf(SupportDecision.SUPPORTED, SupportDecision.UNCERTAIN),
            checkpoint.assessments.map { it.decision }.toSet())
        assertEquals(2, checkpoint.assessments.size)
        assertEquals("later:p1" to SupportDecision.SUPPORTED,
            checkpoint.assessments.single { it.conceptWorkKey == keyB }.evidence.passageId to
                checkpoint.assessments.single { it.conceptWorkKey == keyB }.decision)
        assertEquals(SupportDecision.UNCERTAIN,
            checkpoint.assessments.single { it.conceptWorkKey == keyA }.decision)
        val callsBeforeRestart = calls

        // Replay must reconstruct support and uncertainty from the exact cached decisions and
        // never call the verifier.
        suspend fun replayAll(): ConceptScanReplayResult {
            var state = ConceptScanReplay.start()
            while (true) {
                val result = worker.replayExistingConceptWork(ticket, later, sources, verifier, checkpoint, state, 8)
                if (result is ConceptScanReplayResult.Pending) state = result.replay else return result
            }
        }
        val replayed = replayAll()
        assertTrue("Replay must validate the same checkpoint", replayed is ConceptScanReplayResult.Validated)
        val validated = replayed as ConceptScanReplayResult.Validated
        assertEquals(setOf("later:p1"), validated.supported)
        assertEquals(setOf("later:p0"), validated.uncertain)
        assertEquals("Replay performs zero fresh inference", callsBeforeRestart, calls)

        // Restart before the catalog write: the finished checkpoint must replay from the durable
        // support decisions alone, without a verifier call, and preserve both evidence facts.
        worker = coordinator()
        worker.invalidate(sources)
        ticket = worker.captureCatalogTicket()!!
        val restored = worker.advanceExistingConceptWork(ticket, later, sources, verifier, 8) {
            error("A restarted checkpoint must not call the verifier")
        } as DurableConceptScanResult.AwaitingValidation
        assertEquals(checkpoint, restored.record)
        assertEquals(callsBeforeRestart, calls)
        val restartedReplay = replayAll()
        assertTrue("Restarted replay must validate the checkpoint", restartedReplay is ConceptScanReplayResult.Validated)
        assertEquals(setOf("later:p1"), (restartedReplay as ConceptScanReplayResult.Validated).supported)
        assertEquals(setOf("later:p0"), restartedReplay.uncertain)
        assertEquals("Restart and replay add no inference", callsBeforeRestart, calls)

        // Commit the replayed Note. Support and the unchecked tail belong to concept B; unresolved
        // evidence belongs to concept A and must not suppress B's update.
        var state = ConceptScanReplay.start()
        var committed: CanonicalMembershipUpdateResult
        do {
            committed = worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, checkpoint, state, 8)
            if (committed is CanonicalMembershipUpdateResult.Pending) state = committed.replay
        } while (committed is CanonicalMembershipUpdateResult.Pending)
        assertEquals(CanonicalMembershipUpdateResult.Updated, committed)
        var stored = backing.readMetadata()!!
        var savedA = stored.topics.single { it.id == "topicA" }.canonicalConcept!!
        var savedB = stored.topics.single { it.id == "topicB" }.canonicalConcept!!
        assertEquals("Uncertainty is scoped to concept A", mapOf("later" to later.revision), savedA.uncertainRevisions)
        assertEquals(setOf("seed"), savedA.supported.map { it.pageId }.toSet())
        assertFalse("Unresolved evidence leaves A's coverage incomplete", savedA.matchingComplete)
        assertEquals("Concept B keeps its verified support", setOf("later"), savedB.supported.map { it.pageId }.toSet())
        assertTrue(savedB.supported.any { it.pageId == "later" && it.passageId == "later:p1" })
        assertEquals("Concept B keeps its unchecked tail", mapOf("later" to later.revision), savedB.uncheckedRevisions)
        assertTrue("Verified membership is complete; passage coverage is not", savedB.matchingComplete)
        assertFalse(savedB.passageCoverageComplete)

        // Tail reconciliation restarts the scan exhaustively. Concept A remains uncertain, concept B
        // reuses its witness and checks the previously untouched tail, clearing only the tail.
        ticket = worker.captureCatalogTicket()!!
        var tailResult = worker.advanceExistingConceptWork(ticket, later, sources, verifier, 8, restart = true, verify = verify)
        while (tailResult is DurableConceptScanResult.Pending) {
            tailResult = worker.advanceExistingConceptWork(ticket, later, sources, verifier, 8, restart = false, verify = verify)
        }
        val tail = tailResult as DurableConceptScanResult.AwaitingValidation
        assertEquals(ConceptScanCompletion.EXHAUSTED, tail.record.completion)
        assertTrue(tail.record.earlyConceptWorkKeys.isEmpty())
        assertEquals(setOf(SupportDecision.SUPPORTED, SupportDecision.UNCERTAIN),
            tail.record.assessments.map { it.decision }.toSet())
        state = ConceptScanReplay.start()
        do {
            committed = worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, tail.record, state, 8)
            if (committed is CanonicalMembershipUpdateResult.Pending) state = committed.replay
        } while (committed is CanonicalMembershipUpdateResult.Pending)
        assertEquals(CanonicalMembershipUpdateResult.Updated, committed)
        stored = backing.readMetadata()!!
        savedA = stored.topics.single { it.id == "topicA" }.canonicalConcept!!
        savedB = stored.topics.single { it.id == "topicB" }.canonicalConcept!!
        assertEquals("Tail reconciliation preserves the other concept's uncertainty",
            mapOf("later" to later.revision), savedA.uncertainRevisions)
        assertEquals(setOf("seed"), savedA.supported.map { it.pageId }.toSet())
        assertTrue("Tail reconciliation keeps the verified support", savedB.supported.any { it.pageId == "later" })
        assertTrue("Tail reconciliation keeps both verified chess passages",
            savedB.supported.filter { it.pageId == "later" }.map { it.passageId }.toSet().containsAll(setOf("later:p1", "later:p2")))
        assertTrue("The exhaustive tail is complete", savedB.uncheckedRevisions.isEmpty())
        assertTrue(savedB.passageCoverageComplete)
        assertTrue(savedA.passageCoverageComplete.not())
        assertEquals("One fresh tail decision; the witness and A's uncertainty were reused",
            callsBeforeRestart + 1, calls)
    }
}
