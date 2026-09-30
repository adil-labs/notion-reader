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
    val sort: BrowseSort = BrowseSort.RECENTLY_UPDATED,
    val selectedType: String? = null,
    val selectedArea: String? = null,
    val selectedTopic: String? = null,
    val selectedProject: String? = null,
    val selectedStatus: String? = null
) {
    val hasText: Boolean get() = text.isNotBlank()

    val hasFilters: Boolean
        get() = selectedType != null ||
            selectedArea != null ||
            selectedTopic != null ||
            selectedProject != null ||
            selectedStatus != null

    val activeFilterLabels: List<String>
        get() = listOfNotNull(
            selectedType,
            selectedArea,
            selectedTopic,
            selectedProject,
            selectedStatus
        )

    val activeFilterCount: Int
        get() = activeFilterLabels.size

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
 */
fun BrowseQuery.applyTo(entries: List<ReaderIndexEntry>): BrowseResults {
    val needle = text.trim()

    var matched = entries

    // 1. Text filter: matches title, kind, area, topics, project, status
    if (needle.isNotEmpty()) {
        matched = matched.filter { entry ->
            entry.title.contains(needle, ignoreCase = true) ||
                (entry.kind?.contains(needle, ignoreCase = true) == true) ||
                (entry.area?.contains(needle, ignoreCase = true) == true) ||
                entry.topics.any { it.contains(needle, ignoreCase = true) } ||
                (entry.project?.contains(needle, ignoreCase = true) == true) ||
                (entry.status?.contains(needle, ignoreCase = true) == true)
        }
    }

    // 2. Facet filters
    if (selectedType != null) {
        matched = matched.filter { it.kind.equals(selectedType, ignoreCase = true) }
    }
    if (selectedArea != null) {
        matched = matched.filter { it.area.equals(selectedArea, ignoreCase = true) }
    }
    if (selectedTopic != null) {
        matched = matched.filter { entry ->
            entry.topics.any { it.equals(selectedTopic, ignoreCase = true) }
        }
    }
    if (selectedProject != null) {
        matched = matched.filter { it.project.equals(selectedProject, ignoreCase = true) }
    }
    if (selectedStatus != null) {
        matched = matched.filter { it.status.equals(selectedStatus, ignoreCase = true) }
    }

    // 3. Sorting
    val ordered = when (sort) {
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
