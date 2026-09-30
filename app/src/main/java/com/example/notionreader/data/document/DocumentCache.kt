package com.example.notionreader.data.document

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
}
