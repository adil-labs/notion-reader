package com.example.notionreader.data.document

import com.example.notionreader.data.notion.normalizeNotionPageId
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
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
    private val freshnessPolicy: () -> CacheFreshnessPolicy = { CacheFreshnessPolicy.ONE_HOUR }
) {

    /**
     * Loads a document with provenance and freshness information.
     *
     * @param pageReference Page UUID or URL, or blank for auto-discovered most recent page.
     * @param knownRemoteLastEditedAt Optional timestamp hint from index/queue; if matching the cache,
     *                                network validation is bypassed completely for 0ms start.
     * @param forceRefresh When true, bypasses local TTL and forces revalidation/redownload from Notion.
     */
    suspend fun loadDocument(
        pageReference: String = "",
        knownRemoteLastEditedAt: Instant? = null,
        forceRefresh: Boolean = false
    ): DocumentLoad {
        val canonicalId = if (pageReference.isNotBlank()) {
            normalizeNotionPageId(pageReference) ?: pageReference.trim().lowercase()
        } else {
            null
        }

        val cached = if (canonicalId != null) cache.get(canonicalId) else null

        if (!forceRefresh && cached != null) {
            val policy = freshnessPolicy()
            if (policy == CacheFreshnessPolicy.MANUAL_ONLY) {
                return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.FRESH)
            }

            val maxAgeMillis = policy.maxAgeMillis
            if (maxAgeMillis > 0L) {
                val ageMillis = clock.instant().toEpochMilli() - cached.validatedAt.toEpochMilli()
                if (ageMillis in 0L until maxAgeMillis) {
                    // Within TTL: only bypass network if caller's hint does not declare a newer remote revision
                    if (knownRemoteLastEditedAt == null || cached.remoteLastEditedAt == null || !knownRemoteLastEditedAt.isAfter(cached.remoteLastEditedAt)) {
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
                    return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.STALE_OFFLINE)
                }
                if (pageReference.isBlank()) {
                    val mostRecent = cache.getMostRecentlyUsed() ?: cache.getNewestCached()
                    if (mostRecent != null) {
                        return DocumentLoad(mostRecent.document, DocumentSource.CACHE, DocumentFreshness.STALE_OFFLINE)
                    }
                }
            } else if (e.category == ReaderErrorCategory.PAGE_NOT_FOUND || e.category == ReaderErrorCategory.PERMISSION_DENIED) {
                // Invalidate deleted/trashed or unauthorized note from cache
                if (canonicalId != null) {
                    cache.remove(canonicalId)
                }
            }
            throw e
        }

        if (metadata.isTrashed) {
            cache.remove(metadata.pageId)
            throw ReaderLoadException(
                category = ReaderErrorCategory.PAGE_NOT_FOUND,
                message = "That Notion page is in the trash.",
                diagnostic = "remote metadata indicates page is trashed"
            )
        }

        // Cache Hit: remote timestamp matches cached timestamp -> no need to download blocks!
        if (cached != null && metadata.lastEditedAt != null && metadata.lastEditedAt == cached.remoteLastEditedAt) {
            if (isCachingEnabled()) {
                cache.put(cached.copy(validatedAt = clock.instant()))
            }
            return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.FRESH)
        }

        // Remote is newer or uncached -> fetch full document and update cache atomically
        val fresh = try {
            remote.getDocument(metadata.pageId)
        } catch (e: ReaderLoadException) {
            if (e.category == ReaderErrorCategory.NETWORK_UNAVAILABLE && cached != null) {
                return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.STALE_OFFLINE)
            } else if (e.category == ReaderErrorCategory.PAGE_NOT_FOUND || e.category == ReaderErrorCategory.PERMISSION_DENIED) {
                if (canonicalId != null) {
                    cache.remove(canonicalId)
                }
            }
            throw e
        }

        if (isCachingEnabled()) {
            val now = clock.instant()
            cache.put(
                CachedDocument(
                    document = fresh,
                    remoteLastEditedAt = metadata.lastEditedAt,
                    fetchedAt = now,
                    validatedAt = now
                )
            )
        }

        return DocumentLoad(fresh, DocumentSource.REMOTE, DocumentFreshness.FRESH)
    }

    /**
     * Loads a document, returning just the [ReaderDocument] snapshot.
     */
    suspend fun getDocument(
        pageReference: String = "",
        knownRemoteLastEditedAt: Instant? = null,
        forceRefresh: Boolean = false
    ): ReaderDocument =
        loadDocument(pageReference, knownRemoteLastEditedAt, forceRefresh).document
}
