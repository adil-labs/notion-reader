package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.settings.AutoCacheMode
import com.example.notionreader.domain.settings.AutoCacheNoteCount
import com.example.notionreader.domain.settings.AutoCacheTimeWindow
import com.example.notionreader.domain.settings.ReaderSettings
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCachePlannerTest {

    private val now = Instant.parse("2026-09-30T12:00:00Z")

    @Test
    fun modeOffReturnsEmptyPlan() {
        val entries = listOf(
            entry("1", now.minus(Duration.ofDays(1)))
        )
        val settings = ReaderSettings(autoCacheMode = AutoCacheMode.OFF)

        val plan = AutoCachePlanner.plan(entries, settings, now)
        assertTrue(plan.desiredPageIds.isEmpty())
        assertTrue(plan.notesToPrefetch.isEmpty())
    }

    @Test
    fun modeNoteCountTakesNewestNNotes() {
        val entries = listOf(
            entry("oldest", now.minus(Duration.ofDays(10))),
            entry("newest", now.minus(Duration.ofDays(1))),
            entry("middle", now.minus(Duration.ofDays(5)))
        )
        val settings = ReaderSettings(
            autoCacheMode = AutoCacheMode.NOTE_COUNT,
            autoCacheNoteCount = AutoCacheNoteCount.COUNT_20
        )

        val plan = AutoCachePlanner.plan(entries, settings, now)
        assertEquals(3, plan.notesToPrefetch.size)
        assertEquals(listOf("newest", "middle", "oldest"), plan.notesToPrefetch.map { it.pageId })
        assertEquals(setOf("newest", "middle", "oldest"), plan.desiredPageIds)
    }

    @Test
    fun modeTimeWindowWithSufficientRecentNotesReturnsAllWithinWindow() {
        val entries = (1..8).map { i ->
            entry("note-$i", now.minus(Duration.ofDays(i.toLong())))
        }
        val settings = ReaderSettings(
            autoCacheMode = AutoCacheMode.TIME_WINDOW,
            autoCacheTimeWindow = AutoCacheTimeWindow.DAYS_15
        )

        val plan = AutoCachePlanner.plan(entries, settings, now)
        assertEquals(8, plan.notesToPrefetch.size)
        assertEquals(entries.map { it.pageId }, plan.notesToPrefetch.map { it.pageId })
    }

    @Test
    fun modeTimeWindowWithZeroRecentNotesFallsBackToFiveMostRecentOlderNotes() {
        // User scenario:
        // Architecture: 45 days ago
        // Chess: 70 days ago
        // Accessibility: 90 days ago
        // Kotlin: 120 days ago
        // AI Infrastructure: 150 days ago
        // Other: 200 days ago
        // Window = 30 days
        val entries = listOf(
            entry("arch", now.minus(Duration.ofDays(45))),
            entry("chess", now.minus(Duration.ofDays(70))),
            entry("a11y", now.minus(Duration.ofDays(90))),
            entry("kotlin", now.minus(Duration.ofDays(120))),
            entry("ai", now.minus(Duration.ofDays(150))),
            entry("other", now.minus(Duration.ofDays(200)))
        )
        val settings = ReaderSettings(
            autoCacheMode = AutoCacheMode.TIME_WINDOW,
            autoCacheTimeWindow = AutoCacheTimeWindow.DAYS_30
        )

        val plan = AutoCachePlanner.plan(entries, settings, now)
        // Must fallback to the 5 most recent older notes, excluding "other" (200 days ago)
        assertEquals(5, plan.notesToPrefetch.size)
        assertEquals(listOf("arch", "chess", "a11y", "kotlin", "ai"), plan.notesToPrefetch.map { it.pageId })
        assertEquals(setOf("arch", "chess", "a11y", "kotlin", "ai"), plan.desiredPageIds)
    }

    @Test
    fun modeTimeWindowWithSparseRecentNotesFillsFallbackToMinimumFive() {
        val entries = listOf(
            entry("recent-1", now.minus(Duration.ofDays(2))),
            entry("recent-2", now.minus(Duration.ofDays(5))),
            entry("older-1", now.minus(Duration.ofDays(40))),
            entry("older-2", now.minus(Duration.ofDays(50))),
            entry("older-3", now.minus(Duration.ofDays(60))),
            entry("older-4", now.minus(Duration.ofDays(70)))
        )
        val settings = ReaderSettings(
            autoCacheMode = AutoCacheMode.TIME_WINDOW,
            autoCacheTimeWindow = AutoCacheTimeWindow.DAYS_30
        )

        val plan = AutoCachePlanner.plan(entries, settings, now)
        // 2 in-window + 3 older fallback = 5 notes
        assertEquals(5, plan.notesToPrefetch.size)
        assertEquals(
            listOf("recent-1", "recent-2", "older-1", "older-2", "older-3"),
            plan.notesToPrefetch.map { it.pageId }
        )
    }

    @Test
    fun unknownTimestampsPlacedInFallbackPoolAfterKnownNotes() {
        val entries = listOf(
            entry("unknown-1", null),
            entry("known-old", now.minus(Duration.ofDays(50))),
            entry("unknown-2", null)
        )
        val settings = ReaderSettings(
            autoCacheMode = AutoCacheMode.TIME_WINDOW,
            autoCacheTimeWindow = AutoCacheTimeWindow.DAYS_7
        )

        val plan = AutoCachePlanner.plan(entries, settings, now)
        // Window has 0 notes -> fallback needs 5 notes -> all 3 entries taken,
        // with known note ranked ahead of unknown notes
        assertEquals(3, plan.notesToPrefetch.size)
        assertEquals("known-old", plan.notesToPrefetch[0].pageId)
        assertTrue(plan.notesToPrefetch.map { it.pageId }.containsAll(listOf("unknown-1", "unknown-2")))
    }

    private fun entry(pageId: String, lastEditedAt: Instant?): ReaderIndexEntry = ReaderIndexEntry(
        pageId = pageId,
        title = "Title for $pageId",
        lastEditedAt = lastEditedAt
    )
}
