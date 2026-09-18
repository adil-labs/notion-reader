package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.application.SavedQueueCard

/**
 * Queue Destination: which Saved Queue receives the Notes that were captured.
 *
 * ## What it shows, and what it deliberately does not
 *
 * The contract is unusually specific, listing three things that must **not** appear, and each is a real
 * temptation on a screen like this:
 *
 * - **Smart Queues** — "not valid manual-add destinations", because their membership is query-defined and
 *   adding to one would be asserting a membership the query owns.
 * - **The Active Queue** — "playback state. It is not a general persistent destination for organizing content
 *   from Browse". It is the queue that looks most like a destination and is the only one that must not be.
 * - **Any concept of a current or default queue** — the contract's non-goal is an "ambiguous global concept of
 *   a Current queue while the user is merely browsing", and a chooser is exactly where such a concept takes
 *   hold. So there is no pre-selected row, no "last used" marker, and no default.
 *
 * ## The listener is told what is being placed
 *
 * `Add to queue` from a Note and `Add all to queue` from Browse reach the same screen with different-sized
 * sets, so the screen states the count. Without it a listener who pressed `Add all to queue` over forty
 * results and one who pressed `Add to queue` on a single Note see identical screens and cannot tell which
 * intent they are completing.
 *
 * ## The report
 *
 * The Add has already happened by the time this screen is left, and `report` is what it did. It is shown on
 * the queue's Details screen rather than here, because the Add navigates there — see `ReaderApp.queueReport`.
 * This screen renders it when one is present anyway, since a report that failed to be consumed must be
 * visible rather than silently dropped.
 *
 * ## `Create new queue` is the route out of an empty state
 *
 * The contract allows going straight to Create when no Saved Queue exists, and this screen offers the same
 * control either way rather than branching: a listener with queues may still want a new one, and the created
 * queue receives the captured Notes (`ReaderApp.createSavedQueue`). That is the "these things, into a queue"
 * intent being completed rather than abandoned at a screen with nothing on it.
 */
@Composable
fun QueueDestinationScreen(
    queues: List<SavedQueueCard>,
    /** How many Notes are waiting to be placed. */
    pendingCount: Int,
    onCreateNewQueue: () -> Unit,
    onChooseQueue: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** What the last Add did, if a report is still unread. */
    report: String? = null,
    /** The queue the listener chose before leaving, if Back brought them back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    // A hint for a queue this list no longer shows is consumed rather than left pending.
    LaunchedEffect(focusTarget, queues) {
        if (focusTarget != null && queues.none { it.queueId == focusTarget }) onFocusHandled()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Add to queue" }
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Add to queue",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            // Singular-aware for the same reason a member count is: "1 notes" is only ever produced by an
            // implementation that never handled the case.
            text = if (pendingCount == 1) "1 note to add." else "$pendingCount notes to add.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (report != null) {
            Text(
                text = report,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        if (queues.isEmpty()) {
            // The empty state is a sentence and not a dead end, because the control below is the way out and
            // the contract names it as the supported route.
            Text(
                text = "You have no saved queues yet. Create one and these notes go straight into it.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = "Choose a queue",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            queues.forEach { queue ->
                DestinationRow(
                    queue = queue,
                    onChoose = { onChooseQueue(queue.queueId) },
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled
                )
            }
        }

        Button(
            onClick = onCreateNewQueue,
            modifier = Modifier.restoreFocusOn(
                target = focusTarget,
                id = ReaderFocus.SavedQueueCreate,
                onHandled = onFocusHandled
            )
        ) {
            Text("Create new queue")
        }
    }
}

/** One destination: the queue, its size, and choosing it. One focus stop that is also the control. */
@Composable
private fun DestinationRow(
    queue: SavedQueueCard,
    onChoose: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Before `clickable`, because that is the modifier that creates the focus target this requester
            // attaches to. See `restoreFocusOn` for why `clickable` alone is not enough.
            .restoreFocusOn(
                target = focusTarget,
                id = queue.queueId,
                onHandled = onFocusHandled
            )
            .clickable(onClick = onChoose)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = queue.name,
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = queue.memberDescription,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
