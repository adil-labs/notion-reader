package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.domain.ReaderDocument
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FileDocumentCacheTest {

    private lateinit var tempDir: File
    private lateinit var cache: FileDocumentCache

    @Before
    fun setUp() {
        tempDir = File.createTempFile("doc_cache_test", "").apply {
            delete()
            mkdirs()
        }
        cache = FileDocumentCache(directory = tempDir, maxEntries = 3)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun putAndGetDocumentSuccessfully() = runBlocking {
        val pageId = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"
        val doc = CachedDocument(
            document = ReaderDocument.fromChunkTexts(pageId, "Sample Title", listOf("First paragraph", "Second paragraph")),
            remoteLastEditedAt = Instant.parse("2026-09-30T08:00:00Z"),
            cachedAt = Instant.parse("2026-09-30T09:00:00Z")
        )

        assertNull(cache.get(pageId))

        cache.put(doc)
        assertEquals(1, cache.count())

        val retrieved = cache.get(pageId)
        assertNotNull(retrieved)
        assertEquals(pageId, retrieved!!.document.pageId)
        assertEquals("Sample Title", retrieved.document.title)
        assertEquals(2, retrieved.document.chunkTexts.size)
        assertEquals("First paragraph", retrieved.document.chunkTexts[0])
    }

    @Test
    fun removeDeletesCachedFile() = runBlocking {
        val pageId = "page-to-delete"
        val doc = CachedDocument(
            document = ReaderDocument.fromChunkTexts(pageId, "Title", listOf("Hello")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now()
        )

        cache.put(doc)
        assertEquals(1, cache.count())

        cache.remove(pageId)
        assertEquals(0, cache.count())
        assertNull(cache.get(pageId))
    }

    @Test
    fun lruPruningEnforcesMaxEntries() = runBlocking {
        val doc1 = CachedDocument(
            document = ReaderDocument.fromChunkTexts("page-1", "One", listOf("1")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now()
        )
        val doc2 = CachedDocument(
            document = ReaderDocument.fromChunkTexts("page-2", "Two", listOf("2")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now()
        )
        val doc3 = CachedDocument(
            document = ReaderDocument.fromChunkTexts("page-3", "Three", listOf("3")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now()
        )
        val doc4 = CachedDocument(
            document = ReaderDocument.fromChunkTexts("page-4", "Four", listOf("4")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now()
        )

        cache.put(doc1)
        File(tempDir, "page-1.json").setLastModified(1000)

        cache.put(doc2)
        File(tempDir, "page-2.json").setLastModified(2000)

        cache.put(doc3)
        File(tempDir, "page-3.json").setLastModified(3000)

        assertEquals(3, cache.count())

        // Adding 4th document exceeds maxEntries (3), so oldest (doc1) should be pruned
        cache.put(doc4)
        File(tempDir, "page-4.json").setLastModified(4000)

        assertEquals(3, cache.count())
        assertNull(cache.get("page-1"))
        assertNotNull(cache.get("page-2"))
        assertNotNull(cache.get("page-3"))
        assertNotNull(cache.get("page-4"))
    }
}
