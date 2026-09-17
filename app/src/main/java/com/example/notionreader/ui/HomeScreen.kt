package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ContinueListeningCard
import com.example.notionreader.ui.describeLastActivity

/**
 * Home: "useful starting points".
 *
 * ## What is here, and what is not yet
 *
 * Only Continue listening. The contract gives Home three sections, and the other two are absent for
 * reasons that are recorded rather than accidental:
 *
 * - **Your queues** needs Saved and Smart Queues, which need two specifications that do not exist.
 * - **Explore** needs metadata to generate its entries from. The contract is explicit that Explore should
 *   be "generated transparently from metadata rather than opaque AI recommendations", and that metadata
 *   is the missing Content Metadata & Workspace Schema Adapters specification.
 *
 * ## The empty state, which is not an empty result set
 *
 * With nothing to continue, Home has no sections at all — and a Home with none of its three sections is
 * an empty screen, which is the objection that removed Settings as a tab (`docs/decisions.md` §12.5). It
 * is kept anyway, for a reason that does not apply to Settings: Home has a real, useful route out. So the
 * empty state **explains itself and offers Browse** rather than rendering as a blank list, which is what
 * distinguishes it from the "empty result set" the contract warns about.
 *
 * ## History, not just the live session
 *
 * This section reads real listening history — the Active Queue & Reader Session spec's unfinished
 * sessions, most recently active first — so it is no longer a projection of whatever happens to be
 * playing. That is what makes it *history*: a session paused yesterday is still here after the process has
 * died, and Continue on it restores that session's own queue and position rather than the current one's.
 *
 * The list is capped by the caller: the contract says Home "should show only a small useful subset" and
 * that the full history lives under `Queues → Listening`.
 *
 * ## Accessibility
 *
 * One session is one focus stop: each entry merges its descendants so TalkBack reads the title, position,
 * state and recency as one item instead of four swipes, with Continue as an explicit control beside it.
 * The heading is a heading, so the screen can be skimmed by structure, and the screen names itself with a
 * pane title so entering it says where the user is.
 */
@Composable
fun HomeScreen(
    continueListening: List<ContinueListeningCard>,
    onContinue: (String) -> Unit,
    onBrowse: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The instant the relative times are relative to.
     *
     * A parameter rather than read inside, so the only time-dependent thing on this screen is an input.
     * Reading the clock during composition would make the same session describe itself differently on two
     * recompositions, and would put a clock in the middle of a display-only composable.
     */
    now: Long = System.currentTimeMillis()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { paneTitle = "Home" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (continueListening.isEmpty()) {
            Text(
                text = "Nothing to continue yet.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            Text(
                text = "Play a Note and it will appear here, so you can pick up where you left off.",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(onClick = onBrowse) {
                Text("Browse Notes")
            }
            return@Column
        }

        Text(
            text = "Continue listening",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        continueListening.forEach { session ->
            ContinueListeningEntry(
                session = session,
                now = now,
                onContinue = { onContinue(session.sessionId) },
                onPlayPause = onPlayPause
            )
        }
    }
}

/** One history entry: the session, then Continue, then Play/Pause for the live one. */
@Composable
private fun ContinueListeningEntry(
    session: ContinueListeningCard,
    now: Long,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // One focus stop for the session, as with a Browse result row.
                .semantics(mergeDescendants = true) {}
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyLarge
            )

            if (session.queuePosition != null) {
                Text(
                    text = session.queuePosition,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Text(
                text = "${session.stateDescription} · ${describeLastActivity(now - session.lastActivityAt)}",
                style = MaterialTheme.typography.labelMedium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onContinue) {
                Text("Continue")
            }

            TextButton(onClick = onPlayPause) {
                Text(if (session.isPlaying) "Pause" else "Play")
            }
        }
    }
}
