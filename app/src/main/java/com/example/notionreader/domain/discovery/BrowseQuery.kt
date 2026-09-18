package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.ReaderIndexEntry
import java.time.Instant
/**
 * How a result set is ordered.
 *
 * Only the two orders the available data can actually support are listed. The UI contract offers
 * "Recently updated, Newest date, Oldest date, Title, Relevance", and adds the constraint that matters
 * more than the list: "Not every sorting method must apply to every query. Search may default to
 * Relevance." Date-based orders need a content date and Title needs nothing, so `Title` could be added
 * cheaply — but it is not here, because an enum member is a promise that a surface can select it and
 * nothing selects it yet.
 */
enum class BrowseSort {
    /** `All Notes, Sort = Recently updated` — the contract's default Browse state. */
    RECENTLY_UPDATED,
    TITLE
}

/**
 * What the user is asking Browse for.
 *
 * A query is a *value*, which is what makes the contract's state-restoration rule implementable: Browse
 * has to keep "search text; filters; sort" across a trip to the Note screen and back, and a value can be
 * retained and compared where a set of live text fields cannot.
 */
data class BrowseQuery(
    val text: String = "",
    val sort: BrowseSort = BrowseSort.RECENTLY_UPDATED
) {
    val hasText: Boolean get() = text.isNotBlank()

    companion object {
        /** The contract's default: "All Notes, Sort = Recently updated". Never empty. */
        val Default = BrowseQuery()
    }
}

/**
 * The ordered result set — "the central unit of Browse".
 *
 * The contract describes the model as `BrowseQuery → OrderedResultSet` and then gives the result set
 * three consumers: open one result, `Play all`, `Add all to queue`. So this type is where "the currently
 * visible ordered result set" becomes a thing a caller can hold. Both of the actions that consume the
 * whole set take it from here, and both take it as an immutable ordering — the contract's "preserves the
 * visible order" is then not a rule anyone has to remember, because there is no way to hand over the
 * query instead.
 */
data class BrowseResults(
    val query: BrowseQuery = BrowseQuery.Default,
    val entries: List<ReaderIndexEntry> = emptyList()
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    val size: Int get() = entries.size

    /**
     * The queue identity of every visible result, in visible order.
     *
     * This one line is what `Play all` means: "takes the currently visible result set; preserves the
     * visible order". Returning a `List<QueuedNote>` rather than a description of one means the caller
     * cannot accidentally pass the query along instead.
     */
    fun toQueuedNotes(): List<QueuedNote> = entries.map { it.toQueuedNote() }
}

/**
 * Filters and orders [entries] for [query].
 *
 * A pure function, deliberately: it is the whole of Browse's behaviour that is testable without a
 * device, an emulator or a network, and the contract's Browse rules are almost entirely about ordering
 * and matching rather than about rendering.
 *
 * **Matching is deliberately crude, and that is recorded rather than hidden.** The contract says search
 * "should conceptually match reader-useful fields such as Title, Summary, searchable normalized
 * content", and the Browse/Search implementation specification — which owns the real answer — does not
 * exist. So this matches on the title, case-insensitively, and says so. Two consequences worth being
 * explicit about: it cannot match a word that appears only in a Note's body, and it does not normalise
 * accents or treat synonyms as equivalent. Implementing either here would be inventing the contract that
 * is supposed to define them.
 */
fun BrowseQuery.applyTo(entries: List<ReaderIndexEntry>): BrowseResults {
    val needle = text.trim()

    val matched = if (needle.isEmpty()) {
        entries
    } else {
        entries.filter { it.title.contains(needle, ignoreCase = true) }
    }

    val ordered = when (sort) {
        // Descending by edit time, with unknown dates LAST. Sorting on a nullable directly would put
        // nulls first, which for "Recently updated" is the wrong answer for a row whose date is unknown:
        // not knowing when something changed is not evidence that it changed just now. The first
        // comparator splits known from unknown; the second orders only the known ones, because the
        // unknown group is already collapsed to a single value.
        BrowseSort.RECENTLY_UPDATED -> matched.sortedWith(
            compareBy<ReaderIndexEntry> { it.lastEditedAt == null }
                .thenByDescending { it.lastEditedAt ?: Instant.EPOCH }
        )
        BrowseSort.TITLE -> matched.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
    }

    return BrowseResults(query = this, entries = ordered)
}
