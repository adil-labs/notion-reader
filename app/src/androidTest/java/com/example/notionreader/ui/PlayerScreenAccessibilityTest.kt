package com.example.notionreader.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.ReaderState
import com.example.notionreader.player.sectionSpans
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented accessibility tests for the Player screen.
 *
 * The Player is the interface a listener uses without looking at it, so its semantics carry more
 * weight than its layout. The UI contract requires the primary controls to be `Previous section /
 * Play / Pause / Next section`, requires that state changes "such as paused, resumed, end of section,
 * end of note, and errors must have concise spoken feedback", and requires that metadata is not spoken
 * during ordinary reading.
 */
class PlayerScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = A11y.document()

    private fun playerState(
        index: Int = 0,
        isPlaying: Boolean = false,
        hasReachedEnd: Boolean = false
    ) = ReaderState(
        paragraphs = document.chunkTexts,
        currentParagraphIndex = index,
        isPlaying = isPlaying,
        sections = document.sectionSpans(),
        hasReachedEnd = hasReachedEnd
    )

    private fun showPlayer(
        state: ReaderState = playerState(),
        onCommand: (PlayerCommand, Int?) -> Unit = { _, _ -> }
    ) {
        compose.setContent {
            PlayerScreen(
                state = state,
                documentTitle = document.title,
                onCommand = onCommand
            )
        }
    }

    // ------------------------------------------------------------------ entry point

    @Test
    fun namesTheScreenSoEntryAnnouncesWhereTheUserIs() {
        showPlayer()

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Player")
        ).assertExists()
    }

    @Test
    fun showsTheTitleSectionAndProgressTheContractAsksFor() {
        // Index 4 is the first paragraph of the titled section (the intro has four chunks), which is
        // the only way to exercise the "current section when available" line with a title in it.
        showPlayer(state = playerState(index = 4))

        compose.onNodeWithText("Accessibility Fixture").assertExists()
        compose.onNodeWithText("Section 2 of 3 · Primary Heading").assertExists()
        compose.onNodeWithText("Part 5 of 8").assertExists()
    }

    // ------------------------------------------------------------------ controls

    @Test
    fun thePrimaryControlsAreLabelledAsSectionsRatherThanParagraphs() {
        showPlayer()

        // Not scrolled into view: these controls are outside the scrolling content, so they have no
        // scrollable ancestor. assertIsReachable rather than assertIsDisplayed because the latter
        // asserts the device is currently drawing the window, which these runs cannot guarantee and
        // which produced a different intermittent failure on each run - see A11y.assertIsReachable.
        compose.onNodeWithText("Previous section").assertIsReachable()
        compose.onNodeWithText("Next section").assertIsReachable()
        compose.onNodeWithText("Play").assertIsReachable()
    }

    @Test
    fun noClickableNodeIsUnlabelled() {
        showPlayer()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY, useUnmergedTree = false))
    }

    @Test
    fun playBecomesPauseWhilePlaying() {
        showPlayer(state = playerState(isPlaying = true))

        compose.onNodeWithText("Pause").assertExists()
        compose.onAllNodesWithText("Play").assertCountEquals(0)
    }

    @Test
    fun eachControlInvokesItsOwnAction() {
        val invoked = mutableListOf<PlayerCommand>()

        showPlayer(
            state = playerState(index = 3),
            onCommand = { command, _ -> invoked += command }
        )

        // Semantics actions rather than synthetic taps: an accessibility service invokes these, and
        // unlike a synthesised touch they cannot be silently dropped by the device's window.
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Next section").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Previous section").performSemanticsAction(SemanticsActions.OnClick)

        // Asserted as the *commands* that fired, which is what a media session or a headset button would
        // send. The Player's buttons are one transport among several rather than a special case.
        assertEquals(
            listOf(
                PlayerCommand.TogglePlayback,
                PlayerCommand.NextSection,
                PlayerCommand.PreviousSection
            ),
            invoked
        )
    }

    /**
     * A control that cannot act is disabled rather than silently inert, so TalkBack announces it as
     * unavailable instead of letting a listener press it and hear nothing happen.
     */
    @Test
    fun sectionControlsAreDisabledAtTheDocumentBoundaries() {
        showPlayer(state = playerState(index = 0))

        compose.onNodeWithText("Previous section").assertIsNotEnabled()
        compose.onNodeWithText("Next section").assertIsEnabled()
    }

    @Test
    fun nextSectionIsDisabledAtTheEnd() {
        showPlayer(state = playerState(index = document.chunkTexts.lastIndex))

        compose.onNodeWithText("Next section").assertIsNotEnabled()
        compose.onNodeWithText("Previous section").assertIsEnabled()
    }

    // ------------------------------------------------------------------ spoken state

    /**
     * This is the assertion that guards the riskiest thing in this round. The previous single screen
     * deliberately had no live regions so that TalkBack could never speak over the reader's own TTS.
     * The Player then added one so that paused/resumed/end-of-note are announced — which is required,
     * but is also a live region in an app that talks. It is therefore pinned down here: the *state*
     * announces, the document body does not.
     */
    @Test
    fun playbackStateIsAnnouncedButTheDocumentBodyIsNot() {
        showPlayer(state = playerState(isPlaying = true))

        compose.onNodeWithText("Playing").assert(A11y.hasLiveRegion())
        compose.onNodeWithText("Intro paragraph.").assert(A11y.hasNoLiveRegion())
        compose.onNodeWithText("Accessibility Fixture").assert(A11y.hasNoLiveRegion())
    }

    @Test
    fun thePositionLineIsNotALiveRegion() {
        // It changes on every part. Announcing it would talk over the document continuously.
        showPlayer()

        compose.onNodeWithText("Part 1 of 8").assert(A11y.hasNoLiveRegion())
    }

    @Test
    fun theAnnouncementIsPoliteSoItDoesNotInterruptTheDocument() {
        showPlayer(state = playerState(isPlaying = true))

        compose.onNodeWithText("Playing").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        )
    }

    /**
     * Three states, not two. Collapsing "stopped at the end" into "Paused" would tell a listener the
     * wrong thing about why the reading stopped, and the contract names "end of note" as a state
     * needing spoken feedback.
     */
    @Test
    fun endOfNoteIsDistinctFromAPauseAndIsSpoken() {
        showPlayer(state = playerState(index = document.chunkTexts.lastIndex, hasReachedEnd = true))

        compose.onNodeWithText("End of note").assert(A11y.hasLiveRegion())
        compose.onAllNodesWithText("Paused").assertCountEquals(0)
    }

    @Test
    fun aUserPauseIsReportedAsPausedRatherThanEndOfNote() {
        showPlayer(state = playerState())

        compose.onNodeWithText("Paused").assertExists()
        compose.onAllNodesWithText("End of note").assertCountEquals(0)
    }

    // ------------------------------------------------------------------ metadata boundary

    /**
     * The metadata-to-speech boundary: an untitled intro section must not produce a dangling
     * separator or an empty "Section 1 of 2 ·" line, and nothing in the Player may leak a URL, a block
     * id or other internal identifier into what gets read out.
     */
    @Test
    fun anUntitledSectionRendersAsAPositionWithoutADanglingSeparator() {
        // The intro section has no title, so the line must be just the position. Building it with an
        // unconditional join would render "Section 1 of 3 ·" and a trailing separator.
        showPlayer(state = playerState(index = 0))

        compose.onNodeWithText("Section 1 of 3").assertExists()
        compose.onAllNodesWithText("·", substring = true).assertCountEquals(0)
    }

    @Test
    fun noInternalIdentifierIsExposedOnScreen() {
        showPlayer(state = playerState(index = 4))

        // Chunk and block ids are how resume works; they are not something a listener should hear.
        compose.onAllNodesWithText(":0", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("a11y:", substring = true).assertCountEquals(0)
    }

    // ------------------------------------------------------------------ secondary controls & sections

    @Test
    fun morePlaybackControlsOpensSecondarySurfaceWithRestartAndSections() {
        showPlayer(state = playerState(index = 2))

        compose.onNodeWithText("More playback controls").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "More playback controls")
        ).assertExists()

        compose.onNodeWithText("Restart note").assertIsReachable()
        compose.onNodeWithText("Sections outline").assertIsReachable()
        compose.onNodeWithText("Close controls").assertIsReachable()

        compose.onNodeWithText("Close controls").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Player")
        ).assertExists()
    }

    @Test
    fun defaultsToSectionNavigationGranularityEvenWithMultipleNotes() {
        compose.setContent {
            PlayerScreen(
                state = playerState(),
                documentTitle = "Multi-note Queue",
                queueState = com.example.notionreader.domain.queue.QueueState(
                    queue = com.example.notionreader.domain.queue.ActiveQueue.snapshotOf(
                        listOf(
                            com.example.notionreader.domain.queue.QueuedNote("p1", "Note 1"),
                            com.example.notionreader.domain.queue.QueuedNote("p2", "Note 2")
                        ),
                        com.example.notionreader.domain.queue.QueueOrigin.BrowseResults
                    )
                )
            )
        }

        compose.onNodeWithText("Navigation: Section").assertIsReachable()
        compose.onNodeWithText("Previous section").assertExists()
        compose.onNodeWithText("Next section").assertExists()
    }

    @Test
    fun sectionsButtonOpensOutlineAndShowsAllSections() {
        showPlayer(state = playerState(index = 0))

        compose.onNodeWithText("Sections").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Sections")
        ).assertExists()

        compose.onNodeWithText("3 sections in this Note").assertExists()
        compose.onNodeWithText("1. Introduction", substring = true).assertIsReachable()
        compose.onNodeWithText("Close sections").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Player")
        ).assertExists()
    }
}
