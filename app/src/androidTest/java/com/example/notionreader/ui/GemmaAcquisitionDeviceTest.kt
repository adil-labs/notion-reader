package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.notionreader.ReaderApplication
import com.example.notionreader.data.discovery.GemmaModelPack
import com.example.notionreader.data.discovery.LocalDiscoveryModelManager
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in device acquisition check: downloads the real optional pack over the default network policy. */
class GemmaAcquisitionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitDownloadInstallsThePinnedGemmaPackThroughTheRealAppSettings() {
        org.junit.Assume.assumeTrue("Production acquisition requires explicit wrapper -DeployModel",
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("allowModelDeployment") == "true")
        val application = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val app = application.host.app
        val model = app.discoveryModel as LocalDiscoveryModelManager
        val beforeSession = app.session.state.value
        assertFalse("Do not run acquisition over an active listening session", beforeSession.isPlaying)
        assertEquals(206696682L, model.state.value.totalBytes)
        assertTrue(model.state.value.description.startsWith("EmbeddingGemma 2 INT4"))
        app.openSettingsDiscovery()
        compose.setContent { ReaderShell(app, null, { runBlocking { app.openNote(it) } }, onRetryNoteLoad = {},
            onRetryBrowseIndex = {}, onContinueSession = {}, onReplayCompleted = {}, onCreateSavedQueue = {}) }
        compose.onNodeWithText(GemmaModelPack.description).assertIsReadable()
        val alreadyInstalled = model.state.value.installed
        if (!alreadyInstalled) {
            compose.onNodeWithText("Download model").performScrollTo().assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(300_000) { model.state.value.installed || model.state.value.message != null }
        }
        assertTrue("Actual acquisition failed: ${model.state.value.message}", model.state.value.installed)
        runBlocking { model.checkInstalled() }
        assertTrue(model.state.value.installed)
        compose.onNodeWithText("Delete model and semantic index").performScrollTo().assertIsReachable()
        assertEquals(beforeSession, app.session.state.value)
        File(application.getExternalFilesDir(null), "gemma-acquisition-device.txt").writeText(
            "device=${android.os.Build.MODEL}\napi=${android.os.Build.VERSION.SDK_INT}\nrevision=${GemmaModelPack.revision}\nbytes=${model.state.value.totalBytes}\nalreadyInstalled=$alreadyInstalled\nverified=true\n")
    }
}
