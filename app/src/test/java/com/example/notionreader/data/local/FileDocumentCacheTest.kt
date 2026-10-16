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
    fun ageRetentionAndFullResetEmitTheirOwnReasons() = runBlocking {
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts("old", "Title", listOf("Text")), null, Instant.EPOCH))
        assertEquals(1, cache.pruneOlderThan(1))
        assertEquals(com.example.notionreader.domain.document.CacheRemovalReason.AGED_OUT, cache.invalidations.value.single().reason)
        cache.clear(com.example.notionreader.domain.document.CacheRemovalReason.RESET)
        assertEquals(com.example.notionreader.domain.document.CacheRemovalReason.RESET, cache.invalidations.value.last().reason)
    }

    @Test
    fun restartFinishesAPermanentRemovalInterruptedAfterJournaling() = runBlocking {
        val doc = CachedDocument(ReaderDocument.fromChunkTexts("revoked", "Title", listOf("Private")), null, Instant.now())
        cache.put(doc)
        val bytes = File(tempDir, "revoked.json").readBytes()
        cache.remove("revoked", com.example.notionreader.domain.document.CacheRemovalReason.PERMISSION_DENIED)
        // Simulate a crash after the write-ahead event, before unlinking its old cached file.
        File(tempDir, "revoked.json").writeBytes(bytes)
        val restarted = FileDocumentCache(tempDir)
        assertNull(restarted.get("revoked"))
        assertEquals(com.example.notionreader.domain.document.CacheRemovalReason.PERMISSION_DENIED, restarted.invalidations.value.single().reason)
    }

    @Test
    fun evictionAndCorruptionHaveDifferentReasonsAndCanonicalIdentities() = runBlocking {
        val tiny = FileDocumentCache(tempDir, maxEntries = 0)
        tiny.put(CachedDocument(ReaderDocument.fromChunkTexts("AA-BB", "Title", listOf("Text")), null, Instant.now()))
        assertEquals("aabb", tiny.invalidations.value.single().pageId)
        assertEquals(com.example.notionreader.domain.document.CacheRemovalReason.EVICTED, tiny.invalidations.value.single().reason)
        tiny.acknowledgeInvalidations(tiny.revision.value)
        File(tempDir, "cc-dd.json").writeText("broken")
        tiny.snapshot()
        assertEquals("ccdd", tiny.invalidations.value.single().pageId)
        assertEquals(com.example.notionreader.domain.document.CacheRemovalReason.CORRUPT, tiny.invalidations.value.single().reason)
    }

    @Test
    fun acknowledgingAnEarlierBatchPreservesNewerAccessLoss() = runBlocking {
        cache.remove("first", com.example.notionreader.domain.document.CacheRemovalReason.DELETED)
        val first = cache.invalidations.value.single().generation
        cache.remove("second", com.example.notionreader.domain.document.CacheRemovalReason.PERMISSION_DENIED)
        cache.acknowledgeInvalidations(first)
        assertEquals("second", FileDocumentCache(tempDir).invalidations.value.single().pageId)
        val beforeRestart = cache.revision.value
        assertEquals(beforeRestart, FileDocumentCache(tempDir).revision.value)
    }

    @Test
    fun confirmedDenialAfterEvictionSurvivesRestartUntilAcknowledged() = runBlocking {
        cache.remove("gone", com.example.notionreader.domain.document.CacheRemovalReason.PERMISSION_DENIED)
        val event = cache.invalidations.value.single()
        assertEquals("gone", event.pageId)
        assertEquals(com.example.notionreader.domain.document.CacheRemovalReason.PERMISSION_DENIED, event.reason)
        val restarted = FileDocumentCache(tempDir)
        assertEquals(listOf(event), restarted.invalidations.value)
        assertEquals(true, restarted.revision.value >= event.generation)
        restarted.acknowledgeInvalidations(event.generation)
        assertEquals(emptyList<com.example.notionreader.domain.document.CacheInvalidation>(), FileDocumentCache(tempDir).invalidations.value)
    }

    @Test
    fun cacheClearRetainsUnacknowledgedPermanentLoss() = runBlocking {
        cache.remove("gone", com.example.notionreader.domain.document.CacheRemovalReason.DELETED)
        cache.clear()
        val events = FileDocumentCache(tempDir).invalidations.value
        assertEquals(listOf(com.example.notionreader.domain.document.CacheRemovalReason.DELETED,
            com.example.notionreader.domain.document.CacheRemovalReason.CLEARED), events.map { it.reason })
        assertNull(events.last().pageId)
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

    @Test
    fun removeAllDeletesOnlySpecifiedPageIds() = runBlocking {
        val doc1 = CachedDocument(ReaderDocument.fromChunkTexts("p-1", "P1", listOf("1")), null, Instant.now())
        val doc2 = CachedDocument(ReaderDocument.fromChunkTexts("p-2", "P2", listOf("2")), null, Instant.now())
        val doc3 = CachedDocument(ReaderDocument.fromChunkTexts("p-3", "P3", listOf("3")), null, Instant.now())

        cache.put(doc1)
        cache.put(doc2)
        cache.put(doc3)
        assertEquals(3, cache.count())

        val removed = cache.removeAll(setOf("p-1", "p-3"))
        assertEquals(2, removed)
        assertEquals(1, cache.count())
        assertNull(cache.get("p-1"))
        assertNotNull(cache.get("p-2"))
        assertNull(cache.get("p-3"))
    }

    @Test
    fun enforceBudgetEvictsPrefetchNotesFirstAndPreservesProtected() = runBlocking {
        // Create a cache with maxEntries = 2
        val boundedCache = FileDocumentCache(
            directory = File(tempDir, "bounded_cache").apply { mkdirs() },
            maxEntries = 2
        )

        val prefetch1 = CachedDocument(
            document = ReaderDocument.fromChunkTexts("prefetch-1", "Prefetch 1", listOf("P1")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now(),
            origin = com.example.notionreader.domain.document.CacheOrigin.PREFETCH
        )
        val user1 = CachedDocument(
            document = ReaderDocument.fromChunkTexts("user-1", "User 1", listOf("U1")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now(),
            origin = com.example.notionreader.domain.document.CacheOrigin.INTERACTIVE
        )
        val prefetch2 = CachedDocument(
            document = ReaderDocument.fromChunkTexts("prefetch-2", "Prefetch 2", listOf("P2")),
            remoteLastEditedAt = null,
            cachedAt = Instant.now(),
            origin = com.example.notionreader.domain.document.CacheOrigin.PREFETCH
        )

        boundedCache.put(prefetch1)
        boundedCache.put(user1)
        assertEquals(2, boundedCache.count())

        // Putting a 3rd doc exceeds maxEntries = 2 -> budget enforcement triggers
        boundedCache.put(prefetch2)

        // Cache count should be constrained to 2 entries
        assertEquals(2, boundedCache.count())
        // user-1 should be preserved because it is INTERACTIVE
        assertNotNull(boundedCache.get("user-1"))
        // prefetch-1 (the older prefetch) should have been evicted first
        assertNull(boundedCache.get("prefetch-1"))
        assertNotNull(boundedCache.get("prefetch-2"))
    }
}

