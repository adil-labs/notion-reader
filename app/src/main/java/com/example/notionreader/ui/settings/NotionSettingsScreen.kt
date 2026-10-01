package com.example.notionreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.settings.NotionConnectionStatus

/**
 * Notion integration settings subpage.
 *
 * Displays integration status, workspace connection, and live index freshness.
 * Free of implementation/debug terminology.
 */
@Composable
fun SettingsNotionScreen(
    connectionStatus: NotionConnectionStatus = NotionConnectionStatus.CONNECTED,
    workspaceName: String? = "Personal Workspace",
    indexStatus: String = "Up to date",
    lastRefreshedText: String? = null,
    onRefreshIndex: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsScreenLayout(
        title = "Notion",
        paneTitle = "Notion settings",
        onBack = onBack,
        modifier = modifier
    ) {
        SettingsSection(title = "CONNECTION") {
            val titleText = workspaceName ?: "Notion"
            SettingsStatusRow(
                title = titleText,
                value = connectionStatus.label
            )
            SettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Pages shared with the Notion integration can be read by Notion Reader.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsSection(title = "CONTENT") {
            SettingsStatusRow(
                title = "Notion index",
                value = indexStatus
            )
            if (!lastRefreshedText.isNullOrBlank()) {
                SettingsDivider()
                SettingsStatusRow(
                    title = "Last refreshed",
                    value = lastRefreshedText
                )
            }
            if (onRefreshIndex != null) {
                SettingsDivider()
                SettingsActionRow(
                    title = "Refresh now",
                    onClick = onRefreshIndex
                )
            }
        }
    }
}
