package com.example.notionreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.ui.restoreFocusOn

/**
 * Root Settings hub.
 *
 * Each category is rendered as a single accessible focus stop stating its title and current summary,
 * avoiding the high TalkBack navigation cost of traversing individual switches from the top-level screen.
 */
@Composable
fun SettingsScreen(
    settings: ReaderSettings,
    cachedNotesCount: Int = 0,
    onOpenPlayback: () -> Unit,
    onOpenSpeech: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotion: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .semantics { paneTitle = "Settings" },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 1. Playback
        SettingsCategoryRow(
            title = "Playback",
            summary = "Default navigation: ${settings.defaultNavigationUnit.label}",
            id = "settings_row_playback",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenPlayback
        )

        // 2. Speech
        val speechSummary = if (settings.useSystemSpeechSettings) {
            "System speech · ${settings.playbackAnnouncements.label} announcements"
        } else {
            "${settings.speechRate.label} · ${settings.playbackAnnouncements.label} announcements"
        }
        SettingsCategoryRow(
            title = "Speech",
            summary = speechSummary,
            id = "settings_row_speech",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenSpeech
        )

        // 3. Offline & storage
        val offlineSummary = if (cachedNotesCount > 0) {
            "$cachedNotesCount cached Notes"
        } else {
            "Offline playback and prefetching"
        }
        SettingsCategoryRow(
            title = "Offline & storage",
            summary = offlineSummary,
            id = "settings_row_offline",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenOffline
        )

        // 4. Accessibility
        val a11ySummary = if (settings.keepScreenAwake) {
            "Keep screen awake · ${settings.readerSpacing.label} spacing"
        } else {
            "System sleep · ${settings.readerSpacing.label} spacing"
        }
        SettingsCategoryRow(
            title = "Accessibility",
            summary = a11ySummary,
            id = "settings_row_accessibility",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenAccessibility
        )

        // 5. Appearance
        SettingsCategoryRow(
            title = "Appearance",
            summary = "${settings.appTheme.label} · ${settings.readerSpacing.label} spacing",
            id = "settings_row_appearance",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenAppearance
        )

        // 6. Notion
        SettingsCategoryRow(
            title = "Notion",
            summary = "Personal Workspace · Connected",
            id = "settings_row_notion",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenNotion
        )

        // 7. Privacy & data
        SettingsCategoryRow(
            title = "Privacy & data",
            summary = "History, cache and local data",
            id = "settings_row_privacy",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenPrivacy
        )

        // 8. About
        SettingsCategoryRow(
            title = "About",
            summary = "Notion Reader · Version 1.0.0",
            id = "settings_row_about",
            focusTarget = focusTarget,
            onFocusHandled = onFocusHandled,
            onClick = onOpenAbout
        )
    }
}

@Composable
fun SettingsCategoryRow(
    title: String,
    summary: String,
    id: String,
    focusTarget: String?,
    onFocusHandled: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .restoreFocusOn(target = focusTarget, id = id, onHandled = onFocusHandled)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "$title. $summary."
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
