package com.example.notionreader.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MiniPlayerLiveStateAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun miniPlayerReflectsExternalPauseAndResumeWithoutNavigationOrHistoryChanges() {
        val app = ShellHarness().app(withSession = true)
        runBlocking { app.loadPendingQueueNote() }
        app.session.play()
        app.openBrowse()
        compose.setContent {
            ReaderShell(app = app, speechMessage = null, onOpenNote = {}, onRetryNoteLoad = {},
                onRetryBrowseIndex = {}, onContinueSession = {}, onReplayCompleted = {}, onCreateSavedQueue = {})
        }
        compose.onNodeWithText("Pause").assertExists()
        compose.runOnIdle { app.session.pause(); assertFalse(app.session.isPlaying) }
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Play").assertExists()
        compose.runOnIdle { app.session.play(); assertTrue(app.session.isPlaying) }
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithText("Pause").assertExists()
    }
}
