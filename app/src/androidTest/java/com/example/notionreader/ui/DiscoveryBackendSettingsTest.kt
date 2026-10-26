package com.example.notionreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.domain.settings.DiscoveryBackend
import com.example.notionreader.domain.discovery.EmbeddingRuntimeState
import com.example.notionreader.ui.settings.*
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.assertIsReadable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiscoveryBackendSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyBackendIsSelectableAndFallbackRemainsReadable() {
        var preference by mutableStateOf(DiscoveryBackend.AUTOMATIC)
        compose.setContent { MaterialTheme {
            DiscoverySettingsScreen(DiscoverySettingsUi("Published model", backend = preference,
                runtime = EmbeddingRuntimeState(preference, DiscoveryBackend.CPU, "NPU runtime unavailable; using CPU.")),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, onBackendChange = { preference = it })
        } }
        DiscoveryBackend.entries.forEach { backend ->
            compose.onNodeWithText(backend.label).performScrollTo().assertIsReachable()
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText(backend.label).assertIsSelected()
            compose.runOnIdle { assertEquals(backend, preference) }
        }
        compose.onNodeWithText("Running backend: CPU").performScrollTo().assertIsReadable()
        compose.onNodeWithText("NPU runtime unavailable; using CPU.").performScrollTo().assertIsReadable()
    }

    @Test fun backendChoiceRoundTripsThroughTheShippingRepository() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = com.example.notionreader.data.local.DataStoreSettingsRepository(context)
        val original = repo.readCurrentSettings()
        try {
            DiscoveryBackend.entries.forEach { backend ->
                repo.updateSettings { it.copy(discoveryBackend = backend) }
                assertEquals(backend, com.example.notionreader.data.local.DataStoreSettingsRepository(context)
                    .readCurrentSettings().discoveryBackend)
            }
        } finally { repo.updateSettings { original } }
    }
    @Test fun selectingNpuDoesNotAcquireItsOptionalModel() {
        var selected by mutableStateOf(DiscoveryBackend.AUTOMATIC)
        var downloads = 0
        compose.setContent { MaterialTheme {
            DiscoverySettingsScreen(DiscoverySettingsUi("Base model", modelInstalled = true, backend = selected,
                npuModel = com.example.notionreader.domain.discovery.DiscoveryModelState(description = "Additional 188 MB")),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, onBackendChange = { selected = it }, onNpuDownload = { downloads++ })
        } }
        compose.onNodeWithText("NPU").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithText("Download NPU model · 188 MB").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, downloads) }
    }
}
