package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvidenceFirstSchedulingIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun aFullEvidenceBundleCanAdmitAConceptAfterIndependentSupportChecks() = runBlocking {
        val root = files.newFolder()
        val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2)
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
        val sources = linkedMapOf<String, String>()
        repeat(6) { number ->
            val id = "note$number"
            val body = "Moisture and warmth support seed germination in the garden."
            val document = ReaderDocument.fromChunkTexts(id, "Seeds $number", listOf(body))
            cache.put(CachedDocument(document, null, Instant.EPOCH))
            val revision = semanticRevision(document); sources[id] = revision
            val passage = SemanticPassage("p$number", id, "s$number", "Seeds", "b$number", "c$number", 0, body.length, body, body, 0)
            assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata("Seeds $number"),
                listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
        }
        index.setPaused(false)
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connectionPassComplete = true, policyVersion = TopicPolicy().version)))
        var proposals = 0; var supportChecks = 0
        val metrics = mutableListOf<Map<String, Long>>()
        val runtime = object : TopicLabelRuntime, DiscoveryProposalRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No legacy naming")
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                proposals++
                return DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Concept("Seed germination conditions",
                    "Moisture and warmth support seed germination.", "Listen about seed germination.",
                    listOf(request.evidence.first().passageId)))
            }
            override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun {
                supportChecks++; return TopicSupportRun.Completed(SupportDecision.SUPPORTED)
            }
            override suspend fun close() {}
        }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, topicLabelRuntime = runtime, topicLabelModelAvailable = { true },
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")),
            proposalProgressStore = FileProposalProgressStore(root.resolve("progress")),
            topicSupportIdentity = { identity.copy(prompt = "support") }, topicProposalIdentity = { identity },
            workDiagnostics = { metrics += it })
        controller.reconcile(emptyList(), false)
        repeat(300) {
            if (graph.readMetadata()!!.topics.firstOrNull()?.canonicalConcept?.matchingComplete != true)
                controller.reconcile(emptyList(), true)
        }
        val topics = graph.readMetadata()!!.topics
        assertTrue("The bounded bundle must reach proposal generation", proposals > 0)
        assertTrue("The concept must receive independent support checks", supportChecks > 0)
        assertEquals(supportChecks.toLong(), metrics.sumOf { it["supportFreshDecisions"] ?: 0 })
        assertTrue(metrics.any { (it["supportReused"] ?: 0) > 0 })
        assertEquals(1L, metrics.sumOf { it["topicsAdmitted"] ?: 0 })
        assertEquals(1, metrics.count { "firstPublishedTopicNanos" in it })
        assertTrue(metrics.any { "canonicalAdmissionNanos" in it })
        assertTrue(metrics.any { "corpusReplayNanos" in it })
        assertTrue("A fully checked concept must not be blocked by a full proposal bundle", topics.isNotEmpty())
        assertTrue(ProposalIncompleteReason.EVIDENCE_LIMIT in topics.first().canonicalConcept!!.incomplete)
        assertTrue(topics.first().canonicalConcept!!.citations.isNotEmpty())
        assertTrue("Matching completeness comes from the independent corpus pass", topics.first().canonicalConcept!!.matchingComplete)
        assertEquals(sources, topics.first().canonicalConcept!!.scannedRevisions)
        val published = controller.state.value.publishedTopics.single().topic
        assertEquals("Complete matching must show every independently matched Note",
            sources.keys, published.matchingMembers.map { it.pageId }.toSet())
    }
    @Test fun reconciliationRunsAndReusesAnAbstainedProposal() = runBlocking { scenario(false) }
    @Test fun reconciliationAdmitsAnIndependentlySupportedConcept() = runBlocking { scenario(true) }
    @Test fun aFailedProposalCanBeRetriedAfterContinuation() = runBlocking { scenario(false, failFirst = true) }
    @Test fun unreadableCatalogCannotBeTreatedAsAnEmptyCatalog() = runBlocking { scenario(false, corrupt = true) }
    @Test fun withdrawalDiscardsUnreadableEvidenceWithoutBootstrappingAnEmptyCatalog() = runBlocking {
        scenario(false, corrupt = true, withdraw = true)
    }
    @Test fun oneNoteCanBeAdmittedWithoutGlobalFormation() = runBlocking { scenario(true, incrementalCatalog = true) }
    @Test fun connectionWorkCompletesAfterNewSourceAdmission() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true) }
    @Test fun indexedNotesAreAcceptedBeforeAnotherNoteFailsIndexing() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true, blockedIndex = true) }
    @Test fun aNewlyIndexedNoteIsAcceptedBeforeTheNextNoteFails() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true, blockedIndex = true, newFirst = true) }
    @Test fun aVerifiedNoteCanGenerateAProvisionalProposalWhileAnotherCannotBeIndexed() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true, blockedIndex = true, provisional = true) }
    @Test fun completedIndexingReplacesProvisionalWorkWithFullCoverage() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true, blockedIndex = true, provisional = true, recover = true) }
    @Test fun indexedNoteReachesProposalGenerationBeforeTheNextIndexJobFinishes() = runBlocking { scenario(true, incrementalCatalog = true, unaccepted = true, blockedIndex = true, interleave = true) }
    private suspend fun scenario(concept: Boolean, failFirst: Boolean = false, corrupt: Boolean = false, withdraw: Boolean = false,
        incrementalCatalog: Boolean = false, unaccepted: Boolean = false, blockedIndex: Boolean = false, newFirst: Boolean = false, provisional: Boolean = false, recover: Boolean = false, interleave: Boolean = false) {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
        val verifier = identity.copy(prompt = "support")
        val text = "Moisture and warmth help seeds germinate and begin growing."
        val doc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        if (blockedIndex) cache.put(CachedDocument(ReaderDocument.fromChunkTexts("later", "Later", listOf("Garden tools require maintenance.")), null, Instant.EPOCH))
        val revision = semanticRevision(doc)
        val passage = SemanticPassage("p", "note", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        val meta = SemanticMetadata.from(ReaderIndexEntry("note", doc.title, doc.lastEditedAt))
        if (!newFirst) assertTrue(index.write(SemanticRecord("note", revision, model, meta, listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
        index.setPaused(false)
        val sources = mapOf("note" to revision); val policy = TopicPolicy()
        val graph = FileTopicStore(root.resolve("topics"))
        assertEquals(TopicWriteResult.Written, graph.writeDetailed(TopicSnapshot(identity = model, complete = false, canonicalCatalogReady = true,
            connectionPassComplete = true, acceptedRevisions = if (unaccepted) emptyMap() else sources, policyVersion = policy.version)))
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
        var failIndex = blockedIndex && !interleave
        val laterStarted = CompletableDeferred<Unit>()
        val releaseLater = CompletableDeferred<Unit>()
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) =
                if (blockedIndex && text.isNotEmpty()) listOf(SemanticToken(1, 0, text.length)) else emptyList() }
            override suspend fun embed(text: String): List<Float> {
                if (interleave && text.contains("tools", ignoreCase = true)) { laterStarted.complete(Unit); releaseLater.await() }
                if (failIndex && ((!newFirst && !provisional) || text.contains("tools", ignoreCase = true))) throw java.io.IOException("simulated indexing failure")
                return listOf(1f, 0f)
            }
            override fun release() {}
        }
        val orderedCache = object : com.example.notionreader.domain.document.DocumentCache by cache {
            override suspend fun snapshot() = cache.snapshot().sortedBy { if (it.document.pageId == "note") 0 else 1 }
        }
        fun controller() = DiscoveryController(orderedCache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = FileTopicStore(root.resolve("topics")), topicLabelRuntime = runtime, topicLabelModelAvailable = { true },
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")),
            proposalProgressStore = FileProposalProgressStore(root.resolve("progress")), topicSupportIdentity = { verifier },
            topicProposalIdentity = { identity })
        var controller = controller()
        controller.reconcile(emptyList(), false)
        assertEquals(0, calls)
        if (interleave) {
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async { controller.reconcile(emptyList(), true) }
            try {
                withTimeout(10_000) { laterStarted.await() }
                assertTrue("The verified Note must reach proposal generation before the next embedding finishes", calls > 0)
                assertTrue(graph.readMetadata()!!.topics.isEmpty())
                assertTrue(controller.hasPendingWork)
            } finally { releaseLater.complete(Unit); job.await() }
            return
        }
        if (blockedIndex) {
            controller.reconcile(emptyList(), true)
            val saved = graph.readMetadata()!!
            assertEquals(sources, saved.acceptedRevisions)
            assertFalse(saved.complete)
            assertFalse(saved.connectionPassComplete)
            assertEquals(2, controller.state.value.cachedPageIds.size)
            assertNotNull(controller.state.value.indexFailure)
            assertTrue(controller.hasPendingWork)
            if (provisional) {
                repeat(40) { controller.reconcile(emptyList(), true) }
                assertTrue("The indexed source should reach proposal generation", calls > 0)
                val provisionalStep = controller.advanceEvidenceFirstNote("note") as EvidenceFirstNoteStep.Proposals
                val proposal = provisionalStep.window.window.proposals.single()
                assertTrue(ProposalIncompleteReason.SOURCE_SCAN in proposal.incomplete)
                assertTrue(ProposalIncompleteReason.EXISTING_CONCEPT_CATALOG in proposal.incomplete)
                assertTrue(proposal.incomplete.any { it.blocksConceptAdmission() })
                assertTrue("Incomplete corpus work cannot be published as a complete topic", graph.readMetadata()!!.topics.isEmpty())
                assertNotNull(controller.state.value.indexFailure)
                assertTrue(controller.hasPendingWork)
                if (recover) {
                    failIndex = false
                    controller.requestAutomaticContinuation()
                    repeat(160) { controller.reconcile(emptyList(), true) }
                    assertEquals(setOf("note", "later"), graph.readMetadata()!!.acceptedRevisions.keys)
                    assertTrue("state=${controller.state.value}; calls=$calls", graph.readMetadata()!!.topics.any { it.canonicalConcept != null })
                    assertNull(controller.state.value.indexFailure)
                    return
                }
                val beforeWithdrawal = calls
                cache.remove("note")
                controller = controller()
                repeat(4) { controller.reconcile(emptyList(), false) }
                assertFalse(graph.readMetadata()!!.acceptedRevisions.containsKey("note"))
                assertEquals(beforeWithdrawal, calls)
            }
            return
        }
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
            )
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
    @Test fun boundedProgressDoesNotWakeAndCancelItsOwnActiveWindow() = runBlocking { connectionsScenario(false, windowActive = true) }
    @Test fun metadataOnlyChangesRefreshConnectionEndpoints() = runBlocking { connectionsScenario(true, metadataChange = true) }
    @Test fun emptyAndAbsentMetadataDoNotShareConnectionIdentity() = runBlocking { connectionsScenario(true, metadataChange = true, area = "") }
    private suspend fun connectionsScenario(modelAvailable: Boolean, metadataChange: Boolean = false, area: String = "Garden", windowActive: Boolean = false) {
        var available = modelAvailable
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
            topicStore = graph, topicLabelRuntime = runtime, topicLabelModelAvailable = { available },
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")),
            proposalProgressStore = FileProposalProgressStore(root.resolve("progress")),
            topicSupportIdentity = { if (available) identity.copy(prompt = "support") else null },
            topicProposalIdentity = { if (available) identity else null },
            )
        controller.reconcile(emptyList(), false)
        if (windowActive) {
            val coordinator = DiscoveryWorkCoordinator(System::currentTimeMillis) {
                controller.reconcile(emptyList(), true)
                discoveryWindowResult(true, controller.state.value, true, controller.hasPendingWork)
            }
            assertEquals(DiscoveryWindowResult.DEFERRED, coordinator.runWindow(15_000))
        } else repeat(80) { controller.reconcile(emptyList(), true) }
        if (windowActive) {
            assertEquals("Internal bounded progress must not cancel its current window", 0L, controller.revision.value)
            controller.requestAutomaticContinuation()
            assertEquals("An external wake must still be delivered", 1L, controller.revision.value)
        }
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
        else {
            assertTrue(controller.hasPendingWork)
            assertEquals(DiscoveryTopicBlock.ModelUnavailable, controller.state.value.topicBlock)
            available = true
            repeat(80) { controller.reconcile(emptyList(), true) }
            assertNull("Installing the model must clear the setup message", controller.state.value.topicFailure)
            assertNull("Installing the model must unblock topic work", controller.state.value.topicBlock)
            assertFalse("Topic work must resume when the model becomes available", controller.hasPendingWork)
        }
    }

}
