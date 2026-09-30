package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.discovery.AutoCacheState
import com.example.notionreader.domain.discovery.AutoCacheStateStore
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.settings.AutoCacheMode
import com.example.notionreader.domain.settings.AutoCacheNoteCount
import com.example.notionreader.domain.settings.ReaderSettings
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoCacheReconcilerTest {

    private lateinit var stateStore: FakeAutoCacheStateStore
    private lateinit var documentCache: FakeDocumentCache
    private val prefetchedCalls = mutableListOf<String>()
    private var failPrefetchFor: String? = null

    private lateinit var reconciler: AutoCacheReconciler

    @Before
    fun setUp() {
        stateStore = FakeAutoCacheStateStore()
        documentCache = FakeDocumentCache()
        prefetchedCalls.clear()
        failPrefetchFor = null

        reconciler = AutoCacheReconciler(
            autoCacheStateStore = stateStore,
            documentCache = documentCache,
            prefetchNote = { pageId, _ ->
                if (pageId == failPrefetchFor) {
                    throw RuntimeException("Network down")
                }
                prefetchedCalls.add(pageId)
                documentCache.put(
                    CachedDocument(
                        document = ReaderDocument.fromChunkTexts(pageId, "Title $pageId", listOf("Text")),
                        remoteLastEditedAt = Instant.now(),
                        cachedAt = Instant.now()
                    )
                )
            },
            isNoteCached = { pageId -> documentCache.get(pageId) != null },
            interRequestDelayMs = 0L
        )
    }

    @Test
    fun nonManagedManualNotesAreNeverEvictedByReconciliation() = runBlocking {
        // User manually opened manual-note-1, which is in cache but NOT in autoCacheStateStore
        documentCache.put(
            CachedDocument(
                document = ReaderDocument.fromChunkTexts("manual-1", "Manual Note", listOf("Content")),
                remoteLastEditedAt = Instant.now(),
                cachedAt = Instant.now()
            )
        )
        // Auto-cache previously managed auto-1
        stateStore.write(AutoCacheState(managedPageIds = setOf("auto-1")))
        documentCache.put(
            CachedDocument(
                document = ReaderDocument.fromChunkTexts("auto-1", "Auto Note 1", listOf("Content")),
                remoteLastEditedAt = Instant.now(),
                cachedAt = Instant.now()
            )
        )

        // New plan only desires auto-2
        val entries = listOf(
            ReaderIndexEntry(pageId = "auto-2", title = "Auto Note 2", lastEditedAt = Instant.now())
        )
        val settings = ReaderSettings(
            cacheNotesForOffline = true,
            autoCacheMode = AutoCacheMode.NOTE_COUNT,
            autoCacheNoteCount = AutoCacheNoteCount.COUNT_5
        )

        reconciler.reconcile(entries, settings, Instant.now())

        // auto-2 was prefetched and is now cached
        assertNotNull(documentCache.get("auto-2"))
        // auto-1 was previously managed and is now obsolete -> evicted
        assertNull(documentCache.get("auto-1"))
        // manual-1 was NEVER auto-managed -> must SURVIVE untouched!
        assertNotNull(documentCache.get("manual-1"))
        // State store now only manages auto-2
        assertEquals(setOf("auto-2"), stateStore.read().managedPageIds)
    }

    @Test
    fun failureSafePrefetchPreservesExistingCachedDesiredCandidate() = runBlocking {
        // Note auto-1 is desired and already in cache
        documentCache.put(
            CachedDocument(
                document = ReaderDocument.fromChunkTexts("auto-1", "Auto Note 1", listOf("Content")),
                remoteLastEditedAt = Instant.now(),
                cachedAt = Instant.now()
            )
        )
        stateStore.write(AutoCacheState(managedPageIds = setOf("auto-1")))

        // Note auto-2 is desired but not in cache, and prefetch fails
        failPrefetchFor = "auto-2"

        val entries = listOf(
            ReaderIndexEntry(pageId = "auto-1", title = "Auto 1", lastEditedAt = Instant.now()),
            ReaderIndexEntry(pageId = "auto-2", title = "Auto 2", lastEditedAt = Instant.now())
        )
        val settings = ReaderSettings(
            cacheNotesForOffline = true,
            autoCacheMode = AutoCacheMode.NOTE_COUNT,
            autoCacheNoteCount = AutoCacheNoteCount.COUNT_5
        )

        reconciler.reconcile(entries, settings, Instant.now())

        // auto-1 is retained in cache and still managed
        assertNotNull(documentCache.get("auto-1"))
        assertTrue(stateStore.read().managedPageIds.contains("auto-1"))
        // auto-2 failed to prefetch, so it couldn't be added to managed
        assertFalse(stateStore.read().managedPageIds.contains("auto-2"))
    }

    @Test
    fun protectedNotesInActiveQueueArePreservedEvenIfObsoleteInAutoCache() = runBlocking {
        stateStore.write(AutoCacheState(managedPageIds = setOf("queued-old")))
        documentCache.put(
            CachedDocument(
                document = ReaderDocument.fromChunkTexts("queued-old", "Old Queued", listOf("Content")),
                remoteLastEditedAt = Instant.now(),
                cachedAt = Instant.now()
            )
        )

        // Planner selects nothing
        val settings = ReaderSettings(
            cacheNotesForOffline = true,
            autoCacheMode = AutoCacheMode.OFF
        )

        // queued-old is in protectedPageIds
        reconciler.reconcile(emptyList(), settings, Instant.now(), protectedPageIds = setOf("queued-old"))

        // queued-old must not be evicted
        assertNotNull(documentCache.get("queued-old"))
    }

    private class FakeAutoCacheStateStore : AutoCacheStateStore {
        private var state = AutoCacheState()
        override suspend fun read(): AutoCacheState = state
        override suspend fun write(state: AutoCacheState) { this.state = state }
        override suspend fun clear() { state = AutoCacheState() }
    }

    private class FakeDocumentCache : DocumentCache {
        private val storage = mutableMapOf<String, CachedDocument>()

        override suspend fun get(pageId: String): CachedDocument? = storage[pageId]
        override suspend fun put(document: CachedDocument) { storage[document.document.pageId] = document }
        override suspend fun remove(pageId: String) { storage.remove(pageId) }
        override suspend fun clear() { storage.clear() }
        override suspend fun getMostRecentlyUsed(): CachedDocument? = storage.values.lastOrNull()
        override suspend fun getNewestCached(): CachedDocument? = storage.values.lastOrNull()
        override suspend fun count(): Int = storage.size
        override suspend fun removeAll(pageIds: Set<String>): Int {
            var count = 0
            for (id in pageIds) {
                if (storage.remove(id) != null) count++
            }
            return count
        }
    }
}
