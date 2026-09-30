package com.example.notionreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.application.SavedQueueCard
import com.example.notionreader.domain.queue.QueueMove

/**
 * One Saved Queue: its members, `Play`, and the two states that decide which controls exist.
 *
 * ## What it is for, in the contract's words
 *
 * "understanding the queue; opening Notes; starting playback." So: the name, how many Notes it holds, the
 * members in their order each opening its Note, and `Play` — plus, in Edit mode, the four moves and
 * `Remove`.
 *
 * ## Viewing is a state, not the absence of one
 *
 * The Queue Creation spec models this screen as `Viewing | Editing` rather than as a boolean, because
 * *which controls exist* is derived from the state rather than checked beside it. That is what makes this
 * composable's contract precise: **in Viewing no move and no `Remove` is rendered at all**, in the sense of
 * not existing rather than of being disabled. The UI contract's reason is accessibility as much as tidiness:
 * "the normal Saved Queue Details screen should not expose multiple destructive/reorder controls on every
 * Note", because a screen where every row carries five controls is one a screen-reader listener cannot
 * traverse, and it puts a destructive action one focus stop from an ordinary one.
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
 * ## Two states, and why a state rather than a flag
 *
 * The Queue Creation & Queue Management spec models this screen as `Viewing | Editing`, "not a boolean,
 * deliberately. A boolean named `isEditing` would be the same fact, and the reason to model the state
 * instead is that the *operations available* differ between the two and are derived from the state rather
 * than checked beside it: `Remove`, the four moves and `Done` exist in Editing and nowhere else."
 *
 * That is exactly what the composable below does: the Editing-only controls are inside a branch on the
 * state, so in Viewing they are **absent** rather than disabled. The distinction is not academic — a screen
 * that rendered five disabled controls per member would be one a screen-reader listener has to traverse
 * past, which is the UI contract's objection verbatim.
 *
 * ## `Back` leaves Edit mode rather than the screen, and that is why this registers its own handler
 *
 * The shell registers a `BackHandler` whenever it has somewhere visual to go, and Saved Queue Details is a
 * pushed destination — so without this, Back from Edit mode would leave the listener on the previous screen
 * with their edits applied, making the platform's most reflexive gesture commit changes. The screen's own
 * handler is registered *later* (it is inside the shell's composition), and the innermost handler wins
 * because it was added last — the behaviour `ReaderShellQueueBackTest` already documents for the Player's
 * queue view.
 *
 * It is `enabled` only in Editing, so in Viewing Back is the shell's again and leaves the screen.
 *
 * ## Edits are applied as they are made, not held until `Done`
 *
 * The spec settles this and states why: "`Done` is therefore an exit, not a commit. Stated because the
 * opposite is a defensible design and the difference is user-visible: a listener who reorders and then
 * leaves by some route that is not `Done` should not silently lose the reorder." So every control here calls
 * straight through to an operation, and there is nothing to discard or to lose.
 *
 * ## A queue that is gone
 *
 * The screen's subject can disappear — the list it was opened from is not frozen, and a store that could not
 * be read reports no queues. That is rendered as its own honest state rather than left to a crash or to a
 * blank screen, and it deliberately **offers nothing to fix**: the specification's rule for a session whose
 * source queue was deleted is that "it is not an error state, and nothing is offered to 'fix' it", and the
 * same applies to looking at a queue that is no longer there. Back leaves, which is the whole of it.
 */

/**
 * Which of Saved Queue Details' two states is showing.
 *
 * Private to this file, and deliberately not hoisted into the application layer: nothing outside the screen
 * observes it. Back is handled by the screen's own `BackHandler`, and the operations are driven by the
 * controls rather than by the state, so a listener leaving Edit mode is a fact about the screen and not about
 * the reader. Hoisting it would create a second place where "is this queue being edited" is written.
 */
