package com.example.notionreader.domain.document

/**
 * Port for persisting and retrieving cached reader document snapshots.
 */
interface DocumentCache {
    /** Retrieves the cached document for [pageId], or null if not cached or schema-incompatible. */
    suspend fun get(pageId: String): CachedDocument?

    /** Atomically stores or updates [document] in the cache. */
    suspend fun put(document: CachedDocument)

    /** Removes the cached document for [pageId]. */
    suspend fun remove(pageId: String)

    /** Clears all cached documents. */
    suspend fun clear()

    /** Returns the most recently accessed document in cache (LRU ordering based on last access). */
    suspend fun getMostRecentlyUsed(): CachedDocument?

    /** Returns the cached document with the newest remote last-edited timestamp (or cached timestamp). */
    suspend fun getNewestCached(): CachedDocument?

    /** Returns the most recently accessed document, defaulting to [getMostRecentlyUsed]. */
    suspend fun getMostRecent(): CachedDocument? = getMostRecentlyUsed()

    /** Current count of cached documents. */
    suspend fun count(): Int

    /** Real cache statistics: document count and total byte size. */
    suspend fun stats(): DocumentCacheStats = DocumentCacheStats(noteCount = count())

    /**
     * Prunes cached notes older than [maxAgeMillis] based on their last-edited or validation timestamp.
     * [preservePageIds] are protected from eviction (e.g. active playback note and queued notes).
     * Returns the count of deleted files.
     */
    suspend fun pruneOlderThan(maxAgeMillis: Long, preservePageIds: Set<String> = emptySet()): Int = 0

    /**
     * Evicts cached documents whose canonical pageId is not present in [keepPageIds].
     * Returns the count of deleted documents.
     */
    suspend fun pruneExcept(keepPageIds: Set<String>): Int = 0

    /**
     * Evicts cached documents whose canonical pageId is present in [pageIds].
     * Returns the count of deleted documents.
     */
    suspend fun removeAll(pageIds: Set<String>): Int = 0

    /**
     * Enforces storage limits (both entry count and byte budget), evicting speculative [CacheOrigin.PREFETCH]
     * notes first, followed by oldest-accessed user notes, never evicting [preservePageIds].
     * Returns the count of deleted files.
     */
    suspend fun enforceBudget(preservePageIds: Set<String> = emptySet()): Int = 0
}
