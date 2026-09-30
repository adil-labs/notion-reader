package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Saved Queue Details in the **Viewing** state.
 *
 * The governing specification's instrumented requirement for this screen is stated negatively, and it is the
 * assertion this file exists for: **"Saved Queue Details in Viewing exposes no move and no Remove control."**
 * Negatively, because the failure it guards against is not a missing control but a `Remove` a listener
 * reaches by accident — the UI contract's reason is that "the normal Saved Queue Details screen should not
 * expose multiple destructive/reorder controls on every Note", which is an accessibility requirement as much
 * as a visual one: a screen where every row carries five controls is one a screen-reader user cannot
 * traverse, and it puts a destructive action one focus stop from an ordinary one.
 *
 * The editing state is a later slice, and that is why the assertion is about *absence* rather than about
 * disabled controls: the difference between "Viewing does not show them" and "Viewing shows them disabled"
 * is the difference between a screen that is traversable and one that is not, and only the first satisfies
 * the contract. When Editing arrives, these assertions become the pair they are the first half of.
 *
 * What a device alone can answer here is also whether `Play` is *reachable* rather than merely present, and
 * whether a disabled `Play` explains itself — a disabled control with no explanation is one a listener will
 * keep pressing.
 */
class SavedQueueDetailsAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val members = listOf(
        QueuedNote("p1", "Android TTS overview"),
        QueuedNote("p2", "AccessibilityService notes"),
        QueuedNote("p3", "TalkBack event handling")
    )

    private fun queue(
        id: String = "q1",
        name: String = "Android accessibility",
        members: List<QueuedNote> = this.members
    ) = SavedQueueCard(queueId = id, name = name, members = members, lastEditedAt = 1_000L)

    private fun show(
        queue: SavedQueueCard? = queue(),
        onOpenNote: (String) -> Unit = { },
        onPlay: (String) -> Unit = { },
        onMove: (String, QueueMove) -> Unit = { _, _ -> },
        onRemove: (String) -> Unit = { },
        onRename: (String, String) -> Unit = { _, _ -> },
        onDelete: (String) -> Unit = { }
    ) {
        compose.setContent {
            SavedQueueDetailsScreen(
                queue = queue,
                onOpenNote = onOpenNote,
                onPlay = onPlay,
                onMove = onMove,
                onRemove = onRemove,
                onRename = onRename,
                onDelete = onDelete
            )
        }
    }

    /** Enters Edit mode, which is the screen's other state and the only way to reach the edit controls. */
    private fun enterEditMode() {
        compose.onNodeWithText("Edit queue").performSemanticsAction(SemanticsActions.OnClick)
    }

    // ------------------------------------------------------------ Viewing exposes no editing controls

    @Test
    fun viewingExposesNoMoveControlAtAll() {
        // Absent rather than disabled: a screen that renders four disabled moves per member is one a
        // screen-reader listener has to traverse past, which is the contract's objection verbatim.
        show()

        for (move in listOf("Move up", "Move down", "Move to top", "Move to bottom")) {
            compose.onAllNodesWithText(move).assertCountEquals(0)
        }
    }

    @Test
    fun viewingExposesNoRemoveControlAtAllAndNoDone() {
        // `Remove` is the destructive one, and this is the assertion that it is not one focus stop from an
        // ordinary control. `Done` belongs to the other state, so it is absent here too — and so are the
        // moves, asserted above.
        show()

        compose.onAllNodesWithText("Remove").assertCountEquals(0)
        compose.onAllNodesWithText("Done").assertCountEquals(0)
    }

    @Test
    fun viewingOffersTheWayIntoEditMode() {
        // The counterpart to the assertions above: the edit controls are absent from Viewing, and the one
        // control that *is* here is the way to them. Without this, "Viewing exposes no move" would be
        // satisfied by a screen that could never reach them at all.
        show()

        compose.onNodeWithText("Edit queue").assertIsReachable()
    }

    // ------------------------------------------------------------------------------ what Viewing offers

    @Test
    fun theQueueNamesItselfAndStatesHowManyNotesItHolds() {
        // "understanding the queue" — the contract's first purpose for this screen.
        show()

        compose.onNodeWithText("Android accessibility").assert(A11y.hasHeading())
        compose.onNodeWithText("3 notes").assertIsReadable()
    }

    @Test
    fun playIsReachableOnAPopulatedQueue() {
        val played = mutableListOf<String>()
        show(queue = queue(id = "q7"), onPlay = { played += it })

        compose.onNodeWithText("Play").assertIsReachable()
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("q7"), played)
    }

    @Test
    fun eachMemberIsOneFocusStopCarryingItsPositionAndItsTitle() {
        // One focus stop per member, as the specification requires, and the position is part of the same
        // announcement so a listener hears "3. TalkBack event handling" rather than two things in an order
        // they cannot predict.
        show()

        compose.onNodeWithText("3. TalkBack event handling").assertIsReadable()
        compose.onNodeWithText("1. Android TTS overview").assertIsReadable()
    }

    @Test
    fun activatingAMemberOpensItsNoteAndDoesNotPlayIt() {
        // "Activating an individual queue item opens that Note. It does not start playback." Two different
        // operations, so two different callbacks — and this one takes the *page* id, not the position.
        val opened = mutableListOf<String>()
        val played = mutableListOf<String>()
        show(onOpenNote = { opened += it }, onPlay = { played += it })

        compose.onNodeWithText("2. AccessibilityService notes").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("p2"), opened)
        assertEquals("opening a Note must not play it", emptyList<String>(), played)
    }

    @Test
    fun everyMemberAndControlIsReachableAndNoControlIsAnUnlabelledButton() {
        show()

        compose.onNodeWithText("1. Android TTS overview").assertIsReachable()
        compose.onNodeWithText("Play").assertIsReachable()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    // --------------------------------------------------------------------------------- the empty queue

    @Test
    fun anEmptyQueueShowsPlayUnavailableAndExplainsWhy() {
        // "Play on an empty queue is not offered" — the control is *disabled rather than inert*, "because a
        // Play that can do nothing is a control a listener cannot use, and 'this queue is empty' is the
        // information they actually need". So the assertion is the pair: unavailable, and accompanied by the
        // reason. A disabled control with no explanation is one the listener keeps pressing.
        show(queue = queue(name = "Reading", members = emptyList()))

        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.onNodeWithText("This queue is empty, so there is nothing to play yet.", substring = true)
            .assertIsReadable()
        compose.onNodeWithText("0 notes").assertIsReadable()
    }

    @Test
    fun aPopulatedQueueDoesNotExplainAPlayThatIsAvailable() {
        // The explanation is about a rule in effect, so it is absent when the rule is not — a sentence
        // explaining something that is not true is worse than no sentence.
        show()

        compose.onAllNodesWithText("This queue is empty", substring = true).assertCountEquals(0)
    }

    @Test
    fun anEmptyQueueIsNotShownAsAMissingOne() {
        // Two different states that must not be conflated: a queue the listener deliberately created empty,
        // and a queue that is gone. Telling them the first when it is the second would be a plain lie about
        // their data.
        //
        // The name is passed explicitly rather than left to the helper's default, which is the bug the first
        // version of this test had: it asserted "Reading" existed while the fixture was called something else,
        // so it failed against a screen that was behaving perfectly.
        show(queue = queue(name = "Reading", members = emptyList()))

        compose.onAllNodesWithText("no longer here", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Reading").assertExists()
    }

    // -------------------------------------------------------------------------------- a queue that is gone

    @Test
    fun aQueueThatIsNoLongerThereSaysSoAndOffersNothingToFix() {
        // Reachable: the list it was opened from is not frozen, and a store that could not be read reports no
        // queues. The specification's rule for a session whose source queue was deleted is that "it is not an
        // error state, and nothing is offered to 'fix' it", and the same applies to looking at one.
        show(queue = null)

        compose.onNodeWithText("This saved queue is no longer here.").assert(A11y.hasHeading())
        compose.onNodeWithText("This saved queue is no longer here.").assertIsReadable()
        // Nothing to press: no Play, no retry, no repair.
        compose.onAllNodesWithText("Play").assertCountEquals(0)
    }

    // ------------------------------------------------------------------------- Rename and Delete in Viewing

    @Test
    fun viewingExposesRenameAndDeleteControls() {
        show()

        compose.onNodeWithText("Rename").assertIsReachable()
        compose.onNodeWithText("Delete").assertIsReachable()
    }

    @Test
    fun activatingRenameOpensRenameDialog() {
        show()

        compose.onNodeWithText("Rename").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("Rename queue").assert(A11y.hasHeading())
        compose.onNodeWithText("Cancel").assertIsReachable()
        compose.onNodeWithText("Save").assertIsReachable()
    }

    @Test
    fun activatingDeleteOpensConfirmationDialog() {
        show(queue = queue(name = "Android accessibility"))

        compose.onNodeWithText("Delete").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("Delete queue").assert(A11y.hasHeading())
        compose.onNodeWithText("Delete \"Android accessibility\"?", substring = true)
            .assertIsReadable()
        compose.onNodeWithText("Cancel").assertIsReachable()
        // There are now two Delete controls: the screen button and the dialog confirmation button.
        compose.onAllNodesWithText("Delete").assertCountEquals(2)
        compose.onAllNodesWithText("Delete")[1].assertIsReachable()
    }
}

