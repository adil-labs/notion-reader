package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.playerCommandAvailability
import com.example.notionreader.player.ReaderState

/**
 * The Player: the dedicated continuous spoken-reading surface.
 *
 * It receives state and callbacks only; all navigation and playback logic lives in
 * [com.example.notionreader.player.ReaderEngine] and
 * [com.example.notionreader.application.ReaderSession]. The contract's primary controls are all present —
 * `Previous section / Play / Pause / Next section / Queue` — plus `Previous note` and `Next note` as the
 * secondary actions the contract permits ("fine-grained chunk navigation and advanced commands may exist
 * as secondary actions"). Those two are what make a queue of many Notes navigable without listening to
 * the intervening ones, and they are the only way past a Note that will not load.
 *
 * **The `Queue` control opens a read-only view of the session's own snapshot**, which is what the contract
 * asks for: "`Queue` opens a view of the current frozen Active Queue/session ... This represents the
 * current playback snapshot. It is not the underlying Saved Queue or Smart Queue definition." There is
 * deliberately nothing to edit in it — the session's membership and order are fixed for its lifetime, and
 * removing or reordering would be a different feature requiring a different contract.
 *
 * **Navigation here does not start playback.** `Next note` and `Previous note` preserve the playing state:
 * pressing either while paused moves the position and stays silent, which is the behaviour `Next section`
 * already had ("while paused they only move the position") and the same rule the product contract states
 * as "navigation does not own playback".
 *
 * Accessibility:
 * - Every control has a self-explanatory accessible name ("Previous section", "Play", "Pause",
 *   "Next section", "Previous note", "Next note", "Queue") instead of "back"/"forward"/"button".
 * - [ReaderState.playbackStateDescription] sits in a polite live region, so paused, resumed and end of
 *   note are announced exactly when they happen — those are required announcements. The position text
 *   is *not* live, so it cannot talk over the document while every paragraph advances it.
 * - The screen names itself with a pane title, so entering it says where the user is.
 * - Controls that cannot do anything are disabled rather than silently inert.
 * - Nothing is force-focused, so TalkBack and the document TTS engine do not talk over each other.
 */
