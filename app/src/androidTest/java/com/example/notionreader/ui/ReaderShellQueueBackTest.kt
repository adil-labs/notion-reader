package com.example.notionreader.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.ui.A11y.hasPaneTitle
import org.junit.Rule
import org.junit.Test

/**
 * Back, with the Player's queue view open.
 *
 * ## Why this is its own class with its own rule
 *
 * The queue view registers a `BackHandler` while the shell registers one for the whole application, so
 * pressing Back with the view open is decided by **registration order** — the innermost handler wins because
 * it was added last. That is the framework's rule and it is the behaviour a listener wants, but it is a
 * fragile thing to depend on without saying so, and nothing else in the suite covers it.
 *
 * Dispatching a real Back press needs the Activity — `onBackPressedDispatcher` belongs to it — so this class
 * uses `createAndroidComposeRule` rather than the `createComposeRule` the other shell tests use. It is kept
 * separate rather than switching the whole shell suite over, because changing the rule for a dozen passing
 * tests in order to add one is a risk with no corresponding benefit.
 *
 * One of the two assertions is a *prohibition* straight out of the contract: Back "must never implicitly
 * mean: Pause, Stop, Previous section, Previous note". Closing a view can only satisfy that by having no way
 * to reach playback, which is why the view is given no playback callback at all.
 */
class ReaderShellQueueBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val harness = ShellHarness()

    @Test
    fun backClosesTheQueueViewAndStaysOnThePlayer() {
        val app = harness.app(entries = (1..3).map { harness.entry(it) }, withSession = true)

        compose.setContent {
            ReaderShell(
                app = app,
                speechMessage = null,
                onOpenNote = { },
                onRetryNoteLoad = { },
                onRetryBrowseIndex = { },
                onContinueSession = { },
                onReplayCompleted = { }
            )
        }

        // `Play all` opened the Player over a three-Note queue.
        compose.onNode(hasPaneTitle("Player")).assertExists()

        compose.onNodeWithText("Queue").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasPaneTitle("Active Queue")).assertExists()

        pressBack()

        // Back closed the view. It did not leave the Player, which is what the shell's own handler would
        // have done — so this is the assertion that the inner handler wins.
        compose.onNode(hasPaneTitle("Player")).assertExists()
        compose.onNode(hasPaneTitle("Active Queue")).assertDoesNotExist()
    }

    @Test
    fun backFromThePlayerItselfStillNavigates() {
        val app = harness.app(entries = (1..3).map { harness.entry(it) }, withSession = true)

        compose.setContent {
            ReaderShell(
                app = app,
                speechMessage = null,
                onOpenNote = { },
                onRetryNoteLoad = { },
                onRetryBrowseIndex = { },
                onContinueSession = { },
                onReplayCompleted = { }
            )
        }

        // Closing the view must not consume Back for the rest of the session: with the view shut, Back is
        // the shell's again and returns to the tab the Player was opened over.
        compose.onNodeWithText("Queue").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        pressBack()
        compose.onNode(hasPaneTitle("Player")).assertExists()

        pressBack()

        compose.onNode(hasPaneTitle("Home")).assertExists()
    }

    /** Dispatches a real Back press, as the system would. */
    private fun pressBack() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }
}
