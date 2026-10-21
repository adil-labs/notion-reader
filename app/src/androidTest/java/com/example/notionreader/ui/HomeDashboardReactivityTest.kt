package com.example.notionreader.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueuedNote
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeDashboardReactivityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeDashboardRecomposesWhenActiveQueueAdvances() {
        val harness = ShellHarness()
        val app = harness.app()
        val demo1 = harness.document("p1", "Demo 1")
        val demo2 = harness.document("p2", "Demo 2")

        val queue = ActiveQueue.snapshotOf(
            listOf(QueuedNote("p1", "Demo 1"), QueuedNote("p2", "Demo 2")),
            QueueOrigin.BrowseResults
        )
        app.session.start(queue)
        app.session.supplyDocument(demo1)
        app.session.pause()

        compose.setContent {
            HomeDashboard(
                app = app,
                onOpenNote = {},
                onContinue = {},
                onReplay = {}
            )
        }

        // Initially displays Note 1 of 2
        compose.onNodeWithText("Note 1 of 2", substring = true).assertIsDisplayed()

        // Advance queue state without changing playback state
        app.session.onCurrentNoteFinished()
        app.session.supplyDocument(demo2)
        app.session.pause()

        // HomeDashboard must collect queueState, so UI recomposes to Note 2 of 2
        compose.onNodeWithText("Note 2 of 2", substring = true).assertIsDisplayed()
    }
}
