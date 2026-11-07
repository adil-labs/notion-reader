package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class DiscoveryValidityTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun aSnapshotInvalidatedWhileReadingCannotBeReturnedAsEligible() = runBlocking {
        val cache = FileDocumentCache(files.newFolder("blocked-snapshot"))
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p1", "Title", listOf("Body")), null, Instant.EPOCH))
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = object : com.example.notionreader.domain.document.DocumentCache by cache {
            override suspend fun snapshot(): List<CachedDocument> {
                val old = cache.snapshot()
                captured.complete(Unit)
                release.await()
                return old
            }
        }
        val validity = DiscoveryValidity(blocking)
        val reader = async(Dispatchers.Default) { runCatching { validity.eligibleRevisions() } }
        captured.await()
        cache.remove("p1")
        validity.invalidate()
        release.complete(Unit)
        assertNotNull("An invalidated in-flight snapshot must fail closed", reader.await().exceptionOrNull())
    }
    @Test fun aSnapshotReadBeforeResetCannotBeReturnedAfterReset() = runBlocking {
        val cache = FileDocumentCache(files.newFolder("reset-snapshot"))
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p1", "Title", listOf("Body")), null, Instant.EPOCH))
        val captured = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocking = object : com.example.notionreader.domain.document.DocumentCache by cache {
            override suspend fun snapshot(): List<CachedDocument> {
                val old = cache.snapshot(); captured.complete(Unit); release.await(); return old
            }
        }
        val validity = DiscoveryValidity(blocking)
        val reader = async(Dispatchers.Default) { runCatching { validity.eligibleRevisions() } }
        captured.await(); validity.generation.incrementAndGet(); release.complete(Unit)
        assertNotNull("A reset must invalidate an in-flight eligibility read", reader.await().exceptionOrNull())
    }
    @Test fun rememberedEligibilityDoesNotShareTheCallersMutableMap() = runBlocking {
        val cache = FileDocumentCache(files.newFolder("eligibility-copy"))
        val validity = DiscoveryValidity(cache)
        val sources = mutableMapOf("p1" to "r1")
        validity.remember(validity.capture(), sources)
        sources["p2"] = "r2"
        assertEquals(mapOf("p1" to "r1"), validity.eligibleRevisions())
    }

    @Test
    fun tokenCapturesGenerationAndCacheRevision() = runBlocking {
        val cacheDir = files.newFolder("cache")
        val cache = FileDocumentCache(cacheDir)
        val validity = DiscoveryValidity(cache)

        val token1 = validity.capture()
        assertEquals(0L, token1.reset)
        assertEquals(0L, token1.cacheRevision)
        assertEquals(0L, token1.invalidationGeneration)
        assertTrue(validity.isCurrent(token1))

        // Mutate cache
        val doc = ReaderDocument.fromChunkTexts("p1", "Title 1", listOf("Body 1"))
        cache.put(CachedDocument(doc, null, Instant.EPOCH))

        assertFalse("Token must not be current after cache mutation", validity.isCurrent(token1))

        val token2 = validity.capture()
        assertTrue(token2.cacheRevision > 0L)
        assertTrue(validity.isCurrent(token2))

        // Invalidation increments invalidationGeneration
        validity.invalidate()
        assertFalse("Token must not be current after invalidation", validity.isCurrent(token2))

        val token3 = validity.capture()
        assertTrue(token3.invalidationGeneration > 0L)
        assertTrue(validity.isCurrent(token3))

        // Reset epoch
        validity.generation.incrementAndGet()
        assertFalse("Token must not be current after reset epoch increment", validity.isCurrent(token3))
    }

    @Test
    fun eligibleRevisionsCachesResultsAndInvalidatesPromptly() = runBlocking {
        val cacheDir = files.newFolder("cache")
        val cache = FileDocumentCache(cacheDir)
        val validity = DiscoveryValidity(cache)

        val doc1 = ReaderDocument.fromChunkTexts("p1", "Title 1", listOf("Body 1"))
        cache.put(CachedDocument(doc1, null, Instant.EPOCH))

        val revs1 = validity.eligibleRevisions()
        assertEquals(1, revs1.size)
        assertTrue(revs1.containsKey("p1"))

        // Add second doc
        val doc2 = ReaderDocument.fromChunkTexts("p2", "Title 2", listOf("Body 2"))
        cache.put(CachedDocument(doc2, null, Instant.EPOCH))

        // Explicit invalidation clears cached eligibility
        validity.invalidate()
        val revs2 = validity.eligibleRevisions()
        assertEquals(2, revs2.size)
        assertTrue(revs2.containsKey("p1"))
        assertTrue(revs2.containsKey("p2"))
    }

    @Test
    fun concurrentEligibleRevisionsDoNotDuplicateSnapshots() = runBlocking {
        val cacheDir = files.newFolder("cache")
        val cache = FileDocumentCache(cacheDir)
        val validity = DiscoveryValidity(cache)

        val doc = ReaderDocument.fromChunkTexts("p1", "Title 1", listOf("Body 1"))
        cache.put(CachedDocument(doc, null, Instant.EPOCH))

        // Run 10 concurrent requests for eligibleRevisions
        val jobs = (1..10).map {
            async { validity.eligibleRevisions() }
        }
        val results = jobs.awaitAll()
        assertEquals(10, results.size)
        results.forEach { revs ->
            assertEquals(1, revs.size)
            assertTrue(revs.containsKey("p1"))
        }
    }

    @Test
    fun oldSnapshotCannotReestablishEligibilityAfterNewerInvalidation() = runBlocking {
        val cacheDir = files.newFolder("cache")
        val cache = FileDocumentCache(cacheDir)
        val validity = DiscoveryValidity(cache)

        val doc1 = ReaderDocument.fromChunkTexts("p1", "Title 1", listOf("Body 1"))
        cache.put(CachedDocument(doc1, null, Instant.EPOCH))

        // Capture initial token and snapshot
        val initialToken = validity.capture()
        val oldSnapshot = mapOf("p1" to "rev1")

        // Invalidate validity (newer invalidation arrives)
        validity.invalidate()

        // Cache gets mutated with doc2
        val doc2 = ReaderDocument.fromChunkTexts("p2", "Title 2", listOf("Body 2"))
        cache.put(CachedDocument(doc2, null, Instant.EPOCH))

        // Attempt to remember the old snapshot under the old invalidation generation
        validity.remember(initialToken, oldSnapshot)

        // Eligible revisions must NOT return the old snapshot; it must take a fresh snapshot with 2 documents
        val currentRevs = validity.eligibleRevisions()
        assertEquals("Eligibility must not be reestablished from an invalidated snapshot", 2, currentRevs.size)
        assertTrue(currentRevs.containsKey("p1"))
        assertTrue(currentRevs.containsKey("p2"))
    }

    @Test
    fun concurrentSnapshotReadDuringInvalidationDoesNotReestablishStaleEligibility() = runBlocking {
        val cacheDir = files.newFolder("cache")
        val cache = FileDocumentCache(cacheDir)
        val validity = DiscoveryValidity(cache)

        val doc1 = ReaderDocument.fromChunkTexts("p1", "Title 1", listOf("Body 1"))
        cache.put(CachedDocument(doc1, null, Instant.EPOCH))

        repeat(20) { iteration ->
            val barrier = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val readerJob = async(Dispatchers.Default) {
                val token = validity.capture()
                barrier.complete(Unit)
                release.await()
                validity.remember(token, mapOf("p1" to "stale"))
            }

            barrier.await()
            validity.invalidate()
            val doc = ReaderDocument.fromChunkTexts("p$iteration", "Title $iteration", listOf("Body"))
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            release.complete(Unit)
            readerJob.await()

            val revs = validity.eligibleRevisions()
            assertFalse("Stale snapshot must not reestablish eligibility", revs["p1"] == "stale")
        }
    }
}
