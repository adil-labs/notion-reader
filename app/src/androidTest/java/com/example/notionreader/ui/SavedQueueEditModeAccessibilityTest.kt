package com.example.notionreader.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.application.SavedQueueCard
import com.example.notionreader.domain.queue.QueueMove
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Edit mode: the four moves, `Remove`, `Done`, and Back leaving edit mode rather than the screen.
 *
 * ## What only a device can answer
 *
 * The reordering semantics are the model's and the availability rule is derived and JVM-tested. What this
 * file covers is the shape of the tree a screen reader walks, and each of its assertions is a requirement
 * the governing specification states in words:
 *
 * - "Saved Queue Details in Viewing exposes no move and no Remove control" — the inverse of the first test
 *   here, kept in `SavedQueueDetailsAccessibilityTest` where it belongs;
 * - "in Editing it exposes all four moves and Remove, and `Done`";
 * - "**the moves are disabled rather than inert at the boundaries**";
 * - "**reordering is possible without any gesture** — every move is reachable by an ordinary press";
 * - "a reorder announces the member and its new position, once";
 * - "Back from Edit mode returns to Viewing rather than leaving the screen".
 *
 * ## Why this class needs the Activity
 *
 * One assertion is about Back, and `onBackPressedDispatcher` belongs to the Activity. That is the same trade
 * [ReaderShellQueueBackTest] records, made for the same reason — the alternative is to assert the handler's
 * *existence* rather than its effect, which is the weaker claim.
 *
 * ## What it cannot check
 *
 * That TalkBack *speaks* the announcement. The live region is asserted as a live region, which is the
 * property a test can see; whether speech follows it is the integration spec's listening gate and needs ears.
 */
class SavedQueueEditModeAccessibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val three = listOf(
        QueuedNote("p1", "Android TTS overview"),
        QueuedNote("p2", "AccessibilityService notes"),
        QueuedNote("p3", "TalkBack event handling")
    )

    /**
     * The queue under test.
     *
     * No `report` parameter here, deliberately: the message is the *screen's* argument rather than the
     * card's, and the first version of this fixture had one that was silently ignored — so the test that
     * asserts the announcement asserted against a screen that had never been given it. A parameter that
     * nothing reads is worse than no parameter, because it makes the call site read as though it works.
     */
    private fun queue(members: List<QueuedNote> = three) =
        SavedQueueCard(queueId = "q1", name = "Reading", members = members, lastEditedAt = 1_000L)

    private fun show(
        queue: SavedQueueCard = queue(),
        report: String? = null,
        onMove: (String, QueueMove) -> Unit = { _, _ -> },
        onRemove: (String) -> Unit = { }
    ) {
        compose.setContent {
            SavedQueueDetailsScreen(
                queue = queue,
                onOpenNote = { },
                onPlay = { },
                onMove = onMove,
                onRemove = onRemove,
                report = report
            )
        }
    }

    private fun enterEditMode() {
        compose.onNodeWithText("Edit queue").performSemanticsAction(SemanticsActions.OnClick)
    }

    // ------------------------------------------------------------------ entering and leaving the state

    @Test
    fun editingExposesAllFourMovesAndRemoveAndDone() {
        // The specification's requirement for this state, verbatim. One control per member per move, so three
        // members means three of each — asserted as a count rather than as existence, because a screen that
        // rendered the moves for only the first member would satisfy existence.
        show()
        enterEditMode()

        for (move in listOf("Move up", "Move down", "Move to top", "Move to bottom")) {
            compose.onAllNodesWithText(move).assertCountEquals(3)
        }
        compose.onAllNodesWithText("Remove").assertCountEquals(3)

        // One `Done`, not one per member: it is the state's own control rather than a member's.
        compose.onAllNodesWithText("Done").assertCountEquals(1)
        compose.onAllNodesWithText("Edit queue").assertCountEquals(0)
    }

    @Test
    fun doneReturnsToViewingAndTakesTheEditControlsWithIt() {
        show()
        enterEditMode()

        compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick)

        compose.onAllNodesWithText("Move up").assertCountEquals(0)
        compose.onAllNodesWithText("Remove").assertCountEquals(0)
        compose.onNodeWithText("Play").assertIsReachable()
        compose.onNodeWithText("Edit queue").assertIsReachable()
    }

    @Test
    fun backLeavesEditModeRatherThanTheScreen() {
        // The one place where Back does something other than reverse navigation, and it is the UI contract's
        // own rule for this screen. Without the screen's handler, Back would leave with the edits applied —
        // making the platform's most reflexive gesture commit changes.
        show()
        enterEditMode()
        // `onNodeWithText` already fails unless there is exactly one match, so this is the uniqueness
        // assertion as well as the existence one — `assertCountEquals` takes a collection, not a single node.
        compose.onNodeWithText("Done").assertExists()

        pressBack()

        // Still on the screen, and back in Viewing.
        compose.onNodeWithText("Edit queue").assertIsReachable()
        compose.onAllNodesWithText("Move up").assertCountEquals(0)
    }

    @Test
    fun backInViewingIsNotConsumedByThisScreen() {
        // The handler is enabled only in Editing, so in Viewing Back belongs to the shell. Asserted by
        // consequence: pressing it leaves the screen, which is what the shell's handler does — so a screen
        // that always consumed Back would fail here.
        show()

        pressBack()

        compose.onAllNodesWithText("Edit queue").assertCountEquals(0)
    }

    // ---------------------------------------------------------------------------- availability at edges

    @Test
    fun theMovesAreUnavailableAtTheBoundariesRatherThanInert() {
        // "A disabled move is disabled, not inert." The first member cannot move up or to the top; the last
        // cannot move down or to the bottom. Asserted as the *pair* — available for the middle member — so a
        // screen that disabled everything could not pass.
        show()
        enterEditMode()

        val moveUp = compose.onAllNodesWithText("Move up")
        moveUp[0].assertIsNotEnabled()
        moveUp[1].assertIsReachable()

        val moveDown = compose.onAllNodesWithText("Move down")
        moveDown[2].assertIsNotEnabled()
        moveDown[1].assertIsReachable()

        compose.onAllNodesWithText("Move to top")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("Move to bottom")[2].assertIsNotEnabled()
    }

    @Test
    fun everyMoveIsUnavailableInAQueueOfOne() {
        // The degenerate case: with one member there is nowhere to move it, so all four are unavailable.
        show(queue = queue(members = listOf(QueuedNote("p1", "Only note"))))
        enterEditMode()

        for (move in listOf("Move up", "Move down", "Move to top", "Move to bottom")) {
            compose.onNodeWithText(move).assertIsNotEnabled()
        }

        // `Remove` stays available: removing the only member leaves a valid empty queue, which is not a
        // delete. Disabling it would make an empty queue impossible to reach except by deleting the queue.
        compose.onNodeWithText("Remove").assertIsReachable()
    }

    // ------------------------------------------------------------------------------ moves are not gestures

    @Test
    fun everyMoveIsReachableByAnOrdinaryPress() {
        // "Reordering must never require drag-and-drop." Every move is an ordinary control, so each is
        // reachable and each carries an activation — which is the property a gesture would not have.
        show()
        enterEditMode()

        for (move in listOf("Move up", "Move down", "Move to top", "Move to bottom")) {
            compose.onAllNodesWithText(move)[1].assertIsReachable()
        }
        compose.onAllNodesWithText("Remove")[1].assertIsReachable()
    }

    @Test
    fun aMoveReportsWhichMemberAndWhichMoveWereChosen() {
        // The pair a surface cannot infer: the move belongs to the member whose row it is, and the identity
        // travels with it rather than being looked up from a position that the move itself invalidates.
        val moves = mutableListOf<Pair<String, QueueMove>>()
        show(onMove = { pageId, move -> moves += pageId to move })
        enterEditMode()

        compose.onAllNodesWithText("Move to top")[2].performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("p3" to QueueMove.Top), moves)
    }

    @Test
    fun removeReportsWhichMemberWasChosen() {
        val removed = mutableListOf<String>()
        show(onRemove = { removed += it })
        enterEditMode()

        compose.onAllNodesWithText("Remove")[1].performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("p2"), removed)
    }

    // ------------------------------------------------------------------------------------ the announcement

    @Test
    fun aReordersResultIsAnnouncedOnceAndInTheSamePlaceAsAnAdds() {
        // "A reorder announces the member and its new position, brief and once." The message is the same
        // channel the Add's report uses, so it is one thing to find on the screen rather than two.
        //
        // `report` is passed to the *screen*, which is where the message lives — see `queue()` for why the
        // fixture deliberately does not carry one.
        show(report = "Moved TalkBack event handling to position 1 of 3.")

        compose.onNodeWithText("Moved TalkBack event handling to position 1 of 3.")
            .assert(A11y.hasLiveRegion())
        compose.onNodeWithText("Moved TalkBack event handling to position 1 of 3.").assertIsReadable()
    }

    @Test
    fun withNoMessageTheScreenSaysNothingAboutMoving() {
        show()

        compose.onAllNodesWithText("Moved", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Removed", substring = true).assertCountEquals(0)
    }

    /** Dispatches a real Back press, as the system would. */
    private fun pressBack() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }
}
