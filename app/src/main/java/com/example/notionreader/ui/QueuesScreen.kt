package com.example.notionreader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.example.notionreader.application.HistoryEntry
import com.example.notionreader.application.SavedQueueCard
import com.example.notionreader.application.SmartQueueCard

/**
 * Queues: the tab host for `Listening`, `Saved`, and `Smart`.
 *
 * ## Why this is now only a host
 *
 * It used to be the Listening tab and the tab row in one composable, which was correct while there was one
 * tab. With two, the facts it holds are different in kind — which tab is selected, and what a tab contains
 * — and they fail differently: a tab body rendering the wrong empty state is a defect in the body, while a
 * tab row announcing the wrong ordinal is a defect in the host. Splitting them is what lets Listening's own
 * tests go on asserting Listening without a Saved tab in the tree, and it is the shape that makes a third
 * tab an addition rather than a rewrite.
 *
 * ## Three distinct queue tabs
 *
 * The UI contract's Queues screen provides `Listening | Saved | Smart`:
 * - `Listening`: frozen Active Queue playback history (unfinished and completed sessions).
 * - `Saved`: manually curated playlists of Notes.
 * - `Smart`: query-defined dynamic playlists evaluated at playback time.
 *
 * Splitting them allows each tab to manage its own distinct read model, empty states, and lifecycle
 * without cross-tab interference. TalkBack announces each tab's ordinal cleanly ("1 of 3", etc.).
 *
 * ## The selected tab is retained, because the contract requires it
 *
 * "Queues should retain: selected top tab." `rememberSaveable` is what does it, and it covers both halves of
 * the contract's restoration sentence: the shell's `SaveableStateHolder` keeps this tab's state while another
 * destination is on screen, and the saved-instance-state bundle restores it after a configuration change or a
 * process restart.
 *
 * The enum's *name* is what is saved rather than its ordinal. An ordinal is what the tab row needs and is
 * derived below, but it is also what changes when a tab is added in the middle — so storing it would make a
 * future third tab silently select a different one on restore.
 */

private enum class QueuesTab(val label: String) {
    Listening("Listening"),
    Saved("Saved"),
    Smart("Smart")
}

/**
 * The Queues screen: a tab row, and whichever tab's body.
 *
 * The `Listening`, `Saved`, and `Smart` parameters are all here rather than in separate screen-level composables
 * because the tab row has to be able to switch between already-supplied bodies synchronously.
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
     */
    now: Long = System.currentTimeMillis(),
    /** The entry or queue the listener activated before leaving this screen, if Back brought them back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    /** The saved queues, most recently edited first. Ordering is the application layer's; see `toCards`. */
    savedQueues: List<SavedQueueCard> = emptyList(),
    onOpenSavedQueue: (String) -> Unit = {},
    onCreateSavedQueue: () -> Unit = {},
    /** The smart queues. */
    smartQueues: List<SmartQueueCard> = emptyList(),
    onOpenSmartQueue: (String) -> Unit = {},
    onCreateSmartQueue: () -> Unit = {}
) {
    var selectedName by rememberSaveable { mutableStateOf(QueuesTab.Listening.name) }
    val selected = QueuesTab.entries.firstOrNull { it.name == selectedName } ?: QueuesTab.Listening

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Queues" }
    ) {
        PrimaryTabRow(
            selectedTabIndex = selected.ordinal,
            modifier = Modifier.selectableGroup()
        ) {
            QueuesTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selected,
                    onClick = { selectedName = tab.name },
                    text = { Text(tab.label) }
                )
            }
        }

        when (selected) {
            QueuesTab.Listening -> ListeningTab(
                continueListening = continueListening,
                recentlyCompleted = recentlyCompleted,
                onContinue = onContinue,
                onReplay = onReplay,
                now = now,
                focusTarget = focusTarget,
                onFocusHandled = onFocusHandled
            )

            QueuesTab.Saved -> SavedTab(
                queues = savedQueues,
                onOpenQueue = onOpenSavedQueue,
                onCreate = onCreateSavedQueue,
                focusTarget = focusTarget,
                onFocusHandled = onFocusHandled
            )

            QueuesTab.Smart -> SmartTab(
                queues = smartQueues,
                onOpenQueue = onOpenSmartQueue,
                onCreate = onCreateSmartQueue,
                focusTarget = focusTarget,
                onFocusHandled = onFocusHandled
            )
        }
    }
}
