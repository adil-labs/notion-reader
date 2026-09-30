package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.settings.AutoCacheMode
import com.example.notionreader.domain.settings.ReaderSettings
import java.time.Instant

/**
 * Result of auto-cache planning.
 *
 * @property desiredPageIds Canonical set of page IDs that should be kept in cache.
 * @property notesToPrefetch Ordered list of notes to download if not already cached.
 */
data class AutoCachePlan(
    val desiredPageIds: Set<String>,
    val notesToPrefetch: List<ReaderIndexEntry>
)

/**
 * Declarative planner for offline document auto-caching.
 *
 * Rules:
 * 1. Mode OFF: Desired set is empty.
 * 2. Mode NOTE_COUNT: Takes newest N notes based on lastEditedAt (notes with unknown dates are appended last).
 * 3. Mode TIME_WINDOW:
 *    - Primary selection: Notes edited within the window, up to [MAXIMUM_AUTOMATIC_COUNT].
 *    - Minimum fallback: If fewer than [MINIMUM_FALLBACK_COUNT] notes are within the window, fills remainder
 *      using the most recently edited older notes, followed by unknown-date notes.
 *    - Notes with unknown dates (lastEditedAt == null) are placed ONLY in the fallback pool, never assumed recent.
 */
object AutoCachePlanner {
    const val MINIMUM_FALLBACK_COUNT = 5
    const val MAXIMUM_AUTOMATIC_COUNT = 50

    fun plan(
        entries: List<ReaderIndexEntry>,
        settings: ReaderSettings,
        now: Instant
    ): AutoCachePlan {
        if (!settings.cacheNotesForOffline || settings.autoCacheMode == AutoCacheMode.OFF) {
            return AutoCachePlan(desiredPageIds = emptySet(), notesToPrefetch = emptyList())
        }

        val sortedKnown = entries
            .filter { it.lastEditedAt != null }
            .sortedByDescending { it.lastEditedAt }

        val unknown = entries.filter { it.lastEditedAt == null }
        val allRanked = sortedKnown + unknown

        return when (settings.autoCacheMode) {
            AutoCacheMode.OFF -> AutoCachePlan(emptySet(), emptyList())
            AutoCacheMode.NOTE_COUNT -> {
                val limit = settings.autoCacheNoteCount.count
                val selected = allRanked.take(limit)
                AutoCachePlan(
                    desiredPageIds = selected.map { it.pageId }.toSet(),
                    notesToPrefetch = selected
                )
            }
            AutoCacheMode.TIME_WINDOW -> {
                val cutoff = now.minusMillis(settings.autoCacheTimeWindow.durationMillis)
                val inWindow = sortedKnown.filter { !it.lastEditedAt!!.isBefore(cutoff) }.take(MAXIMUM_AUTOMATIC_COUNT)

                val selected = if (inWindow.size >= MINIMUM_FALLBACK_COUNT) {
                    inWindow
                } else {
                    val needed = MINIMUM_FALLBACK_COUNT - inWindow.size
                    val remainingPool = allRanked.filterNot { candidate ->
                        inWindow.any { it.pageId == candidate.pageId }
                    }
                    inWindow + remainingPool.take(needed)
                }

                AutoCachePlan(
                    desiredPageIds = selected.map { it.pageId }.toSet(),
                    notesToPrefetch = selected
                )
            }
        }
    }
}
