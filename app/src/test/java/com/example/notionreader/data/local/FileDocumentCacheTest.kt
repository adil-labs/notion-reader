package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.domain.ReaderDocument
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    @Test
    fun concurrentWritesDoNotCorruptCacheOrCollideOnTempFiles() = runBlocking {
        val pageId = "concurrent-page"
        val jobs = (1..20).map { i ->
            async(Dispatchers.IO) {
                val doc = CachedDocument(
                    document = ReaderDocument.fromChunkTexts(pageId, "Title $i", listOf("Chunk $i")),
                    remoteLastEditedAt = Instant.ofEpochMilli(1000L + i),
                    cachedAt = Instant.now()
                )
                cache.put(doc)
                cache.get(pageId)
            }
        }
        val results = jobs.awaitAll()
        results.forEach { assertNotNull(it) }

        // Final cache state must be valid
        val finalDoc = cache.get(pageId)
        assertNotNull(finalDoc)
        assertEquals(pageId, finalDoc!!.document.pageId)

        // No leftover .tmp files
        val tmpFiles = tempDir.listFiles { _, name -> name.endsWith(".tmp") }
        assertEquals(0, tmpFiles?.size ?: 0)
    }

    @Test
    fun getMostRecentlyUsedAndGetNewestCachedDistinguishLruFromTimestamp() = runBlocking {
        val olderDocNewerTime = CachedDocument(
            document = ReaderDocument.fromChunkTexts("page-a", "Page A", listOf("A")),
            remoteLastEditedAt = Instant.parse("2026-09-30T12:00:00Z"),
            cachedAt = Instant.parse("2026-09-30T10:00:00Z")
        )
        val newerDocOlderTime = CachedDocument(
            document = ReaderDocument.fromChunkTexts("page-b", "Page B", listOf("B")),
            remoteLastEditedAt = Instant.parse("2026-09-30T08:00:00Z"),
            cachedAt = Instant.parse("2026-09-30T11:00:00Z")
        )

        cache.put(olderDocNewerTime)
        cache.put(newerDocOlderTime)

        // Touch page-b so it is the most recently accessed
        cache.get("page-b")

        val mru = cache.getMostRecentlyUsed()
        val newest = cache.getNewestCached()

        assertNotNull(mru)
        assertNotNull(newest)
        assertEquals("page-b", mru!!.document.pageId)
        assertEquals("page-a", newest!!.document.pageId)
    }

    @Test
    fun pruneOlderThanRemovesExpiredFilesWhilePreservingProtectedNotes() = runBlocking {
        val now = System.currentTimeMillis()
        val oldDoc = CachedDocument(
            document = ReaderDocument.fromChunkTexts("old-page", "Old", listOf("Old")),
            remoteLastEditedAt = Instant.ofEpochMilli(now - 10 * 24L * 3600L * 1000L), // 10 days ago
            fetchedAt = Instant.ofEpochMilli(now - 10 * 24L * 3600L * 1000L),
            validatedAt = Instant.ofEpochMilli(now - 10 * 24L * 3600L * 1000L)
        )
        val protectedOldDoc = CachedDocument(
            document = ReaderDocument.fromChunkTexts("protected-old-page", "Protected Old", listOf("Protected")),
            remoteLastEditedAt = Instant.ofEpochMilli(now - 10 * 24L * 3600L * 1000L), // 10 days ago
            fetchedAt = Instant.ofEpochMilli(now - 10 * 24L * 3600L * 1000L),
            validatedAt = Instant.ofEpochMilli(now - 10 * 24L * 3600L * 1000L)
        )
        val recentDoc = CachedDocument(
            document = ReaderDocument.fromChunkTexts("recent-page", "Recent", listOf("Recent")),
            remoteLastEditedAt = Instant.ofEpochMilli(now - 2 * 24L * 3600L * 1000L), // 2 days ago
            fetchedAt = Instant.ofEpochMilli(now - 2 * 24L * 3600L * 1000L),
            validatedAt = Instant.ofEpochMilli(now - 2 * 24L * 3600L * 1000L)
        )

        cache.put(oldDoc)
        cache.put(protectedOldDoc)
        cache.put(recentDoc)
        assertEquals(3, cache.count())

        // 7-day retention window
        val sevenDaysMillis = 7 * 24L * 3600L * 1000L
        val pruned = cache.pruneOlderThan(sevenDaysMillis, preservePageIds = setOf("protected-old-page"))

        assertEquals(1, pruned)
        assertEquals(2, cache.count())
        assertNull(cache.get("old-page")) // pruned!
        assertNotNull(cache.get("protected-old-page")) // preserved because it's in protectedPageIds!
        assertNotNull(cache.get("recent-page")) // preserved because it's 2 days old (< 7 days)!
    }

    @Test
    fun oldRemoteEditWithRecentValidationIsPrunedByPruneOlderThan() = runBlocking {
        val now = System.currentTimeMillis()
        val oldDocRecentlyValidated = CachedDocument(
            document = ReaderDocument.fromChunkTexts("old-validated-page", "Old but checked recently", listOf("Text")),
            remoteLastEditedAt = Instant.ofEpochMilli(now - 60 * 24L * 3600L * 1000L), // 60 days ago
            fetchedAt = Instant.ofEpochMilli(now - 60 * 24L * 3600L * 1000L),
            validatedAt = Instant.ofEpochMilli(now - 5 * 60 * 1000L) // Validated 5 mins ago!
        )

        cache.put(oldDocRecentlyValidated)
        assertEquals(1, cache.count())

        val sevenDaysMillis = 7 * 24L * 3600L * 1000L
        val pruned = cache.pruneOlderThan(sevenDaysMillis)

        // Retention must use content age (remoteLastEditedAt), so it gets pruned despite recent validation!
        assertEquals(1, pruned)
        assertEquals(0, cache.count())
        assertNull(cache.get("old-validated-page"))
    }

    @Test
    fun pruneExceptRetainsDesiredAndProtectedNotesAndEvictsUnwanted() = runBlocking {
        val now = System.currentTimeMillis()
        val desiredOld = CachedDocument(
            document = ReaderDocument.fromChunkTexts("desired-old", "Desired Old", listOf("D")),
            remoteLastEditedAt = Instant.ofEpochMilli(now - 150 * 24L * 3600L * 1000L),
            cachedAt = Instant.now()
        )
        val queueOld = CachedDocument(
            document = ReaderDocument.fromChunkTexts("queue-old", "Queue Old", listOf("Q")),
            remoteLastEditedAt = Instant.ofEpochMilli(now - 200 * 24L * 3600L * 1000L),
            cachedAt = Instant.now()
        )
        val unwantedRecent = CachedDocument(
            document = ReaderDocument.fromChunkTexts("unwanted", "Unwanted Note", listOf("U")),
            remoteLastEditedAt = Instant.ofEpochMilli(now - 1 * 24L * 3600L * 1000L),
            cachedAt = Instant.now()
        )

        cache.put(desiredOld)
        cache.put(queueOld)
        cache.put(unwantedRecent)
        assertEquals(3, cache.count())

        val pruned = cache.pruneExcept(setOf("desired-old", "queue-old"))
        assertEquals(1, pruned)
        assertEquals(2, cache.count())
        assertNotNull(cache.get("desired-old"))
        assertNotNull(cache.get("queue-old"))
        assertNull(cache.get("unwanted"))
    }
}

