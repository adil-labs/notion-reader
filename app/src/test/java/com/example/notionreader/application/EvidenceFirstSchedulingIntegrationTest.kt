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
    @Test fun connectionWorkCompletesAfterNewSourceAdmission() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true) }
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
        if (unaccepted) assertTrue("Connection work must finish", controller.state.value.topicSnapshot.connectionPassComplete)
        assertFalse(controller.hasPendingWork)
        controller = controller()
        controller.reconcile(emptyList(), false)
        repeat(80) { controller.reconcile(emptyList(), true) }
        assertEquals("Controller recreation must reuse the exact result", expectedCalls, calls)
        assertFalse(controller.hasPendingWork)
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
        assertTrue("Connection rebuilding may finish in the next bounded step", graph.readMetadata()!!.connectionPassComplete)
    }

    @Test fun independentConnectionsAreBuiltWithoutLegacyFormation() = runBlocking { connectionsScenario(true) }
    @Test fun connectionsCanAdvanceWhenTheProposalModelIsUnavailable() = runBlocking { connectionsScenario(false) }
    @Test fun metadataOnlyChangesRefreshConnectionEndpoints() = runBlocking { connectionsScenario(true, metadataChange = true) }
    @Test fun emptyAndAbsentMetadataDoNotShareConnectionIdentity() = runBlocking { connectionsScenario(true, metadataChange = true, area = "") }
    private suspend fun connectionsScenario(modelAvailable: Boolean, metadataChange: Boolean = false, area: String = "Garden") {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val sources = linkedMapOf<String, String>()
        for (id in listOf("one", "two")) {
            val body = "Moisture and warmth support the germination of seeds."
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(body)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); sources[id] = revision
            val p = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, body.length, body, body, 0)
            assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        }
        index.setPaused(false)
        val graph = FileTopicStore(root.resolve("topics")); val policy = TopicPolicy()
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connectionPassComplete = false, policyVersion = policy.version)))
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
        val runtime = object : TopicLabelRuntime, DiscoveryProposalRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No legacy naming")
            override suspend fun propose(request: DiscoveryProposalRequest) = DiscoveryProposalRun.Completed(
                DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.NO_USEFUL_TOPIC))
            override suspend fun close() {}
        }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, topicLabelRuntime = runtime, topicLabelModelAvailable = { modelAvailable },
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")),
            proposalProgressStore = FileProposalProgressStore(root.resolve("progress")),
            topicSupportIdentity = { if (modelAvailable) identity.copy(prompt = "support") else null },
            topicProposalIdentity = { if (modelAvailable) identity else null },
            enableEvidenceFirstScheduling = true)
        controller.reconcile(emptyList(), false)
        repeat(80) { controller.reconcile(emptyList(), true) }
        val saved = FileTopicStore(root.resolve("topics")).read()!!
        assertTrue("Independent connection sweep must finish", saved.connectionPassComplete)
        assertFalse("No legacy global formation is needed", saved.complete)
        assertEquals(setOf("one", "two"), saved.connections.single().let { setOf(it.left.pageId, it.right.pageId) })
        if (metadataChange) {
            val entries = listOf(ReaderIndexEntry("one", "one", area = area), ReaderIndexEntry("two", "two"))
            repeat(80) { controller.reconcile(entries, true) }
            val refreshed = FileTopicStore(root.resolve("topics")).read()!!.connections.single()
            assertEquals(area, listOf(refreshed.left, refreshed.right).single { it.pageId == "one" }.metadata.area)
        }
        if (modelAvailable) assertFalse(controller.hasPendingWork)
        else { assertTrue(controller.hasPendingWork); assertNotNull(controller.state.value.topicFailure) }
    }

}
