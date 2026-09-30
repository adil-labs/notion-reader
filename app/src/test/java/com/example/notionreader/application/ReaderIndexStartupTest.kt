package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.discovery.CachedReaderIndex
import com.example.notionreader.domain.discovery.ReaderIndexStore
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.player.FakeReaderEngine
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderIndexStartupTest {

    private class InMemoryIndexStore(var stored: CachedReaderIndex? = null) : ReaderIndexStore {
        override suspend fun read(): CachedReaderIndex? = stored
        override suspend fun write(index: CachedReaderIndex) {
            stored = index
        }
        override suspend fun clear() {
            stored = null
        }
    }

    private class InMemoryDocCache : DocumentCache {
        val map = mutableMapOf<String, CachedDocument>()
        override suspend fun get(pageId: String): CachedDocument? = map[pageId]
        override suspend fun put(document: CachedDocument) {
            map[document.document.pageId] = document
        }
        override suspend fun remove(pageId: String) {
            map.remove(pageId)
        }
        override suspend fun clear() {
            map.clear()
        }
        override suspend fun getMostRecentlyUsed(): CachedDocument? = map.values.firstOrNull()
        override suspend fun getNewestCached(): CachedDocument? = map.values.firstOrNull()
        override suspend fun count(): Int = map.size
        override suspend fun pruneExcept(keepPageIds: Set<String>): Int {
            val toRemove = map.keys.filter { it !in keepPageIds }
            toRemove.forEach { map.remove(it) }
            return toRemove.size
        }
    }

    private fun createTestApp(
        indexStore: ReaderIndexStore? = null,
        documentCache: DocumentCache? = null
    ): ReaderApp {
        val engine = FakeReaderEngine()
        return ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(ReaderDocument.Demo),
            navigator = ReaderNavigator(),
            loadDocument = DefaultLoadReaderDocument { pageId, _, _, _ ->
                ReaderDocument.fromChunkTexts(pageId, "Title $pageId", listOf("Text"))
            },
            indexStore = indexStore,
            documentCache = documentCache,
            speechEngine = null
        )
    }

    @Test
    fun startupHydratesFromIndexStoreImmediately() = runBlocking {
        val entry1 = ReaderIndexEntry("1", "Offline Note 1", Instant.now())
        val entry2 = ReaderIndexEntry("2", "Offline Note 2", Instant.now())
        val indexStore = InMemoryIndexStore(CachedReaderIndex(listOf(entry1, entry2), Instant.now()))

        val app = createTestApp(indexStore = indexStore)
        assertEquals(0, app.browse.state.value.entries.size)

        app.loadCachedIndex()

        assertEquals(2, app.browse.state.value.entries.size)
        assertEquals(BrowseIndexStatus.Loaded, app.browse.state.value.index)
        assertEquals("Offline Note 1", app.browse.state.value.entries[0].title)
    }

    @Test
    fun emptyCachedIndexHydratesBrowseAsLoaded() = runBlocking {
        val indexStore = InMemoryIndexStore(CachedReaderIndex(emptyList(), Instant.now()))

        val app = createTestApp(indexStore = indexStore)
        assertEquals(0, app.browse.state.value.entries.size)
        assertEquals(BrowseIndexStatus.Loading, app.browse.state.value.index)

        app.loadCachedIndex()

        assertEquals(0, app.browse.state.value.entries.size)
        assertEquals(BrowseIndexStatus.Loaded, app.browse.state.value.index)
    }

    @Test
    fun remoteIndexSuccessWritesToStore() = runBlocking {
        val indexStore = InMemoryIndexStore()
        val app = createTestApp(indexStore = indexStore)

        val freshEntries = listOf(
            ReaderIndexEntry("remote-1", "Remote Note 1", Instant.now())
        )

        app.loadBrowseIndex { freshEntries }

        assertEquals(1, app.browse.state.value.entries.size)
        assertEquals("Remote Note 1", app.browse.state.value.entries[0].title)

        // Store must have been written
        val stored = indexStore.read()
        assertNotNull(stored)
        assertEquals(1, stored!!.entries.size)
        assertEquals("Remote Note 1", stored.entries[0].title)
    }

    @Test
    fun remoteIndexFailurePreservesCachedEntries() = runBlocking {
        val entry1 = ReaderIndexEntry("cached-1", "Cached Note", Instant.now())
        val indexStore = InMemoryIndexStore(CachedReaderIndex(listOf(entry1), Instant.now()))

        val app = createTestApp(indexStore = indexStore)
        app.loadCachedIndex()
        assertEquals(1, app.browse.state.value.entries.size)

        // Remote reload fails
        app.loadBrowseIndex {
            throw ReaderLoadException(ReaderErrorCategory.MALFORMED_RESPONSE, "Failed remote fetch")
        }

        // Entries must be preserved, index status carries the failure
        assertEquals(1, app.browse.state.value.entries.size)
        assertEquals("Cached Note", app.browse.state.value.entries[0].title)
        assertTrue(app.browse.state.value.index is BrowseIndexStatus.Error)
    }

    @Test
    fun clearCacheLeavesIndexIntactWhileClearAllLocalDataClearsBoth() = runBlocking {
        val entry1 = ReaderIndexEntry("note-1", "Note", Instant.now())
        val indexStore = InMemoryIndexStore(CachedReaderIndex(listOf(entry1), Instant.now()))
        val docCache = InMemoryDocCache()

        val doc = CachedDocument(
            document = ReaderDocument.fromChunkTexts("note-1", "Note", listOf("Chunk")),
            remoteLastEditedAt = null,
            fetchedAt = Instant.now()
        )
        docCache.put(doc)

        val app = createTestApp(indexStore = indexStore, documentCache = docCache)

        // "Clear downloaded notes"
        app.clearCache()
        assertEquals(0, docCache.count())
        assertNotNull(indexStore.read()) // index is still intact!

        // "Clear all local data"
        docCache.put(doc)
        app.clearAllLocalData()
        assertEquals(0, docCache.count())
        assertNull(indexStore.read()) // index is now cleared!
    }
}
