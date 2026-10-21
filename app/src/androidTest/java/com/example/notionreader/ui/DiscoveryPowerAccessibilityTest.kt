package com.example.notionreader.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.ReaderApplication
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.settings.DiscoverySettingsScreen
import com.example.notionreader.ui.settings.DiscoverySettingsUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real system battery broadcasts plus passive TalkBack semantics; run on the test emulator. */
class DiscoveryPowerAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private fun battery(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys battery $command")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
    }

    @Test fun systemPowerChangesUpdateWaitingWithoutTurningItIntoAUserPause() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Battery simulation is restricted to the test emulator"
        }
        val application = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val controller = application.host.app.discovery!!
        val paused = controller.state.value.paused
        val estimate = "3 Notes, 25 passages remaining. About 2–3 minutes of embedding work. Subject and connection analysis is additional; its time is not yet estimated."
        try {
            runBlocking { controller.resume() }
            battery("unplug"); battery("set level 15")
            compose.waitUntil(10_000) { controller.state.value.power?.let { it.percent == 15 && !it.eligible } == true }
            compose.setContent {
                val state by controller.state.collectAsState()
                DiscoverySettingsScreen(DiscoverySettingsUi("Test model", modelInstalled = true,
                    processing = discoveryStatus(state.copy(
                        cacheStatus = com.example.notionreader.application.DiscoveryCacheStatus.Ready,
                        modelAvailable = true,
                        indexCoverage = com.example.notionreader.application.DiscoveryCoverageStatus.Ready,
                        embeddingWork = com.example.notionreader.application.DiscoveryEmbeddingWork(3, 25,
                            com.example.notionreader.application.ProcessingTimeRange(70, 170))))),
                    {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
            compose.onNodeWithText("Waiting for power: connect a charger or reach 30% battery.", substring = true)
                .assertIsReadable()
            val description = compose.onNodeWithText(estimate, substring = true).assertIsReadable().fetchSemanticsNode()
            assertNull(description.config.getOrNull(SemanticsProperties.LiveRegion))
            assertFalse(controller.state.value.paused)
            compose.onNodeWithText("Pause indexing").performScrollTo().assertIsReachable()
            battery("set usb 1")
            compose.waitUntil(10_000) { controller.state.value.power?.let { it.connected && it.eligible } == true }
            compose.onNodeWithText("Waiting for power:", substring = true).assertDoesNotExist()
            assertFalse(controller.state.value.paused)
        } finally {
            battery("reset")
            runBlocking { if (paused) controller.pause() else controller.resume() }
        }
    }
}
