package com.example.notionreader.domain.discovery

import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderIndexEntry
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Browse's default state, search and sort.
 *
 * Browse's contract rules are almost entirely about *ordering and matching* rather than about rendering,
 * so they are all testable here — no device, no emulator, no network. The two facts that matter most are
 * that the default query is never empty ("Browse must not open as an empty screen") and that
 * `Play all` can only ever see an ordering, because the result set hands over its entries as queue
 * identities in visible order.
 */
class BrowseQueryTest {

    private fun entry(id: String, title: String, edited: String? = null) = ReaderIndexEntry(
        pageId = id,
        title = title,
        lastEditedAt = edited?.let { Instant.parse(it) }
    )

    private val alpha = entry("a", "Alpha notes", "2026-09-01T10:00:00Z")
    private val beta = entry("b", "Beta practice", "2026-09-15T10:00:00Z")
    private val gamma = entry("c", "Gamma reflection", "2026-09-10T10:00:00Z")
    private val undated = entry("d", "Dated unknown")

    private val all = listOf(alpha, beta, gamma, undated)

    // --- The default state ------------------------------------------------------------------------

    @Test
    fun theDefaultQueryReturnsEveryPageMostRecentlyUpdatedFirst() {
        val results = BrowseQuery.Default.applyTo(all)

        // "Browse defaults to All Notes sorted by Recently updated."
        assertEquals(listOf("b", "c", "a", "d"), results.entries.map { it.pageId })
        assertEquals(4, results.size)
    }

    @Test
    fun theDefaultQueryIsEmptyTextRatherThanNothingToShow() {
        val results = BrowseQuery.Default.applyTo(all)

        assertFalse(results.query.hasText)
        // "Browse must not open as an empty screen": the default query returns the whole set.
        assertFalse(results.isEmpty)
    }

    @Test
    fun aPageWithNoKnownDateSortsLastRatherThanFirst() {
        val results = BrowseQuery.Default.applyTo(all)

        // Not knowing when a page changed is not evidence that it changed just now, so promoting it to the
        // top of "Recently updated" would be a wrong answer presented as a right one.
        assertEquals("d", results.entries.last().pageId)
    }

    // --- Search -----------------------------------------------------------------------------------

    @Test
    fun searchNarrowsTheSameResultSetRatherThanReplacingIt() {
        val results = BrowseQuery(text = "practice").applyTo(all)

        assertEquals(listOf("b"), results.entries.map { it.pageId })
        // The sort is still applied to the narrowed set, which is what makes search a narrowing rather
        // than a separate navigation hierarchy.
        assertEquals(BrowseSort.RECENTLY_UPDATED, results.query.sort)
    }

    @Test
    fun searchIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(listOf("b"), BrowseQuery(text = "  PRACTICE  ").applyTo(all).entries.map { it.pageId })
    }

    @Test
    fun searchMatchingNothingIsAnEmptyResultSetWithTheQueryStillVisible() {
        val results = BrowseQuery(text = "nothing matches this").applyTo(all)

        assertTrue(results.isEmpty)
        // The query is retained, so the screen can still describe and clear what was searched for.
        assertTrue(results.query.hasText)
        assertEquals("nothing matches this", results.query.text)
    }

    @Test
    fun aWhitespaceOnlySearchIsNotASearch() {
        // Otherwise the screen would show "N results for " with an invisible needle, and the empty-state
        // decision would branch on a filter nobody applied.
        assertFalse(BrowseQuery(text = "   ").hasText)
        assertEquals(4, BrowseQuery(text = "   ").applyTo(all).size)
    }

    // --- The result set as the unit of Play all ---------------------------------------------------

    @Test
    fun theResultSetHandsOverQueueIdentitiesInVisibleOrder() {
        val notes = BrowseQuery(text = "a").applyTo(all).toQueuedNotes()

        // "takes the currently visible result set; preserves the visible order" — the conversion returns
        // identities in the displayed order, so no caller has to re-derive it.
        assertEquals(listOf("b", "c", "a", "d"), notes.map { it.pageId })
        assertEquals("Beta practice", notes.first().title)
    }

    @Test
    fun anEmptyResultSetProducesNoQueueIdentities() {
        assertTrue(BrowseQuery(text = "zzz").applyTo(all).toQueuedNotes().isEmpty())
    }

    @Test
    fun everyEntryKeepsItsTitleSoAQueuedNoteCanBeNamedBeforeItIsLoaded() {
        val notes = BrowseQuery.Default.applyTo(all).toQueuedNotes()

        // The title is what "Note 3 of 8" and the between-Notes announcement use, at a point where no
        // document has been fetched.
        assertEquals(all.map { it.title }.toSet(), notes.map { it.title }.toSet())
    }

    // --- Facet Filters ----------------------------------------------------------------------------

    private val facetEntries = listOf(
        ReaderIndexEntry(
            pageId = "1",
            title = "Chess Opening Principles",
            kind = "Practice",
            area = "Chess",
            topics = setOf("Sicilian Defense", "Opening"),
            project = "Board Games",
            status = "In Progress"
        ),
        ReaderIndexEntry(
            pageId = "2",
            title = "Mindfulness in Daily Life",
            kind = "Reflection",
            area = "Mind & Learning",
            topics = setOf("mindfulness", "habits"),
            status = "Done"
        ),
        ReaderIndexEntry(
            pageId = "3",
            title = "Endgame Tactics in Chess",
            kind = "Practice",
            area = "Chess",
            topics = setOf("Endgame"),
            project = "Board Games",
            status = "Done"
        )
    )

    @Test
    fun filteringByTypeReturnsOnlyMatchingEntries() {
        val results = BrowseQuery(selectedType = "Reflection").applyTo(facetEntries)
        assertEquals(listOf("2"), results.entries.map { it.pageId })
        assertEquals(1, results.size)
    }

    @Test
    fun filteringByAreaReturnsOnlyMatchingEntries() {
        val results = BrowseQuery(selectedArea = "Chess").applyTo(facetEntries)
        assertEquals(listOf("1", "3"), results.entries.map { it.pageId })
    }

    @Test
    fun filteringByTopicReturnsOnlyMatchingEntries() {
        val results = BrowseQuery(selectedTopic = "Sicilian Defense").applyTo(facetEntries)
        assertEquals(listOf("1"), results.entries.map { it.pageId })
    }

    @Test
    fun combiningFiltersNarrowsConcurrently() {
        // Spec example: Type = Practice, Area = Chess, Topic = Sicilian Defense
        val query = BrowseQuery(
            selectedType = "Practice",
            selectedArea = "Chess",
            selectedTopic = "Sicilian Defense"
        )
        val results = query.applyTo(facetEntries)
        assertEquals(listOf("1"), results.entries.map { it.pageId })
        assertTrue(query.hasFilters)
        assertEquals(3, query.activeFilterCount)
        assertEquals(listOf("Practice", "Chess", "Sicilian Defense"), query.activeFilterLabels)
    }

    @Test
    fun combiningFiltersWithNoMatchesYieldsEmptyResultSet() {
        val query = BrowseQuery(
            selectedType = "Reflection",
            selectedArea = "Chess"
        )
        val results = query.applyTo(facetEntries)
        assertTrue(results.isEmpty)
        assertTrue(query.hasFilters)
    }

    @Test
    fun searchMatchesMetadataAsWellAsTitle() {
        val results = BrowseQuery(text = "mindfulness").applyTo(facetEntries)
        assertEquals(listOf("2"), results.entries.map { it.pageId })
    }
}
