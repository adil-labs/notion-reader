package com.example.notionreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.BuildConfig
import com.example.notionreader.domain.settings.NotionConnectionStatus
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.SpeechRate
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
    cacheSizeBytes: Long = 0L,
    historyCount: Int = 0,
    speechEngineLabel: String? = null,
    notionStatus: NotionConnectionStatus = NotionConnectionStatus.CONNECTED,
    workspaceName: String? = "Personal Workspace",
    onOpenPlayback: () -> Unit,
    onOpenSpeech: () -> Unit,
    onOpenOffline: () -> Unit,
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { paneTitle = "Settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .semantics { heading() }
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Playback
                val playbackContinuation = if (settings.continueToNextNote) {
                    "Continue between Notes"
                } else {
                    "Stop after each Note"
                }
                SettingsCategoryRow(
                    title = "Playback",
                    summary = "${settings.defaultNavigationUnit.label} navigation · $playbackContinuation",
                    id = "settings_row_playback",
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    onClick = onOpenPlayback
                )

                SettingsDivider()

                // 2. Speech
                val engineDisplay = speechEngineLabel ?: "System default engine"
                val rateDisplay = when (settings.speechRate) {
                    is SpeechRate.SystemDefault -> "System speed"
                    is SpeechRate.Multiplier -> settings.speechRate.label
                }
                SettingsCategoryRow(
                    title = "Speech",
                    summary = "$engineDisplay · $rateDisplay",
                    id = "settings_row_speech",
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    onClick = onOpenSpeech
                )

                SettingsDivider()

                // 3. Offline & storage
                val offlineSummary = if (cachedNotesCount > 0) {
                    "$cachedNotesCount Notes · ${formatBytes(cacheSizeBytes)}"
                } else {
                    "No Notes cached"
                }
                SettingsCategoryRow(
                    title = "Offline & storage",
                    summary = offlineSummary,
                    id = "settings_row_offline",
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    onClick = onOpenOffline
                )

                SettingsDivider()

                // 4. Appearance
                SettingsCategoryRow(
                    title = "Appearance",
                    summary = "${settings.appTheme.label} · ${settings.readerSpacing.label} spacing",
                    id = "settings_row_appearance",
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    onClick = onOpenAppearance
                )

                SettingsDivider()

                // 5. Notion
                val notionSummary = when (notionStatus) {
                    NotionConnectionStatus.CONNECTED -> if (!workspaceName.isNullOrBlank()) "Connected · $workspaceName" else "Connected"
                    NotionConnectionStatus.NOT_CONFIGURED -> "Not configured"
                    NotionConnectionStatus.CONNECTION_PROBLEM -> "Connection problem"
                }
                SettingsCategoryRow(
                    title = "Notion",
                    summary = notionSummary,
                    id = "settings_row_notion",
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    onClick = onOpenNotion
                )

                SettingsDivider()

                // 6. Privacy & data
                val privacySummary = if (historyCount > 0 || cachedNotesCount > 0) {
                    "$historyCount listening sessions · $cachedNotesCount cached Notes"
                } else {
                    "No local history or cached Notes"
                }
                SettingsCategoryRow(
                    title = "Privacy & data",
                    summary = privacySummary,
                    id = "settings_row_privacy",
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    onClick = onOpenPrivacy
                )

                SettingsDivider()

                // 7. About
                SettingsCategoryRow(
                    title = "About",
                    summary = "Version ${BuildConfig.VERSION_NAME}",
                    id = "settings_row_about",
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled,
                    onClick = onOpenAbout
                )
            }
        }
    }
}

/**
 * Individual category row on the root Settings hub.
 *
 * Exposes a single [Role.Button] focus stop with merged semantics, ensuring TalkBack reads
 * the title and summary together without separate nested stops.
 */
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .restoreFocusOn(target = focusTarget, id = id, onHandled = onFocusHandled)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}
