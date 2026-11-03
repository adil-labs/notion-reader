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
                retrievals = retrievals, requireKnownCatalogOnRestore = true)
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
            retrievals = retrievals, requireKnownCatalogOnRestore = true)
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
            retrievals = retrievals, requireKnownCatalogOnRestore = true)
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
            retrievals = retrievals, requireKnownCatalogOnRestore = true)
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
