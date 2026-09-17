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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.player.ReaderState

/**
 * The Player: the dedicated continuous spoken-reading surface.
 *
 * It receives state and callbacks only; all navigation and playback logic lives in
 * [com.example.notionreader.player.ReaderEngine]. The UI contract's player content is honoured as far
 * as the current milestone allows: current Note title, current section, progress, and Playing/Paused
 * state are all shown, and the primary controls are Previous section / Play-Pause / Next section.
 *
 * **The `Queue` control is deliberately absent.** The contract lists it among the primary controls, but
 * it "opens a view of the current frozen Active Queue/session", and neither the Active Queue nor its
 * specification exists yet. A visible control that does nothing when pressed is worse for a TalkBack
 * user than an absent one, so it is deferred to the Active Queue & Reader Session milestone rather
 * than stubbed. Fine-grained chunk navigation is likewise absent: the contract allows it, but only as
 * a secondary action.
 *
 * Accessibility:
 * - Every control has a self-explanatory accessible name ("Previous section", "Play", "Pause",
 *   "Next section") instead of "back"/"forward"/"button".
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
    onPlayPause: () -> Unit,
    onNextSection: () -> Unit,
    onPreviousSection: () -> Unit,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    queueState: QueueState = QueueState(),
    onRetryQueueNote: () -> Unit = {}
) {
    // Reading is hands-free, so the display must not time out mid-paragraph. It is only held awake
    // while playing, so a paused reader still lets the device sleep. (A background playback service is
    // the real long-term answer; see the README.)
    val view = LocalView.current
    DisposableEffect(view, state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
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
                onClick = onPreviousSection,
                enabled = !state.isAtFirstParagraph && !queueState.awaiting && !queueState.isStalled,
                modifier = Modifier.weight(1f)
            ) {
                Text("Previous section")
            }

            Button(
                onClick = onPlayPause,
                // Disabled while the queue cannot proceed. Enabling it would let Play call the engine and
                // replay the Note that just ended, which reads as the queue jumping backwards.
                enabled = !queueState.awaiting && !queueState.isStalled && !queueState.completed,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }

            Button(
                onClick = onNextSection,
                enabled = !state.isAtLastParagraph && !queueState.awaiting && !queueState.isStalled,
                modifier = Modifier.weight(1f)
            ) {
                Text("Next section")
            }
        }
    }
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
