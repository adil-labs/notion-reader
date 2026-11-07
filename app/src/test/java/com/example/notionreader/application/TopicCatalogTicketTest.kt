package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicCatalogTicketTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun aTicketTracksTheProcessCatalogSourcesModelAndQueryVectors() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        var model = EmbeddingIdentity("model", 2)
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, connectionPassComplete = true)))
        val queries = FileTopicRetrievalStore(root.resolve("retrieval")); val retrievals = TopicRetrievalCoordinator(index, queries,
            { model }, TopicRetrievalPolicy(.8f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {}, retrievals = retrievals)
        assertNull(worker.captureCatalogTicket())
        worker.invalidate(emptyMap())
        val restored = worker.captureCatalogTicket()!!; assertTrue(worker.isCatalogTicketCurrent(restored))
        worker.invalidate(emptyMap())
        assertTrue("Unchanged reconciliation must preserve an in-flight ticket", worker.isCatalogTicketCurrent(restored))
        val first = worker.captureCatalogTicket()!!
        val query = TopicRetrievalQuery("topic", semanticHash("concept"), "seed germination", model, TopicRetrievalPolicy(.8f))
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)))) { true })
        assertFalse(worker.isCatalogTicketCurrent(first))
        val second = worker.captureCatalogTicket()!!; assertTrue(worker.isCatalogTicketCurrent(second))
        model = model.copy(model = "other"); assertFalse(worker.isCatalogTicketCurrent(second))
        model = second.identity
        val third = worker.captureCatalogTicket()!!
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts("page", "Title", listOf("A cached body.")), null, Instant.EPOCH))
        assertFalse(worker.isCatalogTicketCurrent(third))
        cache.remove("page")
        assertTrue(cache.invalidations.value.isNotEmpty())
        assertNull("Pending withdrawal must be reconciled before capturing another ticket", worker.captureCatalogTicket())
    }
    @Test fun coordinatorRunsOneBoundedExistingConceptPageAgainstCurrentSources() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2); val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val doc = ReaderDocument.fromChunkTexts("note", "Seed germination", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc); val sources = mapOf("note" to revision)
        val p = SemanticPassage("p", "note", "section", "Summary", "block", "chunk", 0, text.length, text, text, 0)
        val embedded = EmbeddedPassage(p, listOf(1f, 0f)); val metadata = SemanticMetadata("Seed germination")
        val record = SemanticRecord("note", revision, model, metadata, listOf(embedded)); assertTrue(index.write(record))
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val generator = verifier.copy(prompt = "proposal")
        val evidence = TopicLabelEvidence("p", "note", revision, text)
        val proposal = DiscoveryProposalRequest(semanticHash("proposal"), 0, listOf(evidence), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Conditions that allow seeds to germinate and sprout.",
            "Listen to seed germination.", listOf("p"))
        val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
        val request = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence, verifier, 0)
        val assessment = (validateTopicSupport(request, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        val progress = FileProposalProgressStore(root.resolve("progress"))
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })
        val ref = ProposalEvidenceRef("note", revision, "p")
        val policy = TopicRetrievalPolicy(.8f)
        val query = TopicRetrievalQuery("topic", key, "${response.name}\n${response.definition}", model, policy)
        val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
            generator, verifier, listOf(ref), listOf(assessment), listOf(ref), sources, true, proposal.proposalKey,
            queryEmbedding = TopicQueryEmbedding(query, listOf(1f, 0f)))
        val topic = DiscoveredTopic("topic", "seed", p, embedded, listOf(TopicMembership("note", metadata, p, 1f, p, revision)), 1,
            canonicalConcept = concept)
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(topics = listOf(topic), identity = model, canonicalCatalogReady = true,
            acceptedRevisions = sources, connectionPassComplete = true)))
        val queries = FileTopicRetrievalStore(root.resolve("retrieval"))
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)))) { true })
        val retrievals = TopicRetrievalCoordinator(index, queries, { model }, policy, { listOf(1f, 0f) }, { true }, {}, Mutex())
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {}, retrievals = retrievals, proposalProgress = progress)
        worker.invalidate(sources); val ticket = worker.captureCatalogTicket()!!; var calls = 0
        val result = worker.advanceExistingConceptWork(ticket, record, sources, verifier, 1) {
            calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as DurableConceptScanResult.AwaitingValidation
        assertEquals("The current pending assessment survives canonical admission", 0, calls)
        assertEquals(listOf("p"), result.record.assessments.map { it.evidence.passageId })
        assertEquals(DurableConceptScanResult.Stale, worker.advanceExistingConceptWork(ticket, record, emptyMap(), verifier, 1) { error("No inference") })
        val stale = worker.advanceExistingConceptWork(ticket, record, sources, verifier.copy(policy = "changed"), 1, restart = true) {
            cache.remove("note")
            TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        }
        assertEquals(DurableConceptScanResult.Stale, stale)
        assertEquals(result.record, progress.readConceptScan("note", sources))
    }

    @Test fun aCatalogResetDuringCheckpointCommitCannotSaveOldScanWork() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2); val document = ReaderDocument.fromChunkTexts("note", "Title", listOf("A cached body about seed germination."))
        cache.put(CachedDocument(document, null, Instant.EPOCH)); val revision = semanticRevision(document)
        val text = "A cached body about seed germination."
        val p = SemanticPassage("p", "note", "section", "Summary", "block", "chunk", 0, text.length, text, text, 0)
        val record = SemanticRecord("note", revision, model, SemanticMetadata("Title"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
        index.write(record)
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true,
            acceptedRevisions = mapOf("note" to revision), connectionPassComplete = true)))
        val queries = FileTopicRetrievalStore(root.resolve("retrieval")); val retrievals = TopicRetrievalCoordinator(index, queries,
            { model }, TopicRetrievalPolicy(.8f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {}, retrievals = retrievals)
        val sources = mapOf("note" to revision); worker.invalidate(sources); val ticket = worker.captureCatalogTicket()!!
        val backing = FileProposalProgressStore(root.resolve("progress"))
        val racing = object : ProposalProgressStore by backing {
            override suspend fun replaceConceptScan(expected: ExistingConceptScanRecord?, record: ExistingConceptScanRecord,
                stillCurrent: () -> Boolean): ProposalProgressWriteResult {
                worker.releaseMemory()
                return backing.replaceConceptScan(expected, record, stillCurrent)
            }
        }
        val verifier = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "CPU")
        val result = advanceDurableConceptScan(ticket.snapshot, record, model, TopicRetrievalPolicy(.8f), verifier, 1,
            sources, queries, index, racing, 1, { worker.isCatalogTicketCurrent(ticket) }, { true }) { error("No concepts") }
        assertEquals(DurableConceptScanResult.Stale, result)
        assertNull(backing.readConceptScan("note", sources))
    }

    @Test fun resetAndMemoryReleaseInvalidateTicketsWithoutChangingTheScreen() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2)
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, connectionPassComplete = true)))
        val queries = FileTopicRetrievalStore(root.resolve("retrieval")); val retrievals = TopicRetrievalCoordinator(index, queries,
            { model }, TopicRetrievalPolicy(.8f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {}, retrievals = retrievals)
        worker.invalidate(emptyMap()); val first = worker.captureCatalogTicket()!!
        worker.releaseMemory(); assertFalse(worker.isCatalogTicketCurrent(first))
        val second = worker.captureCatalogTicket()!!; worker.clear(); assertFalse(worker.isCatalogTicketCurrent(second))
    }
}
