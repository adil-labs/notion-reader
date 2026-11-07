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
 * A multi-page passage tail must make forward cursor progress while membership work remains
 * continuously pending, across automatic continuation and process recreation, without restarting
 * completed pages or re-invoking the support verifier for a validated witness.
 */
class TailContinuityIntegrationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun aMultiPageTailAdvancesAcrossContinuationMembershipAndRecreation() = runBlocking {
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
        val text = "Moisture and warmth help seeds germinate and begin growing."

        suspend fun add(id: String, passages: Int): SemanticRecord {
            val doc = ReaderDocument.fromChunkTexts(id, id, List(passages) { text })
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val embedded = (0 until passages).map { p ->
                EmbeddedPassage(SemanticPassage("$id:p$p", id, "$id:s$p", "Seeds", "$id:b$p", "$id:c$p",
                    0, text.length, text, text, p), listOf(1f, 0f))
            }
            return SemanticRecord(id, semanticRevision(doc), model, SemanticMetadata(id), embedded)
                .also { assertTrue(index.write(it)) }
        }
        val seed = add("seed", 1)
        val tail = add("tail", 6)
        val membershipNote = add("member", 1)
        index.setPaused(false)
        val sources = mapOf("seed" to seed.revision, "tail" to tail.revision, "member" to membershipNote.revision)

