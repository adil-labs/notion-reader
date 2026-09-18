package com.example.notionreader.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.ui.A11y.hasPaneTitle
import com.example.notionreader.ui.A11y.hasTraversalGroup
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The application shell: the tab bar, the mini-player, Back, and where focus goes when it is used.
 *
 * ## Why this file exists
 *
 * Until this round the shell was written inline in `MainActivity.onCreate`, which made it the one surface
 * in the application with **no instrumentation of any kind**. Its correctness was established by
 * `tools/probe-*.ps1` — a scripted tap sequence whose accessibility dump a human read at the end of a
 * round — and `docs/decisions.md` §19.9 recorded the cost: Browse's result-position fix was four lines of
 * Compose runtime behaviour "verified by construction, not by a test", because there was nowhere to
 * assert it. Three rounds in a row closed with a variant of that sentence.
 *
 * Composing the shell out into `ui/ReaderShell.kt` is what makes these possible, and it is why the
 * enabling refactor came with the tests rather than before them.
 *
 * ## The four things a shell owns, and nothing else does
 *
 * - **Which surfaces are destinations and which are tabs.** The UI contract is explicit — "The Player is
 *   not a fifth bottom-navigation destination" — and the test asserts the count *while the Player is on
 *   screen*, so a tab quietly added for it would be caught rather than counted on a screen where it
 *   happens not to appear.
 * - **Whether the mini-player is on screen.** D25's presence rule, asked as a question about focus stops
 *   rather than about a boolean: "is it two focus stops rather than one or six?" is the check
 *   `docs/decisions.md` §12.4 named, and it needs a rendered tree to answer.
 * - **Where Back goes.** The contract's Back table, asserted against the rendered destination rather than
 *   against `ReaderNavigator` alone — which `ReaderNavigatorTest` already covers, and which cannot see
 *   whether the shell listened.
 * - **Where focus lands.** The contract's "returning with Back restores the previous meaningful focus
 *   target", whose worked example is this file's `backPutsFocusOnTheResultTheListenerOpened`.
 *
 * ## What it cannot check, stated rather than implied
 *
 * Focus asserted here is Compose's **input** focus, which is what `assertIsFocused` can see. Whether
 * TalkBack's speech focus follows a programmatic request is not in the semantics tree, and it is the same
 * gap the integration spec's oldest open gate names: the whole flow performed with TalkBack enabled and
 * *heard*. `docs/decisions.md` §21 records it rather than implying this closes it.
 */
class ReaderShellAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val harness = ShellHarness()

    private fun show(app: ReaderApp, onOpenNote: ((String) -> Unit)? = null) {
        compose.setContent {
            ReaderShell(
                app = app,
                speechMessage = null,
                // Routed through the real application object, because "opening a result lands on the Note
                // screen" is a navigation property and a lambda that only recorded the page id would not
                // exercise it. `runBlocking` is honest here: the loader is a fixture that completes
                // immediately, so there is nothing to wait for.
                onOpenNote = onOpenNote ?: { pageId -> runBlocking { app.openNote(pageId) } },
                onRetryNoteLoad = { },
                onRetryBrowseIndex = { },
                onContinueSession = { },
                onReplayCompleted = { }
            )
        }
    }

    /** The destination on screen, as a listener would experience arriving at it. */
    private fun assertOnScreen(paneTitle: String) {
        compose.onNode(hasPaneTitle(paneTitle)).assertExists()
    }

    /**
     * Waits for focus to arrive on the named control, then asserts it did.
     *
     * ## Why this is not `assertIsFocused` after a `waitForIdle`
     *
     * Because restoring focus is a *frame-based* operation. `restoreFocusOn` requests focus from a
     * `LaunchedEffect` that waits for a frame first, since a request issued before the target is attached
     * and placed is silently dropped — and `ComposeTestRule` installs a **test frame clock**, so those
     * frames only flow when the test asks for them. `waitForIdle` settles recomposition; it does not
     * guarantee a pending frame callback has run. Asserting straight afterwards therefore asks about a
     * request that has not been made yet, which is what made all four of these fail on the device while
     * the node dump showed the right node with the right bounds.
     *
     * `waitUntil` advances the clock (with the default `autoAdvance`), so this is the documented way to let
     * a frame-driven mechanism run. It is a fix on the test side and is worth being explicit about: the
     * production behaviour is correct on a device, where frames flow continuously, and the earlier
     * "verified by construction" claim for this code is what made that fact invisible for a round.
     *
     * A failure here is still a failure — the timeout elapses and the assertion reports what it found — it
     * just no longer races the mechanism it is testing.
     */
    private fun awaitFocusOn(label: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isFocused() and hasText(label, substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(label, substring = true).assertIsFocused()
    }

    private fun isATab(): SemanticsMatcher = SemanticsMatcher("is a tab") { node ->
        node.config.contains(SemanticsProperties.Role) &&
            node.config[SemanticsProperties.Role] == Role.Tab
    }

    /**
     * The mini-player's own region.
     *
     * Two facts are needed and neither is sufficient alone. `isTraversalGroup` is Compose's spelling of
     * "logically grouped region", but a Material3 `NavigationBar` is also a traversal group, so counting
     * by that alone would count the tabs. The session title is what only this region contains, and it is
     * *also* the right scope semantically: the point of the grouping is that this region is about one
     * session.
     */
    private fun theMiniPlayer(sessionTitle: String): SemanticsMatcher =
        hasTraversalGroup() and hasAnyDescendant(hasText(sessionTitle, substring = true))

    // ------------------------------------------------------------------ which surfaces are tabs

    @Test
    fun theThreePopulatedDestinationsAreTabs() {
        show(harness.app())

        compose.onAllNodes(isATab()).assertCountEquals(3)
    }

    @Test
    fun thePlayerIsNotATabEvenWhileItIsOnScreen() {
        val app = harness.app(entries = listOf(harness.entry(1)), withSession = true)

        // `Play all` opened the Player, so the assertion is made with the Player visible — which is the
        // only state in which "is the Player a fourth tab?" is a real question. It is not, and the tab bar
        // still offers three destinations.
        show(app)

        assertOnScreen("Player")
        compose.onAllNodes(isATab()).assertCountEquals(3)
    }

    @Test
    fun theNoteIsNotATabEither() {
        val app = harness.app(entries = listOf(harness.entry(1)))
        app.openBrowse()
        show(app)

        compose.onNodeWithText("Result 1").performSemanticsAction(SemanticsActions.OnClick)

        assertOnScreen("Note")
        compose.onAllNodes(isATab()).assertCountEquals(3)
    }

    // ------------------------------------------------------------------ the mini-player

    @Test
    fun theMiniPlayerIsExactlyTwoFocusStops() {
        val app = harness.app(entries = listOf(harness.entry(1)), withSession = true)
        app.openBrowse()
        show(app)

        assertOnScreen("Browse")

        // Not one (the region would be a single button and lose the separate way to pause) and not six
        // (the title, the position, the state, the control, and the wrapper nodes). The contract's useful
        // announcement is "Current Note title. Playing. Open Player." with "Play/Pause ... an explicit
        // accessible control".
        compose.onAllNodes(hasAnyAncestor(theMiniPlayer("Alpha")) and hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun theMiniPlayerReflectsTheSessionRatherThanReadingTheWholeQueue() {
        val app = harness.app(entries = listOf(harness.entry(1)), withSession = true)
        app.openBrowse()
        show(app)

        // "The mini-player should not read the complete queue state every time focus reaches it." The queue
        // holds two Notes; the region names the current one.
        compose.onNode(hasAnyDescendant(hasText("Alpha", substring = true)) and hasTraversalGroup())
            .assertExists()
        compose.onAllNodes(hasAnyAncestor(theMiniPlayer("Alpha")) and hasText("Beta", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun theMiniPlayerIsAbsentOnThePlayer() {
        val app = harness.app(entries = listOf(harness.entry(1)), withSession = true)

        // The Player's full surface already owns the same controls, so a second copy would be two ways to
        // pause one session.
        show(app)

        assertOnScreen("Player")
        compose.onAllNodes(theMiniPlayer("Alpha")).assertCountEquals(0)
    }

    @Test
    fun theMiniPlayerIsAbsentWhenThereIsNothingToResume() {
        // D25: a session is surfaced once playback has begun. Nothing has been played here — Browse has
        // been loaded and nothing else — so there is no session, and a mini-player describing the demo
        // document would be describing a Note the listener never chose.
        show(harness.app(entries = listOf(harness.entry(1))))

        assertOnScreen("Home")
        compose.onAllNodes(hasText("Play")).assertCountEquals(0)
        compose.onAllNodes(hasText("Pause")).assertCountEquals(0)
    }

    // ------------------------------------------------------------------ nothing takes focus on entry

    @Test
    fun nothingStealsFocusOnEntry() {
        val app = harness.app(entries = listOf(harness.entry(1)), withSession = true)
        app.openBrowse()

        show(app)

        // "Opening a full screen should place TalkBack focus at a meaningful starting point ... It must not
        // unpredictably land on: bottom navigation; mini-player; decorative content." Compose's default is
        // that nothing is focused, which is the correct behaviour here: the listener's own swipe decides
        // where to start rather than the screen deciding for them. The assertion is that it stays that way,
        // so a `requestFocus()` added to the bar or the mini-player would break it.
        compose.onAllNodes(isFocused()).assertCountEquals(0)
    }

    // ------------------------------------------------------------------ Back

    @Test
    fun backFromBrowseReturnsToHome() {
        val app = harness.app(entries = listOf(harness.entry(1)))
        app.openBrowse()
        show(app)

        assertOnScreen("Browse")

        app.back()

        // Product decision (§12.5): the bottom-navigation convention, rather than finishing the app.
        assertOnScreen("Home")
    }

    @Test
    fun backFromANoteReturnsToTheTabItWasOpenedFrom() {
        val app = harness.app(entries = listOf(harness.entry(1)))
        app.openBrowse()
        show(app)

        compose.onNodeWithText("Result 1").performSemanticsAction(SemanticsActions.OnClick)
        assertOnScreen("Note")

        app.back()

        assertOnScreen("Browse")
    }

    @Test
    fun backFromThePlayerReturnsToTheNoteThatStartedIt() {
        val app = harness.app(entries = listOf(harness.entry(1)))
        app.openBrowse()
        show(app)

        compose.onNodeWithText("Result 1").performSemanticsAction(SemanticsActions.OnClick)
        assertOnScreen("Note")

        // Play on the Note screen: "creates a new Active Queue containing only this Note; opens Player".
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)
        assertOnScreen("Player")

        app.back()

        // The Note, not Browse: Play is reachable from the Note screen, so "the previous visual context" is
        // not always a tab — which is why navigation models a list of stops rather than one field.
        assertOnScreen("Note")

        app.back()
        assertOnScreen("Browse")
    }

    // ------------------------------------------------------------------ focus restoration (H)

    @Test
    fun backPutsFocusOnTheResultTheListenerOpened() {
        val app = harness.app(entries = (1..5).map { harness.entry(it) })
        app.openBrowse()
        show(app)

        compose.onNodeWithText("Result 3").performSemanticsAction(SemanticsActions.OnClick)
        assertOnScreen("Note")

        app.back()

        // The contract's worked example: "Browse result 7 -> Note -> Back should preferably restore focus
        // to result 7". Before this round only the *scroll position* came back, so a listener landed
        // wherever their finger last was rather than on the row they left from.
        awaitFocusOn("Result 3")
    }

    @Test
    fun backFromThePlayerPutsFocusOnThePlayControlThatStartedIt() {
        val app = harness.app(entries = listOf(harness.entry(1)))
        app.openBrowse()
        show(app)

        compose.onNodeWithText("Result 1").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)
        assertOnScreen("Player")

        app.back()

        // A named id rather than the Note's page id, because the *button* is what was pressed and the Play
        // buttons of two different Notes are the same control.
        awaitFocusOn("Play")
    }

    @Test
    fun backFromPlayAllPutsFocusOnTheSetLevelControl() {
        val app = harness.app(entries = (1..3).map { harness.entry(it) })
        app.openBrowse()
        show(app)

        compose.onNodeWithText("Play all").performSemanticsAction(SemanticsActions.OnClick)
        assertOnScreen("Player")

        app.back()

        // `Play all` acts on the whole result set and has no content identity, so the control is what is
        // remembered. The alternative — no hint at all — would drop the listener at the top of a list they
        // had deliberately left from its foot.
        awaitFocusOn("Play all")
    }

    @Test
    fun aFocusHintIsNotReissuedWhenTheListenerReturnsSomewhereElseFirst() {
        val app = harness.app(entries = (1..5).map { harness.entry(it) })
        app.openBrowse()
        show(app)

        compose.onNodeWithText("Result 3").performSemanticsAction(SemanticsActions.OnClick)
        app.back()
        awaitFocusOn("Result 3")

        // Now open a *different* row. The hint from the previous trip must not still be pending, or the
        // unrelated return would pull focus back to Result 3 — the "unpredictable focus movement" the
        // contract's rule exists to prevent.
        compose.onNodeWithText("Result 5").performSemanticsAction(SemanticsActions.OnClick)
        app.back()

        awaitFocusOn("Result 5")
    }

    // ------------------------------------------------------------------ Browse's result position (§19.9)

    @Test
    fun browseKeepsItsResultPositionAcrossATripToANote() {
        val app = harness.app(entries = (1..40).map { harness.entry(it) })
        app.openBrowse()
        show(app)

        // Scroll to the foot of the list, so the first result is no longer composed. That is what makes
        // "did the position survive?" answerable without asking the device where anything is: a `LazyColumn`
        // disposes what is far outside its window, so a reset list would have Result 1 back in the tree.
        compose.onNode(hasScrollAction()).performScrollToIndex(39)
        compose.onNodeWithText("Result 40").assertExists()
        compose.onNodeWithText("Result 1").assertDoesNotExist()

        compose.onNodeWithText("Result 40").performSemanticsAction(SemanticsActions.OnClick)
        assertOnScreen("Note")

        app.back()

        // This is the assertion §19.9 said did not exist. The fix was four lines of Compose runtime
        // behaviour, and until this round it was verified by construction only because the shell was not
        // testable.
        compose.onNodeWithText("Result 40").assertExists()
        compose.onNodeWithText("Result 1").assertDoesNotExist()
    }
}
