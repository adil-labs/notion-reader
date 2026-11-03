package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FreshCanonicalCatalogIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun aProvenFreshInstallationBuildsATopicWithoutFormation() = runBlocking { scenario("clean") }
    @Test fun anOrphanFollowPreventsEmptyCatalogInitialization() = runBlocking { scenario("follow") }
    @Test fun corruptInterestsPreventEmptyCatalogInitialization() = runBlocking { scenario("corrupt-interest") }
    @Test fun orphanLabelEvidencePreventsEmptyCatalogInitialization() = runBlocking { scenario("label") }
    @Test fun orphanCheckpointPreventsEmptyCatalogInitialization() = runBlocking { scenario("checkpoint") }
    @Test fun orphanProposalPreventsEmptyCatalogInitialization() = runBlocking { scenario("proposal") }
    @Test fun aFreshInstallReconcilesPendingWithdrawalBeforeInitializing() = runBlocking { scenario("withdrawal") }
    @Test fun aFreshInstallWaitsForOtherConsumersBeforeAcknowledgingWithdrawal() = runBlocking { scenario("unacknowledged") }
    @Test fun withdrawalImmediatelyAfterFreshWriteCannotPublishTheEmptyCatalog() = runBlocking { scenario("stale-init") }
    @Test fun clearingAndReopeningRebuildsWithoutLosingAFollow() = runBlocking { scenario("reset") }
    @Test fun aRejectedResetCatalogReportsFailureWithoutLosingTheFollow() = runBlocking { scenario("reset-reject") }
    private suspend fun scenario(kind: String) {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val text = "Moisture and warmth support the germination of seeds."
        val doc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        if (kind == "withdrawal" || kind == "unacknowledged") {
            val old = ReaderDocument.fromChunkTexts("old", "Old", listOf("Previously cached content."))
            cache.put(CachedDocument(old, null, Instant.EPOCH)); cache.remove("old")
            assertTrue(cache.invalidations.value.isNotEmpty())
        }
        val revision = semanticRevision(doc); val p = SemanticPassage("p", "note", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        assertTrue(index.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        index.setPaused(false)
        val interests = FileDiscoveryInterestStore(root.resolve("interests"))
        if (kind == "follow") interests.write(listOf(DiscoveryInterest("follow", "seeds", originTopicId = "old-topic")))
        if (kind == "corrupt-interest") { root.resolve("interests").mkdirs(); root.resolve("interests/interests.json").writeText("{broken") }
        val labels = FileTopicLabelStore(root.resolve("labels"))
        if (kind == "label") root.resolve("labels/label-${semanticHash("old")}.json").writeText("{}")
        val graph = FileTopicStore(root.resolve("topics"), allowFreshInitialization = true)
        val checkpoint = FileTopicStore(root.resolve("checkpoint"))
        val queries = FileTopicRetrievalStore(root.resolve("queries")); val progress = FileProposalProgressStore(root.resolve("progress"))
        if (kind == "checkpoint") assertTrue(checkpoint.write(TopicSnapshot(identity = model)))
        if (kind == "proposal") {
            val prior = ProposalConceptRecord(semanticHash("prior"), listOf(ProposalEvidenceRef("note", revision, "p")),
                "Seed germination", "Conditions for seeds to germinate.", "Listen to seed germination.", listOf("p"))
            assertEquals(ProposalProgressWriteResult.Written, progress.writeConcept(prior) { true })
        }
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
        val runtime = object : TopicLabelRuntime, DiscoveryProposalRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No legacy naming")
            override suspend fun propose(request: DiscoveryProposalRequest) = DiscoveryProposalRun.Completed(
                DiscoveryProposalResponse.Concept("Seed germination conditions", "Moisture and warmth support seed germination.",
                    "Listen to conditions for seed germination.", listOf(request.evidence.first().passageId)))
            override suspend fun verifySupport(request: TopicSupportRequest) = TopicSupportRun.Completed(SupportDecision.SUPPORTED)
            override suspend fun close() {}
        }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        var acknowledgementLimit = if (kind == "unacknowledged") 0L else Long.MAX_VALUE
        var rejectReset = false
        val topicStore = if (kind == "reset-reject") object : TopicStore by graph {
            override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult =
                if (rejectReset) TopicWriteResult.Rejected(TopicWriteFailure.IoFailure) else graph.writeDetailed(snapshot)
        } else if (kind == "stale-init") object : TopicStore by graph {
            override suspend fun initializeFresh(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                val result = graph.initializeFresh(snapshot, current)
                if (result == TopicGuardedWriteResult.Written) cache.remove("note")
                return result
            }
        } else graph
        fun controller() = DiscoveryController(cache, index, interests, provider, topicStore = topicStore, topicCheckpointStore = checkpoint,
            invalidationAcknowledgementLimit = { acknowledgementLimit },
            topicLabelRuntime = runtime, topicLabelModelAvailable = { true }, topicLabelStore = labels, topicRetrievalStore = queries,
            proposalProgressStore = progress, topicSupportIdentity = { identity.copy(prompt = "support") },
            topicProposalIdentity = { identity }, enableEvidenceFirstScheduling = true)
        var controller = controller()
        controller.reconcile(emptyList(), false)
        if (kind == "unacknowledged") {
            assertNull(graph.readMetadata())
            assertTrue(cache.invalidations.value.isNotEmpty())
            acknowledgementLimit = Long.MAX_VALUE
        } else if (kind != "clean" && kind != "withdrawal" && kind != "reset" && kind != "reset-reject") {
            repeat(3) { controller.reconcile(emptyList(), true) }
            assertNull("Unknown pre-existing topic state must not be replaced", graph.readMetadata())
            assertNotNull(controller.state.value.topicFailure)
            if (kind == "follow") assertEquals("old-topic", interests.readForCatalogBootstrap()!!.single().originTopicId)
            if (kind == "stale-init") assertTrue(cache.invalidations.value.isNotEmpty())
            return
        }
        repeat(80) { controller.reconcile(emptyList(), true) }
        val saved = graph.readMetadata()!!
        assertTrue(saved.canonicalCatalogReady)
        assertFalse(saved.complete)
        assertEquals(setOf("note"), saved.acceptedRevisions.keys)
        assertEquals(1, saved.topics.count { it.canonicalConcept != null })
        if (kind == "withdrawal" || kind == "unacknowledged") assertTrue(cache.invalidations.value.isEmpty())
        if (kind == "reset" || kind == "reset-reject") {
            val topicId = saved.topics.single().id
            interests.write(listOf(DiscoveryInterest("follow", "seeds", displayName = "My seeds", originTopicId = topicId)))
            rejectReset = kind == "reset-reject"
            controller.clearIndex()
            assertTrue(index.isPaused())
            assertEquals(topicId, interests.read().single().originTopicId)
            if (kind == "reset-reject") {
                assertNull(graph.readMetadata())
                assertNotNull("A failed reset write must be visible", controller.state.value.topicFailure)
                controller = controller()
                controller.reconcile(emptyList(), false)
                assertNotNull(controller.state.value.topicFailure)
                assertEquals(topicId, interests.read().single().originTopicId)
                return
            }
            assertTrue("The cleared catalog must be durably ready for rebuilding", graph.readMetadata()!!.canonicalCatalogReady)
            assertTrue(graph.readMetadata()!!.topics.isEmpty())
            assertTrue(index.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
            index.setPaused(false)
            controller = controller()
            repeat(80) { controller.reconcile(emptyList(), true) }
            assertEquals(1, graph.readMetadata()!!.topics.count { it.canonicalConcept != null })
            assertEquals("My seeds", interests.read().single().displayName)
        }
    }
}
