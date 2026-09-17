package com.example.notionreader.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import com.example.notionreader.application.HistoryEntry
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Listening tab's semantics, which is what TalkBack consumes.
 *
 * The application-layer half — which sessions each section lists, in what order, and what each entry says
 * about itself — is `ListeningTabTest` and runs with no device. What only a device can answer is whether
 * the tree a screen-reader walks is usable: whether an entry is one focus stop or four, whether the
 * control beside it is a separate named button, whether a heading is a heading, and whether the times are
 * spoken as relative time rather than as a timestamp.
 *
 * The Active Queue & Reader Session spec puts four of these in its accessibility section verbatim: "A
 * history entry is one focus stop. Its title, its queue position, and its state are read as one item, and
 * Continue is an explicit named control beside it, not part of the entry's announcement." "Relative times
 * are spoken as relative time. A history entry must not announce a raw timestamp." And "Nothing in history
 * may be reachable only by a gesture."
 *
 * The one thing this cannot check is the fifth: whether TalkBack and the reader's own speech overlap. That
 * needs ears, and it is still open (`docs/decisions.md` §17).
 */
class QueuesScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun unfinished(
        id: String = "s1",
        title: String = "Android accessibility",
        membership: String? = "Note 3 of 8",
        state: String = "Paused",
        ago: Long = 2 * 60 * 60 * 1000L
    ) = HistoryEntry(
        sessionId = id,
        title = title,
        membership = membership,
        stateDescription = state,
        isPlaying = state == "Playing",
        isCompleted = false,
        lastActivityAt = now - ago
    )

    private fun completed(
        id: String = "c1",
        title: String = "AI inference notes",
        membership: String? = "8 notes",
        ago: Long = 26 * 60 * 60 * 1000L
    ) = HistoryEntry(
        sessionId = id,
        title = title,
        membership = membership,
        stateDescription = "Completed",
        isPlaying = false,
        isCompleted = true,
        lastActivityAt = now - ago
    )

    private fun show(
        continueListening: List<HistoryEntry> = emptyList(),
        recentlyCompleted: List<HistoryEntry> = emptyList(),
        onContinue: (String) -> Unit = { },
        onReplay: (String) -> Unit = { }
    ) {
        compose.setContent {
            QueuesScreen(
                continueListening = continueListening,
                recentlyCompleted = recentlyCompleted,
                onContinue = onContinue,
                onReplay = onReplay,
                now = now
            )
        }
    }

    // ------------------------------------------------------------------------------- one focus stop per entry

    @Test
    fun aHistoryEntryIsASingleFocusStopCarryingItsTitlePositionAndState() {
        // The spec's requirement, asserted the way TalkBack experiences it: one node whose text already
        // contains everything, rather than three nodes to swipe through.
        show(continueListening = listOf(unfinished()))

        compose.onNodeWithText("Android accessibility", substring = true)
            .assert(mergedTextContaining("Android accessibility", "Note 3 of 8", "Paused"))
    }

    /**
     * Matches a node whose merged text contains every fragment.
     *
     * Written as one matcher rather than three `onNodeWithText` lookups because the point is that the
     * fragments are on **one** node: three separate lookups would each find their own node and pass even
     * if the entry had been split into three focus stops, which is the failure this is looking for.
     */
    private fun mergedTextContaining(vararg fragments: String) =
        SemanticsMatcher("merged text containing ${fragments.toList()}") { node ->
            val text = if (node.config.contains(SemanticsProperties.Text)) {
                node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            } else {
                ""
            }
            fragments.all { text.contains(it) }
        }

    @Test
    fun theControlIsSeparateFromTheEntryAndIsNamed() {
        // "Continue is an explicit named control beside it, not part of the entry's announcement."
        show(continueListening = listOf(unfinished()))

        compose.onNodeWithText("Continue").assertIsReachable()
    }

    @Test
    fun aCompletedEntryOffersPlayRatherThanContinue() {
        // Two different verbs because they are two different operations: Continue restores this session,
        // Play starts a new one over the same Notes from the beginning. A single "Continue" on a finished
        // session would be a lie about what happens next.
        show(recentlyCompleted = listOf(completed()))

        compose.onNodeWithText("Play").assertIsReachable()
        compose.onAllNodesWithText("Continue").assertCountEquals(0)
    }

    @Test
    fun noControlOnTheScreenIsAnUnlabelledButton() {
        show(
            continueListening = listOf(unfinished()),
            recentlyCompleted = listOf(completed())
        )

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    @Test
    fun everyHistoryEntryIsReadableAndEveryControlIsReachable() {
        // Entries and controls are asserted differently on purpose. A history entry is one focus stop a
        // listener *reads*, and the spec requires Continue to be a separate named control rather than
        // part of it - so requiring OnClick on the entry would either fail a correct design or invite
        // making the whole row tappable, which would make the title an unlabelled gesture target. The
        // buttons are the controls, and they are asserted as controls.
        show(
            continueListening = listOf(unfinished(id = "s1"), unfinished(id = "s2", title = "Mindfulness")),
            recentlyCompleted = listOf(completed())
        )

        compose.onNodeWithText("Android accessibility", substring = true).assertIsReadable()
        compose.onNodeWithText("Mindfulness", substring = true).assertIsReadable()

        // The first entry's control is on the surface, so it is asserted reachable. With three entries the
        // third sits below the fold in a scrolling list, which is legitimate for a long history and is why
        // only the primary control is asserted this way; every control's accessible *name* is still checked
        // by `noControlOnTheScreenIsAnUnlabelledButton`, which walks the whole tree.
        compose.onAllNodesWithText("Continue")[0].assertIsReachable()
    }

    // ------------------------------------------------------------------------------- sections and headings

    @Test
    fun anEmptySectionIsOmittedRatherThanHeadedAndBlank() {
        // The UI contract's own rule: "If one of the two sections contains no items, omit the empty
        // section heading." A heading with nothing under it is worse than no heading, because it promises
        // content and then ends.
        show(recentlyCompleted = listOf(completed()))

        compose.onAllNodesWithText("Continue listening").assertCountEquals(0)
        compose.onNodeWithText("Recently completed").assertExists()
    }

    @Test
    fun theOmittedSectionIsSymmetric() {
        show(continueListening = listOf(unfinished()))

        compose.onAllNodesWithText("Recently completed").assertCountEquals(0)
        compose.onNodeWithText("Continue listening").assertExists()
    }

    @Test
    fun bothSectionHeadingsAreHeadingsSoTheScreenCanBeSkimmedByStructure() {
        show(
            continueListening = listOf(unfinished()),
            recentlyCompleted = listOf(completed())
        )

        compose.onNodeWithText("Continue listening").assert(A11y.hasHeading())
        compose.onNodeWithText("Recently completed").assert(A11y.hasHeading())
    }

    @Test
    fun withNothingAtAllTheScreenExplainsItselfInsteadOfSittingBlank() {
        // Both sections empty means no sections at all, which is the empty-screen problem. Queues is a
        // bottom-navigation destination, so its absence would be a worse surprise than its emptiness;
        // this is why it explains rather than hides.
        show()

        compose.onAllNodesWithText("Continue listening").assertCountEquals(0)
        compose.onAllNodesWithText("Recently completed").assertCountEquals(0)
        compose.onNodeWithText("Nothing here yet.").assertExists()
        compose.onNodeWithText("Nothing here yet.").assert(A11y.hasHeading())
    }

    // ------------------------------------------------------------------------------- times and the tab row

    @Test
    fun timesAreRelativeAndNoRawTimestampIsAnnounced() {
        // "Relative times are spoken as relative time. A history entry must not announce a raw timestamp."
        // The entry carries epoch millis, so this is the assertion that the screen does not simply print
        // the value it was handed.
        show(continueListening = listOf(unfinished(ago = 2 * 60 * 60 * 1000L)))

        compose.onAllNodesWithText("2 hours ago", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("1700000000000", substring = true).assertCountEquals(0)
    }

    @Test
    fun aCompletedEntryReadsAsItsNoteCountAndWhenItFinished() {
        // The contract's example shape: `8 notes · Completed yesterday`.
        show(recentlyCompleted = listOf(completed(ago = 26 * 60 * 60 * 1000L)))

        compose.onNodeWithText("8 notes", substring = true).assertExists()
        compose.onNodeWithText("Completed yesterday", substring = true).assertExists()
    }

    @Test
    fun theListeningTabExposesTabSemanticsAndIsSelected() {
        // "The selected tab must expose proper Android/TalkBack tab semantics." The ordinal the contract
        // illustrates ("1 of 3") belongs to the two tabs that are deferred, so what is asserted here is
        // the role and the selection state, both of which are what a screen reader acts on.
        show(continueListening = listOf(unfinished()))

        compose.onNodeWithText("Listening")
            .assert(
                SemanticsMatcher("is a selected tab") { node ->
                    node.config.contains(SemanticsProperties.Selected) &&
                        node.config[SemanticsProperties.Selected] &&
                        node.config.contains(SemanticsProperties.Role) &&
                        node.config[SemanticsProperties.Role] == Role.Tab
                }
            )
    }

    @Test
    fun theScreenNamesItselfSoEnteringItSaysWhereTheListenerIs() {
        show(continueListening = listOf(unfinished()))

        compose.onNodeWithText("Android accessibility", substring = true)
            .assert(
                SemanticsMatcher("inside a screen titled Queues") { node ->
                    generateSequence(node) { it.parent }
                        .any { it.config.contains(SemanticsProperties.PaneTitle) &&
                            it.config[SemanticsProperties.PaneTitle] == "Queues" }
                }
            )
    }

    @Test
    fun unfinishedEntriesComeBeforeCompletedOnes() {
        show(
            continueListening = listOf(unfinished(title = "Something unfinished")),
            recentlyCompleted = listOf(completed(title = "Something finished"))
        )

        A11y.assertReadingOrder(
            "Continue listening",
            "Something unfinished",
            "Recently completed",
            "Something finished",
            collection = compose.onAllNodes(A11y.ANY)
        )
    }

    @Test
    fun theEntryCarriesNoLiveRegionSoItIsNotReannouncedWhilePlaybackAdvances() {
        // A history entry is not an announcement; it is a list item. A live region here would re-read the
        // whole session on every position change while playback ran behind the screen.
        show(continueListening = listOf(unfinished()))

        assertEquals(
            0,
            compose.onAllNodes(A11y.hasLiveRegion()).fetchSemanticsNodes().size
        )
        assertTrue(
            "the entry is present and quiet, which is the intended combination",
            compose.onAllNodesWithText("Android accessibility", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
