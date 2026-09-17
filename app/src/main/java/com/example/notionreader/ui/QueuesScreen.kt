package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.HistoryEntry

/**
 * Queues: the Listening tab.
 *
 * ## What ships, and what is deferred
 *
 * The UI contract's Queues screen is three top tabs, `Listening | Saved | Smart`. **Listening ships and
 * the other two do not**, which is the scoped deferral recorded in `docs/decisions.md` §18 and in
 * [com.example.notionreader.application.ReaderDestination]. Saved and Smart are persistent queue storage
 * and the specifications that would define them do not exist; the integration spec explicitly delegates
 * Saved Queue and Smart Queue persistence away from itself. Shipping them would put two permanently
 * unusable controls one tap from a populated surface, which is the objection §12.5 already resolved for a
 * whole destination — the empty screen is the same wrong whichever it is nested in.
 *
 * The tab row is here with one tab rather than not at all, because the contract requires tab semantics
 * and because the structure that will hold three tabs is then already in place. TalkBack therefore
 * announces "Listening, tab, selected, 1 of 1", which is true; the contract's "1 of 3" arrives with the
 * two tabs it counts.
 *
 * ## Two sections, and an omitted heading is not an empty one
 *
 * The contract's own rule is that "if one of the two sections contains no items, omit the empty section
 * heading". So a listener with history but nothing finished sees one section, not one section and a
 * heading with nothing under it.
 *
 * With **neither** list populated the screen has no sections at all, which is the empty-screen problem
 * again; it is kept rather than hidden because Queues is a bottom-navigation destination and its absence
 * would be a worse surprise than its emptiness. So the empty state explains itself and offers the way
 * out, exactly as Home's does.
 *
 * ## The two controls are different verbs, not two labels for one
 *
 * An unfinished entry offers **Continue**, which restores that exact session — its own queue and its own
 * saved position. A completed entry offers **Play**, which starts a *new* session over the same Notes
 * from the beginning. The Active Queue & Reader Session spec is explicit that these are different: a
 * completed session is "history, not resumable state", and reusing its identity would overwrite the
 * record the listener is looking at.
 *
 * ## Accessibility
 *
 * One history entry is one focus stop — title, membership and state merge into a single announcement —
 * with the control beside it as a separate named button, because the contract requires that Continue not
 * be read as part of the sentence describing the session. Times are relative and never raw, the two
 * section headings are headings so the screen can be skimmed structurally, and the screen names itself
 * with a pane title so entering it says where the listener is.
 */
@Composable
fun QueuesScreen(
    continueListening: List<HistoryEntry>,
    recentlyCompleted: List<HistoryEntry>,
    onContinue: (String) -> Unit,
    onReplay: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The instant the relative times are relative to.
     *
     * A parameter for the same reason Home's is: reading a clock during composition would make one entry
     * describe itself differently on two recompositions, and would put a clock in a display-only
     * composable.
     */
    now: Long = System.currentTimeMillis()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Queues" }
    ) {
        // `selectableGroup` is what makes the tabs read as one set of mutually exclusive choices rather
        // than as unrelated buttons.
        PrimaryTabRow(selectedTabIndex = 0, modifier = Modifier.selectableGroup()) {
            Tab(
                selected = true,
                onClick = { },
                text = { Text("Listening") }
            )
        }

        Column(
            modifier = Modifier
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
                        onActivate = { onContinue(entry.sessionId) }
                    )
                }
            }

            if (recentlyCompleted.isNotEmpty()) {
                SectionHeading("Recently completed")
                recentlyCompleted.forEach { entry ->
                    HistoryRow(
                        entry = entry,
                        now = now,
                        onActivate = { onReplay(entry.sessionId) }
                    )
                }
            }
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
    onActivate: () -> Unit
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
        Button(onClick = onActivate) {
            Text(if (entry.isCompleted) "Play" else "Continue")
        }
    }
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