private enum class SavedQueueDetailsState { Viewing, Editing }

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
    /** One of the four moves, for the member with this page identity. */
    onMove: (String, QueueMove) -> Unit = { _, _ -> },
    /** `Remove`, for the member with this page identity. */
    onRemove: (String) -> Unit = { },
    /** `Rename`: updates the name of the queue. */
    onRename: (String, String) -> Unit = { _, _ -> },
    /** `Delete`: removes this queue. */
    onDelete: (String) -> Unit = { },
    /**
     * What the last queue operation did, when this screen is where the listener should hear it.
     *
     * The Add navigates here, so this is where its report is read — see `ReaderApp.queueMessage` for why the
     * de-duplication rule and the report are one requirement rather than two. A move or a remove sets it
     * too, and it is shown in the same place for the same reason: a listener who cannot see the list has no
     * other way to know where a member went.
     */
    report: String? = null,
    /**
     * Called once the listener has left this screen, so a message about a completed operation is not still
     * there the next time they look at that queue.
     */
    onReportShown: () -> Unit = {},
    /** The member the listener activated before leaving, if Back brought them back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    if (queue == null) {
        GoneQueue(modifier = modifier)
        return
    }

    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    // Retained across a configuration change, because a listener halfway through reordering a queue should
    // not be returned to Viewing by a rotation — the same argument the shell makes for Browse's scroll
    // position. The enum's *name* is saved rather than its ordinal so that a third state added later cannot
    // silently restore as the wrong one.
    var stateName by rememberSaveable { mutableStateOf(SavedQueueDetailsState.Viewing.name) }
    val state = SavedQueueDetailsState.entries.firstOrNull { it.name == stateName } ?: SavedQueueDetailsState.Viewing

    // Back leaves Edit mode and does not leave the screen. `enabled` only in Editing, so in Viewing this is
    // not registered at all and the shell's handler is the one that runs.
    BackHandler(enabled = state == SavedQueueDetailsState.Editing) {
        stateName = SavedQueueDetailsState.Viewing.name
    }

    // ## Why this is a dispose effect and not an effect keyed on the report
    //
    // The first version consumed the report **as soon as it rendered**, which cleared the state the moment it
    // was drawn — so the sentence appeared for one frame and vanished. Two of the new shell tests caught it,
    // and it is a real defect rather than a test artefact: a listener who cannot see the screen and wants the
    // sentence read a second time has nothing left to read, and the whole reason the report exists is that
    // "nothing was added" is information.
    //
    // The lifetime that is actually right is the *visit*: the report describes an operation the listener has
    // just made, so it belongs on screen while they are looking at the queue it changed, and it must not still
    // be there next time they open that queue — which would read as though it had just happened again.
    //
    // `rememberUpdatedState` so the disposer always calls the current callback: `DisposableEffect(Unit)`
    // captures the first one, and a stale reference would clear the wrong thing after any recomposition that
    // produced a new lambda.
    val currentOnReportShown by rememberUpdatedState(onReportShown)
    DisposableEffect(Unit) {
        onDispose { currentOnReportShown() }
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

        if (report != null) {
            Text(
                // A live region, because the listener has just pressed something and the sentence is the
                // answer: an Add that added nothing is still a result, and it is the case that most needs
                // saying out loud.
                text = report,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

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

        // The state's own control. In Viewing it offers the way in; in Editing it is `Done`, whose label
        // states what it does — "Cancel" would promise to undo, which this specification does not offer,
        // because edits are applied as they are made.
        Button(
            onClick = {
                stateName = if (state == SavedQueueDetailsState.Editing) {
                    SavedQueueDetailsState.Viewing.name
                } else {
                    SavedQueueDetailsState.Editing.name
                }
            }
        ) {
            Text(if (state == SavedQueueDetailsState.Editing) "Done" else "Edit queue")
        }

        if (state == SavedQueueDetailsState.Viewing) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        renameText = queue.name
                        showRenameDialog = true
                    },
                    modifier = Modifier.restoreFocusOn(
                        target = focusTarget,
                        id = ReaderFocus.SavedQueueRename,
                        onHandled = onFocusHandled
                    )
                ) {
                    Text("Rename")
                }

                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.restoreFocusOn(
                        target = focusTarget,
                        id = ReaderFocus.SavedQueueDelete,
                        onHandled = onFocusHandled
                    )
                ) {
                    Text("Delete")
                }
            }
        }

        if (queue.members.isEmpty()) {
            return@Column
        }

        Text(
            text = "Notes",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        queue.members.forEachIndexed { index, member ->
            SavedQueueMemberRow(
                // The position as it is spoken, counting from 1. Rendered rather than derived from the
                // loop index at the point of display, so "1." and the row are the same fact.
                position = index + 1,
                title = member.title,
                pageId = member.pageId,
                onOpen = { onOpenNote(member.pageId) },
                focusTarget = focusTarget,
                onFocusHandled = onFocusHandled,
                /**
                 * The four moves and `Remove`, in Editing and nowhere else.
                 *
                 * The moves come from `QueueMove.entries`, so a fifth move would appear here without this
                 * file being told about it, and its *availability* comes from `QueueMove.isAvailableFor` —
                 * which answers by applying the move and seeing whether anything changed. Deriving it means
                 * the boundary rule is stated once, in the model, rather than restated here in a form that
                 * could disagree with it.
                 */
                editing = state == SavedQueueDetailsState.Editing,
                moves = QueueMove.entries.map { move ->
                    QueueMoveControl(
                        move = move,
                        enabled = move.isAvailableFor(queue.members, index),
                        onClick = { onMove(member.pageId, move) }
                    )
                },
                onRemove = { onRemove(member.pageId) }
            )
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = "Rename queue",
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Queue name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            onRename(queue.queueId, trimmed)
                            showRenameDialog = false
                        }
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Delete queue",
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                Text("Delete \"${queue.name}\"? The notes in this queue will not be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(queue.queueId)
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** One move, as a control with a name that states its effect. */
private data class QueueMoveControl(
    val move: QueueMove,
    val enabled: Boolean,
    val onClick: () -> Unit
)

/**
 * The label a move is presented under.
 *
 * The spec names them: "the moves are named by their effect: `Move up`, `Move down`, `Move to top`,
 * `Move to bottom` ... Nothing is called 'more', 'options' or an icon-only label." Derived from the enum
 * rather than passed in, so a move cannot be rendered under a label that disagrees with what it does.
 */
private val QueueMove.label: String
    get() = when (this) {
        QueueMove.Up -> "Move up"
        QueueMove.Down -> "Move down"
        QueueMove.Top -> "Move to top"
        QueueMove.Bottom -> "Move to bottom"
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedQueueMemberRow(
    position: Int,
    title: String,
    pageId: String,
    onOpen: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit,
    /** Whether the queue is being edited, in which case the moves and `Remove` are shown beside the row. */
    editing: Boolean = false,
    moves: List<QueueMoveControl> = emptyList(),
    onRemove: () -> Unit = { }
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // The row itself: one focus stop, and the whole of it opens the Note. It stays a row that *opens*
        // rather than becoming an edit target, because the spec's Viewing state says a member "opens that
        // Note" and Editing changes which *controls* exist beside it rather than what the row means.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Before `clickable`, because that is the modifier that creates the focus target this
                // requester attaches to.
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

        if (!editing) return@Column

        // The controls are **beside** the member rather than merged into it — "one member row in Details is
        // one focus stop whose moves are separate controls beside it — the shape Queues already uses for
        // history entries". Merging them would make a five-part announcement of one row, and would put a
        // destructive action inside the sentence describing the member.
        //
        // ## Why this wraps, and it is a defect fix rather than a preference
        //
        // It was a `Row`, and five `TextButton`s do not fit across a phone. `In Editing it exposes all four
        // moves and Remove` was satisfied in the tree — every control was present and named — while `Remove`,
        // as the last child, was pushed outside the Row's bounds and clipped to **zero area**:
        //
        //     'Remove' is laid out but occupies no area, so nothing can be tapped:
        //       bounds=Rect.fromLTRB(0.0, 0.0, 0.0, 0.0)
        //
        // A control a listener cannot reach is not a control, which is the whole reason `assertIsReachable`
        // asserts area and overlap rather than mere presence. So the fix is layout: `FlowRow` wraps the
        // controls onto as many lines as they need, which keeps every move an ordinary named control at every
        // width and at every font scale — a `Row` would overflow again the moment a listener turned the system
        // font size up, which is exactly the configuration this screen has to work in.
        //
        // The order is preserved as the moves then `Remove`, so wrapping puts `Remove` first on a second line
        // rather than moving it anywhere a listener would not expect.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                // A traversal group, so a listener moving through the screen meets these as a set belonging
                // to the member above rather than as five unrelated buttons.
                .semantics { isTraversalGroup = true }
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            moves.forEach { control ->
                TextButton(onClick = control.onClick, enabled = control.enabled) {
                    // The move's own name, and the member's, so two members' `Move up` controls are told
                    // apart by a listener who reaches them out of order. A bare "Move up" five times over
                    // is the accessibility version of an icon-only label.
                    Text(control.move.label)
                }
            }

            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}
