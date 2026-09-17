package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/**
 * A resumable listening session, as Home and the mini-player both need to describe it.
 *
 * A small value rather than the session itself, so this screen stays display-only: it cannot pause,
 * stop, or reorder anything because it is not given the means to. `isPlaying` is a description for the
 * screen and its control, not a claim that the screen owns the state.
 */
data class ContinueListeningCard(
    /** The Note title. */
    val title: String,
    /** "Note 3 of 8", or null for a queue of one. */
    val queuePosition: String?,
    /** "Playing", "Paused", or "Loading…" — the same vocabulary the Player uses. */
    val stateDescription: String,
    val isPlaying: Boolean
)

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
 * This is still the weaker surface until persistence exists, because Continue listening means *history*
 * ("Paused 2 hours ago") and history is what the Active Queue & Reader Session specification owns. Until
 * then this section can only show the live session, which is nearly what the mini-player shows. That
 * tension is recorded in `docs/decisions.md` §14.5 rather than papered over.
 *
 * ## Accessibility
 *
 * One session is one focus stop: the card merges its descendants so TalkBack reads the title, position
 * and state as one item instead of three swipes, with Continue as an explicit control beside it. The
 * heading is a heading, so the screen can be skimmed by structure, and the screen names itself with a
 * pane title so entering it says where the user is.
 */
@Composable
fun HomeScreen(
    continueListening: ContinueListeningCard?,
    onContinue: () -> Unit,
    onBrowse: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { paneTitle = "Home" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (continueListening == null) {
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // One focus stop for the session, as with a Browse result row.
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = continueListening.title,
                style = MaterialTheme.typography.bodyLarge
            )

            if (continueListening.queuePosition != null) {
                Text(
                    text = continueListening.queuePosition,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Text(
                text = continueListening.stateDescription,
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
                Text(if (continueListening.isPlaying) "Pause" else "Play")
            }
        }
    }
}
