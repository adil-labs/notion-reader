package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryControllerTest {
    @Test fun searchTimingIncludesEmbeddingAndNeverContainsTheQuery() = runBlocking {
        val dir = Files.createTempDirectory("search-timing").toFile()
        try {
            val samples = mutableListOf<Map<String, Long>>()
            val provider = Provider().apply { onEmbed = { Thread.sleep(25) } }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, queryTimings = { samples += it })
            controller.search("private query text")
            val timing = samples.last()
            assertTrue("Whole search timing must include inference", timing.getValue("searchTotalNs") >= timing.getValue("embeddingNs"))
            assertTrue(timing.getValue("embeddingNs") >= 20_000_000L)
            assertEquals(1L, timing.getValue("completed"))
            assertEquals(setOf("searchTotalNs", "waitNs", "embeddingNs", "retrievalNs", "completed"), timing.keys)
        } finally { dir.deleteRecursively() }
    }
    @Test fun aFailingTimingObserverCannotTurnSuccessfulSearchIntoAnError() = runBlocking {
        val dir = Files.createTempDirectory("search-observer").toFile()
        try {
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")),
                FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), queryTimings = { error("diagnostic failure") })
            controller.search("query")
            assertNull(controller.state.value.message)
            assertFalse(controller.state.value.searching)
        } finally { dir.deleteRecursively() }
    }
    @Test fun editingOneParagraphReusesUnchangedPassageEmbeddings() = runBlocking {
        val dir = Files.createTempDirectory("discovery-incremental").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            val original = ReaderDocument.fromChunkTexts("p", "Title", listOf("first", "second"))
            cache.put(CachedDocument(original, null, Instant.EPOCH)); store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertEquals(2, provider.calls)
            val edited = original.copy(sections = original.sections.map { section -> section.copy(chunks = section.chunks.mapIndexed { i, c -> if (i == 0) c.copy(text = "edited") else c }) })
            cache.put(CachedDocument(edited, null, Instant.EPOCH))
            controller.reconcile(emptyList(), true)
            assertEquals(3, provider.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun olderRecommendationsAreRankedAmongEligibleOlderNotes() = runBlocking {
        val dir = Files.createTempDirectory("discovery-older").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                clock = { Instant.parse("2026-10-15T00:00:00Z") })
            val entries = (0..5).map { i ->
                val id = if (i == 5) "zOld" else "aRecent$i"
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, id, listOf("concept")), null, Instant.EPOCH))
                ReaderIndexEntry(id, id, Instant.parse(if (i == 5) "2020-01-01T00:00:00Z" else "2026-10-14T00:00:00Z"))
            }
            store.setPaused(false); controller.reconcile(entries, true)
            controller.saveInterest(null, "concept"); controller.refreshRecommendations()
            assertEquals(listOf("zold"), controller.state.value.older.map { it.pageId })
        } finally { dir.deleteRecursively() }
    }

    private class Provider(private val version: String = "test") : EmbeddingProvider {
        var calls = 0
        var onEmbed: (suspend () -> Unit)? = null
        override val identity = EmbeddingIdentity(version, 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text).map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
        }
        override suspend fun embed(text: String): List<Float> { calls++; onEmbed?.invoke(); return listOf(1f, 0f) }
    }

    @Test fun unchangedContentReusesEmbeddingsAndMetadataOnlyChangesStillFilter() = runBlocking {
        val dir = Files.createTempDirectory("discovery-controller").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("content")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(listOf(ReaderIndexEntry("p", "Title", area = "Old")), true)
            assertEquals(1, provider.calls)
            controller.reconcile(listOf(ReaderIndexEntry("p", "Title", area = "New")), true)
            assertEquals(1, provider.calls)
            controller.search("concept", BrowseQuery(selectedArea = "New"))
            assertEquals(listOf("p"), controller.state.value.results.map { it.pageId })
            cache.clear()
            controller.reconcile(emptyList(), false)
            assertTrue(controller.state.value.results.isEmpty())
            assertTrue(store.pageIds().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun evictionDuringInferenceCannotCommitOrPublishTheRemovedDocument() = runBlocking {
        val dir = Files.createTempDirectory("discovery-eviction").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider().apply { onEmbed = { cache.clear() } }
            val controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("secret")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertTrue(store.pageIds().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun clearingTheIndexCannotBeUndoneByAnInFlightEmbedding() = runBlocking {
        val dir = Files.createTempDirectory("discovery-reset").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = FileSemanticIndexStore(dir.resolve("index"))
            lateinit var controller: DiscoveryController
            val provider = Provider().apply { onEmbed = { controller.clearIndex() } }
            controller = DiscoveryController(cache, store, FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("secret")), null, Instant.EPOCH))
            store.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertTrue(store.pageIds().isEmpty())
            assertTrue(store.isPaused())
            assertTrue(controller.state.value.paused)
        } finally { dir.deleteRecursively() }
    }

    @Test fun pausingBulkWorkDoesNotGenerateEmbeddingsAndExplicitInterestsRoundTrip() = runBlocking {
        val dir = Files.createTempDirectory("discovery-pause").toFile()
        try {
            val provider = Provider()
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")), FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            controller.reconcile(emptyList(), false)
            assertEquals(0, provider.calls)
            controller.saveInterest(null, "  motivation  ")
            assertEquals("motivation", controller.state.value.interests.single().text)
            controller.removeInterest(controller.state.value.interests.single().id)
            assertTrue(controller.state.value.interests.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedIndexingResumesFromCompletedDocumentsWithoutReembeddingThem() = runBlocking {
        val dir = Files.createTempDirectory("discovery-resume").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            listOf("a", "b").forEach { id -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, id, listOf("text")), null, Instant.EPOCH)) }
            index.setPaused(false)
            val interrupted = Provider().apply { onEmbed = { if (calls == 2) throw kotlinx.coroutines.CancellationException("interrupted") } }
            try {
                DiscoveryController(cache, index, interests, interrupted).reconcile(emptyList(), true)
                fail("Indexing must propagate cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, index.pageIds().size)
            val resumed = Provider()
            DiscoveryController(cache, FileSemanticIndexStore(dir.resolve("index")), interests, resumed).reconcile(emptyList(), true)
            assertEquals(1, resumed.calls)
            assertEquals(2, index.pageIds().size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun changingTheModelRebuildsRecordsInsteadOfReusingIncompatibleVectors() = runBlocking {
        val dir = Files.createTempDirectory("discovery-model-change").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Title", listOf("text")), null, Instant.EPOCH))
            index.setPaused(false)
            DiscoveryController(cache, index, interests, Provider("old")).reconcile(emptyList(), true)
            val replacement = Provider("replacement")
            DiscoveryController(cache, index, interests, replacement).reconcile(emptyList(), true)
            assertEquals(1, replacement.calls)
            assertEquals(replacement.identity, index.read("p")!!.identity)
            assertEquals(1, cache.count())
        } finally { dir.deleteRecursively() }
    }
}
