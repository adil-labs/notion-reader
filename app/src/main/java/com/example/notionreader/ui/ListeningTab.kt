package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.HistoryEntry

/**
 * The Listening tab's body: recent sessions, in two sections.
 *
 * ## Two sections, and an omitted heading is not an empty one
 *
 * The contract's own rule is that "if one of the two sections contains no items, omit the empty section
 * heading". So a listener with history but nothing finished sees one section, not one section and a
 * heading with nothing under it.
 *
 * With **neither** list populated the tab has no sections at all, which is the empty-screen problem again;
 * it is kept rather than hidden because Queues is a bottom-navigation destination and its absence would be
 * a worse surprise than its emptiness. So the empty state explains itself and offers the way out, exactly
 * as Home's does.
 *
 * ## The two controls are different verbs, not two labels for one
 *
 * An unfinished entry offers **Continue**, which restores that exact session — its own queue and its own
 * saved position. A completed entry offers **Play**, which starts a *new* session over the same Notes
 * from the beginning. The Active Queue & Reader Session spec is explicit that these are different: a
 * completed session is "history, not resumable state", and reusing its identity would overwrite the
 * record the listener is looking at.
 *
 * ## Why this is a separate composable from the tab host
 *
 * Because the two tabs are separate surfaces that happen to share a screen. `QueuesScreen` owns the tab row
 * and which tab is selected; each tab owns its own content, its own empty state and its own focus hints.
 * Before Saved existed they were the same composable, and the first thing that would have gone wrong is
 * that an empty-history state would have rendered *inside* the Saved tab.
 */
@Composable
fun ListeningTab(
    continueListening: List<HistoryEntry>,
    recentlyCompleted: List<HistoryEntry>,
    onContinue: (String) -> Unit,
    onReplay: (String) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    /** The history entry the listener activated before leaving this screen, if Back brought them back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    // A hint for a session neither list shows any more is consumed rather than left pending.
    LaunchedEffect(focusTarget, continueListening, recentlyCompleted) {
        val owned = (continueListening + recentlyCompleted).any { it.sessionId == focusTarget }
        if (focusTarget != null && !owned) onFocusHandled()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (continueListening.isEmpty() && recentlyCompleted.isEmpty()) {
            EmptyHistory()
            return@Column
        }

        if (continueListening.isNotEmpty()) {
            SectionHeading("Continue listening")
            continueListening.forEach { entry ->
                HistoryRow(
                    entry = entry,
                    now = now,
                    // One lambda for both sections, because "activate this entry" is the same
                    // intention either way and the verb is derived from the entry rather than chosen
                    // by the caller.
                    onActivate = { onContinue(entry.sessionId) },
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled
                )
            }
        }

        if (recentlyCompleted.isNotEmpty()) {
            SectionHeading("Recently completed")
            recentlyCompleted.forEach { entry ->
                HistoryRow(
                    entry = entry,
                    now = now,
                    onActivate = { onReplay(entry.sessionId) },
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled
                )
            }
        }
    }
}

/**
 * The state with nothing in it.
 *
 * It explains rather than sitting blank, and it offers the route out rather than leaving the listener on
 * a destination with no exits — the same reasoning as Home's, which is the precedent this follows.
 */
@Composable
private fun EmptyHistory() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Nothing here yet.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = "Play a Note, or Play all from Browse, and your listening sessions will be listed " +
                "here — the ones you have not finished first.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** One history entry: the session as one announcement, then the control that acts on it. */
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    now: Long,
    onActivate: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // One focus stop, as with a Browse result row and for the same reason: the title, the
                // position and the state are one thing to a listener, and four swipes through them is
                // three more than the information deserves.
                .semantics(mergeDescendants = true) {}
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge
            )

            Text(
                text = describeHistoryEntry(entry, now),
                style = MaterialTheme.typography.labelMedium
            )
        }

        // Outside the merged column on purpose: the contract requires Continue to be an explicit named
        // control rather than part of the entry's announcement, so that a listener hears the session and
        // then chooses what to do with it.
        Button(
            onClick = onActivate,
            modifier = Modifier.restoreFocusOn(
                target = focusTarget,
                // The session id, which is what this control was given when it opened the Player — so the
                // entry the listener left is the entry focus returns to.
                id = entry.sessionId,
                onHandled = onFocusHandled
            )
        ) {
            Text(if (entry.isCompleted) "Play" else "Continue")
        }
    }
}

/** A section heading. A heading rather than a bold line, so the screen can be navigated by structure. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
}

/**
 * The line under a history entry's title.
 *
 * Two shapes, matching the two the UI contract gives as examples: `Paused · Note 3 of 8 · 2 hours ago`
 * for something unfinished, and `8 notes · Completed yesterday` for something finished. The difference is
 * not cosmetic — an unfinished entry's state is what Continue is about to change, and a completed entry
 * has no state to report, only what it contained and when it ended.
 *
 * Both are built from facts the entry carries, so the same session cannot describe itself two ways.
 */
private fun describeHistoryEntry(entry: HistoryEntry, now: Long): String {
    val relative = describeLastActivity(now - entry.lastActivityAt)

    return if (entry.isCompleted) {
        // "Completed yesterday", not "Completed Yesterday": the relative time continues this line rather
        // than starting a sentence of its own.
        listOfNotNull(entry.membership, "Completed ${relative.asSentenceContinuation()}")
            .joinToString(" · ")
    } else {
        listOfNotNull(entry.stateDescription, entry.membership, relative).joinToString(" · ")
    }
}

/** "Yesterday" becomes "yesterday", for a relative time that follows a word rather than opening a line. */
private fun String.asSentenceContinuation(): String =
    replaceFirstChar { character -> character.lowercase() }
