package com.example.notionreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.ui.settings.SettingsOfflineScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.example.notionreader.ui.A11y.assertIsReachable
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking

class StorageSettingsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun storageSliderCanBeAdjustedThroughAccessibilityAndPersistsTheChoice() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent { MaterialTheme { SettingsOfflineScreen(settings, onUpdate = { settings = it(settings) }, onClearCache = {}, onBack = {}) } }
        compose.onNodeWithContentDescription("Note cache limit").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(256L*1024*1024, settings.noteCacheLimitBytes) }
        compose.onNodeWithContentDescription("Note cache limit").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "256 MiB"))
        compose.onNodeWithText("Increase Note cache limit").assertIsReachable()
    }

    @Test fun loweringCacheLimitWaitsForConfirmationAndCancelPreservesTheLimit() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent { MaterialTheme { SettingsOfflineScreen(settings, cachedNotesCount = 100,
            cacheSizeBytes = 100L*1024*1024, onUpdate = { settings = it(settings) }, onClearCache = {}, onBack = {}) } }
        compose.onNodeWithContentDescription("Note cache limit").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Reduce Note cache limit?").assertExists()
        compose.runOnIdle { assertEquals(128L*1024*1024, settings.noteCacheLimitBytes) }
        compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(128L*1024*1024, settings.noteCacheLimitBytes) }
        compose.onNodeWithContentDescription("Note cache limit").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "128 MiB"))
    }

    @Test fun confirmedShrinkPersistsTheSelectedLimit() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent { MaterialTheme { SettingsOfflineScreen(settings, cachedNotesCount = 100,
            cacheSizeBytes = 100L*1024*1024, onUpdate = { settings = it(settings) }, onClearCache = {}, onBack = {}) } }
        compose.onNodeWithContentDescription("Note cache limit").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Reduce limit").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(64L*1024*1024, settings.noteCacheLimitBytes) }
    }

    @Test fun discoveryMaximumIsOptionalAndCanReturnToAutomatic() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent { MaterialTheme { com.example.notionreader.ui.settings.DiscoverySettingsScreen(
            com.example.notionreader.ui.settings.DiscoverySettingsUi("Local model"), {}, {}, {}, {}, {}, {}, {}, {}, {},
            settings = settings, onStorageUpdate = { settings = it(settings) }) } }
        compose.onNodeWithText("Automatic Discovery storage").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Discovery maximum").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle { assertEquals(false, settings.discoveryStorageAutomatic); assertEquals(512L*1024*1024, settings.discoveryCustomLimitBytes) }
        compose.onNodeWithText("Automatic Discovery storage").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(true, settings.discoveryStorageAutomatic) }
        compose.onNodeWithContentDescription("Discovery maximum").assertDoesNotExist()
    }

    @Test fun storageChoicesRoundTripThroughTheShippingPreferencesRepository() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = com.example.notionreader.data.local.DataStoreSettingsRepository(context)
        val original = repo.readCurrentSettings()
        try {
            repo.updateSettings { it.copy(noteCacheLimitBytes = 256L*1024*1024, discoveryStorageAutomatic = false,
                discoveryCustomLimitBytes = 512L*1024*1024, extendedStorage = true,
                storagePolicyExplained = true, autoCacheNoteCount = com.example.notionreader.domain.settings.AutoCacheNoteCount.COUNT_100) }
            val restored = com.example.notionreader.data.local.DataStoreSettingsRepository(context).readCurrentSettings()
            assertEquals(256L*1024*1024, restored.noteCacheLimitBytes)
            assertEquals(512L*1024*1024, restored.discoveryCustomLimitBytes)
            assertEquals(false, restored.discoveryStorageAutomatic)
            assertEquals(true, restored.extendedStorage)
            assertEquals(100, restored.autoCacheNoteCount.count)
        } finally { repo.updateSettings { original } }
    }
}
