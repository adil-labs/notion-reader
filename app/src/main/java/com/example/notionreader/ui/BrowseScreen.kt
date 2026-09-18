package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.BrowseIndexStatus
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.domain.discovery.BrowseResults
import com.example.notionreader.domain.discovery.BrowseSort
import com.example.notionreader.domain.ReaderIndexEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Browse: the reader-oriented discovery surface and "the primary semantic discovery interface".
 *
 * ## What is here, and what is deliberately not
 *
 * This implements Browse's **default state** and search. The contract's default is "`All Notes`, `Sort =
 * Recently updated`" and "Browse must not open as an empty screen", and both are satisfied from the
 * integration's own page list — no filter needs to exist for the default result set to exist.
 *
 * Absent, each for a stated reason rather than an oversight:
 *
 * - **Filters (Type, Area, Topics, Project, More).** These need the Content Metadata & Workspace Schema
 *   Adapters specification, which does not exist, and `docs/decisions.md` §5 records that the workspace
 *   cannot supply part of what it would have to say: no data source has a `relation` property, `Status`
 *   vocabularies differ per schema, and Area is a per-schema union rather than a controlled vocabulary.
 *   Rendering filter controls before that is settled would mean inventing the vocabulary they filter on.
 * - **`Add all to queue`.** It routes to Queue Destination, which is Saved Queue territory — slice F,
 *   blocked on two specifications. There is no Saved Queue to add to, so the control would be inert.
 * - **`Browse Notion structure`.** Slice E. Raw Notion navigation is the contract's "secondary escape
 *   hatch" and is deliberately built after the primary discovery surface.
 *
 * ## Accessibility
 *
 * The contract's rule for a result list is "One Browse result or one Queue row should normally be one
 * primary focus item", so each row **merges its descendants** and is a single focus stop: the title and
 * the date are one announcement, not two. It says to avoid a separate stop for the title, type, date,
 * area, summary and menu, and it gives an example of a useful default announcement — so the date is
 * included in the merged row rather than being its own focusable node.
 *
 * Loading, empty and error are three distinct textual states, and none of them is conveyed by animation
 * alone. The error is a polite live region so a failure is announced when it happens, and Retry is
 * inside that notice but outside the live-region text, for the same reason as on the Note screen: a
 * control must not be read as part of the sentence describing the failure.
 *
 * The result count is deliberately **not** a live region. Search changes it on every keystroke, and the
 * contract warns against repeatedly re-announcing large parts of the interface for small state changes.
 *
 * Focus restoration is per row rather than per screen: see [restoreFocusOn] for why the item is the only
 * thing that can know when it exists.
 */
@Composable
fun BrowseScreen(
    state: BrowseResults,
    isLoading: Boolean,
    error: BrowseIndexStatus.Error?,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onOpenNote: (ReaderIndexEntry) -> Unit,
    onPlayAll: () -> Unit,
    onClearQuery: () -> Unit,
    onRetry: () -> Unit,
    onSortChange: (BrowseSort) -> Unit = {},
    modifier: Modifier = Modifier,
    /** The element the listener activated before leaving this screen, if they came back with Back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    // A hint this screen cannot resolve — a page that has since left the result set, say — is consumed
    // rather than left pending. Leaving it would mean it fired the next time a row with that id appeared,
    // which is the unpredictable focus movement the contract's rule exists to prevent.
    LaunchedEffect(focusTarget, state.entries) {
        val owned = focusTarget == ReaderFocus.BrowsePlayAll ||
            state.entries.any { it.pageId == focusTarget }
        if (focusTarget != null && !owned) onFocusHandled()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Browse" }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = onQueryChange,
                label = { Text("Search notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // "When filters are active, Browse should expose a concise summary", so the user does not have
            // to inspect every control to understand the current state. Only the search is a filter today,
            // so the summary is one phrase plus a way out of it.
            if (state.query.hasText) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "${state.size} result${if (state.size == 1) "" else "s"} for " +
                            "\"${state.query.text}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearQuery) {
                        Text("Clear search")
                    }
                }
            }

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Sort:",
                    style = MaterialTheme.typography.labelMedium
                )
                Button(
                    onClick = { onSortChange(BrowseSort.RECENTLY_UPDATED) },
                    enabled = state.query.sort != BrowseSort.RECENTLY_UPDATED
                ) {
                    Text("Recently updated")
                }
                Button(
                    onClick = { onSortChange(BrowseSort.TITLE) },
                    enabled = state.query.sort != BrowseSort.TITLE
                ) {
                    Text("Title")
                }
            }

            Text(
                text = "All Notes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
        }

        // A failure is shown as a notice *above* the results rather than instead of them. Blanking a
        // visible result set because a refresh failed would lose content the user can already use — the
        // same rule the Note screen follows for a failed refresh.
        if (error != null) {
            BrowseErrorNotice(error = error, onRetry = onRetry)
        }

        when {
            // "Loading Notes… is not equivalent to No matching Notes." Loading is stated in words, and
            // it is only shown when there is nothing to show, so a refresh of a visible result set does
            // not replace the results with a loading line.
            isLoading && state.isEmpty -> Text(
                text = "Loading Notes…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // Two different empty states, because they mean different things: nothing matched the search,
            // versus the integration cannot see any pages at all. Collapsing them would tell someone their
            // search was too narrow when the real problem is that no page has been shared.
            state.isEmpty && state.query.hasText -> Text(
                text = "No matching Notes.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            state.isEmpty && error != null -> Unit

            state.isEmpty -> Text(
                text = "No Notes are available. Share a page with the Notion integration to read it here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = state.entries, key = { it.pageId }) { entry ->
                        BrowseResultRow(
                            entry = entry,
                            onClick = { onOpenNote(entry) },
                            focusTarget = focusTarget,
                            onFocusHandled = onFocusHandled
                        )
                    }
                }

                // "For a non-empty result set, Browse exposes Play all ... These controls belong to the
                // result set as a whole. They should not be repeated on every individual result row."
                Button(
                    onClick = onPlayAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A set-level action has no content identity, so it is named by the control — see
                        // `ReaderFocus`. The listener who left the Player with Back lands on the button
                        // they pressed to get there.
                        .restoreFocusOn(
                            target = focusTarget,
                            id = ReaderFocus.BrowsePlayAll,
                            onHandled = onFocusHandled
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("Play all")
                }
            }
        }
    }
}

/**
 * One result, as one focus stop.
 *
 * `mergeDescendants = true` is the whole accessibility contract for a result row: TalkBack reads
 * "Title, September 17" as one item rather than making the user swipe through the title and then the
 * date. The contract's example announcement is "Thoughts are less reactive but still frequent.
 * Reflection. Mind and Learning. September 17." — the type and area parts of that are waiting on the
 * metadata specification; the title and date are what exist.
 *
 * The page id is the row's identity everywhere — it is the list key, the id the navigation layer records,
 * and the id this row answers to when Back returns.
 */
@Composable
private fun BrowseResultRow(
    entry: ReaderIndexEntry,
    onClick: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Before `clickable`, because that is the modifier that creates the focus target this
            // requester attaches to.
            .restoreFocusOn(
                target = focusTarget,
                id = entry.pageId,
                onHandled = onFocusHandled
            )
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyLarge
        )

        val date = entry.lastEditedAt?.let {
            DateTimeFormatter.ofPattern("MMMM d").withZone(ZoneId.systemDefault()).format(it)
        }
        // Omitted rather than shown as "unknown": a row whose date could not be read should read as if it
        // has no date, which is true, rather than announcing a failure inside a list.
        if (date != null) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * A failed index load.
 *
 * The live region sits on the message text, not on the Column that wraps it and the Retry button. That is
 * a defect this project already fixed once on the Note screen: semantics applied to a parent that does
 * not merge its descendants creates its own node, so TalkBack watches an empty container while the
 * changing text sits in a child — and the announcement silently never fires.
 */
@Composable
private fun BrowseErrorNotice(
    error: BrowseIndexStatus.Error,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )

        // From the taxonomy's `canRetry`, not from reading the sentence: repeating a request that failed
        // because the token was revoked or the page was never shared cannot make it succeed.
        if (error.canRetry) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
