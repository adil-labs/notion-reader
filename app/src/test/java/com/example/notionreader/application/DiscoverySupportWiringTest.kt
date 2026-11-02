package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoverySupportWiringTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun coordinatorUsesTheSharedRuntimeAndVersionedSupportIdentity() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2); val labelIdentity = TopicLabelIdentity("a", "t", "c", "label", "s", "v", "CPU")
        val supportIdentity = labelIdentity.copy(prompt = "support", policy = "support-v1")
        val proposalIdentity = labelIdentity.copy(prompt = "proposal", policy = "proposal-v1")
        var activeProposalIdentity = proposalIdentity; var switchProposalDuringInference = false
        var lastProposal: DiscoveryProposalRequest? = null
        var activeSupportIdentity = supportIdentity; var switchDuringInference = false
        var decision = SupportDecision.SUPPORTED
        var releases = 0; var supportCalls = 0; var proposalCalls = 0
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String): List<Float> = error("Query vector is already stored")
            override fun release() { releases++ }
        }
        val labeler = object : TopicLabelRuntime, TopicSupportRuntime, DiscoveryProposalRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No naming work")
            override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun {
                assertEquals(activeSupportIdentity, request.verifier); assertTrue("Embedding engine must release before support inference", releases > 0)
                supportCalls++
                if (switchDuringInference) activeSupportIdentity = activeSupportIdentity.copy(policy = "changed-during-inference")
                return TopicSupportRun.Completed(decision)
            }
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                assertEquals(activeProposalIdentity, request.identity); proposalCalls++; lastProposal = request
                if (switchProposalDuringInference) activeProposalIdentity = activeProposalIdentity.copy(policy = "changed-during-proposal")
                return DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE))
            }
            override suspend fun close() {}
        }
        val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val doc = ReaderDocument.fromChunkTexts("note", "Seed germination", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc); val p = SemanticPassage("p", "note", "section", "Summary", "block", "chunk", 0, text.length, text, text, 0)
        val embedded = EmbeddedPassage(p, listOf(1f, 0f)); val metadata = SemanticMetadata("Seed germination")
        assertTrue(index.write(SemanticRecord("note", revision, model, metadata, listOf(embedded)))); index.setPaused(false)
        val raw = DiscoveredTopic("topic", "seed", p, embedded, listOf(TopicMembership("note", metadata, p, 1f, p, revision)), 1)
        val request = topicLabelRequest(raw, labelIdentity, 0)!!
        val label = (validateTopicLabel(request, "Seed germination", request.evidence.map { it.passageId }, request.groupFingerprint, 0,
            "Conditions that allow seeds to germinate and sprout.") as TopicLabelValidation.Accepted).label
        val graph = object : TopicStore {
            var snapshot = TopicSnapshot(topics = listOf(raw.copy(generatedLabel = label)), identity = model, complete = true, connectionPassComplete = true)
            override suspend fun read() = snapshot
            override suspend fun write(snapshot: TopicSnapshot): Boolean { this.snapshot = snapshot; return true }
            override suspend fun clear() { snapshot = TopicSnapshot() }
            override suspend fun bytes() = 0L
        }
        val queries = FileTopicRetrievalStore(root.resolve("retrieval")); val policy = TopicRetrievalPolicy(.85f)
        val query = TopicRetrievalQuery("topic", label.workKey, topicConceptQuery(label)!!, model, policy)
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)))) { true })
        val backing = FileProposalProgressStore(root.resolve("progress")); var changeQueryDuringReplay = false
        val progress = object : ProposalProgressStore by backing {
            override suspend fun readConceptScan(pageId: String, sources: Map<String, String>): ExistingConceptScanRecord? {
                val saved = backing.readConceptScan(pageId, sources)
                if (changeQueryDuringReplay && saved != null) {
                    changeQueryDuringReplay = false
                    queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(0f, 1f)))) { true }
                }
                return saved
            }
        }
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, topicLabelRuntime = labeler, topicLabelModelAvailable = { true }, topicRetrievalStore = queries,
            proposalProgressStore = progress, topicSupportIdentity = { activeSupportIdentity }, topicProposalIdentity = { activeProposalIdentity })
        controller.reconcile(emptyList(), false)
        val proposed = controller.advanceEvidenceFirstNote("note") as EvidenceFirstNoteStep.Proposals
        assertTrue("Verified existing support must prevent a duplicate proposal", proposed.window.window.proposals.isEmpty())
        assertEquals(1, supportCalls)
        val result = controller.advanceExistingConceptWorkForNote("note", 1) as DurableConceptScanResult.AwaitingValidation
        assertEquals(1, supportCalls); assertEquals(listOf(SupportDecision.SUPPORTED), result.record.assessments.map { it.decision })
        val replayed = controller.replayExistingConceptWorkForNote("note", result.record, ConceptScanReplay.start(), 1)
            as ConceptScanReplayResult.Validated
        assertEquals(setOf("p"), replayed.supported); assertTrue(replayed.uncertain.isEmpty()); assertEquals(1, supportCalls)
        changeQueryDuringReplay = true
        assertEquals(ConceptScanReplayResult.Stale, controller.replayExistingConceptWorkForNote("note", result.record, ConceptScanReplay.start(), 1))
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)))) { true })
        activeSupportIdentity = supportIdentity.copy(policy = "unsupported-v2")
        assertEquals(ConceptScanReplayResult.Stale, controller.replayExistingConceptWorkForNote("note", result.record, ConceptScanReplay.start(), 1))
        decision = SupportDecision.UNSUPPORTED
        val generated = controller.advanceEvidenceFirstNote("note", restart = true, generateIndex = 0) as EvidenceFirstNoteStep.Generated
        assertEquals(DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE), generated.generation.response)
        assertEquals(1, proposalCalls)
        assertTrue(controller.advanceEvidenceFirstNote("note", generateIndex = 0) is EvidenceFirstNoteStep.Generated)
        assertEquals("An unchanged abstention must survive reuse", 1, proposalCalls)
        activeProposalIdentity = proposalIdentity.copy(policy = "proposal-v2"); switchProposalDuringInference = true
        assertEquals(EvidenceFirstNoteStep.Stale, controller.advanceEvidenceFirstNote("note", generateIndex = 0))
        assertEquals(2, proposalCalls)
        assertNull(progress.readAbstention(discoveryProposalWorkKey(lastProposal!!), mapOf("note" to revision)))
        switchProposalDuringInference = false
        activeSupportIdentity = supportIdentity.copy(policy = "uncertain-v3")
        decision = SupportDecision.UNCERTAIN
        assertEquals(EvidenceFirstNoteStep.Uncertain(setOf("p")), controller.advanceEvidenceFirstNote("note", restart = true))
        val uncertainCheckpoint = progress.readConceptScan("note", mapOf("note" to revision))!!
        activeSupportIdentity = supportIdentity.copy(policy = "support-v4"); switchDuringInference = true
        assertEquals(DurableConceptScanResult.Stale, controller.advanceExistingConceptWorkForNote("note", 1, restart = true))
        assertEquals(4, supportCalls)
        assertEquals(uncertainCheckpoint, progress.readConceptScan("note", mapOf("note" to revision)))
    }
}
