package com.example.notionreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import com.example.notionreader.domain.settings.DiscoveryEffort
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class DiscoverySettingsUi(
    val modelDescription: String,
    val modelInstalled: Boolean = false,
    val downloading: Boolean = false,
    val wifiOnly: Boolean = true,
    val processing: com.example.notionreader.ui.DiscoveryStatusUi = com.example.notionreader.ui.DiscoveryStatusUi(),
    val storageDescription: String = "0 bytes used · Automatic storage",
    val error: String? = null,
    val downloadAllowed: Boolean = false,
    val downloadProgress: String? = null,
    val effort: DiscoveryEffort = DiscoveryEffort.BALANCED
)

@Composable
fun DiscoverySettingsScreen(
    state: DiscoverySettingsUi,
    onWifiOnlyChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onClearIndex: () -> Unit,
    onRebuild: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEffortChange: (DiscoveryEffort) -> Unit = {},
    settings: com.example.notionreader.domain.settings.ReaderSettings? = null,
    onStorageUpdate: ((com.example.notionreader.domain.settings.ReaderSettings) -> com.example.notionreader.domain.settings.ReaderSettings) -> Unit = {},
    storageMessage: String? = null,
    extendedMaximumBytes: Long = 0L
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp)
        .semantics { paneTitle = "Discovery settings" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Discovery settings", Modifier.semantics { heading() })
        settings?.let { StoragePolicyExplanation(it, onStorageUpdate) }
        Text(state.modelDescription)
        state.downloadProgress?.let { Text(it) }
        Text("Embeddings run on this device. Only cached Notes are indexed.")
        Text("Download using Wi-Fi only")
        Checkbox(checked = state.wifiOnly, onCheckedChange = onWifiOnlyChange,
            modifier = Modifier.semantics { contentDescription = "Download using Wi-Fi only" })
        if (state.downloading) Button(onClick = onCancelDownload) { Text("Cancel download") }
        else if (state.modelInstalled) Button(onClick = onDeleteModel) { Text("Delete model and semantic index") }
        else Button(onClick = onDownload, enabled = state.downloadAllowed) { Text("Download model") }
        Text(state.processing.coverage)
        state.processing.message?.let { Text(it) }
        state.processing.workDescription?.let { Text(it) }
        Text("Preparation waits below 30% battery unless a charger is connected.")
        Text("Discovery effort", Modifier.semantics { heading() })
        DiscoveryEffort.entries.forEach { effort ->
            Row(Modifier.selectable(selected = state.effort == effort, role = Role.RadioButton,
                onClick = { onEffortChange(effort) }).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.effort == effort, onClick = null)
                Text(effort.label)
            }
        }
        Text("Discovery continues automatically, including in the background when Android permits. Effort changes when work runs; quality and coverage stay the same.")
        Text(state.storageDescription)
        storageMessage?.let { Text(it) }
        settings?.let { prefs ->
            SettingsSwitchRow(title = "Automatic Discovery storage", checked = prefs.discoveryStorageAutomatic,
                onCheckedChange = { automatic -> onStorageUpdate { it.copy(discoveryStorageAutomatic = automatic) } },
                summary = "Grows with indexed content while keeping space free for your device. Only cached Notes are indexed.")
            if (!prefs.discoveryStorageAutomatic) {
                StorageLimitControl("Discovery maximum", prefs.discoveryCustomLimitBytes,
                    if (prefs.extendedStorage) extendedMaximumBytes else 0L) { size -> onStorageUpdate { it.copy(discoveryCustomLimitBytes = size) } }
                Text("If existing data exceeds this maximum, new indexing waits. Existing eligible results are retained.")
            }
        }
        state.error?.takeUnless { it == state.processing.message }?.let { Text(it) }
        val indexingAction = state.processing.indexingAction
        Button(onClick = if (indexingAction == com.example.notionreader.application.DiscoveryIndexingAction.Resume) onResume else onPause,
            enabled = indexingAction != null) {
            Text(when (indexingAction) {
                com.example.notionreader.application.DiscoveryIndexingAction.Resume -> "Resume indexing"
                com.example.notionreader.application.DiscoveryIndexingAction.Pause -> "Pause indexing"
                null -> if (state.modelInstalled) "Checking indexing status…" else "Set up Discovery to index Notes"
            })
        }
        Button(onClick = onClearIndex) { Text("Clear index and pause indexing") }
        Button(onClick = onRebuild, enabled = state.modelInstalled) { Text("Rebuild index") }
        Button(onClick = onBack) { Text("Back") }
    }
}
