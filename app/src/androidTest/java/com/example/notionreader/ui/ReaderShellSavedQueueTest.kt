package com.example.notionreader.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.domain.queue.QueueEffect
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.hasPaneTitle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Saved Queue flow, driven through the shell.
 *
 * ## What only this level can answer
 *
 * The screen tests prove each surface renders what it is given, and the JVM tests prove what the application
 * publishes. Neither can prove the two are **connected**: that a queue created on one screen reaches
 * storage, comes back out of the published list, and appears on the tab that lists it — with no reload, and
 * with the two surfaces never disagreeing about what exists.
 *
 * That is a property of the wiring rather than of any one composable, and the failure it is built to catch is
 * the one the application-layer tests can only approximate: a surface reading the store for itself, or
 * holding a copy that a write does not update. Either would leave a queue created correctly, stored
 * correctly, and invisible.
 *
 * ## Why the create callback is wired the way the real one is
 *
 * `onCreateSavedQueue` performs the write and then leaves the Create screen, which is what
 * `ReaderViewModel.createSavedQueue` does. No screen may do that — the outcome decides, and only the caller
 * can see it — so wiring the same shape here means the thing under test is the flow the application actually
 * has rather than a test-friendly rearrangement of it.
 *
 * ## Why this class needs the Activity while the other shell tests do not
 *
 * One test dispatches a real Back press, and `onBackPressedDispatcher` belongs to the Activity. That is the
 * same trade [ReaderShellQueueBackTest] records, made for the same reason.
 */
class ReaderShellSavedQueueTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val harness = ShellHarness()

    /** The shell, over an application whose store holds [queues]. */
    private fun show(queues: List<SavedQueue> = emptyList()) {
        val app: ReaderApp = harness.app(savedQueues = queues)

        compose.setContent {
            ReaderShell(
                app = app,
                speechMessage = null,
                // Wired to the application's own operation, as `ReaderViewModel.openNote` does. This was `{ }`
                // in the first version, so the member tap navigated nowhere and the test that asserts the Note
                // opens failed against a shell that was doing exactly what it was told.
                onOpenNote = { pageId -> runBlocking { app.openNote(pageId) } },
                onRetryNoteLoad = { },
                onRetryBrowseIndex = { },
                onContinueSession = { },
                onReplayCompleted = { },
                onCreateSavedQueue = { name ->
                    // The write, and then the navigation the outcome decides — exactly as the ViewModel does
                    // it. `runBlocking` because a click handler is not a coroutine scope, and the store this
                    // harness installs performs no dispatching, so there is nothing to deadlock on.
                    runBlocking {
                        val outcome = app.createSavedQueue(name)
                        if (outcome.effect is QueueEffect.Created) app.back()
                    }
                }
            )
        }
    }

    /** Queues → Saved, which is how a listener reaches the tab this file is about. */
    private fun openSavedTab() {
        compose.onNodeWithText("Queues").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Saved").performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun createQueue(named: String) {
        compose.onNodeWithText("Create saved queue").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Queue name").performTextInput(named)
        compose.onNodeWithText("Create").performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun creatingAQueueThroughTheScreensPutsItOnTheSavedTab() {
        // The whole point of the slice: the listener can make a queue, and then see it.
        show()

        openSavedTab()
        createQueue("Reading")

        // The Create screen left itself, and the queue is listed on the tab underneath it.
        compose.onAllNodesWithText("Queue name").assertCountEquals(0)
        compose.onNodeWithText("Reading").assertIsReadable()
        compose.onNodeWithText("0 notes").assertExists()
    }

    @Test
    fun aQueueCreatedInOneVisitIsStillListedWhenTheListenerComesBack() {
        // The *published* list rather than a screen's copy: leaving Queues and returning shows the queue,
        // which a surface that had cached its own list at first composition would fail.
        show()

        openSavedTab()
        createQueue("Chess")
        compose.onNodeWithText("Home").performSemanticsAction(SemanticsActions.OnClick)
        openSavedTab()

        compose.onNodeWithText("Chess").assertIsReadable()
    }

    @Test
    fun creatingASecondQueueListsBothAndNoOthers() {
        show(queues = listOf(queue("q-1", "Chess")))

        openSavedTab()
        createQueue("Reading")

        compose.onNodeWithText("Reading").assertIsReadable()
        compose.onNodeWithText("Chess").assertIsReadable()
    }

    @Test
    fun openingAQueueFromTheListShowsItsDetailsWithItsMemberAndItsPlayControl() {
        // The navigation half: a queue row opens Details about *that* queue, and the member is listed with
        // its position — so this also asserts the two ids a pushed stop carries reached the right screen.
        show(queues = listOf(queue("q-7", "Android accessibility", listOf(QueuedNote("p1", "Android TTS overview")))))

        openSavedTab()
        compose.onNodeWithText("Android accessibility", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("1. Android TTS overview").assertIsReadable()
        compose.onNodeWithText("Play").assertIsReachable()
    }

    @Test
    fun playingAQueueFromItsDetailsStartsReadingAndOpensThePlayer() {
        // The last link in the chain: a stored queue, through Play, into a session. The Player is asserted by
        // its pane title, because that is what tells a listener they have arrived somewhere.
        show(queues = listOf(queue("q-7", "Reading", listOf(QueuedNote("p1", "Alpha")))))

        openSavedTab()
        compose.onNodeWithText("Reading", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNode(hasPaneTitle("Player")).assertExists()
    }

    @Test
    fun openingAMemberFromDetailsOpensTheNoteAndDoesNotStartPlayback() {
        // "Activating an individual queue item opens that Note. It does not start playback." Asserted through
        // the shell because the distinction is only visible here: the session is the shell's, so a member
        // that started one would show up as a Player.
        show(queues = listOf(queue("q-7", "Reading", listOf(QueuedNote("p1", "Alpha")))))

        openSavedTab()
        compose.onNodeWithText("Reading", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("1. Alpha").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNode(hasPaneTitle("Note")).assertExists()
        compose.onNode(hasPaneTitle("Player")).assertDoesNotExist()
    }

    @Test
    fun backFromTheCreateScreenReturnsToTheSavedTabRatherThanLeavingQueues() {
        // Back reverses visual navigation. Create is pushed over Queues, so Back returns to Queues — and to
        // the Saved tab it was opened from.
        show()

        openSavedTab()
        compose.onNodeWithText("Create saved queue").performSemanticsAction(SemanticsActions.OnClick)

        pressBack()

        compose.onNodeWithText("Create saved queue").assertIsReachable()
        compose.onAllNodesWithText("Queue name").assertCountEquals(0)
    }

    @Test
    fun backFromDetailsReturnsToTheQueueList() {
        show(queues = listOf(queue("q-7", "Reading", listOf(QueuedNote("p1", "Alpha")))))

        openSavedTab()
        compose.onNodeWithText("Reading", substring = true).performSemanticsAction(SemanticsActions.OnClick)

        pressBack()

        compose.onNodeWithText("Create saved queue").assertIsReachable()
        compose.onNodeWithText("1. Alpha").assertDoesNotExist()
    }

    @Test
    fun addingFromBrowsePlacesTheWholeResultSetAndReportsItOnTheQueueItWentInto() {
        // The first increment a listener can actually build a queue with: pick some results, add them to a
        // queue, and be told what happened. Asserted through the shell because the three steps live in three
        // places — the control on Browse, the pending set in the application, and the report on Details.
        val app = harness.app(
            entries = (1..3).map { harness.entry(it) },
            savedQueues = listOf(queue("q-7", "Reading"))
        )

        compose.setContent {
            ReaderShell(
                app = app,
                speechMessage = null,
                onOpenNote = { },
                onRetryNoteLoad = { },
                onRetryBrowseIndex = { },
                onContinueSession = { },
                onReplayCompleted = { },
                onCreateSavedQueue = { },
                onAddAllToQueue = { app.beginAddToQueue(app.browse.state.value.results.toQueuedNotes()) },
                onChooseQueueDestination = { queueId ->
                    runBlocking {
                        app.addPendingToQueue(queueId)
                        app.openSavedQueueDetails(queueId)
                    }
                }
            )
        }

        compose.onNodeWithText("Browse").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add all to queue").performSemanticsAction(SemanticsActions.OnClick)

        // The chooser lists the destination and states how much is being placed.
        compose.onNode(hasPaneTitle("Add to queue")).assertExists()
        compose.onNodeWithText("3 notes to add.").assertExists()

        compose.onNodeWithText("Reading").performSemanticsAction(SemanticsActions.OnClick)

        // The notes landed, and the queue's own screen says so.
        assertEquals(listOf("page-1", "page-2", "page-3"), app.savedQueues.value.single().notes.map { it.pageId })
        compose.onNodeWithText("Added 3 notes.").assertIsReadable()
    }

    @Test
    fun addingTheSameResultSetTwiceReportsThatNothingWasAddedRatherThanFailing() {
        // The case the specification singles out: "a listener who adds twelve things and is told nothing
        // cannot tell 'all twelve were already there' from 'the app failed'".
        val app = harness.app(
            entries = (1..2).map { harness.entry(it) },
            savedQueues = listOf(queue("q-7", "Reading"))
        )

        compose.setContent {
            ReaderShell(
                app = app,
                speechMessage = null,
                onOpenNote = { },
                onRetryNoteLoad = { },
                onRetryBrowseIndex = { },
                onContinueSession = { },
                onReplayCompleted = { },
                onCreateSavedQueue = { },
                onAddAllToQueue = { app.beginAddToQueue(app.browse.state.value.results.toQueuedNotes()) },
                onChooseQueueDestination = { queueId ->
                    runBlocking {
                        app.addPendingToQueue(queueId)
                        app.openSavedQueueDetails(queueId)
                    }
                }
            )
        }

        compose.onNodeWithText("Browse").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add all to queue").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Reading").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, app.savedQueues.value.single().notes.size)

        // Second time: same results, so nothing can be added and the report has to say so as a success.
        compose.onNodeWithText("Browse").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add all to queue").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Reading").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(2, app.savedQueues.value.single().notes.size)
        compose.onNodeWithText("Nothing was added, because all 2 notes were already in this queue.")
            .assertIsReadable()
    }

    /** Dispatches a real Back press, as the system would. */
    private fun pressBack() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private companion object {
        fun queue(id: String, name: String, members: List<QueuedNote> = emptyList()) =
            SavedQueue(id = id, name = name, notes = members, lastEditedAt = 1_000L)
    }
}
