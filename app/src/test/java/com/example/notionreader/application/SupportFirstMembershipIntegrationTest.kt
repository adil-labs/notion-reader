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

/** Early support confirms membership while the Note's remaining candidates stay unchecked. */
class SupportFirstMembershipIntegrationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun earlySupportConfirmsMembershipAndLeavesAnExplicitUncheckedTail() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val queries = FileTopicRetrievalStore(root.resolve("queries")); val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        val generator = verifier.copy(prompt = "proposal")
        val seedText = "Moisture and warmth help seeds germinate and begin growing."
        val laterText = "Moisture and warmth allow dormant seeds to sprout and germinate."
        suspend fun add(id: String, texts: List<String>): SemanticRecord {
            val doc = ReaderDocument.fromChunkTexts(id, id, texts)
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val passages = texts.mapIndexed { order, text ->
                val passage = SemanticPassage("$id:p$order", id, "$id:section", "Seeds", "$id:block$order",
                    "$id:chunk$order", 0, text.length, text, text, order)
                EmbeddedPassage(passage, listOf(1f, 0f))
            }
            return SemanticRecord(id, semanticRevision(doc), model, SemanticMetadata(id), passages).also { assertTrue(index.write(it)) }
        }
        val seed = add("seed", listOf(seedText))
        val later = add("later", listOf(laterText, "Water and suitable temperature let seeds germinate."))
        val sources = mapOf(seed.pageId to seed.revision, later.pageId to later.revision)
        val seedPassage = seed.passages.single()
        val seedRef = ProposalEvidenceRef(seed.pageId, seed.revision, seedPassage.passage.id)
        val offered = TopicLabelEvidence(seedRef.passageId, seedRef.pageId, seedRef.revision, seedText)
        val proposal = DiscoveryProposalRequest(semanticHash("proposal"), 0, listOf(offered), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture helps seeds germinate.", "Listen about seeds.", listOf(seedRef.passageId))
        val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
        val pending = TopicSupportRequest("pending:$key", key, response.name, response.definition, offered, verifier, 0)
        val assessment = (validateTopicSupport(pending, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "${response.name}\n${response.definition}",
            model, TopicRetrievalPolicy(.8f)), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
            generator, verifier, listOf(seedRef), listOf(assessment), listOf(seedRef),
            mapOf("seed" to seed.revision), false, proposal.proposalKey, queryEmbedding = query)
        val member = TopicMembership("seed", seed.metadata, seedPassage.passage, 1f, seedPassage.passage, seed.revision)
        val topic = DiscoveredTopic("topic", "Seeds", seedPassage.passage, seedPassage, listOf(member), 1,
            canonicalConcept = concept)
        val backing = FileTopicStore(root.resolve("topic-store"))
        val initial = TopicSnapshot(topics = listOf(topic), identity = model, canonicalCatalogReady = true,
            acceptedRevisions = sources)
        assertEquals(TopicWriteResult.Written, backing.writeDetailed(initial))
        val retrievals = TopicRetrievalCoordinator(index, queries, { model }, TopicRetrievalPolicy(.8f),
            { listOf(1f, 0f) }, { true }, {}, Mutex())
        fun coordinator() = TopicCoordinator(cache, index, backing, null, { model }, TopicPolicy(), Mutex(), { true },
            {}, {}, retrievals = retrievals, proposalProgress = progress)
        var worker = coordinator()
        worker.invalidate(sources)
        val ticket = worker.captureCatalogTicket()!!
        var calls = 0
        val scan = worker.advanceExistingConceptWork(ticket, later, sources, verifier, 1) {
            calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as DurableConceptScanResult.AwaitingValidation
        assertEquals(1, calls)
        assertEquals(ConceptScanCompletion.SUPPORTED_EARLY, scan.record.completion)
        assertEquals(setOf(key), scan.record.earlyConceptWorkKeys)
        assertEquals(1, scan.record.assessments.size)
        assertEquals(CanonicalMembershipUpdateResult.Updated,
            worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, scan.record, ConceptScanReplay.start(), 1))
        val stored = backing.readMetadata()!!.topics.single().canonicalConcept!!
        assertEquals(setOf("seed", "later"), stored.supported.map { it.pageId }.toSet())
        assertEquals("The unchecked candidate tail must stay visible as coverage state",
            mapOf("later" to later.revision), stored.uncheckedRevisions)
        assertTrue("Every current Note has a membership outcome", stored.matchingComplete)
        assertFalse("Passage coverage is not exhaustive", stored.passageCoverageComplete)
        assertEquals("later:p0", stored.supported.single { it.pageId == "later" }.passageId)

        // Restart: the completed early checkpoint is not a finished tail. The exhaustive pass
        // restarts, reuses the durable witness, and leaves the lower-ranked passage for the next
        // bounded page; no verifier call is allowed for the witness.
        worker = coordinator()
        worker.invalidate(sources)
        val restartedTicket = worker.captureCatalogTicket()!!
        val restarted = worker.advanceExistingConceptWork(restartedTicket, later, sources, verifier, 1) {
            error("The durable first witness must be reused")
        }
        assertTrue("A completed early checkpoint with a pending tail must restart: $restarted",
            restarted is DurableConceptScanResult.Pending)
        val rebuilt = worker.advanceExistingConceptWork(restartedTicket, later, sources, verifier, 1) {
            calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as DurableConceptScanResult.AwaitingValidation
        assertEquals(ConceptScanCompletion.EXHAUSTED, rebuilt.record.completion)
        var replay = ConceptScanReplay.start()
        var committed: CanonicalMembershipUpdateResult
        do {
            committed = worker.advanceCanonicalMembershipUpdate(restartedTicket, later, sources, verifier,
                rebuilt.record, replay, 1)
            if (committed is CanonicalMembershipUpdateResult.Pending) replay = committed.replay
        } while (committed is CanonicalMembershipUpdateResult.Pending)
        assertEquals(CanonicalMembershipUpdateResult.Updated, committed)
        val reopened = backing.readMetadata()!!.topics.single().canonicalConcept!!
        assertTrue(reopened.uncheckedRevisions.isEmpty())
        assertTrue(reopened.passageCoverageComplete)
        assertEquals(2, calls)
    }
}
