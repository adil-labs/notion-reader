package com.example.notionreader.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.domain.settings.NotionConnectionStatus
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.SpeechRate
import com.example.notionreader.player.SpeechEngineInfo
import com.example.notionreader.player.SpeechEngineState
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.hasPaneTitle
import com.example.notionreader.ui.settings.SettingsAboutScreen
import com.example.notionreader.ui.settings.SettingsAppearanceScreen
import com.example.notionreader.ui.settings.SettingsNotionScreen
import com.example.notionreader.ui.settings.SettingsOfflineScreen
import com.example.notionreader.ui.settings.SettingsPlaybackScreen
import com.example.notionreader.ui.settings.SettingsPrivacyScreen
import com.example.notionreader.ui.settings.SettingsScreen
import com.example.notionreader.ui.settings.SettingsSpeechScreen
import com.example.notionreader.ui.settings.SpeechRateDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Accessibility assertions for the Settings hub and subpage surfaces.
 *
 * Verifies that each category row on the root screen is a single accessible focus stop
 * with Role.Button, avoiding high traversal costs for screen readers.
 * Also verifies paneTitles, standard Back button labeling ("Back"), switch semantics,
 * and dialog accessibility.
 */
class SettingsScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsScreenDeclaresPaneTitle() {
        compose.setContent {
            SettingsScreen(
                settings = ReaderSettings(),
                onOpenPlayback = {},
                onOpenSpeech = {},
                onOpenOffline = {},
                onOpenAppearance = {},
                onOpenNotion = {},
                onOpenPrivacy = {},
                onOpenAbout = {}
            )
        }

