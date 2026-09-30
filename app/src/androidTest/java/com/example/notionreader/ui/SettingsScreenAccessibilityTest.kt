package com.example.notionreader.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.hasPaneTitle
import com.example.notionreader.ui.settings.SettingsPlaybackScreen
import com.example.notionreader.ui.settings.SettingsScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Accessibility assertions for the Settings hub and subpage surfaces.
 *
 * Verifies that each category row on the root screen is a single accessible focus stop
 * with Role.Button, avoiding high traversal costs for screen readers.
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
                onOpenAccessibility = {},
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
                onOpenPlayback = {},
                onOpenSpeech = {},
                onOpenOffline = {},
                onOpenAccessibility = {},
                onOpenAppearance = {},
                onOpenNotion = {},
                onOpenPrivacy = {},
                onOpenAbout = {}
            )
        }

        // 8 category buttons on root Settings
        val buttonMatcher = hasClickAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button
        )
        compose.onAllNodes(buttonMatcher).assertCountEquals(8)

        // Assert Playback row is reachable
        compose.onNodeWithContentDescription("Playback. Default navigation: Section.").assertIsReachable()
        compose.onNodeWithContentDescription("Offline & storage. 42 cached Notes.").assertIsReachable()
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
                onOpenAccessibility = {},
                onOpenAppearance = {},
                onOpenNotion = {},
                onOpenPrivacy = {},
                onOpenAbout = {}
            )
        }

        compose.onNodeWithContentDescription("Playback. Default navigation: Section.")
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
        // Switch for Continue to next Note
        val switchMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onNode(switchMatcher).assertIsReachable()
    }
}
