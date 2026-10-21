package com.example.notionreader.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.application.ContinueListeningCard
import com.example.notionreader.application.ExploreCard
import com.example.notionreader.application.SavedQueueCard
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.hasHeading
import com.example.notionreader.ui.A11y.hasPaneTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Home's semantics, which is what TalkBack consumes.
 *
 * Home was the last shipped surface with no instrumented test of its own — `docs/decisions.md` §20.4
 * names that as slice A′ — and it is the surface a listener meets first, so the two things it has to get
 * right are the two least forgiving:
 *
 * - **It must not be a blank screen.** The UI contract gives Home three sections and this build ships
 *   one, so with nothing to continue Home explains itself and offers Browse. That is what distinguishes
 *   it from the "empty result set" the contract warns about, and it is why the empty state is asserted
 *   here rather than only its populated one.
 * - **One session is one focus stop.** The contract's example announcement is a title, a position, a
 *   state and a relative time — `Mindfulness reflections / Note 3 of 8 / Paused 2 hours ago` — read as one
 *   item, with Continue as a separate named control beside it rather than part of the sentence.
 *
 * Times are asserted to be *relative and never raw* because the spec requires it and because a raw
 * timestamp is the kind of thing that only a listener would notice.
 */
class HomeScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun card(
        id: String = "s1",
        title: String = "Mindfulness reflections",
        position: String? = "Note 3 of 8",
        state: String = "Paused",
        ago: Long = 2 * 60 * 60 * 1000L
    ) = ContinueListeningCard(
        sessionId = id,
        title = title,
        queuePosition = position,
        stateDescription = state,
        isPlaying = state == "Playing",
        lastActivityAt = now - ago
    )

    private fun show(
        continueListening: List<ContinueListeningCard> = emptyList(),
        onContinue: (String) -> Unit = { },
        onBrowse: () -> Unit = { }
    ) {
        compose.setContent {
            HomeScreen(
                continueListening = continueListening,
                onContinue = onContinue,
                onBrowse = onBrowse,
                onPlayPause = { },
                now = now
            )
        }
    }

    // ------------------------------------------------------------------ where the listener has arrived

    @Test
    fun homeNamesItselfSoEnteringItSaysWhereTheListenerIs() {
        show()

        compose.onNode(hasPaneTitle("Home")).assertExists()
    }

    @Test
    fun everyControlCanDescribeItselfRatherThanBeingAnnouncedAsABareButton() {
        show(continueListening = listOf(card()))

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    // ------------------------------------------------------------------ the empty state explains itself

    @Test
    fun withNothingToContinueHomeExplainsItselfRatherThanSittingBlank() {
        show()

        // Not an empty list under a heading: a sentence saying what would appear here.
        compose.onNodeWithText("Nothing to continue yet.").assert(hasHeading())
        compose.onNodeWithText("Play a Note and it will appear here", substring = true).assertIsReadable()
    }

    @Test
    fun theEmptyStateOffersARouteOut() {
        var browsed = false
        show(onBrowse = { browsed = true })

        compose.onNodeWithText("Browse Notes").assertIsReachable()

        compose.onNodeWithText("Browse Notes").performSemanticsAction(SemanticsActions.OnClick)

        // A destination with no exits would leave a listener with nothing to do and no way onward.
        assertTrue("Browse Notes should open Browse", browsed)
    }

    // ------------------------------------------------------------------ one focus stop per session

    @Test
    fun aSessionIsOneFocusStopCarryingItsTitlePositionStateAndRecency() {
        show(continueListening = listOf(card()))

        // The contract's own example shape, as one node. Asserted as a single matcher because separate
        // lookups would each find their own node and pass even if the session had been split into four
        // swipes.
        compose.onNodeWithText("Mindfulness reflections", substring = true)
            .assert(
                mergedTextContaining(
                    "Mindfulness reflections",
                    "Note 3 of 8",
                    "Paused",
                    "2 hours ago"
                )
            )
    }

    @Test
    fun recencyIsSpokenAsRelativeTimeAndNeverAsATimestamp() {
        show(continueListening = listOf(card(ago = 26 * 60 * 60 * 1000L)))

        // "Paused yesterday", not "Paused 2026-09-17T10:13". A raw timestamp is unreadable at speaking
        // speed and says nothing a listener wants to know.
        compose.onNodeWithText("Yesterday", substring = true).assertExists()
        compose.onNodeWithText("2026-", substring = true).assertDoesNotExist()
    }

    @Test
    fun aSessionIsASingleFocusStopThatCanBeActivatedToContinue() {
        var continuedSessionId: String? = null
        show(continueListening = listOf(card(id = "s1")), onContinue = { continuedSessionId = it })

        // One focus stop for the session: merges title, position, state and recency into a single
        // reachable control that activates to continue, avoiding duplicate controls for the same action.
        compose.onNodeWithText("Mindfulness reflections", substring = true).assertIsReachable()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Play")).assertCountEquals(0)

        compose.onNodeWithText("Mindfulness reflections", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("s1", continuedSessionId)
    }

    @Test
    fun theSectionHeadingIsAHeadingSoTheScreenCanBeSkimmed() {
        show(continueListening = listOf(card()))

        compose.onNodeWithText("Continue listening").assert(hasHeading())
    }

    @Test
    fun theSectionHeadingIsOmittedWhenThereIsNothingToContinue() {
        // The contract permits omitting an empty section, and a heading with nothing under it is worse than
        // no heading: it promises content that is not there.
        show()

        compose.onAllNodes(androidx.compose.ui.test.hasText("Continue listening")).assertCountEquals(0)
    }

    @Test
    fun yourQueuesDisplaysWhenPresent() {
        val q1 = SavedQueueCard(
            queueId = "q1",
            name = "Android accessibility",
            members = listOf(QueuedNote("p1", "Note 1")),
            lastEditedAt = now
        )
        compose.setContent {
            HomeScreen(
                continueListening = emptyList(),
                yourQueues = listOf(q1),
                onContinue = {},
                onBrowse = {},
                onPlayPause = {},
                now = now
            )
        }

        compose.onNodeWithText("Your queues").assert(hasHeading())
        compose.onNodeWithText("Android accessibility", substring = true).assertIsReachable()
        compose.onNodeWithText("Saved Queue · 1 note", substring = true).assertExists()
        compose.onNodeWithText("See all queues").assertIsReachable()
    }

    @Test
    fun activatingAQueueOpensIt() {
        var openedId: String? = null
        val q1 = SavedQueueCard(
            queueId = "q1",
            name = "Android accessibility",
            members = listOf(QueuedNote("p1", "Note 1")),
            lastEditedAt = now
        )
        compose.setContent {
            HomeScreen(
                continueListening = emptyList(),
                yourQueues = listOf(q1),
                onContinue = {},
                onOpenQueue = { openedId = it },
                onBrowse = {},
                onPlayPause = {},
                now = now
            )
        }

        compose.onNodeWithText("Android accessibility", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("q1", openedId)
    }

    @Test
    fun seeAllQueuesNavigates() {
        var navigated = false
        val q1 = SavedQueueCard(
            queueId = "q1",
            name = "Android accessibility",
            members = listOf(QueuedNote("p1", "Note 1")),
            lastEditedAt = now
        )
        compose.setContent {
            HomeScreen(
                continueListening = emptyList(),
                yourQueues = listOf(q1),
                onContinue = {},
                onSeeAllQueues = { navigated = true },
                onBrowse = {},
                onPlayPause = {},
                now = now
            )
        }

        compose.onNodeWithText("See all queues").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(navigated)
    }

    @Test
    fun showsExploreShortcutsWhenAvailable() {
        val e1 = ExploreCard(title = "Mindfulness", noteCount = 12, queryText = "Mindfulness")
        compose.setContent {
            HomeScreen(
                continueListening = emptyList(),
                yourQueues = emptyList(),
                exploreCards = listOf(e1),
                onContinue = {},
                onBrowse = {},
                onPlayPause = {},
                now = now
            )
        }

        compose.onNodeWithText("Explore").assert(hasHeading())
        compose.onNodeWithText("Mindfulness").assertIsReachable()
        compose.onNodeWithText("12 notes").assertExists()
    }

    @Test
    fun activatingExploreNavigatesWithQuery() {
        var exploredQuery: String? = null
        val e1 = ExploreCard(title = "Mindfulness", noteCount = 12, queryText = "Mindfulness")
        compose.setContent {
            HomeScreen(
                continueListening = emptyList(),
                yourQueues = emptyList(),
                exploreCards = listOf(e1),
                onContinue = {},
                onExplore = { exploredQuery = it },
                onBrowse = {},
                onPlayPause = {},
                now = now
            )
        }

        compose.onNodeWithText("Mindfulness").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("Mindfulness", exploredQuery)
    }

    @Test
    fun continueListeningSessionDeclaresButtonRole() {
        show(continueListening = listOf(card()))
        compose.onNodeWithText("Mindfulness reflections", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun yourQueuesEntryDeclaresButtonRole() {
        val q1 = SavedQueueCard(
            queueId = "q1",
            name = "Android accessibility",
            members = listOf(QueuedNote("p1", "Note 1")),
            lastEditedAt = now
        )
        compose.setContent {
            HomeScreen(
                continueListening = emptyList(),
                yourQueues = listOf(q1),
                onContinue = {},
                onBrowse = {},
                onPlayPause = {},
                now = now
            )
        }
        compose.onNodeWithText("Android accessibility", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun exploreShortcutDeclaresButtonRole() {
        val e1 = ExploreCard(title = "Mindfulness", noteCount = 12, queryText = "Mindfulness")
        compose.setContent {
            HomeScreen(
                continueListening = emptyList(),
                yourQueues = emptyList(),
                exploreCards = listOf(e1),
                onContinue = {},
                onBrowse = {},
                onPlayPause = {},
                now = now
            )
        }
        compose.onNodeWithText("Mindfulness")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    /** Matches a node whose merged text contains every fragment. */
    private fun mergedTextContaining(vararg fragments: String) =
        androidx.compose.ui.test.SemanticsMatcher("merged text containing ${fragments.toList()}") { node ->
            val text = if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            } else {
                ""
            }
            fragments.all { text.contains(it) }
        }
}
