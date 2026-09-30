package com.example.notionreader.domain.document

import com.example.notionreader.domain.ReaderDocument
import java.time.Instant

const val DOCUMENT_CACHE_SCHEMA_VERSION = 1

/**
 * Origin / purpose for which a document was placed into cache.
 */
enum class CacheOrigin {
    /** Explicitly requested by user viewing/reading a note interactively. */
    INTERACTIVE,
    /** Fetched to satisfy the active queue or current audio playback. */
    PLAYBACK,
    /** Speculatively prefetched for offline readiness by the auto-cache engine. */
    PREFETCH
}

/**
 * A cached [ReaderDocument] snapshot with freshness and provenance metadata.
 *
 * Distinguishes between:
 * - [fetchedAt]: when the document blocks were downloaded from Notion and normalized.
 * - [validatedAt]: when the document was last verified against Notion (either on initial download
 *   or during a lightweight metadata check where remote revision remained unchanged).
 * - [origin]: how the document entered cache ([CacheOrigin.INTERACTIVE], [CacheOrigin.PLAYBACK], or [CacheOrigin.PREFETCH]).
 * - [cachedAt]: backwards-compatible accessor returning [validatedAt].
 */
data class CachedDocument(
    val document: ReaderDocument,
    val remoteLastEditedAt: Instant?,
    val fetchedAt: Instant,
    val validatedAt: Instant = fetchedAt,
    val origin: CacheOrigin = CacheOrigin.PLAYBACK,
    val schemaVersion: Int = DOCUMENT_CACHE_SCHEMA_VERSION
) {
    /** Backwards-compatible constructor for code and tests passing cachedAt. */
    constructor(
        document: ReaderDocument,
        remoteLastEditedAt: Instant?,
        cachedAt: Instant,
        origin: CacheOrigin = CacheOrigin.PLAYBACK,
        schemaVersion: Int = DOCUMENT_CACHE_SCHEMA_VERSION
    ) : this(
        document = document,
        remoteLastEditedAt = remoteLastEditedAt,
        fetchedAt = cachedAt,
        validatedAt = cachedAt,
        origin = origin,
        schemaVersion = schemaVersion
    )

    val cachedAt: Instant get() = validatedAt
}