        compose.onNode(hasPaneTitle("Settings")).assertExists()
    }

    @Test
    fun eachCategoryRowIsASingleReachableButton() {
        compose.setContent {
            SettingsScreen(
                settings = ReaderSettings(),
                cachedNotesCount = 42,
                cacheSizeBytes = 18_400_000L,
                historyCount = 14,
                onOpenPlayback = {},
                onOpenSpeech = {},
                onOpenOffline = {},
                onOpenAppearance = {},
                onOpenNotion = {},
                onOpenPrivacy = {},
                onOpenAbout = {}
            )
        }

        // Exactly 7 category buttons on root Settings
        val buttonMatcher = hasClickAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button
        )
        compose.onAllNodes(buttonMatcher).assertCountEquals(7)

        // Assert Playback and Offline rows are reachable
        compose.onNode(hasText("Playback", substring = true) and buttonMatcher).assertIsReachable()
        compose.onNode(hasText("Offline & storage", substring = true) and buttonMatcher).assertIsReachable()
        compose.onNode(hasText("Privacy & data", substring = true) and buttonMatcher).assertIsReachable()
    }

    @Test
    fun activatingPlaybackRowInvokesCallback() {
        var opened = false
        compose.setContent {
            SettingsScreen(
                settings = ReaderSettings(),
                onOpenPlayback = { opened = true },
                onOpenSpeech = {},
                onOpenOffline = {},
                onOpenAppearance = {},
                onOpenNotion = {},
                onOpenPrivacy = {},
                onOpenAbout = {}
            )
        }

        compose.onNode(hasText("Playback", substring = true) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)

        assertTrue("Activating Playback row must invoke onOpenPlayback", opened)
    }

    @Test
    fun playbackSubpageDeclaresPaneTitleAndSwitches() {
        compose.setContent {
            SettingsPlaybackScreen(
                settings = ReaderSettings(),
                onUpdate = {},
                onBack = {}
            )
        }

        compose.onNode(hasPaneTitle("Playback settings")).assertExists()
        // Switches for Continue to next Note and Keep screen awake
        val switchMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onAllNodes(switchMatcher).assertCountEquals(2)
        compose.onNode(hasText("Continue to next Note", substring = true) and switchMatcher).assertIsReachable()
        compose.onNode(hasText("Keep screen awake while playing", substring = true) and switchMatcher).assertIsReachable()
        // Back button with contentDescription "Back"
        compose.onNodeWithContentDescription("Back").assertIsReachable()
    }

    @Test
    fun playbackPauseDisabledWhenContinueToNextNoteIsOff() {
        compose.setContent {
            SettingsPlaybackScreen(
                settings = ReaderSettings(continueToNextNote = false),
                onUpdate = {},
                onBack = {}
            )
        }

        // The Pause between Notes row must indicate disabled supporting text
        compose.onNode(hasText("Pause between Notes", substring = true)).assertExists()
        compose.onNode(hasText("Available when Continue to next Note is on.", substring = true)).assertExists()
    }

    @Test
    fun speechSubpageDeclaresPaneTitleAndReachableControls() {
        compose.setContent {
            SettingsSpeechScreen(
                settings = ReaderSettings(),
                onUpdate = {},
                onBack = {}
            )
        }

        compose.onNode(hasPaneTitle("Speech settings")).assertExists()
        compose.onNodeWithContentDescription("Back").assertIsReachable()
        compose.onNode(hasText("Speech rate", substring = true)).assertIsReachable()
        compose.onNode(hasText("Voice pitch", substring = true)).assertIsReachable()
        compose.onNode(hasText("Playback announcements", substring = true)).assertIsReachable()
    }

    @Test
    fun speechSubpageShowsFallbackWarningWhenEngineUnavailable() {
        val state = SpeechEngineState(
            requestedPackage = "com.custom.engine",
            activePackage = "com.google.android.tts",
            isFallback = true
        )
        compose.setContent {
            SettingsSpeechScreen(
                settings = ReaderSettings(ttsEnginePackage = "com.custom.engine"),
                engineState = state,
                onUpdate = {},
                onBack = {}
            )
        }

        compose.onNode(hasText("Selected speech engine is unavailable. Using the system default engine.")).assertExists()
        compose.onNode(hasText("Unavailable engine · using system default", substring = true)).assertExists()
    }

    @Test
    fun speechRateDialogAllowsSelection() {
        var selected: SpeechRate? = null
        compose.setContent {
            SpeechRateDialog(
                currentRate = SpeechRate.RATE_1_0,
                onRateSelected = { selected = it },
                onDismissRequest = {}
            )
        }

        compose.onNode(hasContentDescription("Speech rate", substring = true)).assertExists()
        compose.onNode(hasText("Use system default", substring = true)).assertExists()
        compose.onNode(hasText("OK")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(SpeechRate.RATE_1_0, selected)
    }

    @Test
    fun offlineSubpageDeclaresPaneTitleAndSwitches() {
        compose.setContent {
            SettingsOfflineScreen(
                settings = ReaderSettings(),
                cachedNotesCount = 10,
                cacheSizeBytes = 1024 * 1024L,
                onUpdate = {},
                onClearCache = {},
                onBack = {}
            )
        }

        compose.onNode(hasPaneTitle("Offline & storage settings")).assertExists()
        compose.onNodeWithContentDescription("Back").assertIsReachable()

        val switchMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onNode(hasText("Save Notes for offline use", substring = true) and switchMatcher).assertIsReachable()
        compose.onNode(hasText("Prefetch upcoming Notes", substring = true) and switchMatcher).assertIsReachable()
    }

    @Test
    fun appearanceSubpageDeclaresPaneTitle() {
        compose.setContent {
            SettingsAppearanceScreen(
                settings = ReaderSettings(),
                onUpdate = {},
                onBack = {}
            )
        }

        compose.onNode(hasPaneTitle("Appearance settings")).assertExists()
        compose.onNodeWithContentDescription("Back").assertIsReachable()
        compose.onNode(hasText("Theme", substring = true)).assertIsReachable()
        compose.onNode(hasText("Reader spacing", substring = true)).assertIsReachable()
    }

    @Test
    fun notionSubpageDeclaresPaneTitleAndStates() {
        compose.setContent {
            SettingsNotionScreen(
                connectionStatus = NotionConnectionStatus.CONNECTED,
                workspaceName = "Personal Workspace",
                indexStatus = "Up to date",
                onBack = {}
            )
        }

        compose.onNode(hasPaneTitle("Notion settings")).assertExists()
        compose.onNodeWithContentDescription("Back").assertIsReachable()
        compose.onNode(hasText("Personal Workspace", substring = true)).assertExists()
        compose.onNode(hasText("Connected", substring = true)).assertExists()
        compose.onNode(hasText("Up to date", substring = true)).assertExists()
    }

    @Test
    fun privacySubpageDeclaresPaneTitleAndActions() {
        compose.setContent {
            SettingsPrivacyScreen(
                historyCount = 5,
                cachedNotesCount = 2,
                cacheSizeBytes = 50000L,
                onClearHistory = {},
                onClearCache = {},
                onClearAllData = {},
                onBack = {}
            )
        }

        compose.onNode(hasPaneTitle("Privacy & data settings")).assertExists()
        compose.onNodeWithContentDescription("Back").assertIsReachable()
        compose.onNode(hasText("Clear listening history", substring = true)).assertIsReachable()
        compose.onNode(hasText("Clear cached Notes", substring = true)).assertIsReachable()
        compose.onNode(hasText("Clear all local app data", substring = true)).assertIsReachable()
    }

    @Test
    fun aboutSubpageDeclaresPaneTitleAndNotices() {
        compose.setContent {
            SettingsAboutScreen(
                onBack = {}
            )
        }

        compose.onNode(hasPaneTitle("About settings")).assertExists()
        compose.onNodeWithContentDescription("Back").assertIsReachable()
        compose.onNode(hasText("Open-source licenses", substring = true)).assertIsReachable()
        compose.onNode(hasText("Privacy policy", substring = true)).assertIsReachable()
    }
}
