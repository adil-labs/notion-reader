package com.example.notionreader.domain.discovery

/**
 * Port for persisting and retrieving the cached workspace discovery index.
 */
interface ReaderIndexStore {
    /** Reads the cached discovery index, or null if missing or schema-incompatible. */
    suspend fun read(): CachedReaderIndex?

    /** Atomically writes the discovery index to persistent storage. */
    suspend fun write(index: CachedReaderIndex)

    /** Clears the persisted discovery index. */
    suspend fun clear()
}
