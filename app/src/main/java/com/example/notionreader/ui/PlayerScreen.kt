package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.reader.ReaderState

/**
 * The Player: the dedicated continuous spoken-reading surface.
 *
 * It receives state and callbacks only; all navigation and playback logic lives in
 * [com.example.notionreader.reader.ReaderEngine]. The UI contract's player content is honoured as far
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
    statusMessage: String? = null
) {
    // Reading is hands-free, so the display must not time out mid-paragraph. It is only held awake
    // while playing, so a paused reader still lets the device sleep. (A background playback service is
    // the real long-term answer; see the README.)
    val view = LocalView.current
    DisposableEffect(view, state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { paneTitle = "Player" }
    ) {
        Text(
            text = documentTitle,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

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

        Text(
            text = state.playbackStateDescription,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )

        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Text(
            text = state.currentParagraph,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPreviousSection,
                enabled = !state.isAtFirstParagraph,
                modifier = Modifier.weight(1f)
            ) {
                Text("Previous section")
            }

            Button(
                onClick = onPlayPause,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }

            Button(
                onClick = onNextSection,
                enabled = !state.isAtLastParagraph,
                modifier = Modifier.weight(1f)
            ) {
                Text("Next section")
            }
        }
    }
}
