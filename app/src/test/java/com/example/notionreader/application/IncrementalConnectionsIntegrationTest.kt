package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncrementalConnectionsIntegrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun blockedProposalInferenceDoesNotStarveIndependentConnections() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val sources = linkedMapOf<String, String>()
        for ((id, title, text) in listOf(Triple("one", "Seeds", "Seeds need moisture and warmth. See Tools."),
                Triple("two", "Tools", "Tools help prepare a garden for planting."))) {
            val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); sources[id] = revision
            val passage = SemanticPassage("$id:p", id, "$id:s", title, "$id:b", "$id:c", 0, text.length, text, text, 0)
            assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(title), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
        }
        index.setPaused(false)
        val graph = FileTopicStore(root.resolve("topics")); val policy = TopicPolicy()
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connectionPassComplete = false, policyVersion = policy.version)))
        val identity = TopicLabelIdentity("a", "t", "template", "proposal", "schema", "v1", "CPU")
        var attempts = 0
        val runtime = object : TopicLabelRuntime, DiscoveryProposalRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("Legacy naming is not used")
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun { attempts++; return DiscoveryProposalRun.Unavailable }
            override suspend fun verifySupport(request: TopicSupportRequest) = TopicSupportRun.Completed(SupportDecision.SUPPORTED)
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
            topicRetrievalStore = FileTopicRetrievalStore(root.resolve("queries")), proposalProgressStore = FileProposalProgressStore(root.resolve("progress")),
            topicSupportIdentity = { identity.copy(prompt = "support") }, topicProposalIdentity = { identity })
        repeat(100) { controller.reconcile(emptyList(), true) }
        assertTrue("The fixture must reach blocked proposal inference", attempts > 0)
        val saved = graph.read()!!
        assertTrue("Independent connection work must finish despite blocked proposals", saved.connectionPassComplete)
        assertTrue(saved.connections.any { it.independentNamedReference })
        assertTrue("Proposal processing must remain honestly pending", controller.hasPendingWork)
        assertNotNull(controller.state.value.topicFailure)
    }

    @Test fun aBoundedConnectionSweepResumesAfterControllerRecreation() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val sources = linkedMapOf<String, String>()
        repeat(20) { n ->
            val id = "note%02d".format(n); val text = "Moisture and warmth support the germination of seeds."
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); sources[id] = revision
            val p = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, text.length, text, text, 0)
            assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        }
        var reads = 0
        val observed = object : SemanticIndexStore by index {
            override suspend fun read(pageId: String): SemanticRecord? { reads++; return index.read(pageId) }
        }
        val policy = TopicPolicy(); val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connectionPassComplete = false, policyVersion = policy.version)))
        fun coordinator(): TopicCoordinator {
            val matches = FileTopicRetrievalStore(root.resolve("matches"))
            val retrievals = TopicRetrievalCoordinator(observed, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
            return TopicCoordinator(cache, observed, FileTopicStore(root.resolve("topics")), null, { model }, policy, Mutex(), { true }, {}, {},
                retrievals = retrievals)
        }
        var worker = coordinator(); worker.invalidate(sources)
        assertEquals(IncrementalConnectionsResult.Pending, worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources))
        val partial = FileTopicStore(root.resolve("topics")).readMetadata()!!
        assertFalse(partial.connectionPassComplete)
        assertTrue(partial.connectionCursor > 0 || partial.connectionPassageCursor > 0 || partial.connectionTargetCursor > 0)
        worker = coordinator(); worker.invalidate(sources)
        reads = 0
        repeat(4) {
            val ticket = worker.captureCatalogTicket()!!
            if (!ticket.snapshot.connectionPassComplete) worker.advanceIncrementalConnections(ticket, sources)
        }
        assertTrue("Resuming must avoid replaying completed work: $reads reads", reads <= 140)
        val result = FileTopicStore(root.resolve("topics")).read()!!
        assertTrue(result.connectionPassComplete)
        assertEquals(190, result.connections.size)
        assertEquals(190, result.connections.map { it.id }.toSet().size)
        assertFalse(result.complete)
    }
    @Test fun anUnreadableConnectionWriteCannotReplaceTheInspectedSnapshot() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val sources = linkedMapOf<String, String>(); val members = mutableListOf<TopicMembership>()
        for (id in listOf("one", "two")) {
            val text = "Moisture and warmth support the germination of seeds."
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); sources[id] = revision
            val passage = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, text.length, text, text, 0)
            assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
            members += TopicMembership(id, SemanticMetadata(id), passage, .8f, passage, revision)
        }
        val backing = FileTopicStore(root.resolve("topics")); val policy = TopicPolicy()
        assertTrue(backing.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connections = listOf(NoteConnection("prior", members[0], members[1], .8f)),
            connectionPassComplete = false, policyVersion = policy.version)))
        var unreadable = false
        val graph = object : TopicStore by backing {
            override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                val result = backing.writeGuarded(snapshot, current)
                if (result == TopicGuardedWriteResult.Written) unreadable = true
                return result
            }
            override suspend fun readMetadata(): TopicSnapshot? = if (unreadable) null else backing.readMetadata()
        }
        val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(root.resolve("matches")), { model },
            TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        var shown = TopicView()
        val worker = TopicCoordinator(cache, index, graph, null, { model }, policy, Mutex(), { true }, {}, { shown = it },
            retrievals = retrievals)
        worker.invalidate(sources); worker.setInspected(true)
        val previous = shown.snapshot
        assertEquals("prior", previous.connections.single().id)
        assertEquals(IncrementalConnectionsResult.Failed, worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources))
        assertTrue(unreadable)
        assertEquals(previous, shown.snapshot)
        assertTrue(shown.status.orEmpty().contains("could not be reloaded"))
    }

    @Test fun aWithdrawalImmediatelyAfterAConnectionWriteCannotRestoreItsEvidence() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val sources = linkedMapOf<String, String>()
        for (id in listOf("one", "two")) {
            val text = "Moisture and warmth support the germination of seeds."
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); sources[id] = revision
            val p = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, text.length, text, text, 0)
            assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        }
        val backing = FileTopicStore(root.resolve("topics")); val policy = TopicPolicy()
        val priorWork = ConnectionWork(inputs = sources.mapValues { semanticHash("prior-input") }, titleKeys = sources.mapValues { semanticHash("prior-title") },
            changedIds = sources.keys, incrementalKey = semanticHash("prior-sweep"))
        assertTrue(backing.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connectionPassComplete = false, connectionTargetCount = sources.size, connectionWork = priorWork, policyVersion = policy.version)))
        var withdrawn = false
        val graph = object : TopicStore by backing {
            override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                val result = backing.writeGuarded(snapshot, current)
                if (!withdrawn && result == TopicGuardedWriteResult.Written && snapshot.connections.isNotEmpty()) {
                    withdrawn = true; cache.remove("two")
                }
                return result
            }
        }
        val matches = FileTopicRetrievalStore(root.resolve("matches"))
        val retrievals = TopicRetrievalCoordinator(index, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val worker = TopicCoordinator(cache, index, graph, null, { model }, policy, Mutex(), { true }, {}, {},
            retrievals = retrievals)
        worker.invalidate(sources)
        assertEquals(IncrementalConnectionsResult.Deferred, worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources))
        assertTrue(withdrawn)
        val restored = FileTopicStore(root.resolve("topics")).read()!!
        assertEquals(mapOf("one" to sources.getValue("one")), restored.acceptedRevisions)
        assertTrue(restored.connections.none { it.left.pageId == "two" || it.right.pageId == "two" })
        assertTrue(cache.invalidations.value.isNotEmpty())
    }

    @Test fun anUnreadableIndexedSourceCannotBeReportedAsCompleteCoverage() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val backing = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val sources = linkedMapOf<String, String>()
        for (id in listOf("one", "two")) {
            val text = "Moisture and warmth support the germination of seeds."
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); sources[id] = revision
            val p = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, text.length, text, text, 0)
            assertTrue(backing.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        }
        val index = object : SemanticIndexStore by backing {
            override suspend fun read(pageId: String) = if (pageId == "two") null else backing.read(pageId)
        }
        val graph = FileTopicStore(root.resolve("topics")); val policy = TopicPolicy()
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connectionPassComplete = false, policyVersion = policy.version)))
        val matches = FileTopicRetrievalStore(root.resolve("matches"))
        val retrievals = TopicRetrievalCoordinator(index, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        var view = TopicView()
        val worker = TopicCoordinator(cache, index, graph, null, { model }, policy, Mutex(), { true }, {}, { view = it },
            retrievals = retrievals)
        worker.invalidate(sources)
        assertEquals(IncrementalConnectionsResult.Failed, worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources))
        assertFalse(graph.readMetadata()!!.connectionPassComplete)
        assertNotNull(view.failure)
    }

    @Test fun metadataChangedDuringASweepCannotCommitStaleEndpoints() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val backing = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val sources = linkedMapOf<String, String>()
        for (id in listOf("one", "two")) {
            val text = "Moisture and warmth support the germination of seeds."
            val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
            val revision = semanticRevision(doc); sources[id] = revision
            val p = SemanticPassage("$id:p", id, "$id:s", "Seeds", "$id:b", "$id:c", 0, text.length, text, text, 0)
            assertTrue(backing.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val index = object : SemanticIndexStore by backing {
            var first = true
            override suspend fun read(pageId: String): SemanticRecord? {
                if (first) { first = false; entered.complete(Unit); release.await() }
                return backing.read(pageId)
            }
        }
        val graph = FileTopicStore(root.resolve("topics")); val policy = TopicPolicy()
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources,
            connectionPassComplete = false, policyVersion = policy.version)))
        val matches = FileTopicRetrievalStore(root.resolve("matches"))
        val retrievals = TopicRetrievalCoordinator(index, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val worker = TopicCoordinator(cache, index, graph, null, { model }, policy, Mutex(), { true }, {}, {},
            retrievals = retrievals)
        worker.invalidate(sources)
        val ticket = worker.captureCatalogTicket()!!
        val sweep = async { worker.advanceIncrementalConnections(ticket, sources) }
        entered.await()
        val revised = mapOf("one" to SemanticMetadata("one", area = "Garden"), "two" to SemanticMetadata("two"))
        worker.build(sources, semanticMetadataHash(revised), cache.revision.value, metadata = revised)
        release.complete(Unit)
        assertEquals(IncrementalConnectionsResult.Deferred, sweep.await())
        assertFalse(graph.readMetadata()!!.connectionPassComplete)
        assertEquals("Metadata that is not present in the index cannot be claimed as processed",
            IncrementalConnectionsResult.Failed, worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources))
        assertFalse(graph.readMetadata()!!.connectionPassComplete)
    }

}
