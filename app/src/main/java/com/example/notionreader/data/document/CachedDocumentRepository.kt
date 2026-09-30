package com.example.notionreader.data.document

import com.example.notionreader.data.notion.normalizeNotionPageId
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
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
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Loads a document with provenance and freshness information.
     *
     * @param pageReference Page UUID or URL, or blank for auto-discovered most recent page.
     * @param knownRemoteLastEditedAt Optional timestamp hint from index/queue; if matching the cache,
     *                                network validation is bypassed completely for 0ms start.
     */
    suspend fun loadDocument(
        pageReference: String = "",
        knownRemoteLastEditedAt: Instant? = null
    ): DocumentLoad {
        val canonicalId = if (pageReference.isNotBlank()) {
            normalizeNotionPageId(pageReference) ?: pageReference.trim().lowercase()
        } else {
            null
        }

        val cached = if (canonicalId != null) cache.get(canonicalId) else null

        // 0ms Fast-path: if caller already knows the remote last edited timestamp from Browse/Queue
        // and it matches the cached copy, return cached copy as fresh without any network call.
        if (cached != null && knownRemoteLastEditedAt != null && cached.remoteLastEditedAt == knownRemoteLastEditedAt) {
            return DocumentLoad(cached.document, DocumentSource.CACHE, DocumentFreshness.FRESH)
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

        cache.put(
            CachedDocument(
                document = fresh,
                remoteLastEditedAt = metadata.lastEditedAt,
                cachedAt = clock.instant()
            )
        )

        return DocumentLoad(fresh, DocumentSource.REMOTE, DocumentFreshness.FRESH)
    }

    /**
     * Loads a document, returning just the [ReaderDocument] snapshot.
     */
    suspend fun getDocument(
        pageReference: String = "",
        knownRemoteLastEditedAt: Instant? = null
    ): ReaderDocument =
        loadDocument(pageReference, knownRemoteLastEditedAt).document
}
