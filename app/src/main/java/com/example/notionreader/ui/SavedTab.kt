package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.application.SavedQueueCard

/**
 * The Saved tab: persistent, manually curated queues.
 *
 * The contract's mental model for it is *"Playlists I built"*, and its example is a list of names each with
 * a member count, then `Create saved queue`.
 *
 * ## One row is one focus stop, and the queue is not called a button
 *
 * The contract says "each queue should normally be one logical TalkBack focus target". So the name and the
 * member count merge into a single announcement — the same shape a history entry has on the Listening tab,
 * and the same reason: a listener who hears a name and then a count has been told one thing, not two.
 *
 * Activating it opens Saved Queue Details. It **does not** start playback, which is the contract's rule for
 * this tab and the same rule the rest of the application follows.
 *
 * ## `Create saved queue` is always offered, and it is the empty state's route out
 *
 * The contract puts it on this tab and describes it as the deliberate way to make an empty queue. It is
 * therefore *not* something the empty state replaces: with no queues at all it is the only thing on screen
 * that does anything, and with queues it is how the collection grows. A tab that could only ever show what
 * already existed would be a dead end for a listener who has just installed the app.
 *
 * ## What is not here
 *
 * No Smart tab, and no member counts that imply a query. Smart Queues are a different tab, a different
 * specification and deliberately out of scope — this tab lists what the listener built and nothing else.
 */
@Composable
fun SavedTab(
    queues: List<SavedQueueCard>,
    onOpenQueue: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    /** The queue the listener activated before leaving this screen, if Back brought them back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    // A hint for a queue this list no longer shows is consumed rather than left pending — a deleted queue
    // would otherwise fire the target on some later visit.
    LaunchedEffect(focusTarget, queues) {
        if (focusTarget != null && queues.none { it.queueId == focusTarget }) onFocusHandled()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (queues.isEmpty()) {
            Text(
                text = "No saved queues yet.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            Text(
                text = "A saved queue is a list of Notes you keep — like a playlist. Create one, then add " +
                    "Notes to it while you browse.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            // The heading is stated rather than implied by the tab's own label, so the list can be skimmed
            // by structure the same way the Listening tab's sections can.
            Text(
                text = "Saved queues",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )

            queues.forEach { queue ->
                SavedQueueRow(
                    queue = queue,
                    onOpen = { onOpenQueue(queue.queueId) },
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled
                )
            }
        }

        // Outside the branch above on purpose: it is offered whether or not there are queues, because it is
        // both the way to add one to an existing collection and the way out of an empty tab.
        Button(
            onClick = onCreate,
            modifier = Modifier.restoreFocusOn(
                target = focusTarget,
                // The control's own id, not a queue's: this is the element that was activated to open the
                // Create screen, so it is what focus returns to. Nothing else on this screen could be it —
                // a queue row's id is a queue, and no queue existed when this was pressed.
                id = ReaderFocus.SavedQueueCreate,
                onHandled = onFocusHandled
            )
        ) {
            Text("Create saved queue")
        }
    }
}

/** One queue: its name and its membership as a single announcement, then nothing else to swipe through. */
@Composable
private fun SavedQueueRow(
    queue: SavedQueueCard,
    onOpen: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    // One focus stop that is also the control. The whole row is the activation, so unlike a history entry —
    // where the entry is a description and a separate button is the verb — there is no second control here
    // and the merged node *is* what the listener presses. `mergeDescendants` is what makes the name and the
    // count one announcement rather than two.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Before `clickable`, because that is the modifier that creates the focus target this requester
            // attaches to.
            .restoreFocusOn(
                target = focusTarget,
                // The queue's identity, which is what this row was given when it opened Details.
                id = queue.queueId,
                onHandled = onFocusHandled
            )
            .clickable(onClick = onOpen)
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
