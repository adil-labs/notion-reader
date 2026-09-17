package com.example.notionreader.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.reader.DocumentLoadState
import com.example.notionreader.reader.ReaderDocument
import com.example.notionreader.reader.ReaderErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented accessibility tests for the Note screen.
 *
 * Every assertion below traces to a requirement rather than to a preference. The UI contract's
 * accessibility section and the integration spec's accessibility-validation section between them
 * require: a meaningful focus starting point, stable accessible names, reading order matching logical
 * order, distinguishable loading/empty/error states, errors surfaced with concise spoken feedback, and
 * that per-item noise is not read out.
 *
 * These run on a device or emulator, not on the JVM, because semantics only exist once Compose has
 * composed them.
 */
class NoteScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showNote(
        loadState: DocumentLoadState = DocumentLoadState.Loaded(A11y.document()),
        onPlay: () -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        compose.setContent {
            NoteScreen(
                document = A11y.document(),
                loadState = loadState,
                onPlay = onPlay,
                onRetry = onRetry
            )
        }
    }

    // ------------------------------------------------------------------ entry point

    /**
     * The contract requires that opening a screen "should place TalkBack focus at a meaningful
     * starting point, normally the screen heading", and that it "must not unpredictably land on
     * bottom navigation, mini-player, decorative content, arbitrary coordinates".
     */
    @Test
    fun namesTheScreenSoEntryAnnouncesWhereTheUserIs() {
        showNote()

        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Note"))
            .assertExists()
    }

    @Test
    fun exposesTheDocumentTitleAsTheFirstHeading() {
        showNote()

        compose.onNodeWithText("Accessibility Fixture").assert(A11y.hasHeading())
        compose.onAllNodesWithText("Accessibility Fixture").assertCountEquals(1)
    }

    // ------------------------------------------------------------------ controls

    @Test
    fun thePrimaryActionIsLabelledAndClickable() {
        var played = 0
        showNote(onPlay = { played++ })

        // assertIsDisplayed because reachability is part of the requirement: a primary action a
        // listener cannot reach is not a primary action.
        //
        // **Not** performScrollTo, which was tried and is wrong here: these controls are siblings of the
        // scrolling content, not inside it, so they have no ancestor with a scroll action and
        // performScrollTo throws "Semantic Node has no parent layout with a Scroll SemanticsAction". The
        // intermittent "is not displayed" failure this assertion produced is the device's display state,
        // so it is fixed in `tools/run-accessibility-tests.ps1` by keeping the screen on for the run.
        compose.onNodeWithText("Play").assertIsDisplayed()

        // Invoked through the semantics action rather than a synthetic tap. That is what an
        // accessibility service actually performs, and unlike a synthesised touch it does not depend
        // on the device's window accepting input - which made the touch version fail intermittently
        // on this real device with the click silently dropped.
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, played)
    }

    /** A "button" that announces only "button" is the failure mode this guards against. */
    @Test
    fun noClickableNodeIsUnlabelled() {
        showNote()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY, useUnmergedTree = false))
    }

    // ------------------------------------------------------------------ reading order and noise

    @Test
    fun rendersTheDocumentInReadingOrder() {
        showNote()

        A11y.assertReadingOrder(
            "Accessibility Fixture",
            "Intro paragraph.",
            "Bullet item.",
            "Unchecked task.",
            "Primary Heading",
            "Quoted text.",
            "val answer = 42",
            "Secondary Heading",
            collection = compose.onAllNodes(A11y.ANY, useUnmergedTree = false)
        )
    }

    /**
     * A bullet read aloud as "bullet" and a checkbox read as "ballot box" are noise between the user
     * and the content, so the glyphs are cleared from the semantics tree while staying visible.
     */
    @Test
    fun decorativeMarkersAreNotAnnounced() {
        showNote()

        compose.onAllNodesWithText("•", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("☐", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("☑", useUnmergedTree = true).assertCountEquals(0)
    }

    /**
     * The to-do's state is a *state description* rather than part of its text, so TalkBack can report
     * it on demand without it interrupting the item's content. Whether it is announced at all is a
     * policy the contract leaves to the Player (D10); that it is *available* is the mapping guarantee.
     */
    @Test
    fun todoStateIsAvailableWithoutBeingFoldedIntoTheText() {
        showNote()

        compose.onNodeWithText("Unchecked task.").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not checked")
        )
        compose.onNodeWithText("Checked task.").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Checked")
        )
    }

    /**
     * A heading is both a section title and a spoken chunk (D3), so a screen that rendered both the
     * section title and the heading chunk would announce it twice.
     */
    @Test
    fun aHeadingIsRenderedExactlyOnce() {
        showNote()

        compose.onAllNodesWithText("Primary Heading").assertCountEquals(1)
        compose.onAllNodesWithText("Secondary Heading").assertCountEquals(1)
    }

    /** The language label is informative, so unlike the decorative glyphs it is exposed. */
    @Test
    fun codeLanguageIsExposedAlongsideTheCode() {
        showNote()

        // assertExists rather than assertIsDisplayed: the Note is a LazyColumn, so an item below the
        // viewport is not composed at all. The claim worth testing is that the language is in the
        // semantics tree alongside the source, not that this particular fixture fits on one screen.
        compose.onNodeWithText("kotlin").assertExists()
        compose.onNodeWithText("val answer = 42").assertExists()
    }

    // ------------------------------------------------------------------ load states

    /**
     * "Loading …" is not equivalent to "no matching content", and the contract adds that loading must
     * not be communicated only through animation.
     */
    @Test
    fun loadingIsStatedInTextRatherThanOnlyShownAsMotion() {
        showNote(loadState = DocumentLoadState.Loading)

        compose.onNodeWithText("Loading Notes…").assertExists()
    }

    @Test
    fun anEmptyNoteSaysSoInsteadOfRenderingNothing() {
        val empty = ReaderDocument(pageId = "empty", title = "Empty Note")

        compose.setContent {
            NoteScreen(
                document = empty,
                loadState = DocumentLoadState.Loaded(empty),
                onPlay = {},
                onRetry = {}
            )
        }

        compose.onNodeWithText("This Note has no readable content.").assertExists()
    }

    /**
     * The UI contract: "Permission problems must not be collapsed into generic network errors", and the
     * error must be surfaced where recovery is meaningful. A revoked credential cannot be fixed by
     * pressing Retry, so offering it would be a lie.
     */
    @Test
    fun aPermissionFailureIsNamedAndDoesNotOfferRetry() {
        showNote(
            loadState = DocumentLoadState.Error(
                category = ReaderErrorCategory.AUTHORIZATION_REQUIRED,
                message = ReaderErrorCategory.AUTHORIZATION_REQUIRED.message
            )
        )

        compose
            .onNodeWithText(
                "Could not load this Note. ${ReaderErrorCategory.AUTHORIZATION_REQUIRED.message}",
                substring = true
            )
            .assertExists()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun aTransientFailureOffersRetryAndRetryWorks() {
        var retries = 0
        showNote(
            loadState = DocumentLoadState.Error(
                category = ReaderErrorCategory.NETWORK_UNAVAILABLE,
                message = ReaderErrorCategory.NETWORK_UNAVAILABLE.message
            ),
            onRetry = { retries++ }
        )

        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, retries)
    }

    /** Errors require concise spoken feedback, so the error region is announced. */
    @Test
    fun anErrorIsALiveRegionSoItIsSpokenWhenItAppears() {
        showNote(
            loadState = DocumentLoadState.Error(
                category = ReaderErrorCategory.SERVER_ERROR,
                message = ReaderErrorCategory.SERVER_ERROR.message
            )
        )

        compose.onNodeWithText(ReaderErrorCategory.SERVER_ERROR.message, substring = true)
            .assert(A11y.hasLiveRegion())
    }

    /**
     * The Note is for manual reading, and moving focus through it "must not change Player progress".
     * Structure that guarantees this: the screen takes display state and two callbacks, neither of
     * which can move a reading position.
     */
    @Test
    fun nothingOnTheNoteStartsPlaybackOnItsOwn() {
        var played = 0
        showNote(onPlay = { played++ })

        compose.onAllNodes(A11y.ANY, useUnmergedTree = false).fetchSemanticsNodes()

        assertEquals("merely rendering the Note must not start playback", 0, played)
    }

    @Test
    fun theNoteDoesNotUseLiveRegionsWhileReading() {
        showNote()

        // The document body must stay quiet: a live region here would make TalkBack read the document
        // over the reader's own TTS. Only the loading and error states are allowed to announce.
        compose.onNodeWithText("Intro paragraph.").assert(A11y.hasNoLiveRegion())
        compose.onNodeWithText("Accessibility Fixture").assert(A11y.hasNoLiveRegion())
    }

    @Test
    fun loadingAnnouncementIsPoliteRatherThanInterrupting() {
        showNote(loadState = DocumentLoadState.Loading)

        compose.onNodeWithText("Loading Notes…").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        )
    }
}
