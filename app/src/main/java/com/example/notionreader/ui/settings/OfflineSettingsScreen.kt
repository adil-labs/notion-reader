package com.example.notionreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.settings.AutoCacheMode
import com.example.notionreader.domain.settings.AutoCacheNoteCount
import com.example.notionreader.domain.settings.AutoCacheTimeWindow
import com.example.notionreader.domain.settings.CacheFreshnessPolicy
import com.example.notionreader.domain.settings.DownloadNetworkPolicy
import com.example.notionreader.domain.settings.ReaderSettings

private sealed class AutoDownloadOption(val label: String) {
    object Off : AutoDownloadOption("Off")
    data class TimeWindow(val window: AutoCacheTimeWindow) : AutoDownloadOption("Updated in the last ${window.days} days")
    data class NoteCount(val count: AutoCacheNoteCount) : AutoDownloadOption("Most recent ${count.count} Notes")
}

/**
 * Offline & storage settings subpage.
 *
 * Configures offline note saving, prefetching, network policies, update checks,
 * and allows clearing cached documents.
 */
@Composable
fun SettingsOfflineScreen(
    settings: ReaderSettings,
    cachedNotesCount: Int = 0,
    cacheSizeBytes: Long = 0L,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAutoDownloadDialog by remember { mutableStateOf(false) }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var showFreshnessDialog by remember { mutableStateOf(false) }
    var showConfirmClearCache by remember { mutableStateOf(false) }

    val autoDownloadOptions = remember {
        listOf(
            AutoDownloadOption.Off,
            AutoDownloadOption.TimeWindow(AutoCacheTimeWindow.DAYS_7),
            AutoDownloadOption.TimeWindow(AutoCacheTimeWindow.DAYS_15),
            AutoDownloadOption.TimeWindow(AutoCacheTimeWindow.DAYS_30),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_5),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_10),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_20),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_50)
        )
    }

    val currentAutoDownloadOption = when (settings.autoCacheMode) {
        AutoCacheMode.OFF -> AutoDownloadOption.Off
        AutoCacheMode.TIME_WINDOW -> AutoDownloadOption.TimeWindow(settings.autoCacheTimeWindow)
        AutoCacheMode.NOTE_COUNT -> AutoDownloadOption.NoteCount(settings.autoCacheNoteCount)
    }

    val autoDownloadSummary = when (settings.autoCacheMode) {
        AutoCacheMode.OFF -> "Off"
        AutoCacheMode.TIME_WINDOW -> settings.autoCacheTimeWindow.label
        AutoCacheMode.NOTE_COUNT -> settings.autoCacheNoteCount.label
    }

    if (showAutoDownloadDialog) {
        SettingsSingleChoiceDialog(
            title = "Auto-download recent Notes",
            options = autoDownloadOptions,
            selectedOption = currentAutoDownloadOption,
            optionLabel = { it.label },
            onOptionSelected = { chosen ->
                when (chosen) {
                    is AutoDownloadOption.Off -> onUpdate {
                        it.copy(autoCacheMode = AutoCacheMode.OFF)
                    }
                    is AutoDownloadOption.TimeWindow -> onUpdate {
                        it.copy(
                            autoCacheMode = AutoCacheMode.TIME_WINDOW,
                            autoCacheTimeWindow = chosen.window
                        )
                    }
                    is AutoDownloadOption.NoteCount -> onUpdate {
                        it.copy(
                            autoCacheMode = AutoCacheMode.NOTE_COUNT,
                            autoCacheNoteCount = chosen.count
                        )
                    }
                }
            },
            onDismissRequest = { showAutoDownloadDialog = false }
        )
    }

    if (showNetworkDialog) {
        SettingsSingleChoiceDialog(
            title = "Download over",
            options = DownloadNetworkPolicy.values().toList(),
            selectedOption = settings.downloadNetworkPolicy,
            optionLabel = { it.label },
            onOptionSelected = { policy -> onUpdate { it.copy(downloadNetworkPolicy = policy) } },
            onDismissRequest = { showNetworkDialog = false }
        )
    }

    if (showFreshnessDialog) {
        SettingsSingleChoiceDialog(
            title = "Check Notion for updates",
            options = CacheFreshnessPolicy.values().toList(),
            selectedOption = settings.cacheFreshnessPolicy,
            optionLabel = { it.label },
            onOptionSelected = { freshness -> onUpdate { it.copy(cacheFreshnessPolicy = freshness) } },
            onDismissRequest = { showFreshnessDialog = false }
        )
    }

    if (showConfirmClearCache) {
        ClearCacheConfirmationDialog(
            onConfirm = onClearCache,
            onDismissRequest = { showConfirmClearCache = false }
        )
    }

    SettingsScreenLayout(
        title = "Offline & storage",
        paneTitle = "Offline & storage settings",
        onBack = onBack,
        modifier = modifier
    ) {
        SettingsSection(title = "OFFLINE") {
            SettingsSwitchRow(
                title = "Save Notes for offline use",
                checked = settings.cacheNotesForOffline,
                onCheckedChange = { checked -> onUpdate { it.copy(cacheNotesForOffline = checked) } },
                summary = "Keeps opened and downloaded Notes available offline."
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Prefetch upcoming Notes",
                checked = settings.prefetchUpcomingNotes,
                onCheckedChange = { checked -> onUpdate { it.copy(prefetchUpcomingNotes = checked) } },
                summary = "Downloads upcoming Notes in the current queue."
            )
        }

        SettingsSection(title = "AUTOMATIC DOWNLOADS") {
            SettingsValueRow(
                title = "Auto-download recent Notes",
                value = autoDownloadSummary,
                onClick = { showAutoDownloadDialog = true }
            )
            SettingsDivider()
            SettingsValueRow(
                title = "Download over",
                value = settings.downloadNetworkPolicy.label,
                onClick = { showNetworkDialog = true }
            )
        }

        // Informational empty-state banner if time window produced 0 notes
        if (settings.autoCacheMode == AutoCacheMode.TIME_WINDOW && cachedNotesCount == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "No Notes were updated in the last ${settings.autoCacheTimeWindow.days} days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsSection(title = "REFRESH") {
            SettingsValueRow(
                title = "Check Notion for updates",
                value = settings.cacheFreshnessPolicy.label,
                onClick = { showFreshnessDialog = true }
            )
        }

        SettingsSection(title = "STORAGE") {
            val storageValue = if (cachedNotesCount > 0) {
                "$cachedNotesCount Notes · ${formatBytes(cacheSizeBytes)}"
            } else {
                "No Notes cached"
            }
            SettingsStatusRow(
                title = "Cached Notes",
                value = storageValue
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Clear cached Notes",
                enabled = cachedNotesCount > 0,
                onClick = { showConfirmClearCache = true }
            )
        }
    }
}