@Composable
fun PlayerScreen(
    state: ReaderState,
    documentTitle: String,
    /**
     * Invokes a Player command.
     *
     * **One callback rather than one per control**, because a control is a *rendering* of a command: the
     * Product & Architecture Contract requires that core Player commands "be representable independently of
     * individual UI widgets", and a lambda per button is what made that untrue before this existed. The
     * transport the Background Playback spec adds invokes the same commands through the same entry point.
     *
     * The index is the one argument any command carries — `JumpToNote` — and is null for every other.
     */
    onCommand: (PlayerCommand, Int?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    queueState: QueueState = QueueState(),
    onRetryQueueNote: () -> Unit = {}
) {
    // One published answer, read by every control below. A disabled control and an unadvertised transport
    // command are therefore the same fact rather than two evaluations of it.
    val availability = playerCommandAvailability(state, queueState)

    var showQueue by rememberSaveable { mutableStateOf(false) }

    // Reading is hands-free, so the display must not time out mid-paragraph. It is only held awake
    // while playing, so a paused reader still lets the device sleep. (A background playback service is
    // the real long-term answer; see the README.)
    val view = LocalView.current
    DisposableEffect(view, state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    if (showQueue) {
        ActiveQueueView(
            queueState = queueState,
            onClose = { showQueue = false },
            onSelectNote = { index ->
                // Selecting a row is `JumpToNote`, which preserves the playing state and is a no-op for the
                // Note already current. The view closes either way, because the listener asked to go there.
                onCommand(PlayerCommand.JumpToNote, index)
                showQueue = false
            },
            modifier = modifier
        )
        return
    }

    // A queued Note is named by the queue, not by the engine: while the next Note is still loading, the
    // engine is still holding the previous one, and showing its title would be showing the wrong Note.
    val title = queueState.currentItem?.title ?: documentTitle

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { paneTitle = "Player" }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

        // "queue position such as Note 3 of 8". Absent for a queue of one, which is every Play from the
        // Note screen — "Note 1 of 1" on the common path would be noise rather than information.
        val queuePosition = queueState.positionDescription
        if (queuePosition != null) {
            Text(
                text = queuePosition,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (queueState.isStalled) {
            // The queue is stalled on this Note: it did not load, and it has not been skipped. The
            // section and position lines are deliberately not shown, because the engine is still holding
            // the Note that already finished and would describe it as though it were current.
            QueueFailureNotice(
                failure = queueState.failure,
                onRetry = onRetryQueueNote
            )
        } else if (queueState.awaiting) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        } else {
            val sectionLine = listOfNotNull(
                state.sectionPositionDescription,
                state.currentSection?.title
            ).joinToString(" · ")

            if (sectionLine.isNotEmpty()) {
                Text(
                    text = sectionLine,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Text(
                text = state.positionDescription,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            // "At the final Note: announce that the queue has ended". Shown in place of the playback
            // state rather than as well as it: "End of queue" and "End of note" are different facts and
            // saying both would be saying the less useful one twice.
            val playbackLine = if (queueState.completed) "End of queue" else state.playbackStateDescription

            Text(
                text = playbackLine,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (!queueState.awaiting && !queueState.isStalled) {
            Text(
                text = state.currentParagraph,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            )
        } else {
            // Keeps the controls at the bottom of the screen in both states, so a transition cannot make
            // the buttons move under the user's finger.
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onCommand(PlayerCommand.PreviousSection, null) },
                enabled = availability.canMoveToPreviousSection,
                modifier = Modifier.weight(1f)
            ) {
                Text("Previous section")
            }

            Button(
                // TogglePlayback rather than a branch here: the label is the only thing that differs, and
                // the command is defined as exactly Play or Pause so the two cannot drift apart.
                onClick = { onCommand(PlayerCommand.TogglePlayback, null) },
                // Disabled while the queue cannot proceed. Enabling it would let Play call the engine and
                // replay the Note that just ended, which reads as the queue jumping backwards.
                enabled = availability.allows(PlayerCommand.TogglePlayback),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }

            Button(
                onClick = { onCommand(PlayerCommand.NextSection, null) },
                enabled = availability.canMoveToNextSection,
                modifier = Modifier.weight(1f)
            ) {
                Text("Next section")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (queueState.size > 1) {
                Button(
                    onClick = { onCommand(PlayerCommand.PreviousNote, null) },
                    // Enabled while the queue is *stalled*, deliberately. A Note that will not load leaves
                    // the session waiting with Retry as its only offer, and if moving on were also
                    // forbidden the rest of the queue would be unreachable — one missing page would cost
                    // the listener every Note after it. The spec's "it is never skipped" is about the app
                    // never advancing past a failed Note on its own; this is a listener pressing a control
                    // that says what it does, on a queue whose membership has not changed.
                    enabled = availability.canMoveToPreviousNote,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Previous note")
                }
            }

            Button(
                // A surface action rather than a command: `OpenQueue` changes what the phone is
                // displaying, so it is handled by the state that holds that and is deliberately absent
                // from the command set a transport may offer.
                onClick = { showQueue = true },
                enabled = availability.canOpenQueue,
                modifier = Modifier.weight(1f)
            ) {
                Text("Queue")
            }

            if (queueState.size > 1) {
                Button(
                    onClick = { onCommand(PlayerCommand.NextNote, null) },
                    enabled = availability.canMoveToNextNote,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Next note")
                }
            }
        }
    }
}

/**
 * The current frozen Active Queue — the session's snapshot, as a read-only view.
 *
 * ## Read-only, because the contract says so twice over
 *
 * The product contract's queue ownership model is "Saved Queues are edited. Smart Queues are configured.
 * **Active Queues are played.**" and its playback invariant is that "Active Queue membership and ordering
 * are fixed snapshots for the lifetime of that listening session". So there is deliberately no way to
 * remove, reorder, or add a Note here: the only thing a listener can do to a frozen queue is move within
 * it. The UI contract agrees about what this surface is — "`Queue` opens a view of the current frozen
 * Active Queue/session if such a view is required. This represents the current playback snapshot. **It is
 * not the underlying Saved Queue or Smart Queue definition.**"
 *
 * ## Why it is a view of the Player rather than a destination
 *
 * Because it is a view *of the Player's own session*, and the contract lists no screen for it — the ten
 * secondary screens name `Queue Destination`, which is the Saved Queue chooser that `Add all to queue`
 * routes through, and is a different thing entirely. It also means Back here is not the contract's
 * "returning from a secondary destination" case, so this is not a place focus restoration applies.
 *
 * Back closes the view and returns to the Player without touching playback. That is the one Back rule the
 * contract states as a prohibition — "Back must never implicitly mean: Pause, Stop, Previous section,
 * Previous note" — and it holds here because this composable is given no playback callback at all: it
 * cannot stop anything.
 *
 * ## Accessibility
 *
 * One row is one focus stop, which the contract requires of a queue row specifically ("One Browse result or
 * one Queue row should normally be one primary focus item"), with the position carried as the row's state
 * description so a listener hears where the row sits without a second swipe.
 *
 * The `▶` marker on the current row is **decorative and cleared from the semantics tree**. It is the same
 * defect this project already fixed on the Note screen, where bullets and check glyphs were being read out:
 * a screen reader announcing "▶" tells a listener nothing, and the row already says "Now playing".
 */
@Composable
private fun ActiveQueueView(
    queueState: QueueState,
    onClose: () -> Unit,
    onSelectNote: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Back closes this view rather than leaving the Player. The shell also registers a Back handler, and
    // an inner one wins by registration order — which is the framework's rule but a fragile thing to rely
    // on silently, so the intent is stated here and the behaviour is asserted by an instrumented test.
    BackHandler(onBack = onClose)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { paneTitle = "Active Queue" }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Active Queue",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Button(onClick = onClose) {
                Text("Close queue")
            }
        }

        // The same "Note 3 of 8" the Player shows, from the same source, so the two cannot disagree about
        // where the session is. Absent for a queue of one, which is every Play from the Note screen.
        val queuePosition = queueState.positionDescription
        if (queuePosition != null) {
            Text(
                text = queuePosition,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(queueState.items) { index, item ->
                val isCurrent = index == queueState.index
                val status = describeQueueRow(index = index, currentIndex = queueState.index)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectNote(index) }
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                        .semantics(mergeDescendants = true) {
                            stateDescription = status
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${index + 1}. ${item.title}",
                            style = if (isCurrent) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.bodyLarge
                            }
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isCurrent) {
                        Text(
                            text = "▶",
                            style = MaterialTheme.typography.bodyMedium,
                            // Decorative: it repeats what the row already says, so it must not be read out.
                            modifier = Modifier.clearAndSetSemantics {}
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where a queue row sits relative to the session's position.
 *
 * **Deliberately about position, not about completion.** The first version said "Completed" for every row
 * before the current one, which is a claim the session cannot make: a listener who pressed `Next note`
 * twice, or tapped a row near the end of the queue, moved *past* Notes that were never read. The queue
 * knows one fact — which Note is current — and these three labels say exactly that and nothing more.
 *
 * This is the same discipline the rest of the project applies to state it does not have: a false
 * "Completed" is worse than a vaguer true one, because a listener deciding whether to go back would rely
 * on it.
 */
private fun describeQueueRow(index: Int, currentIndex: Int): String = when {
    index == currentIndex -> "Now playing"
    index < currentIndex -> "Earlier in the queue"
    else -> "Later in the queue"
}

/**
 * A Note in the queue that could not be loaded, with Retry.
 *
 * The live region is on the message itself rather than on the Column that wraps it and the button. That
 * is a defect this project already fixed once on the Note screen: semantics on a parent that does not
 * merge its descendants creates its own node, so TalkBack watches an empty container while the changing
 * text sits in a child — and the announcement silently never fires.
 */
@Composable
private fun QueueFailureNotice(
    failure: ReaderLoadException?,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = failure?.message ?: "Could not load this Note.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )

        // Only offered when retrying could plausibly succeed, which is the taxonomy's answer rather than
        // a guess: repeated attempts at a missing page or a revoked token cannot start working.
        if (failure?.category?.canRetry != false) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
