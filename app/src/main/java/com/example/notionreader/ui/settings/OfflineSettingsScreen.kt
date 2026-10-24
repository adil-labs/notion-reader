package com.example.notionreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    modifier: Modifier = Modifier,
    storageMessage: String? = null,
    extendedMaximumBytes: Long = 0L
) {
    var showAutoDownloadDialog by remember { mutableStateOf(false) }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var showFreshnessDialog by remember { mutableStateOf(false) }
    var showConfirmClearCache by remember { mutableStateOf(false) }
    var pendingLimit by remember { mutableStateOf<Long?>(null) }
    pendingLimit?.let { proposed ->
        AlertDialog(onDismissRequest = { pendingLimit = null }, title = { Text("Reduce Note cache limit?") },
            text = { Text("Cached Notes currently use ${formatStorageBytes(cacheSizeBytes)}. Reducing the limit to ${storageSize(proposed)} may remove older offline Notes and dependent Discovery results. Speculative downloads are removed first. Current and upcoming queue Notes are protected; cleanup waits if they exceed the limit.") },
            confirmButton = { TextButton(onClick = { onUpdate { it.copy(noteCacheLimitBytes = proposed) }; pendingLimit = null }) { Text("Reduce limit") } },
            dismissButton = { TextButton(onClick = { pendingLimit = null }) { Text("Cancel") } })
    }

    val autoDownloadOptions = remember {
        listOf(
            AutoDownloadOption.Off,
            AutoDownloadOption.TimeWindow(AutoCacheTimeWindow.DAYS_7),
            AutoDownloadOption.TimeWindow(AutoCacheTimeWindow.DAYS_15),
            AutoDownloadOption.TimeWindow(AutoCacheTimeWindow.DAYS_30),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_5),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_10),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_20),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_50),
            AutoDownloadOption.NoteCount(AutoCacheNoteCount.COUNT_100)
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
        StoragePolicyExplanation(settings, onUpdate)
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
            SettingsStatusRow(
                title = "Offline Notes",
                value = "$cachedNotesCount Notes · ${formatStorageBytes(cacheSizeBytes)}"
            )
            Text(
                text = "Downloaded copies used for offline reading and Discovery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            SettingsDivider()
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StorageLimitControl(
                    label = "Storage limit",
                    bytes = settings.noteCacheLimitBytes,
                    extendedMaximum = if (settings.extendedStorage) extendedMaximumBytes else 0L
                ) { next ->
                    if (next < settings.noteCacheLimitBytes && cacheSizeBytes > next) pendingLimit = next
                    else onUpdate { it.copy(noteCacheLimitBytes = next) }
                }
                Text(
                    text = "Increasing the limit does not download additional Notes by itself. Reducing the limit can remove offline copies and dependent Discovery results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (cacheSizeBytes > settings.noteCacheLimitBytes) {
                    Text(
                        text = "Cache cleanup is waiting for protected queue Notes. Playback remains available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (cachedNotesCount >= 200) {
                    Text(
                        text = "The 200-Note safety limit has been reached. Increasing the byte limit does not increase this count limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                storageMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SettingsDivider()
            SettingsActionRow(
                title = "Clear cached Notes",
                summary = "Removes downloaded copies from this device. Dependent Discovery results are also cleared.",
                enabled = cachedNotesCount > 0,
                onClick = { showConfirmClearCache = true }
            )
            SettingsDivider()
            ExpandableStorageExplanation(
                collapsedSummary = if (settings.extendedStorage) "Larger storage limits: Enabled" else null
            ) {
                Text(
                    text = "The default 128 MiB cache is sized for about 100 Notes of up to 1 MiB each. A separate 200-Note safety limit also applies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsSwitchRow(
                    title = "Allow larger storage limits",
                    checked = settings.extendedStorage,
                    onCheckedChange = { value -> onUpdate { it.copy(extendedStorage = value) } },
                    summary = "Offers larger storage limits for both Offline Notes and Discovery (up to 80% of safely available space). Space is used only as needed; at least 2 GiB or 10% of device capacity stays free."
                )
            }
        }
    }
}
