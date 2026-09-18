package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.ReaderState
import com.example.notionreader.player.sectionSpans
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.hasPaneTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Player's Note-level navigation and its view of the frozen Active Queue.
 *
 * ## What the contract asks of these two surfaces
 *
 * The UI contract lists `Queue` among the Player's primary controls and says exactly what it opens: "a
 * view of the current frozen Active Queue/session ... This represents the current playback snapshot. **It
 * is not the underlying Saved Queue or Smart Queue definition.**" Its row rule is the one the other lists
 * follow — "One Browse result or one Queue row should normally be one primary focus item" — and the one
 * thing it forbids about Back is that it ever "implicitly mean: Pause, Stop, Previous section, Previous
 * note".
 *
 * The product contract fixes what the view may *not* offer: "Saved Queues are edited. Smart Queues are
 * configured. **Active Queues are played.**" and "Active Queue membership and ordering are fixed snapshots
 * for the lifetime of that listening session". So the assertions below include the absence of editing —
 * a read-only surface is a requirement here, not an unfinished feature.
 *
 * ## What is asserted here and what is asserted on the JVM
 *
 * Everything about *where the session moves* is `ReaderSessionQueueTest`, which drives transitions with no
 * device. What only a device can answer is what a screen reader meets: whether a row is one focus stop,
 * whether the current row says so, whether the decorative marker is read out, and whether a control that
 * cannot act is announced as unavailable.
 */
class PlayerQueueViewAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = A11y.document()

    private fun queueOf(vararg titles: String) = ActiveQueue.snapshotOf(
        notes = titles.mapIndexed { index, title -> QueuedNote("page-$index", title) },
        origin = QueueOrigin.BrowseResults
    )

    private fun playerState(
        index: Int = 0,
        isPlaying: Boolean = false
    ) = ReaderState(
        paragraphs = document.chunkTexts,
        currentParagraphIndex = index,
        isPlaying = isPlaying,
        sections = document.sectionSpans(),
        hasReachedEnd = false
    )

    private fun showPlayer(
        queueState: QueueState = QueueState(),
        state: ReaderState = playerState(),
        onCommand: (PlayerCommand, Int?) -> Unit = { _, _ -> }
    ) {
        compose.setContent {
            PlayerScreen(
                state = state,
                documentTitle = document.title,
                onCommand = onCommand,
                queueState = queueState
            )
        }
    }

    /** Opens the queue view and returns once it is on screen. */
    private fun openQueue() {
        compose.onNodeWithText("Queue").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ the Queue control

    @Test
    fun theQueueControlIsPresentAndNamed() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta"), index = 0))

        // The fourth of the contract's four named primary controls, and named as what it opens.
        compose.onNodeWithText("Queue").assertIsReachable()
    }

    @Test
    fun theQueueControlIsUnavailableWithNoQueueRatherThanInert() {
        showPlayer(queueState = QueueState())

        // Disabled rather than present-and-doing-nothing: the contract's rule that controls which cannot
        // act are announced as unavailable instead of letting a press do nothing.
        compose.onNodeWithText("Queue").assertIsNotEnabled()
    }

    // ------------------------------------------------------------------ the view itself

    @Test
    fun theQueueViewNamesItselfSoEnteringItSaysWhereTheListenerIs() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta"), index = 0))

        openQueue()

        compose.onNode(hasPaneTitle("Active Queue")).assertExists()
    }

    @Test
    fun theQueueViewListsEveryNoteInTheSnapshotInOrder() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 1))

        openQueue()

        // The whole frozen membership, in the frozen order — which is the point of the view. Numbered,
        // because a listener moving between Notes wants to know where each one sits.
        compose.onNodeWithText("1. Alpha").assertExists()
        compose.onNodeWithText("2. Beta").assertExists()
        compose.onNodeWithText("3. Gamma").assertExists()
    }

    @Test
    fun aQueueRowIsOneFocusStopCarryingItsPosition() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta"), index = 1))

        openQueue()

        // One node whose text already contains the title and the marker, rather than two swipes — the
        // contract's "one Queue row should normally be one primary focus item", asserted as one matcher
        // because two lookups would each find their own node and pass on a split row.
        compose.onNodeWithText("2. Beta", substring = true)
            .assert(mergedTextContaining("2. Beta", "Now playing"))
    }

    @Test
    fun theCurrentNoteIsDistinguishableAndTheOthersAreDescribedByPosition() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 1))

        openQueue()

        compose.onNodeWithText("2. Beta", substring = true)
            .assert(mergedTextContaining("Now playing"))

        // Position, not completion. The first version said "Completed" for everything before the current
        // Note, which is a claim the session cannot support: a listener who pressed `Next note` past a Note
        // never read it. These labels say the one thing the queue actually knows.
        compose.onNodeWithText("1. Alpha", substring = true)
            .assert(mergedTextContaining("Earlier in the queue"))
        compose.onNodeWithText("3. Gamma", substring = true)
            .assert(mergedTextContaining("Later in the queue"))
    }

    @Test
    fun theDecorativeMarkerOnTheCurrentRowIsNotReadOut() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta"), index = 0))

        openQueue()

        // "▶" is a picture, not information: the row already says "Now playing", so announcing the glyph
        // would be noise. This is the same defect this project fixed on the Note screen, where bullet and
        // check glyphs were being read aloud.
        val spoken = compose.onAllNodes(A11y.ANY).fetchSemanticsNodes()
            .mapNotNull { node ->
                if (node.config.contains(SemanticsProperties.Text)) {
                    node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
                } else {
                    null
                }
            }

        assertTrue(
            "the decorative marker must not be in the semantics tree; found it in $spoken",
            spoken.none { it.contains("▶") }
        )
    }

    @Test
    fun theQueuePositionIsCarriedFromTheSameSourceThePlayerUses() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 1))

        openQueue()

        // The same "Note 3 of 8" string, from the same `QueueState`, so the view and the Player cannot
        // disagree about where the session is.
        compose.onNodeWithText("Note 2 of 3").assertExists()
    }

    // ------------------------------------------------------------------ acting on the view

    @Test
    fun selectingARowAsksTheSessionToMoveThere() {
        var selected: Int? = null
        showPlayer(
            queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 0),
            onCommand = { command, index ->
                if (command == PlayerCommand.JumpToNote) selected = index
            }
        )

        openQueue()
        compose.onNodeWithText("3. Gamma", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)

        // The index travels with the command: "jump somewhere" with no destination is not a command.
        assertEquals(2, selected)
    }

    @Test
    fun closingTheQueueReturnsToThePlayerWithoutTouchingTheSession() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta"), index = 0))

        openQueue()
        compose.onNodeWithText("Close queue").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        // Back on the Player: the view is a view, and closing it is not a navigation and must not be one.
        compose.onNode(hasPaneTitle("Player")).assertExists()

        // And the view is gone rather than merely covered.
        compose.onAllNodes(hasPaneTitle("Active Queue")).assertCountEquals(0)
    }

    @Test
    fun theQueueViewOffersNoWayToEditTheSnapshot() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 0))

        openQueue()

        // Not an omission: "Active Queue membership and ordering are fixed snapshots for the lifetime of
        // that listening session", and "Saved Queues are edited ... Active Queues are played". Removing or
        // reordering here would be a feature requiring a different contract, so its absence is asserted
        // rather than left to be discovered.
        for (control in listOf("Remove", "Delete", "Move up", "Move down", "Move to top", "Add note")) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(control)).assertCountEquals(0)
        }
    }

    @Test
    fun noClickableNodeInTheQueueViewIsUnlabelled() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta"), index = 0))

        openQueue()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    // ------------------------------------------------------------------ Note-level navigation

    @Test
    fun noteControlsAppearForAQueueOfMoreThanOne() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta"), index = 0))

        compose.onNodeWithText("Next note").assertIsReachable()
    }

    @Test
    fun noteControlsAreAbsentForASingleNoteQueue() {
        // A single-Note queue is how every Play from the Note screen is expressed, so "Next note" there
        // would be a control that could never act. A second `setContent` in one test is not allowed — and
        // would have been asserting about the first composition anyway — so this is its own case.
        showSingleNoteQueue()

        compose.onAllNodes(androidx.compose.ui.test.hasText("Next note")).assertCountEquals(0)
        compose.onAllNodes(androidx.compose.ui.test.hasText("Previous note")).assertCountEquals(0)
    }

    private fun showSingleNoteQueue() {
        compose.setContent {
            PlayerScreen(
                state = playerState(),
                documentTitle = document.title,
                queueState = QueueState(queue = queueOf("Only"), index = 0)
            )
        }
    }

    @Test
    fun noteControlsAreDisabledAtTheQueueBoundaries() {
        showPlayer(queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 0))

        // At the first Note there is nothing before it, and at the last there is nothing after: disabled
        // rather than inert, so TalkBack says so.
        compose.onNodeWithText("Previous note").assertIsNotEnabled()
        compose.onNodeWithText("Next note").assertIsEnabled()
    }

    @Test
    fun noteControlsAreUnavailableWhileTheQueueIsLoadingRatherThanLyingAboutIt() {
        showPlayer(
            queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 1, awaiting = true)
        )

        // Mid-fetch the session is not at a Note it can move relative to yet; enabling these would let a
        // press be silently dropped or, worse, issue a second transition the queue cannot honour.
        compose.onNodeWithText("Previous note").assertIsNotEnabled()
        compose.onNodeWithText("Next note").assertIsNotEnabled()
    }

    @Test
    fun eachNoteControlInvokesItsOwnAction() {
        val invoked = mutableListOf<PlayerCommand>()
        showPlayer(
            queueState = QueueState(queue = queueOf("Alpha", "Beta", "Gamma"), index = 1),
            onCommand = { command, _ -> invoked += command }
        )

        compose.onNodeWithText("Next note").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Previous note").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(
            listOf(PlayerCommand.NextNote, PlayerCommand.PreviousNote),
            invoked
        )
    }

    /** Matches a node whose merged text contains every fragment. */
    private fun mergedTextContaining(vararg fragments: String) =
        SemanticsMatcher("merged text containing ${fragments.toList()}") { node ->
            val text = if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            } else {
                ""
            }
            fragments.all { text.contains(it) }
        }
}
