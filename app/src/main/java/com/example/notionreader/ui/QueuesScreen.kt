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

/**
 * Queues: the tab host for `Listening` and `Saved`.
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
 * ## Two tabs, and why not three
 *
 * The UI contract's Queues screen is `Listening | Saved | Smart`. **Smart is not here**, and that is the
 * scoped deferral `docs/decisions.md` §18 records rather than a redefinition: Smart Queues are query-defined
 * persistent queues owned by the Content Metadata & Workspace Schema Adapters spec, which does not exist.
 * Shipping the tab would put a permanently unusable control one tap from two populated surfaces, which is
 * the objection §12.5 already resolved for a whole destination.
 *
 * The consequence is visible and worth stating plainly: TalkBack announces "1 of 2" where the contract's
 * example says "1 of 3". The ordinal counts the tabs that exist, and the third arrives with the tab it
 * counts — the same relationship the single-tab version documented when it announced "1 of 1".
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
    Saved("Saved")
}

/**
 * The Queues screen: a tab row, and whichever tab's body.
 *
 * The `Listening` parameters and the `Saved` ones are both here rather than in two screen-level composables
 * because the tab row has to be able to switch between already-supplied bodies synchronously — a screen that
 * fetched its own tab's data would turn tab selection into a load.
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
     * describe itself differently on two recompositions, and would put a clock in a display-only composable.
     */
    now: Long = System.currentTimeMillis(),
    /** The entry or queue the listener activated before leaving this screen, if Back brought them back. */
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    /** The saved queues, most recently edited first. Ordering is the application layer's; see `toCards`. */
    savedQueues: List<SavedQueueCard> = emptyList(),
    onOpenSavedQueue: (String) -> Unit = {},
    onCreateSavedQueue: () -> Unit = {}
) {
    // Defaulted rather than hoisted into the application layer: which tab is showing is a fact about this
    // screen, and the contract asks for it to be *retained* rather than to be controllable from elsewhere.
    // Nothing outside Queues can observe it, and nothing needs to move it.
    var selectedName by rememberSaveable { mutableStateOf(QueuesTab.Listening.name) }
    val selected = QueuesTab.entries.firstOrNull { it.name == selectedName } ?: QueuesTab.Listening

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Queues" }
    ) {
        // `selectableGroup` is what makes the tabs read as one set of mutually exclusive choices rather than
        // as unrelated buttons.
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
        }
    }
}
