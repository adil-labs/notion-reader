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

    @Test fun incompleteModelFilesStillAppearInMeasuredStorageUsage() {
        compose.setContent { MaterialTheme {
            com.example.notionreader.ui.settings.DiscoverySettingsScreen(
                com.example.notionreader.ui.settings.DiscoverySettingsUi("Setup incomplete", modelInstalled = false,
                    modelBytes = 5L * 1024 * 1024, discoveryBytes = 0L),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, settings = ReaderSettings(storagePolicyExplained = true))
        } }
        compose.onNodeWithText("Downloaded model").performScrollTo()
        compose.onNodeWithText("5.0 MiB").assertExists()
        compose.onNodeWithText("Not downloaded").assertDoesNotExist()
    }

    @Test fun discoveryUsageAndLimitsUseConsistentBinaryUnits() {
        compose.setContent { MaterialTheme {
            com.example.notionreader.ui.settings.DiscoverySettingsScreen(
                com.example.notionreader.ui.settings.DiscoverySettingsUi("Local model", discoveryBytes = 1024L * 1024),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, settings = ReaderSettings(storagePolicyExplained = true))
        } }
        compose.onNodeWithText("Discovery data").performScrollTo()
        compose.onNodeWithText("1.0 MiB used").assertExists()
    }

    @Test fun storageSliderCanBeAdjustedThroughAccessibilityAndPersistsTheChoice() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent { MaterialTheme { SettingsOfflineScreen(settings, onUpdate = { settings = it(settings) }, onClearCache = {}, onBack = {}) } }
        compose.onNodeWithContentDescription("Storage limit").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(256L*1024*1024, settings.noteCacheLimitBytes) }
        compose.onNodeWithContentDescription("Storage limit").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "256 MiB"))
        compose.onNodeWithText("Increase Storage limit").assertIsReachable()
    }

    @Test fun loweringCacheLimitWaitsForConfirmationAndCancelPreservesTheLimit() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent { MaterialTheme { SettingsOfflineScreen(settings, cachedNotesCount = 100,
            cacheSizeBytes = 100L*1024*1024, onUpdate = { settings = it(settings) }, onClearCache = {}, onBack = {}) } }
        compose.onNodeWithContentDescription("Storage limit").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Reduce Note cache limit?").assertExists()
        compose.runOnIdle { assertEquals(128L*1024*1024, settings.noteCacheLimitBytes) }
        compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(128L*1024*1024, settings.noteCacheLimitBytes) }
        compose.onNodeWithContentDescription("Storage limit").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "128 MiB"))
    }

    @Test fun confirmedShrinkPersistsTheSelectedLimit() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent { MaterialTheme { SettingsOfflineScreen(settings, cachedNotesCount = 100,
            cacheSizeBytes = 100L*1024*1024, onUpdate = { settings = it(settings) }, onClearCache = {}, onBack = {}) } }
        compose.onNodeWithContentDescription("Storage limit").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Reduce limit").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(64L*1024*1024, settings.noteCacheLimitBytes) }
    }

    @Test fun discoveryStorageManagementRadioChoicesSurviveTogglingAndRetainCustomLimit() {
        var settings by mutableStateOf(ReaderSettings(storagePolicyExplained = true, discoveryStorageAutomatic = true, discoveryCustomLimitBytes = 256L*1024*1024))
        compose.setContent {
            MaterialTheme {
                com.example.notionreader.ui.settings.DiscoverySettingsScreen(
                    com.example.notionreader.ui.settings.DiscoverySettingsUi("Local model"),
                    {}, {}, {}, {}, {}, {}, {}, {}, {},
                    settings = settings, onStorageUpdate = { settings = it(settings) }
                )
            }
        }
        compose.onNodeWithText("Automatic (recommended)").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Set a maximum").assertIsNotSelected()
        compose.onNodeWithContentDescription("Storage limit").assertDoesNotExist()

        compose.onNodeWithText("Set a maximum").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(false, settings.discoveryStorageAutomatic) }
        compose.onNodeWithText("Set a maximum").assertIsSelected()
        compose.onNodeWithContentDescription("Storage limit").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.runOnIdle { assertEquals(512L*1024*1024, settings.discoveryCustomLimitBytes) }

        compose.onNodeWithText("Automatic (recommended)").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(true, settings.discoveryStorageAutomatic) }
        compose.onNodeWithContentDescription("Storage limit").assertDoesNotExist()

        compose.onNodeWithText("Set a maximum").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(false, settings.discoveryStorageAutomatic) }
        compose.runOnIdle { assertEquals(512L*1024*1024, settings.discoveryCustomLimitBytes) }
        compose.onNodeWithText("When the limit is reached, preparation of additional Discovery data waits. Existing eligible results remain available.", substring = true).assertExists()
    }

    @Test fun zeroUsageIsDistinctFromMissingMeasurementsInDiscovery() {
        val discoverySettings = ReaderSettings(storagePolicyExplained = true)
        var discoveryBytes by mutableStateOf<Long?>(null)
        compose.setContent {
            MaterialTheme {
                com.example.notionreader.ui.settings.DiscoverySettingsScreen(
                    com.example.notionreader.ui.settings.DiscoverySettingsUi("Local model", discoveryBytes = discoveryBytes, modelBytes = 0L),
                    {}, {}, {}, {}, {}, {}, {}, {}, {},
                    settings = discoverySettings
                )
            }
        }
        compose.onNodeWithText("Usage unavailable").assertExists()
        compose.onNodeWithText("0 bytes used").assertDoesNotExist()

        discoveryBytes = 0L
        compose.onNodeWithText("0 bytes used").assertExists()
        compose.onNodeWithText("Usage unavailable").assertDoesNotExist()
    }

    @Test fun zeroUsageShowsActualBytesInOfflineNotes() {
        var offlineSettings by mutableStateOf(ReaderSettings(storagePolicyExplained = true))
        compose.setContent {
            MaterialTheme {
                SettingsOfflineScreen(
                    settings = offlineSettings,
                    cachedNotesCount = 0,
                    cacheSizeBytes = 0L,
                    onUpdate = { offlineSettings = it(offlineSettings) },
                    onClearCache = {},
                    onBack = {}
                )
            }
        }
        compose.onNodeWithText("Offline Notes").performScrollTo().assertExists()
        compose.onNodeWithText("0 Notes · 0 bytes").assertExists()
        compose.onNodeWithText("No Notes cached").assertDoesNotExist()
    }

    @Test fun largerStorageLimitsInOfflineRemainDiscoverableWhenCollapsed() {
        var offlineSettings by mutableStateOf(ReaderSettings(storagePolicyExplained = true, extendedStorage = true))
        compose.setContent {
            MaterialTheme {
                SettingsOfflineScreen(
                    settings = offlineSettings,
                    cachedNotesCount = 5,
                    cacheSizeBytes = 5L * 1024 * 1024,
                    onUpdate = { offlineSettings = it(offlineSettings) },
                    onClearCache = {},
                    onBack = {}
                )
            }
        }
        compose.onNodeWithText("Larger storage limits: Enabled").performScrollTo().assertExists()
        compose.onNodeWithText("How storage works").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Allow larger storage limits").performScrollTo().assertExists()
        compose.onNodeWithText("Offers larger storage limits for both Offline Notes and Discovery", substring = true).assertExists()
    }

    @Test fun largerStorageLimitsAreExplainedInDiscovery() {
        val discoverySettings = ReaderSettings(storagePolicyExplained = true, extendedStorage = true)
        compose.setContent {
            MaterialTheme {
                com.example.notionreader.ui.settings.DiscoverySettingsScreen(
                    com.example.notionreader.ui.settings.DiscoverySettingsUi("Local model"),
                    {}, {}, {}, {}, {}, {}, {}, {}, {},
                    settings = discoverySettings
                )
            }
        }
        compose.onNodeWithText("How storage works").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Larger storage limits can be enabled under Offline & storage settings.", substring = true).assertExists()
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
