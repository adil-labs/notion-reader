package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.ReaderIndexEntry
import java.time.Instant

const val READER_INDEX_CACHE_SCHEMA_VERSION = 1

/**
 * Persisted snapshot of the normalized workspace discovery index.
 *
 * Stored locally so that Browse, Explore, Smart Queues, and offline auto-cache
 * planning are immediately available upon app launch without requiring network.
 */
data class CachedReaderIndex(
    val entries: List<ReaderIndexEntry>,
    val fetchedAt: Instant,
    val schemaVersion: Int = READER_INDEX_CACHE_SCHEMA_VERSION
) {
    fun isFresh(now: Instant, ttlMillis: Long = DEFAULT_INDEX_TTL_MILLIS): Boolean {
        val age = now.toEpochMilli() - fetchedAt.toEpochMilli()
        return age in 0L until ttlMillis
    }

    companion object {
        const val DEFAULT_INDEX_TTL_MILLIS = 60 * 60 * 1000L // 1 hour
    }
}
