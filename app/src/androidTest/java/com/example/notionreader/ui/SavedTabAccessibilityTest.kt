package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
 * The Saved tab's semantics, which is what TalkBack consumes.
 *
 * The application-layer half — the ordering, the member count's wording, whether `Play` is offered — is
 * `SavedQueueCardTest` and runs with no device. What only a device can answer is whether the tree a
 * screen-reader walks is usable: whether a queue is one focus stop or two, whether the count is read as
 * part of the queue's name, and whether the tab can be reached and activated without a gesture.
 *
 * The contract's rule for this tab is that "each queue should normally be one logical TalkBack focus
 * target", which is the same requirement a history entry carries on the Listening tab and the same reason:
 * a listener who hears a name and then a count has been told one thing, not two.
 *
 * ## What is deliberately not asserted
 *
 * Nothing here asserts that a member count *changes* anything, and nothing asserts a `Smart` tab. Smart
 * Queues are out of scope for the governing specification, so their absence is an assertion rather than a
 * gap — see [theSavedTabOffersNoSmartQueues] — and the tab row's ordinal is asserted as the tabs that
 * exist, which is two.
 */
class SavedTabAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun queue(
        id: String = "q1",
        name: String = "Android accessibility",
        memberCount: Int = 11
    ) = SavedQueueCard(
        queueId = id,
        name = name,
        members = List(memberCount) { QueuedNote("page-$it", "Note $it") },
        lastEditedAt = 1_000L
    )

    private fun show(
        queues: List<SavedQueueCard> = emptyList(),
        onOpenQueue: (String) -> Unit = { },
        onCreate: () -> Unit = { }
    ) {
        compose.setContent {
            SavedTab(queues = queues, onOpenQueue = onOpenQueue, onCreate = onCreate)
        }
    }

    // ----------------------------------------------------------------------------- one focus stop per queue

    @Test
    fun aQueueIsASingleFocusStopCarryingItsNameAndItsCount() {
        // The contract's requirement, asserted the way TalkBack experiences it: one node whose text already
        // contains both facts, rather than a name and a count as two swipes.
        show(queues = listOf(queue(name = "Android accessibility", memberCount = 11)))

        compose.onNode(mergedTextContaining("Android accessibility", "11 notes")).assertIsReadable()
    }

    @Test
    fun theRowIsItsOwnControlRatherThanHavingOneBesideIt() {
        // The difference from a history entry, and it is deliberate. A history entry *describes* something
        // (a session that already exists) and needs a separate verb, so its row is readable and its button is
        // reachable. A queue row *is* the verb — activating it opens the queue — so the merged node is the
        // control, and it must be reachable rather than merely readable.
        show(queues = listOf(queue()))

        compose.onNodeWithText("Android accessibility", substring = true).assertIsReachable()
    }

    @Test
    fun activatingAQueueOpensItByNameAndDoesNotPlayIt() {
        // "Activating one opens Saved Queue Details. It does not immediately start playback." So the callback
        // is the queue's identity and there is no playback control on this tab at all.
        val opened = mutableListOf<String>()
        show(queues = listOf(queue(id = "q7"), queue(id = "q9", name = "Chess")), onOpenQueue = { opened += it })

        compose.onNodeWithText("Chess", substring = true).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("q9"), opened)
        // No `Play` anywhere on this tab: playing is the Details screen's control, not the list's.
        compose.onAllNodesWithText("Play").assertCountEquals(0)
    }

    @Test
    fun everyQueueIsReadableAndNoControlIsAnUnlabelledButton() {
        show(queues = listOf(queue("q1", "Android accessibility"), queue("q2", "Chess calculation review", 6)))

        compose.onNodeWithText("Android accessibility", substring = true).assertIsReadable()
        compose.onNodeWithText("Chess calculation review", substring = true).assertIsReadable()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    @Test
    fun oneNoteIsSpokenInTheSingularInTheTreeAsWellAsInTheModel() {
        // The model's wording is asserted with no device, but the wording reaches a listener through *this*
        // tree, so the boundary is worth one assertion: a merge that dropped a child's text would leave the
        // model correct and the screen silent.
        show(queues = listOf(queue(name = "Reading", memberCount = 1)))

        compose.onNode(mergedTextContaining("Reading", "1 note")).assertIsReadable()
        compose.onAllNodesWithText("1 notes", substring = true).assertCountEquals(0)
    }

    @Test
    fun queuesAreListedInTheOrderTheyAreGivenRatherThanReSorted() {
        // The order is the application layer's decision (`toCards`, most recently edited first), and this
        // asserts the tab does not impose a second one — a screen that sorted by name would silently replace
        // "most recently edited" with "alphabetical".
        show(
            queues = listOf(
                queue("q1", "Zebra"),
                queue("q2", "Apple"),
                queue("q3", "Mango")
            )
        )

        A11y.assertReadingOrder("Zebra", "Apple", "Mango", collection = compose.onAllNodes(A11y.ANY))
    }

    // --------------------------------------------------------------------------------------- create

    @Test
    fun createIsOfferedWhenThereAreNoQueues() {
        // It is both the way to grow a collection and the only way out of an empty tab, so it is not
        // something the empty state replaces.
        show(queues = emptyList())

        compose.onNodeWithText("Create saved queue").assertIsReachable()
    }

    @Test
    fun createIsOfferedAlongsideExistingQueues() {
        // The other half of the same rule, and it is a separate test rather than a second `show()` call
        // because `setContent` may only be called once per test — the first version called it twice and
        // failed with "has already set content", which is a rule about the test rule rather than about the
        // screen. Two tests, one rule each.
        show(queues = listOf(queue()))

        compose.onNodeWithText("Create saved queue").assertIsReachable()
    }

    @Test
    fun activatingCreateReportsTheIntention() {
        val created = mutableListOf<String>()
        show(queues = emptyList(), onCreate = { created += "pressed" })

        compose.onNodeWithText("Create saved queue").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("pressed"), created)
    }

    // ---------------------------------------------------------------------------------- the empty state

    @Test
    fun withNoQueuesTheTabExplainsItselfInsteadOfSittingBlank() {
        // The empty-screen rule this project applies wherever a surface can be empty: it says what is
        // missing, what the thing is, and how to get one.
        show(queues = emptyList())

        compose.onNodeWithText("No saved queues yet.").assert(A11y.hasHeading())
        compose.onNodeWithText("No saved queues yet.").assertIsReadable()
        compose.onNodeWithText("Create saved queue").assertIsReachable()
    }

    @Test
    fun theEmptyStateIsGoneOnceThereIsAQueue() {
        // So an empty tab and a populated one cannot be confused for each other, and so the explanation does
        // not sit above a list that contradicts it.
        show(queues = listOf(queue()))

        compose.onAllNodesWithText("No saved queues yet.").assertCountEquals(0)
        compose.onNodeWithText("Saved queues").assert(A11y.hasHeading())
    }

    // ------------------------------------------------------------------------------- no Smart queues

    @Test
    fun theSavedTabOffersNoSmartQueuesAndNoSmartTab() {
        // The specification's scope boundary, asserted rather than assumed: Smart Queues are a different tab
        // and a different specification, and this tab lists what the listener built and nothing else.
        show(queues = listOf(queue()))

        compose.onAllNodesWithText("Smart", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Smart Queue", substring = true).assertCountEquals(0)
    }

    /**
     * One node carrying every fragment, which is the whole point of the assertion it is used for.
     *
     * Written as one matcher rather than several `onNodeWithText` lookups because the fragments have to be
     * on **one** node: separate lookups would each find their own node and pass even if the queue had been
     * split into two focus stops, which is the failure being looked for. The same helper, for the same
     * reason, is used on the Listening tab and on Home.
     */
    private fun mergedTextContaining(vararg fragments: String) =
        SemanticsMatcher("merged text containing ${fragments.toList()}") { node ->
            val text = if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            } else {
                ""
            }
            fragments.all { text.contains(it) }
        }}
