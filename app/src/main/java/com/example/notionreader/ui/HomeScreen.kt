package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ContinueListeningCard
import com.example.notionreader.application.ExploreCard
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.application.SavedQueueCard
import com.example.notionreader.ui.describeLastActivity

/**
 * Home: "useful starting points".
 *
 * ## Sections
 *
 * - **Continue listening**: Resumable Active Queue snapshots from history.
 * - **Your queues**: Persistent queues (Saved Queues, capped to recent entries, with "See all queues").
 * - **Explore**: Absent pending Content Metadata & Workspace Schema Adapters specification.
 *
 * ## The empty state, which is not an empty result set
 *
 * With nothing to continue and no queues, Home explains itself and offers Browse rather than
 * rendering as a blank list.
 */
@Composable
fun HomeScreen(
    continueListening: List<ContinueListeningCard>,
    onContinue: (String) -> Unit,
    onBrowse: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    yourQueues: List<SavedQueueCard> = emptyList(),
    onOpenQueue: (String) -> Unit = {},
    onSeeAllQueues: () -> Unit = {},
    exploreCards: List<ExploreCard> = emptyList(),
    onExplore: (String) -> Unit = {},
    /**
     * The instant the relative times are relative to.
     */
    now: Long = System.currentTimeMillis(),
    /**
     * The element the listener activated before leaving this screen, if they came back with Back.
     */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    LaunchedEffect(focusTarget, continueListening, yourQueues) {
        if (focusTarget != null &&
            continueListening.none { it.sessionId == focusTarget } &&
            yourQueues.none { it.queueId == focusTarget } &&
            focusTarget != ReaderFocus.HomeSeeAllQueues
        ) {
            onFocusHandled()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .semantics { paneTitle = "Home" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (continueListening.isEmpty() && yourQueues.isEmpty() && exploreCards.isEmpty()) {
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

        if (continueListening.isNotEmpty()) {
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
                    onPlayPause = onPlayPause,
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled
                )
            }
        }

        if (yourQueues.isNotEmpty()) {
            Text(
                text = "Your queues",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            yourQueues.forEach { queue ->
                HomeQueueEntry(
                    queue = queue,
                    onOpen = { onOpenQueue(queue.queueId) },
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled
                )
            }

            Button(
                onClick = onSeeAllQueues,
                modifier = Modifier.restoreFocusOn(
                    target = focusTarget,
                    id = ReaderFocus.HomeSeeAllQueues,
                    onHandled = onFocusHandled
                )
            ) {
                Text("See all queues")
            }
        }

        if (exploreCards.isNotEmpty()) {
            Text(
                text = "Explore",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            exploreCards.forEach { card ->
                HomeExploreEntry(
                    card = card,
                    onSelect = { onExplore(card.queryText) }
                )
            }
        }
    }
}

/** One explore entry on Home: topic name and note count as a single announcement. */
@Composable
private fun HomeExploreEntry(
    card: ExploreCard,
    onSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "${card.noteCount} notes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One history entry: the session, then Continue, then Play/Pause for the live one. */
@Composable
private fun ContinueListeningEntry(
    session: ContinueListeningCard,
    now: Long,
    onContinue: () -> Unit,
    onPlayPause: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
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
            Button(
                onClick = onContinue,
                modifier = Modifier.restoreFocusOn(
                    target = focusTarget,
                    // A history entry is identified by its session, never by its title — the same id the
                    // navigation layer was given when Continue opened the Player.
                    id = session.sessionId,
                    onHandled = onFocusHandled
                )
            ) {
                Text("Continue")
            }

            TextButton(onClick = onPlayPause) {
                Text(if (session.isPlaying) "Pause" else "Play")
            }
        }
    }
}

/** One queue entry on Home: its name and its membership as a single announcement. */
@Composable
private fun HomeQueueEntry(
    queue: SavedQueueCard,
    onOpen: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .restoreFocusOn(
                target = focusTarget,
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
            text = "Saved Queue · ${queue.memberDescription}",
            style = MaterialTheme.typography.labelMedium
        )
    }
}

