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

class CanonicalMembershipUpdateTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun aReplayedSupportedNoteUpdatesTheExistingCanonicalTopic() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val queries = FileTopicRetrievalStore(root.resolve("queries")); val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        val generator = verifier.copy(prompt = "proposal")
        val text = "Moisture and warmth help seeds germinate and begin growing."
        suspend fun add(id: String): SemanticRecord {
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val passage = SemanticPassage("$id:p", id, "$id:section", "Seeds", "$id:block", "$id:chunk", 0, text.length, text, text, 0)
            return SemanticRecord(id, semanticRevision(doc), model, SemanticMetadata(id), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
                .also { assertTrue(index.write(it)) }
        }
        val seed = add("seed"); val later = add("later"); val third = add("third")
        val sources = mapOf(seed.pageId to seed.revision, later.pageId to later.revision, third.pageId to third.revision)
        val p = seed.passages.single(); val ref = ProposalEvidenceRef(seed.pageId, seed.revision, p.passage.id)
        val offered = TopicLabelEvidence(ref.passageId, ref.pageId, ref.revision, text)
        val proposal = DiscoveryProposalRequest(semanticHash("proposal"), 0, listOf(offered), generator)
        val response = DiscoveryProposalResponse.Concept("Seed germination", "Moisture helps seeds germinate.", "Listen about seeds.", listOf(ref.passageId))
        val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
        val pending = TopicSupportRequest("pending:$key", key, response.name, response.definition, offered, verifier, 0)
        val assessment = (validateTopicSupport(pending, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "${response.name}\n${response.definition}", model, TopicRetrievalPolicy(.8f)), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
            generator, verifier, listOf(ref), listOf(assessment), listOf(ref), mapOf("seed" to seed.revision, "third" to third.revision), false,
            proposal.proposalKey, queryEmbedding = query)
        val member = TopicMembership("seed", seed.metadata, p.passage, 1f, p.passage, seed.revision)
        val topic = DiscoveredTopic("topic", "Seeds", p.passage, p, listOf(member), 1, canonicalConcept = concept)
        var beforeCommit: () -> Unit = {}
        var rejectWrite: (() -> Unit)? = null
        var afterCommit: suspend () -> Unit = {}
        val backing = FileTopicStore(root.resolve("topic-store"))
        val initial = TopicSnapshot(topics = listOf(topic), identity = model, complete = true, acceptedRevisions = sources)
        assertEquals(TopicWriteResult.Written, backing.writeDetailed(initial))
        val graph = object : TopicStore by backing {
            var snapshot = initial
            override suspend fun write(snapshot: TopicSnapshot): Boolean {
                val result = backing.write(snapshot); if (result) this.snapshot = snapshot; return result
            }
            override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult {
                val result = backing.writeDetailed(snapshot); if (result == TopicWriteResult.Written) this.snapshot = snapshot; return result
            }
            override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                if (!current()) return TopicGuardedWriteResult.Stale
                rejectWrite?.let { rejectWrite = null; it(); return TopicGuardedWriteResult.Rejected(TopicWriteResult.Rejected(TopicWriteFailure.IoFailure)) }
                beforeCommit()
                if (!current()) return TopicGuardedWriteResult.Stale
                val result = backing.writeGuarded(snapshot, current)
                if (result == TopicGuardedWriteResult.Written) { this.snapshot = snapshot; afterCommit() }
                return result
            }
            override suspend fun clear() { backing.clear(); snapshot = TopicSnapshot() }
        }
        val retrievals = TopicRetrievalCoordinator(index, queries, { model }, TopicRetrievalPolicy(.8f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val worker = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {},
            retrievals = retrievals, proposalProgress = progress)
        worker.invalidate(sources)
        val ticket = worker.captureCatalogTicket()!!
        val scan = worker.advanceExistingConceptWork(ticket, later, sources, verifier, 4) {
            TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as DurableConceptScanResult.AwaitingValidation
        val forged = scan.record.copy(assessments = emptyList())
        assertEquals(CanonicalMembershipUpdateResult.Stale,
            worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, forged, ConceptScanReplay.start(), 4))
        assertEquals(listOf("seed"), graph.snapshot.topics.single().canonicalConcept!!.supported.map { it.pageId })
        var verifierCurrent = true
        beforeCommit = { verifierCurrent = false }
        assertEquals(CanonicalMembershipUpdateResult.Stale,
            worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, scan.record, ConceptScanReplay.start(), 4) { verifierCurrent })
        assertEquals(listOf("seed"), graph.snapshot.topics.single().canonicalConcept!!.supported.map { it.pageId })
        beforeCommit = {}; verifierCurrent = true
        rejectWrite = { verifierCurrent = false }
        assertEquals(CanonicalMembershipUpdateResult.Stale,
            worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, scan.record, ConceptScanReplay.start(), 4) { verifierCurrent })
        assertEquals("topic", graph.snapshot.topics.single().id)
        verifierCurrent = true
        afterCommit = { verifierCurrent = false; afterCommit = {} }
        assertEquals(CanonicalMembershipUpdateResult.Stale,
            worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, scan.record, ConceptScanReplay.start(), 4) { verifierCurrent })
        assertEquals("topic", graph.snapshot.topics.single().id)
        assertEquals(listOf("seed"), graph.snapshot.topics.single().canonicalConcept!!.supported.map { it.pageId })
        verifierCurrent = true
        val result = worker.advanceCanonicalMembershipUpdate(ticket, later, sources, verifier, scan.record, ConceptScanReplay.start(), 4)
        assertEquals(CanonicalMembershipUpdateResult.Updated, result)
        val updated = graph.snapshot.topics.single()
        assertEquals("topic", updated.id)
        assertEquals(setOf("seed", "later"), updated.canonicalConcept!!.supported.map { it.pageId }.toSet())
        assertTrue(updated.canonicalConcept!!.matchingComplete)
        assertFalse(worker.isCatalogTicketCurrent(ticket))
        worker.invalidate(sources)
        val restored = worker.captureCatalogTicket()!!.snapshot.topics.single()
        assertEquals(TopicPublicationStatus.Established, discoveryTopicPublicationStatus(restored))
        assertEquals(setOf("seed", "later"), restored.matchingMembers.map { it.pageId }.toSet())
        val currentTicket = worker.captureCatalogTicket()!!
        val uncertain = worker.advanceExistingConceptWork(currentTicket, seed, sources, verifier, 4) {
            TopicSupportRun.Completed(SupportDecision.UNCERTAIN)
        } as DurableConceptScanResult.AwaitingValidation
        assertEquals(CanonicalMembershipUpdateResult.Incomplete,
            worker.advanceCanonicalMembershipUpdate(currentTicket, seed, sources, verifier, uncertain.record, ConceptScanReplay.start(), 4))
        assertEquals(setOf("seed", "later"), graph.snapshot.topics.single().canonicalConcept!!.supported.map { it.pageId }.toSet())
        val thirdScan = worker.advanceExistingConceptWork(currentTicket, third, sources, verifier, 4) {
            TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as DurableConceptScanResult.AwaitingValidation
        afterCommit = { cache.remove("third"); afterCommit = {} }
        assertEquals(CanonicalMembershipUpdateResult.Stale,
            worker.advanceCanonicalMembershipUpdate(currentTicket, third, sources, verifier, thirdScan.record, ConceptScanReplay.start(), 4))
        assertEquals("topic", graph.snapshot.topics.single().id)
        assertEquals(setOf("seed", "later"), graph.snapshot.topics.single().canonicalConcept!!.supported.map { it.pageId }.toSet())
        assertFalse(graph.snapshot.topics.single().canonicalConcept!!.matchingComplete)
        assertNull(progress.readSupport(thirdScan.record.assessments.single().workKey, sources))
        val reopened = FileTopicStore(root.resolve("topic-store")).read()!!
        assertEquals(setOf("seed", "later"), reopened.topics.single().canonicalConcept!!.supported.map { it.pageId }.toSet())
        assertFalse(reopened.topics.single().canonicalConcept!!.matchingComplete)
        assertTrue(cache.invalidations.value.any { it.pageId == "third" })
        assertTrue(root.resolve("topic-store").walkTopDown().filter { it.isFile }.none { it.readText().contains("third:p") })
        cache.remove("later")
        worker.invalidate(mapOf("seed" to seed.revision))
        val withdrawn = graph.snapshot.topics.single().canonicalConcept!!
        assertEquals(listOf("seed"), withdrawn.supported.map { it.pageId })
        assertFalse(withdrawn.matchingComplete)
    }
}
