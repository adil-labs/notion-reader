package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.AutoCacheStateStore
import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.document.CacheTelemetry
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.document.DocumentCacheStats
import com.example.notionreader.domain.document.DocumentLoadPurpose
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Completed entries no longer need offline protection; the current Note does. */
fun cacheProtectedPageIds(pageIds: List<String>, currentIndex: Int): Set<String> =
    pageIds.drop(currentIndex.coerceAtLeast(0)).toSet()

/**
 * Coordinates document cache statistics, background pruning, and prefetching.
 */
class DocumentCacheCoordinator(
    private val documentCache: DocumentCache?,
    private val autoCacheStateStore: AutoCacheStateStore?,
    private val cacheTelemetry: CacheTelemetry?,
    private val loadDocument: LoadReaderDocument
) {
    private val _cacheStats = MutableStateFlow(DocumentCacheStats())
    val cacheStats: StateFlow<DocumentCacheStats> = _cacheStats.asStateFlow()

    val autoCacheReconciler: AutoCacheReconciler? = if (autoCacheStateStore != null && documentCache != null) {
        AutoCacheReconciler(
            autoCacheStateStore = autoCacheStateStore,
            documentCache = documentCache,
            prefetchNote = { pageId, lastEditedAt -> prefetchNote(pageId, lastEditedAt) },
            isNoteCached = { pageId -> isNoteCached(pageId) },
            telemetry = cacheTelemetry
        )
    } else null

    suspend fun updateCacheStats() {
        _cacheStats.value = documentCache?.stats() ?: DocumentCacheStats()
    }

    suspend fun pruneCacheOlderThan(maxAgeMillis: Long, preservePageIds: Set<String> = emptySet()): Int {
        val count = documentCache?.pruneOlderThan(maxAgeMillis, preservePageIds) ?: 0
        if (count > 0) {
            updateCacheStats()
        }
        return count
    }

    suspend fun pruneCacheExcept(keepPageIds: Set<String>): Int {
        val count = documentCache?.pruneExcept(keepPageIds) ?: 0
        if (count > 0) {
            updateCacheStats()
        }
        return count
    }

    suspend fun removeCachedNotes(pageIds: Set<String>): Int {
        val count = documentCache?.removeAll(pageIds) ?: 0
        if (count > 0) {
            updateCacheStats()
        }
        return count
    }

    suspend fun isNoteCached(pageId: String): Boolean {
        return documentCache?.get(pageId) != null
    }

    suspend fun prefetchNote(
        pageId: String,
        knownRemoteLastEditedAt: Instant? = null,
        forceRefresh: Boolean = false
    ): Result<ReaderDocument> {
        val result = loadDocument(pageId, knownRemoteLastEditedAt, forceRefresh, DocumentLoadPurpose.PREFETCH)
        updateCacheStats()
        return result
    }

    suspend fun clearCache(reason: CacheRemovalReason? = null) {
        if (reason != null) {
            documentCache?.clear(reason)
        } else {
            documentCache?.clear()
        }
        updateCacheStats()
    }
}
