package com.example.notionreader.data.document

import com.example.notionreader.data.notion.normalizeNotionPageId
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.document.CacheOrigin
import com.example.notionreader.domain.document.CacheTelemetry
import com.example.notionreader.domain.document.DocumentLoadPurpose
import com.example.notionreader.domain.settings.CacheFreshnessPolicy
import java.time.Clock
import java.time.Instant

/**
 * Repository orchestrating stale-while-revalidate caching and offline fallback for [ReaderDocument].
 *
 * Playback depends on cached [ReaderDocument]s rather than network connectivity. Network access is
 * used to determine whether a newer remote snapshot exists.
 */
class CachedDocumentRepository(
    private val remote: RemoteDocumentSource,
    private val cache: DocumentCache,
    private val clock: Clock = Clock.systemUTC(),
    private val isCachingEnabled: () -> Boolean = { true },
    private val freshnessPolicy: () -> CacheFreshnessPolicy = { CacheFreshnessPolicy.ONE_HOUR },
    private val telemetry: CacheTelemetry? = null
) {

    /**
     * Loads a document with provenance and freshness information.
     *
     * @param pageReference Page UUID or URL, or blank for auto-discovered most recent page.
     * @param knownRemoteLastEditedAt Optional timestamp hint from index/queue; if matching the cache,
     *                                network validation is bypassed completely for 0ms start.
     * @param forceRefresh When true, bypasses local TTL and forces revalidation/redownload from Notion.
     * @param purpose Reason for load, mapping directly to cache provenance ([CacheOrigin]).
     */
    suspend fun loadDocument(
        pageReference: String = "",
        knownRemoteLastEditedAt: Instant? = null,
        forceRefresh: Boolean = false,
        purpose: DocumentLoadPurpose = DocumentLoadPurpose.PLAYBACK
    ): DocumentLoad {
        val canonicalId = if (pageReference.isNotBlank()) {
            normalizeNotionPageId(pageReference) ?: pageReference.trim().lowercase()
        } else {
            null
        }

        val targetOrigin = when (purpose) {
            DocumentLoadPurpose.INTERACTIVE -> CacheOrigin.INTERACTIVE
            DocumentLoadPurpose.PLAYBACK -> CacheOrigin.PLAYBACK
            DocumentLoadPurpose.PREFETCH -> CacheOrigin.PREFETCH
        }

        val cached = if (canonicalId != null) cache.get(canonicalId) else null

        if (!forceRefresh && cached != null) {
            val policy = freshnessPolicy()
            if (policy == CacheFreshnessPolicy.MANUAL_ONLY) {
                telemetry?.recordHit()
                return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.FRESH)
            }

            val maxAgeMillis = policy.maxAgeMillis
            if (maxAgeMillis > 0L) {
                val ageMillis = clock.instant().toEpochMilli() - cached.validatedAt.toEpochMilli()
                if (ageMillis in 0L until maxAgeMillis) {
                    // Within TTL: only bypass network if caller's hint does not declare a newer remote revision
                    if (knownRemoteLastEditedAt == null || cached.remoteLastEditedAt == null || !knownRemoteLastEditedAt.isAfter(cached.remoteLastEditedAt)) {
                        telemetry?.recordHit()
                        return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.FRESH)
                    }
                }
            }
        }

        val metadata = try {
            remote.getMetadata(pageReference)
        } catch (e: ReaderLoadException) {
            if (e.category == ReaderErrorCategory.NETWORK_UNAVAILABLE) {
                if (cached != null) {
                    telemetry?.recordStaleFallback()
                    return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.STALE_OFFLINE)
                }
                if (pageReference.isBlank()) {
                    val mostRecent = cache.getMostRecentlyUsed() ?: cache.getNewestCached()
                    if (mostRecent != null) {
                        telemetry?.recordStaleFallback()
                        return DocumentLoad(mostRecent.document, DocumentSource.CACHE, DocumentFreshness.STALE_OFFLINE)
                    }
                }
            } else if (e.category == ReaderErrorCategory.PAGE_NOT_FOUND || e.category == ReaderErrorCategory.PERMISSION_DENIED) {
                // Invalidate deleted/trashed or unauthorized note from cache
                if (canonicalId != null) {
                    cache.remove(canonicalId, if (e.category == ReaderErrorCategory.PERMISSION_DENIED) CacheRemovalReason.PERMISSION_DENIED else CacheRemovalReason.DELETED)
                }
            }
            throw e
        }

        if (metadata.isTrashed) {
            cache.remove(metadata.pageId, CacheRemovalReason.DELETED)
            throw ReaderLoadException(
                category = ReaderErrorCategory.PAGE_NOT_FOUND,
                message = "That Notion page is in the trash.",
                diagnostic = "remote metadata indicates page is trashed"
            )
        }

        // Cache Hit: remote timestamp matches cached timestamp -> no need to download blocks!
        if (cached != null && metadata.lastEditedAt != null && metadata.lastEditedAt == cached.remoteLastEditedAt) {
            telemetry?.recordHit()
            if (isCachingEnabled()) {
                val effectiveOrigin = if (cached.origin == CacheOrigin.PREFETCH && targetOrigin != CacheOrigin.PREFETCH) {
                    targetOrigin
                } else {
                    cached.origin
                }
                saveIfSpace(cached.copy(validatedAt = clock.instant(), origin = effectiveOrigin), purpose)
            }
            return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.FRESH)
        }

        // Remote is newer or uncached -> fetch full document and update cache atomically
        val fresh = try {
            remote.getDocument(metadata.pageId)
        } catch (e: ReaderLoadException) {
            if (e.category == ReaderErrorCategory.NETWORK_UNAVAILABLE && cached != null) {
                telemetry?.recordStaleFallback()
                return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.STALE_OFFLINE)
            } else if (e.category == ReaderErrorCategory.PAGE_NOT_FOUND || e.category == ReaderErrorCategory.PERMISSION_DENIED) {
                cache.remove(metadata.pageId, if (e.category == ReaderErrorCategory.PERMISSION_DENIED) CacheRemovalReason.PERMISSION_DENIED else CacheRemovalReason.DELETED)
            }
            throw e
        }

        telemetry?.recordMiss()
        if (isCachingEnabled()) {
            val now = clock.instant()
            saveIfSpace(
                CachedDocument(
                    document = fresh,
                    remoteLastEditedAt = metadata.lastEditedAt,
                    fetchedAt = now,
                    validatedAt = now,
                    origin = targetOrigin
                ), purpose
            )
        }

        return DocumentLoad(fresh, DocumentSource.REMOTE, DocumentFreshness.FRESH)
    }

    private suspend fun saveIfSpace(document: CachedDocument, purpose: DocumentLoadPurpose) {
        try { cache.put(document) }
        catch (refused: com.example.notionreader.domain.document.StorageRefused) {
            // Listening and manual reading can use the fetched document without caching it.
            if (purpose == DocumentLoadPurpose.PREFETCH) throw refused
        }
    }

    /**
     * Loads a document, returning just the [ReaderDocument] snapshot.
     */
    suspend fun getDocument(
        pageReference: String = "",
        knownRemoteLastEditedAt: Instant? = null,
        forceRefresh: Boolean = false,
        purpose: DocumentLoadPurpose = DocumentLoadPurpose.PLAYBACK
    ): ReaderDocument =
        loadDocument(pageReference, knownRemoteLastEditedAt, forceRefresh, purpose).document
}
