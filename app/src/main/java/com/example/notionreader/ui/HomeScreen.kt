package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
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
 * - **Explore**: A bounded preview with a complete Area-and-Topic catalog.
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
    onSeeAllExplore: () -> Unit = {},
    /**
     * The instant the relative times are relative to.
     */
    now: Long = System.currentTimeMillis(),
    /**
     * The element the listener activated before leaving this screen, if they came back with Back.
     */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    embedded: Boolean = false,
    showContinueListening: Boolean = true,
    showShortcuts: Boolean = true
) {
    LaunchedEffect(focusTarget, continueListening, yourQueues) {
        if (!embedded && focusTarget != null &&
            continueListening.none { it.sessionId == focusTarget } &&
            yourQueues.none { it.queueId == focusTarget } &&
            focusTarget != ReaderFocus.HomeSeeAllQueues
            && focusTarget != ReaderFocus.HomeSeeAllExplore
        ) {
            onFocusHandled()
        }
    }

    Column(
        modifier = modifier
            .then(if (embedded) Modifier else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            .then(if (embedded) Modifier.padding(vertical = 8.dp) else Modifier.padding(24.dp))
            .then(if (embedded) Modifier else Modifier.semantics { paneTitle = "Home" }),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showShortcuts && continueListening.isEmpty() && yourQueues.isEmpty() && exploreCards.isEmpty()) {
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

        if (showContinueListening && continueListening.isNotEmpty()) {
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
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    compact = embedded && !showShortcuts
                )
            }
        }

        if (showShortcuts && yourQueues.isNotEmpty()) {
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

        if (showShortcuts && exploreCards.isNotEmpty()) {
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

            Button(
                onClick = onSeeAllExplore,
                modifier = Modifier.restoreFocusOn(
                    target = focusTarget,
                    id = ReaderFocus.HomeSeeAllExplore,
                    onHandled = onFocusHandled
                )
            ) { Text("See all Explore") }
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

/**
 * One unfinished listening session on Home: title, queue position, state and relative recency as
 * a single accessible focus stop.
 */
@Composable
internal fun ContinueListeningEntry(
    session: ContinueListeningCard,
    now: Long,
    onContinue: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit,
    compact: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (compact) Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)) else Modifier)
            .restoreFocusOn(
                target = focusTarget,
                // A history entry is identified by its session, never by its title — the same id the
                // navigation layer was given when Continue opened the Player.
                id = session.sessionId,
                onHandled = onFocusHandled
            )
            .clickable(
                onClick = onContinue,
                onClickLabel = "Continue listening"
            )
            // One focus stop for the session, as with a Browse result row.
            .semantics(mergeDescendants = true) {}
            .then(if (compact) Modifier.padding(16.dp) else Modifier.padding(vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = session.title,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            maxLines = if (compact) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis
        )

        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(session.queuePosition, session.stateDescription).joinToString(" · "),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("Continue", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        } else {
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

