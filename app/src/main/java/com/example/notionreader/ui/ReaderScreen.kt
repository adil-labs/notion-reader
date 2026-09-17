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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.reader.ReaderState

/**
 * Reader UI. It receives state and actions only; all navigation and playback logic lives in
 * [com.example.notionreader.reader.ReaderEngine].
 *
 * Accessibility:
 * - Every control has a self-explanatory accessible name ("Previous paragraph", "Play", "Pause",
 *   "Next paragraph") instead of "back"/"forward"/"button".
 * - The reading position is exposed as focusable text ("Paragraph 4 of 12").
 * - [statusMessage] is plain focusable text, not a live region: TalkBack can find it when the user
 *   explores, but it never speaks over the document while reading.
 * - Nothing is force-focused, so TalkBack and the document TTS engine do not talk over each other.
 */
@Composable
fun ReaderScreen(
    state: ReaderState,
    documentTitle: String,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
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
    ) {
        Text(
            text = "Notion Reader",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = documentTitle,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { heading() }
        )

        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Text(
            text = state.positionDescription,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 16.dp)
        )

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
                onClick = onPrevious,
                modifier = Modifier.weight(1f)
            ) {
                Text("Previous paragraph")
            }

            Button(
                onClick = onPlayPause,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }

            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f)
            ) {
                Text("Next paragraph")
            }
        }
    }
}
