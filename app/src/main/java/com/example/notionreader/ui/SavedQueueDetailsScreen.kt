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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.application.SavedQueueCard

/**
 * One Saved Queue, in the **Viewing** state.
 *
 * ## What it is for, in the contract's words
 *
 * "understanding the queue; opening Notes; starting playback." So: the name, how many Notes it holds, the
 * members in their order each opening its Note, and `Play`.
 *
 * ## Viewing is a state, not the absence of one
 *
 * Edit mode is this screen's other state, and the Queue Creation spec models it as
 * `Viewing | Editing` rather than as a boolean — because *which controls exist* is derived from the state
 * rather than checked beside it. That is what makes this composable's contract precise: **no move and no
 * `Remove` is rendered here at all**, in the sense of not existing rather than of being disabled. The UI
 * contract's reason is accessibility as much as tidiness: "the normal Saved Queue Details screen should not
 * expose multiple destructive/reorder controls on every Note", because a screen where every row carries
 * five controls is one a screen-reader listener cannot traverse, and it puts a destructive action one focus
 * stop from an ordinary one.
 *
 * The editing surface is a later slice. What matters here is that its absence is the state's, and that a
 * later slice adds a branch rather than a flag.
 *
 * ## `Play` is disabled on an empty queue, and says why
 *
 * The spec: **Play on an empty queue is not offered** — "the control is disabled rather than inert, because
 * a Play that can do nothing is a control a listener cannot use, and 'this queue is empty' is the
 * information they actually need". So the disabled control is accompanied by that sentence. A disabled
 * button with no explanation is a control the listener will keep pressing.
 *
 * `isPlayable` comes from [SavedQueueCard], which is also what `ReaderApp.playSavedQueue` refuses on, so
 * the screen and the operation cannot disagree about when Play is offered.
 *
 * ## A member row opens its Note and does not play it
 *
 * The contract is explicit: "Activating an individual queue item opens that Note. It does not start
 * playback." That is the same rule Browse's rows follow, and it is why the row is a plain activation with
 * no play control beside it.
 *
 * ## A queue that is gone
 *
 * The screen's subject can disappear — the list it was opened from is not frozen, and a store that could not
 * be read reports no queues. That is rendered as its own honest state rather than left to a crash or to a
 * blank screen, and it deliberately **offers nothing to fix**: the specification's rule for a session whose
 * source queue was deleted is that "it is not an error state, and nothing is offered to 'fix' it", and the
 * same applies to looking at a queue that is no longer there. Back leaves, which is the whole of it.
 */
@Composable
fun SavedQueueDetailsScreen(
    /**
     * The queue, or null when it is no longer there.
     *
     * Nullable rather than non-null with the caller expected to avoid it, because the case is reachable and
     * not a programming error: the list the screen was opened from can change underneath it, and a store
     * that could not be read reports no queues at all. A `SavedQueueDetails` screen whose subject is missing
     * is a real state, so it is rendered rather than crashed on — the same posture as the Listening tab
     * consuming a focus hint for a session it no longer shows.
     */
    queue: SavedQueueCard?,
    onOpenNote: (String) -> Unit,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The member the listener activated before leaving, if Back brought them back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    if (queue == null) {
        GoneQueue(modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = queue.name }
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = queue.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = queue.memberDescription,
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { onPlay(queue.queueId) },
            enabled = queue.isPlayable,
            modifier = Modifier.restoreFocusOn(
                target = focusTarget,
                // A set-level control again — it plays the queue, not a member — so it is named by the
                // control rather than by any content.
                id = ReaderFocus.SavedQueuePlay,
                onHandled = onFocusHandled
            )
        ) {
            Text("Play")
        }

        if (!queue.isPlayable) {
            Text(
                // The reason, next to the control that is unavailable. Omitted when Play *is* available,
                // because a sentence explaining a rule that is not in effect is noise.
                text = "This queue is empty, so there is nothing to play yet. Add Notes to it from Browse.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (queue.members.isEmpty()) {
            return@Column
        }

        Text(
            text = "Notes",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        queue.members.forEachIndexed { position, member ->
            SavedQueueMemberRow(
                // The position as it is spoken, counting from 1. Rendered rather than derived from the
                // loop index at the point of display, so "1." and the row are the same fact.
                position = position + 1,
                title = member.title,
                pageId = member.pageId,
                onOpen = { onOpenNote(member.pageId) },
                focusTarget = focusTarget,
                onFocusHandled = onFocusHandled
            )
        }
    }
}

/**
 * The state for a queue that is no longer there.
 *
 * It says what happened rather than showing an empty queue, because an empty queue is a *different* and
 * perfectly valid state — one the listener can have created deliberately — and conflating the two would tell
 * them their queue was empty when in fact it is gone.
 */
@Composable
private fun GoneQueue(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Saved queue" }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "This saved queue is no longer here.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = "It may have been deleted. Your other queues are on the Saved tab.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** One member: its position, its title, and opening itself. One focus stop, like a Browse row. */
@Composable
private fun SavedQueueMemberRow(
    position: Int,
    title: String,
    pageId: String,
    onOpen: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Before `clickable`, because that is the modifier that creates the focus target this requester
            // attaches to.
            .restoreFocusOn(
                target = focusTarget,
                // The page id: a member row is identified by the Note it opens, which is also what Back
                // records when it leaves this screen for that Note.
                id = pageId,
                onHandled = onFocusHandled
            )
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 12.dp)
    ) {
        Text(
            // The position and the title as one string, so a listener hears "3, AccessibilityService
            // notes" rather than two announcements that could be read in either order.
            text = "$position. $title",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
