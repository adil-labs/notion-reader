package com.example.notionreader.application

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.discovery.AutoCachePlanner
import com.example.notionreader.domain.discovery.AutoCacheState
import com.example.notionreader.domain.discovery.AutoCacheStateStore
import com.example.notionreader.domain.document.CacheTelemetry
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.settings.ReaderSettings
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Reconciles the desired offline auto-cache set with the current cache state.
 *
 * Enforces:
 * 1. Paced prefetching (polite 350ms delay between remote calls).
 * 2. Ownership separation: only notes managed by the auto-cache engine are eligible for automatic eviction.
 * 3. Failure safety: partial network dropouts preserve previously cached candidates rather than dropping them.
 * 4. Protection: active queue and playback notes are strictly preserved.
 */
class AutoCacheReconciler(
    private val autoCacheStateStore: AutoCacheStateStore,
    private val documentCache: DocumentCache,
    private val prefetchNote: suspend (pageId: String, lastEditedAt: Instant?) -> Unit,
    private val isNoteCached: suspend (pageId: String) -> Boolean,
    private val interRequestDelayMs: Long = 350L,
    private val telemetry: CacheTelemetry? = null
) {
    /**
     * Executes declarative reconciliation between the desired auto-cache set and currently managed notes.
     */
    suspend fun reconcile(
        entries: List<ReaderIndexEntry>,
        settings: ReaderSettings,
        now: Instant,
        protectedPageIds: Set<String> = emptySet(),
        maxNotes: Int? = null
    ) {
        val plan = AutoCachePlanner.plan(entries, settings, now)
        val notesToPrefetch = if (maxNotes != null) plan.notesToPrefetch.take(maxNotes) else plan.notesToPrefetch

        val previousState = autoCacheStateStore.read()
        val previousManaged = previousState.managedPageIds

        telemetry?.recordPrefetchScheduled(notesToPrefetch.size)

        // 1. Fetch missing desired notes politely
        for (entry in notesToPrefetch) {
            try {
                if (!isNoteCached(entry.pageId)) {
                    prefetchNote(entry.pageId, entry.lastEditedAt)
                    telemetry?.recordPrefetchCompleted()
                    if (interRequestDelayMs > 0) {
                        delay(interRequestDelayMs)
                    }
                }
            } catch (e: CancellationException) {
                telemetry?.recordPrefetchCancelled()
                throw e
            } catch (_: Exception) {
                // Non-fatal background prefetch failure for individual note
            }
        }

        // 2. Failure-safe managed set computation:
        // A desired note is managed if it is currently in cache (either newly fetched,
        // or previously cached and retained across a transient network drop).
        val desired = plan.desiredPageIds
        val currentlyManaged = mutableSetOf<String>()
        for (pageId in desired) {
            if (isNoteCached(pageId)) {
                currentlyManaged.add(pageId)
            }
        }

        // 3. Update auto-cache state
        autoCacheStateStore.write(
            AutoCacheState(
                managedPageIds = currentlyManaged,
                lastReconciledAt = now
            )
        )

        // 4. Selective eviction: ONLY evict notes that were previously managed by auto-cache
        // and are no longer desired or managed, excluding any protected IDs (active playback / queue).
        val obsoleteManaged = (previousManaged - currentlyManaged) - protectedPageIds
        if (obsoleteManaged.isNotEmpty()) {
            val removed = documentCache.removeAll(obsoleteManaged)
            telemetry?.recordEviction(removed)
        }
    }
}