        val seedEvidence = TopicLabelEvidence("seed:p0", "seed", seed.revision, text)
        val proposalKey = semanticHash("proposal")
        val request = DiscoveryProposalRequest(proposalKey, 0, listOf(seedEvidence), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture helps seeds germinate.",
            "Listen about seeds.", listOf("seed:p0"))
        val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
        val seedCitation = (validateTopicSupport(TopicSupportRequest("pending:$key", key, response.name,
            response.definition, seedEvidence, verifier, 0), SupportDecision.SUPPORTED, 0, key, sources)
            as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(seedCitation) { true })
        // p0 is the already-verified witness that established early membership; p1..p5 are the
        // deliberately unchecked tail.
        val tailWitnessEvidence = TopicLabelEvidence("tail:p0", "tail", tail.revision, text)
        val tailWitness = (validateTopicSupport(TopicSupportRequest("topic", key, response.name, response.definition,
            tailWitnessEvidence, verifier, 0), SupportDecision.SUPPORTED, 0, key, sources)
            as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(tailWitness) { true })

        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "${response.name}\n${response.definition}",
            model, policy), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
            generator, verifier, listOf(seedCitation.evidence), listOf(seedCitation),
            supported = listOf(seedCitation.evidence, tailWitness.evidence),
            scannedRevisions = mapOf("seed" to seed.revision, "tail" to tail.revision),
            matchingComplete = false, proposalKey = proposalKey,
            uncheckedRevisions = mapOf("tail" to tail.revision), queryEmbedding = query)
        val seedPassage = seed.passages.single()
        val topic = DiscoveredTopic("topic", response.name, seedPassage.passage, seedPassage,
            listOf(TopicMembership("seed", seed.metadata, seedPassage.passage, 1f, seedPassage.passage, seed.revision)),
            1, canonicalConcept = concept,
            canonicalProjection = CanonicalTopicProjection(key, response.name, response.definition,
                response.listeningDescription, emptyList(), null))
        val backing = FileTopicStore(root.resolve("topics"))
        assertEquals(TopicWriteResult.Written, backing.writeDetailed(TopicSnapshot(
            topics = listOf(topic), identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            policyVersion = topicPolicy.version)))
        val retrievals = TopicRetrievalCoordinator(index, queries, { model }, policy, { listOf(1f, 0f) }, { true }, {}, Mutex())
        fun coordinator() = TopicCoordinator(cache, index, backing, null, { model }, topicPolicy, Mutex(), { true }, {}, {},
            retrievals = retrievals, proposalProgress = progress)
        var worker = coordinator()
        worker.invalidate(sources)
        val ticket = worker.captureCatalogTicket()!!
        var verifierCalls = 0
        val verify: suspend (TopicSupportRequest) -> TopicSupportRun = {
            verifierCalls++
            TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        }
        var scheduler = CanonicalMembershipScheduler()
        val cursorOrders = mutableListOf<Int>()
        val examined = mutableListOf<Int>()
        var lastCheckpoint: ExistingConceptScanRecord? = null

        suspend fun step(): CanonicalMembershipScheduleResult {
            val result = scheduler.step(ticket.snapshot, sources, verifier,
                retrievalPolicyIdentity = policy.toString(),
                scan = { pageId, restart ->
                    if (pageId == "member") {
                        // Membership work stays continuously pending; the tail must still run.
                        DurableConceptScanResult.Pending
                    } else {
                        worker.advanceExistingConceptWork(ticket, tail, sources, verifier, 1, restart, verify = verify)
                    }
                },
                commit = { _, saved, replay, limit ->
                    worker.advanceCanonicalMembershipUpdate(ticket, tail, sources, verifier, saved, replay, limit)
                })
            val checkpoint = progress.readConceptScan("tail", sources)
            if (checkpoint != null && checkpoint != lastCheckpoint) {
                lastCheckpoint = checkpoint
                examined += checkpoint.examinedCandidates
                checkpoint.next?.passage?.passageId?.removePrefix("tail:p")?.toIntOrNull()?.let { cursorOrders += it }
            }
            return result
        }

        // Membership work is always pending, so the tail must get a bounded opportunity.
        var steps = 0
        while (steps++ < 10 && cursorOrders.isEmpty()) step()
        assertEquals("The tail must run despite continuously pending membership work; cursors=$cursorOrders",
            listOf(0), cursorOrders)
        val callsAfterFirstPage = verifierCalls

        // Automatic continuation must preserve the durable cursor, not restart the tail.
        scheduler.continueAutomatically()
        steps = 0
        while (steps++ < 10 && cursorOrders.size < 2) step()
        assertEquals("Automatic continuation must advance the tail cursor", listOf(0, 1), cursorOrders)
        assertEquals("A resumed page must not re-invoke the verified witness", 1, verifierCalls)

        // Process recreation: the new scheduler must resume the durable partial checkpoint.
        scheduler = CanonicalMembershipScheduler()
        steps = 0
        while (steps++ < 10 && cursorOrders.size < 3) step()
        assertEquals("A recreated scheduler must resume, not restart, the tail", listOf(0, 1, 2), cursorOrders)
        assertEquals("No page may be re-examined after recreation", 2, verifierCalls)

        // Drain the remaining tail pages. Monotonic cursor and examined counts prove no completed
        // page was restarted.
        steps = 0
        while (steps++ < 40 && progress.readConceptScan("tail", sources)?.next != null) step()
        val done = progress.readConceptScan("tail", sources)!!
        assertNull("The exhaustive tail must finish", done.next)
        assertEquals(ConceptScanCompletion.EXHAUSTED, done.completion)
        assertTrue("Tail cursors must be monotonic: ${cursorOrders}",
            cursorOrders == cursorOrders.sorted() && cursorOrders.distinct().size == cursorOrders.size)
        assertTrue("Examined candidates must be monotonic: ${examined}",
            examined == examined.sorted() && examined.distinct().size == examined.size)
        assertEquals("Only p1..p5 require fresh decisions; p0 is reused", 5, verifierCalls)
        assertEquals("The first page reused the witness without inference", callsAfterFirstPage, 0)
        val saved = backing.readMetadata()!!.topics.single().canonicalConcept!!
        assertTrue("The tail must be reconciled", saved.uncheckedRevisions.isEmpty())
        assertEquals("The witness and tail remain verified members", setOf("seed", "tail"),
            saved.supported.map { it.pageId }.toSet())
        assertTrue(saved.supported.filter { it.pageId == "tail" }.map { it.passageId }.toSet()
            .containsAll((0..5).map { "tail:p$it" }))
        assertFalse("Membership coverage stays incomplete while the member Note is unscanned", saved.matchingComplete)
    }
}
