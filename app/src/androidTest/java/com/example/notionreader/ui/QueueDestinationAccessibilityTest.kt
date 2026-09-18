package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.application.SavedQueueCard
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Queue Destination's semantics, and the three things the contract forbids it to show.
 *
 * The UI contract lists what this screen **does not** show, and each item is a real temptation rather than a
 * hypothetical one:
 *
 * - Smart Queues, which "are not valid manual-add destinations" because their membership is query-defined;
 * - the Active Queue, which "is playback state. It is not a general persistent destination for organizing
 *   content from Browse" — it is the thing that most looks like a destination and must not be one;
 * - "ambiguous Current Queue", the contract's named non-goal, and a chooser is precisely where such a
 *   concept takes hold.
 *
 * So the awkward, valuable assertions here are the negative ones: a screen that listed a Smart queue or
 * pre-selected something would still pass every positive assertion in this file.
 */
class QueueDestinationAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun queue(id: String = "q1", name: String = "Reading", memberCount: Int = 0) = SavedQueueCard(
        queueId = id,
        name = name,
        members = List(memberCount) { QueuedNote("page-$it", "Note $it") },
        lastEditedAt = 1_000L
    )

    private fun show(
        queues: List<SavedQueueCard> = listOf(queue()),
        pendingCount: Int = 3,
        report: String? = null,
        onCreateNewQueue: () -> Unit = { },
        onChooseQueue: (String) -> Unit = { }
    ) {
        compose.setContent {
            QueueDestinationScreen(
                queues = queues,
                pendingCount = pendingCount,
                onCreateNewQueue = onCreateNewQueue,
                onChooseQueue = onChooseQueue,
                report = report
            )
        }
    }

    // ------------------------------------------------------------------ what is being placed is stated

    @Test
    fun theScreenSaysHowManyNotesAreWaitingToBePlaced() {
        // `Add to queue` from a Note and `Add all to queue` from Browse reach the same screen with
        // different-sized sets, so without this a listener cannot tell which intent they are completing.
        show(pendingCount = 12)

        compose.onNodeWithText("12 notes to add.").assertIsReadable()
    }

    @Test
    fun oneNoteIsStatedInTheSingular() {
        show(pendingCount = 1)

        compose.onNodeWithText("1 note to add.").assertIsReadable()
        compose.onAllNodesWithText("1 notes", substring = true).assertCountEquals(0)
    }

    // ------------------------------------------------------------------------------- the destinations

    @Test
    fun eachQueueIsOneFocusStopThatIsAlsoItsOwnControl() {
        // One focus stop per destination, like a Saved-tab row and for the same reason: the name and the size
        // are one thing to a listener, and choosing it is the same gesture as reading it.
        show(queues = listOf(queue(name = "Reading", memberCount = 11)))

        compose.onNode(
            mergedTextContaining("Reading", "11 notes")
        ).assertIsReachable()
    }

    @Test
    fun choosingADestinationReportsWhichQueueWasChosen() {
        val chosen = mutableListOf<String>()
        show(queues = listOf(queue("q7", "Chess"), queue("q9", "Reading")), onChooseQueue = { chosen += it })

        compose.onNodeWithText("Reading", substring = true).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("q9"), chosen)
    }

    @Test
    fun createNewQueueIsOfferedAlongsideExistingQueues() {
        // The contract's rule is that it is the route out of an empty chooser, and a listener who already has
        // queues may still want a new one. Two tests rather than two `show()` calls in one, because
        // `setContent` may only be called once per test — the bug the Saved tab's tests had.
        show(queues = listOf(queue()))

        compose.onNodeWithText("Create new queue").assertIsReachable()
    }

    @Test
    fun createNewQueueIsOfferedWhenThereAreNoQueuesAtAll() {
        show(queues = emptyList())

        compose.onNodeWithText("Create new queue").assertIsReachable()
    }

    @Test
    fun withNoQueuesTheScreenExplainsThatCreatingOneWillReceiveTheNotes() {
        // The create-if-empty path is a *behaviour* — the new queue receives the Notes — and the sentence is
        // what makes it predictable rather than a surprise.
        show(queues = emptyList())

        compose.onNodeWithText("Create new queue").assertIsReachable()
        compose.onAllNodesWithText("Choose a queue").assertCountEquals(0)
    }

    @Test
    fun everyDestinationAndControlIsReadableAndNoControlIsAnUnlabelledButton() {
        show(queues = listOf(queue("q1", "Reading"), queue("q2", "Chess")))

        compose.onNodeWithText("Reading", substring = true).assertIsReadable()
        compose.onNodeWithText("Chess", substring = true).assertIsReadable()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    // ------------------------------------------------------------------- what the contract forbids

    @Test
    fun noSmartQueueIsOffered() {
        // "Do not show: Smart Queues", because their membership is query-defined. There is no Smart tab and
        // no Smart queue type to list yet, and this asserts the screen does not invent one.
        show()

        compose.onAllNodesWithText("Smart", substring = true).assertCountEquals(0)
    }

    @Test
    fun theActiveQueueIsNotOfferedAsADestination() {
        // "Do not show: Active Queue." It is the queue that most resembles a destination and the only one
        // that must not be listed — adding to it would edit a frozen snapshot, which the product contract
        // forbids outright.
        show()

        compose.onAllNodesWithText("Active Queue").assertCountEquals(0)
    }

    @Test
    fun nothingIsPresentedAsACurrentOrDefaultDestination() {
        // The contract's non-goal is "an ambiguous global concept of a Current queue while the user is merely
        // browsing", and a chooser is where such a concept takes hold. So there is no pre-selected row and no
        // marker: the assertion is that no queue row is marked in any way a listener could read as a default.
        show(queues = listOf(queue("q1", "Reading"), queue("q2", "Chess")))

        compose.onAllNodesWithText("Current", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Default", substring = true).assertCountEquals(0)
    }

    // ------------------------------------------------------------------------------------- the report

    @Test
    fun anAddsReportIsShownAndAnnounced() {
        // The spec makes the de-duplication rule and its reporting one requirement: a listener who adds twelve
        // things and is told nothing cannot tell "all twelve were already there" from "the app failed".
        show(report = "Added 2 notes. 2 were already in this queue.")

        compose.onNodeWithText("Added 2 notes. 2 were already in this queue.")
            .assert(A11y.hasLiveRegion())
    }

    @Test
    fun withNoReportTheScreenSaysNothingAboutAdding() {
        // A sentence explaining something that did not happen is worse than no sentence.
        show(report = null)

        compose.onAllNodesWithText("Added", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Nothing was added", substring = true).assertCountEquals(0)
    }

    /** One node carrying every fragment, so a split row cannot pass. See the same helper on the Saved tab. */
    private fun mergedTextContaining(vararg fragments: String) =
        androidx.compose.ui.test.SemanticsMatcher("merged text containing ${fragments.toList()}") { node ->
            val text = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) {
                node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(" ") { it.text }
            } else {
                ""
            }
            fragments.all { text.contains(it) }
        }
}
