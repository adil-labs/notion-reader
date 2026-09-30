package com.example.notionreader.domain.discovery

import java.time.Instant

/**
 * Persisted state of the notes currently managed by the auto-cache engine.
 *
 * Distinguishes notes automatically prefetched and managed by the offline policy
 * from general user-opened, manual, or search-result notes in the document cache.
 */
data class AutoCacheState(
    val managedPageIds: Set<String> = emptySet(),
    val lastReconciledAt: Instant? = null
)

/**
 * Port for persisting and retrieving the [AutoCacheState].
 */
interface AutoCacheStateStore {
    suspend fun read(): AutoCacheState
    suspend fun write(state: AutoCacheState)
    suspend fun clear()
}
