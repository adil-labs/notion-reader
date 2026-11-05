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

class CanonicalSourceAcceptanceTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun anEditedSourceCannotBeAcceptedAfterItsIndexRead() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val backing = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val text = "Seeds germinate with moisture and warmth."
        val doc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(text))
        cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc); val p = SemanticPassage("p", "note", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        assertTrue(backing.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        val index = object : SemanticIndexStore by backing {
            var change = true
            override suspend fun read(pageId: String): SemanticRecord? {
                val old = backing.read(pageId)
                if (change) {
                    change = false
                    cache.put(CachedDocument(ReaderDocument.fromChunkTexts("note", "Seeds", listOf("An edited seed passage.")), null, Instant.EPOCH))
                }
                return old
            }
        }
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true)))
        val matches = FileTopicRetrievalStore(root.resolve("matches"))
        val retrievals = TopicRetrievalCoordinator(index, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val coordinator = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {},
            retrievals = retrievals)
        val before = cache.revision.value
        coordinator.invalidate(mapOf("note" to revision))
        coordinator.build(mapOf("note" to revision), 0, before)
        assertNotEquals(before, cache.revision.value)
        assertTrue("A stale source must not enter persisted catalog coverage", graph.readMetadata()!!.acceptedRevisions.isEmpty())
        assertTrue(coordinator.sourceAcceptancePending)
    }
    @Test fun anEditImmediatelyAfterTheWriteCannotResurrectStaleCoverage() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val text = "Seeds germinate with moisture and warmth."
        val doc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(text))
        cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc); val p = SemanticPassage("p", "note", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        assertTrue(index.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        val backing = FileTopicStore(root.resolve("topics"))
        assertTrue(backing.write(TopicSnapshot(identity = model, canonicalCatalogReady = true)))
        var changed = false
        val graph = object : TopicStore by backing {
            override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                val result = backing.writeGuarded(snapshot, current)
                if (!changed && result == TopicGuardedWriteResult.Written && snapshot.acceptedRevisions.isNotEmpty()) {
                    changed = true
                    cache.put(CachedDocument(ReaderDocument.fromChunkTexts("note", "Seeds", listOf("An edited seed passage.")), null, Instant.EPOCH))
                }
                return result
            }
        }
        val matches = FileTopicRetrievalStore(root.resolve("matches"))
        val retrievals = TopicRetrievalCoordinator(index, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val coordinator = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {},
            retrievals = retrievals)
        val before = cache.revision.value
        coordinator.invalidate(mapOf("note" to revision))
        coordinator.build(mapOf("note" to revision), 0, before)
        assertTrue(changed)
        val restored = FileTopicStore(root.resolve("topics")).readMetadata()
        assertTrue("A post-write edit must not leave stale source coverage on disk", restored == null || restored.acceptedRevisions.isEmpty())
    }

    @Test fun aRejectedInventoryWriteLeavesThePriorCatalogIntact() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val text = "Seeds germinate with moisture and warmth."
        val doc = ReaderDocument.fromChunkTexts("note", "Seeds", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc); val p = SemanticPassage("p", "note", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        assertTrue(index.write(SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        val backing = FileTopicStore(root.resolve("topics"))
        assertTrue(backing.write(TopicSnapshot(identity = model, canonicalCatalogReady = true)))
        val graph = object : TopicStore by backing {
            override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean) =
                TopicGuardedWriteResult.Rejected(TopicWriteResult.Rejected(TopicWriteFailure.IoFailure))
        }
        var view = TopicView()
        val matches = FileTopicRetrievalStore(root.resolve("matches"))
        val retrievals = TopicRetrievalCoordinator(index, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val coordinator = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, { view = it },
            retrievals = retrievals)
        val sourceRevision = cache.revision.value
        coordinator.invalidate(mapOf("note" to revision))
        coordinator.build(mapOf("note" to revision), 0, sourceRevision)
        assertTrue(backing.readMetadata()!!.acceptedRevisions.isEmpty())
        assertNotNull(view.failure)
        assertFalse(coordinator.sourceAcceptancePending)
    }

    @Test fun aVerifiedIndexedSubsetCanBeAcceptedWithoutClaimingFullCoverage() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache"))
        val index = FileSemanticIndexStore(root.resolve("index")); val model = EmbeddingIdentity("model", 2)
        val text = "Seeds germinate with moisture and warmth."
        val first = ReaderDocument.fromChunkTexts("one", "Seeds", listOf(text))
        val second = ReaderDocument.fromChunkTexts("two", "Tools", listOf("Tools for a garden."))
        cache.put(CachedDocument(first, null, Instant.EPOCH)); cache.put(CachedDocument(second, null, Instant.EPOCH))
        val sources = mapOf("one" to semanticRevision(first), "two" to semanticRevision(second))
        val p = SemanticPassage("p", "one", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        assertTrue(index.write(SemanticRecord("one", sources.getValue("one"), model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
        val graph = FileTopicStore(root.resolve("topics"))
        assertTrue(graph.write(TopicSnapshot(identity = model, canonicalCatalogReady = true)))
        val matches = FileTopicRetrievalStore(root.resolve("matches"))
        val retrievals = TopicRetrievalCoordinator(index, matches, { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, Mutex())
        val coordinator = TopicCoordinator(cache, index, graph, null, { model }, TopicPolicy(), Mutex(), { true }, {}, {},
            retrievals = retrievals)
        coordinator.invalidate(sources)
        coordinator.build(sources, 0, cache.revision.value, indexComplete = false, indexedSources = mapOf("one" to "stale"))
        assertTrue("An incompatible claimed revision must not enter the catalog", graph.readMetadata()!!.acceptedRevisions.isEmpty())
        coordinator.build(sources, 0, cache.revision.value, indexComplete = false, indexedSources = mapOf("one" to sources.getValue("one")))
        val saved = graph.readMetadata()!!
        assertEquals(mapOf("one" to sources.getValue("one")), saved.acceptedRevisions)
        assertFalse(saved.complete)
        assertFalse(saved.connectionPassComplete)
    }

}
