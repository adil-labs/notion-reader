package com.example.notionreader.data.document

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CachedDocumentRepositoryTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private val v1Time = Instant.parse("2026-09-30T10:00:00Z")
    private val v2Time = Instant.parse("2026-09-30T11:00:00Z")

    private val docV1 = ReaderDocument.fromChunkTexts(
        pageId = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        title = "Architecture v1",
        texts = listOf("Paragraph 1", "Paragraph 2")
    ).copy(lastEditedAt = v1Time)

    private val docV2 = ReaderDocument.fromChunkTexts(
        pageId = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        title = "Architecture v2",
        texts = listOf("Paragraph 1 modified", "Paragraph 2 added", "Paragraph 3")
    ).copy(lastEditedAt = v2Time)

    @Test
    fun cacheHitDoesNotDownloadBlocksWhenRemoteTimestampIsIdentical() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            metadata = RemoteDocumentMetadata(
                pageId = docV1.pageId,
                title = docV1.title,
                lastEditedAt = v1Time,
                isTrashed = false
            )
            document = docV1
        }
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        val load = repository.loadDocument(docV1.pageId)

        assertEquals(DocumentSource.CACHE, load.source)
        assertEquals(DocumentFreshness.FRESH, load.freshness)
        assertEquals(docV1.title, load.document.title)
        assertEquals(1, fakeRemote.getMetadataCalls)
        assertEquals(0, fakeRemote.getDocumentCalls) // Blocks NOT downloaded!
    }

    @Test
    fun knownTimestampHintBypassesNetworkEntirelyForZeroMsStart() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource()
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        val load = repository.loadDocument(docV1.pageId, knownRemoteLastEditedAt = v1Time)

        assertEquals(DocumentSource.CACHE, load.source)
        assertEquals(DocumentFreshness.FRESH, load.freshness)
        assertEquals(0, fakeRemote.getMetadataCalls) // Zero network requests!
        assertEquals(0, fakeRemote.getDocumentCalls)
    }

    @Test
    fun staleCacheDownloadsBlocksAndUpdatesCacheWhenRemoteIsNewer() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            metadata = RemoteDocumentMetadata(
                pageId = docV2.pageId,
                title = docV2.title,
                lastEditedAt = v2Time,
                isTrashed = false
            )
            document = docV2
        }
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        val load = repository.loadDocument(docV1.pageId)

        assertEquals(DocumentSource.REMOTE, load.source)
        assertEquals(DocumentFreshness.FRESH, load.freshness)
        assertEquals("Architecture v2", load.document.title)
        assertEquals(1, fakeRemote.getMetadataCalls)
        assertEquals(1, fakeRemote.getDocumentCalls)

        // Cache updated with fresh document
        val cached = fakeCache.get(docV1.pageId)
        assertNotNull(cached)
        assertEquals("Architecture v2", cached!!.document.title)
        assertEquals(v2Time, cached.remoteLastEditedAt)
    }

    @Test
    fun offlineFallbackReturnsCachedDocumentWhenNetworkIsUnavailable() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            throwOnMetadata = ReaderLoadException(
                category = ReaderErrorCategory.NETWORK_UNAVAILABLE,
                message = "Could not reach Notion."
            )
        }
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        val load = repository.loadDocument(docV1.pageId)

        assertEquals(DocumentSource.CACHE, load.source)
        assertEquals(DocumentFreshness.STALE_OFFLINE, load.freshness)
        assertEquals("Architecture v1", load.document.title)
    }

    @Test
    fun offlineFailureThrowsWhenNoCachedDocumentExists() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            throwOnMetadata = ReaderLoadException(
                category = ReaderErrorCategory.NETWORK_UNAVAILABLE,
                message = "Could not reach Notion."
            )
        }
        val fakeCache = FakeDocumentCache()

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        try {
            repository.loadDocument(docV1.pageId)
            fail("Expected network exception")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.NETWORK_UNAVAILABLE, e.category)
        }
    }

    @Test
    fun permissionDeniedDoesNotFallBackToStaleCache() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            throwOnMetadata = ReaderLoadException(
                category = ReaderErrorCategory.PERMISSION_DENIED,
                message = "No access"
            )
        }
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        try {
            repository.loadDocument(docV1.pageId)
            fail("Expected permission denied exception")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.PERMISSION_DENIED, e.category)
            assertNull(fakeCache.get(docV1.pageId)) // Cached copy invalidated
        }
    }

    @Test
    fun authorizationRequiredPreservesOfflineCache() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            throwOnMetadata = ReaderLoadException(
                category = ReaderErrorCategory.AUTHORIZATION_REQUIRED,
                message = "Token expired"
            )
        }
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        try {
            repository.loadDocument(docV1.pageId)
            fail("Expected auth required exception")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.AUTHORIZATION_REQUIRED, e.category)
            assertNotNull(fakeCache.get(docV1.pageId)) // Global auth failure does NOT wipe user's offline notes
        }
    }

    @Test
    fun trashedRemotePageInvalidatesCacheAndThrowsNotFound() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            metadata = RemoteDocumentMetadata(
                pageId = docV1.pageId,
                title = docV1.title,
                lastEditedAt = v2Time,
                isTrashed = true
            )
        }
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        try {
            repository.loadDocument(docV1.pageId)
            fail("Expected page not found exception")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.PAGE_NOT_FOUND, e.category)
            assertNull(fakeCache.get(docV1.pageId)) // Cache entry deleted
        }
    }

    @Test
    fun whenCachingIsDisabledNewDocumentsAreNotWrittenToCache() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            metadata = RemoteDocumentMetadata(
                pageId = docV1.pageId,
                title = docV1.title,
                lastEditedAt = v1Time,
                isTrashed = false
            )
            document = docV1
        }
        val fakeCache = FakeDocumentCache()

        val repository = CachedDocumentRepository(
            remote = fakeRemote,
            cache = fakeCache,
            clock = fixedClock,
            isCachingEnabled = { false }
        )
        val load = repository.loadDocument(docV1.pageId)

        assertEquals(DocumentSource.REMOTE, load.source)
        assertEquals(DocumentFreshness.FRESH, load.freshness)
        assertNull(fakeCache.get(docV1.pageId))
    }

    @Test
    fun blankReferenceOfflineFallsBackToMostRecentCachedNote() = runBlocking {
        val fakeRemote = FakeRemoteDocumentSource().apply {
            throwOnMetadata = ReaderLoadException(
                category = ReaderErrorCategory.NETWORK_UNAVAILABLE,
                message = "Could not reach Notion."
            )
        }
        val fakeCache = FakeDocumentCache().apply {
            put(CachedDocument(docV1, remoteLastEditedAt = v1Time, cachedAt = v1Time))
        }

        val repository = CachedDocumentRepository(fakeRemote, fakeCache, fixedClock)
        val load = repository.loadDocument("")

        assertEquals(DocumentSource.CACHE, load.source)
        assertEquals(DocumentFreshness.STALE_OFFLINE, load.freshness)
        assertEquals(docV1.pageId, load.document.pageId)
    }

    private class FakeRemoteDocumentSource : RemoteDocumentSource {
        var metadata: RemoteDocumentMetadata? = null
        var document: ReaderDocument? = null
        var throwOnMetadata: ReaderLoadException? = null
        var throwOnDocument: ReaderLoadException? = null

        var getMetadataCalls = 0
        var getDocumentCalls = 0

        override suspend fun getMetadata(pageReference: String): RemoteDocumentMetadata {
            getMetadataCalls++
            throwOnMetadata?.let { throw it }
            return metadata ?: throw ReaderLoadException(ReaderErrorCategory.PAGE_NOT_FOUND, "Not found")
        }

        override suspend fun getDocument(pageReference: String): ReaderDocument {
            getDocumentCalls++
            throwOnDocument?.let { throw it }
            return document ?: throw ReaderLoadException(ReaderErrorCategory.PAGE_NOT_FOUND, "Not found")
        }
    }

    private class FakeDocumentCache : DocumentCache {
        private val storage = mutableMapOf<String, CachedDocument>()
        private val accessOrder = mutableMapOf<String, Long>()
        private var accessCounter = 0L

        override suspend fun get(pageId: String): CachedDocument? {
            val doc = storage[pageId.lowercase()]
            if (doc != null) {
                accessOrder[pageId.lowercase()] = ++accessCounter
            }
            return doc
        }

        override suspend fun put(document: CachedDocument) {
            val key = document.document.pageId.lowercase()
            storage[key] = document
            accessOrder[key] = ++accessCounter
        }

        override suspend fun remove(pageId: String) {
            val key = pageId.lowercase()
            storage.remove(key)
            accessOrder.remove(key)
        }

        override suspend fun clear() {
            storage.clear()
            accessOrder.clear()
        }

        override suspend fun getMostRecentlyUsed(): CachedDocument? {
            val latestKey = accessOrder.maxByOrNull { it.value }?.key ?: return null
            return storage[latestKey]
        }

        override suspend fun getNewestCached(): CachedDocument? =
            storage.values.maxByOrNull { it.remoteLastEditedAt ?: it.cachedAt }

        override suspend fun getMostRecent(): CachedDocument? = getMostRecentlyUsed() ?: getNewestCached()

        override suspend fun count(): Int = storage.size
    }
}
