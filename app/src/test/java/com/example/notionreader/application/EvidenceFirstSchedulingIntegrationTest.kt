package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvidenceFirstSchedulingIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun reconciliationRunsAndReusesAnAbstainedProposal() = runBlocking { scenario(false) }
    @Test fun reconciliationAdmitsAnIndependentlySupportedConcept() = runBlocking { scenario(true) }
    @Test fun aFailedProposalCanBeRetriedAfterContinuation() = runBlocking { scenario(false, failFirst = true) }
    @Test fun unreadableCatalogCannotBeTreatedAsAnEmptyCatalog() = runBlocking { scenario(false, corrupt = true) }
    @Test fun withdrawalDiscardsUnreadableEvidenceWithoutBootstrappingAnEmptyCatalog() = runBlocking {
        scenario(false, corrupt = true, withdraw = true)
    }
    @Test fun oneNoteCanBeAdmittedWithoutGlobalFormation() = runBlocking { scenario(true, incrementalCatalog = true) }
    @Test fun connectionWorkRemainsPendingAfterNewSourceAdmission() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true) }
    private suspend fun scenario(concept: Boolean, failFirst: Boolean = false, corrupt: Boolean = false, withdraw: Boolean = false,
        incrementalCatalog: Boolean = false, unaccepted: Boolean = false) {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = identity.copy(prompt = "support")
        val text = "Moisture and warmth help seeds germinate and begin growing."
        val doc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc)
        val passage = SemanticPassage("p", "note", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        val meta = SemanticMetadata.from(ReaderIndexEntry("note", doc.title, doc.lastEditedAt))
        assertTrue(index.write(SemanticRecord("note", revision, model, meta, listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
        index.setPaused(false)
        val sources = mapOf("note" to revision); val policy = TopicPolicy()
        val graph = FileTopicStore(root.resolve("topics"))
        assertEquals(TopicWriteResult.Written, graph.writeDetailed(TopicSnapshot(identity = model, complete = !incrementalCatalog, canonicalCatalogReady = incrementalCatalog,
            connectionPassComplete = true, acceptedRevisions = if (unaccepted) emptyMap() else sources, policyVersion = policy.version,
            sourceSignature = if (incrementalCatalog) null else topicSourceSignature(model, policy, semanticMetadataHash(mapOf("note" to meta)), sources))))
        if (corrupt) root.resolve("topics/topics.json").writeText("{broken")
        var calls = 0; var fail = failFirst
        val runtime = object : TopicLabelRuntime, DiscoveryProposalRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No legacy naming")
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                calls++
                if (fail) return DiscoveryProposalRun.Unavailable
                val response = if (concept) DiscoveryProposalResponse.Concept("Seed germination conditions",
                    "Moisture and warmth support seed germination.", "Listen to conditions for seed germination.",
                    listOf(request.evidence.first().passageId)) else DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.NO_USEFUL_TOPIC)
                return DiscoveryProposalRun.Completed(response)
            }
            override suspend fun verifySupport(request: TopicSupportRequest) = TopicSupportRun.Completed(SupportDecision.SUPPORTED)
            override suspend fun close() {}
        }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        fun controller() = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = FileTopicStore(root.resolve("topics")), topicLabelRuntime = runtime, topicLabelModelAvailable = { true },
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")),
            proposalProgressStore = FileProposalProgressStore(root.resolve("progress")), topicSupportIdentity = { verifier },
            topicProposalIdentity = { identity }, enableEvidenceFirstScheduling = true)
        var controller = controller()
        controller.reconcile(emptyList(), false)
        if (!corrupt && !incrementalCatalog) {
            assertTrue("A compatible legacy catalog must migrate before incremental work", controller.state.value.topicSnapshot.canonicalCatalogReady)
        }
        assertEquals(0, calls)
        if (corrupt) {
            repeat(3) { controller.reconcile(emptyList(), true) }
            assertNotNull(controller.state.value.topicFailure)
            assertTrue(controller.hasPendingWork)
            assertEquals(0, calls)
            assertEquals("{broken", root.resolve("topics/topics.json").readText())
            if (withdraw) {
                cache.remove("note")
                controller.reconcile(emptyList(), false)
                assertFalse(root.resolve("topics/topics.json").exists())
                assertTrue(cache.invalidations.value.isNotEmpty())
                controller = controller()
                repeat(3) { controller.reconcile(emptyList(), true) }
                assertNotNull(controller.state.value.topicFailure)
                assertEquals(0, calls)
                assertFalse(root.resolve("topics/topics.json").exists())
            }
            return
        }
        repeat(80) { controller.reconcile(emptyList(), true) }
        if (failFirst) {
            assertNotNull(controller.state.value.topicFailure)
            assertTrue(controller.hasPendingWork)
            fail = false
            controller.requestAutomaticContinuation()
            repeat(80) { controller.reconcile(emptyList(), true) }
        }
        val expectedCalls = if (failFirst) 2 else 1
        assertEquals("state=${controller.state.value}", expectedCalls, calls)
        if (concept) {
            assertTrue("state=${controller.state.value}, pending=${controller.hasPendingWork}", controller.state.value.topicSnapshot.topics.any { it.canonicalConcept != null })
            val admitted = controller.state.value.topicSnapshot.topics.single { it.canonicalConcept != null }
            assertEquals(setOf("note"), admitted.matchingMembers.map { it.pageId }.toSet())
            assertEquals(TopicPublicationStatus.Emerging, discoveryTopicPublicationStatus(admitted))
            if (incrementalCatalog) assertFalse("Admission must not require global formation", controller.state.value.topicSnapshot.complete)
        }
        if (unaccepted) {
            assertTrue(controller.hasPendingWork)
            assertNotNull("Unfinished connection work must not spin", controller.state.value.topicFailure)
        } else assertFalse(controller.hasPendingWork)
        controller = controller()
        controller.reconcile(emptyList(), false)
        repeat(80) { controller.reconcile(emptyList(), true) }
        assertEquals("Controller recreation must reuse the exact result", expectedCalls, calls)
        if (unaccepted) assertTrue(controller.hasPendingWork) else assertFalse(controller.hasPendingWork)
    }
    @Test fun sourceAcceptanceIsBoundedAndDoesNotPublishPartialInventory() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val revisions = linkedMapOf<String, String>()
        repeat(10) { n ->
            val id = "note%02d".format(n); val body = "Evidence about seed germination number $n."
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(body)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); revisions[id] = revision
            val p = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, body.length, body, body, 0)
            assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        }
        index.setPaused(false)
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, connectionPassComplete = true)))
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")),
            proposalProgressStore = FileProposalProgressStore(root.resolve("progress")),
            topicSupportIdentity = { verifier }, topicProposalIdentity = { verifier.copy(prompt = "proposal") },
            enableEvidenceFirstScheduling = true)
        controller.reconcile(emptyList(), false)
        controller.reconcile(emptyList(), true)
        assertTrue("A bounded partial scan cannot publish a partial source inventory", graph.readMetadata()!!.acceptedRevisions.isEmpty())
        assertNull("A normal bounded continuation is not a processing failure", controller.state.value.topicFailure)
        controller.reconcile(emptyList(), true)
        assertEquals(revisions, graph.readMetadata()!!.acceptedRevisions)
        assertFalse(graph.readMetadata()!!.connectionPassComplete)
    }

}
