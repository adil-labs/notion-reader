package com.example.notionreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.settings.DiscoveryEffort
import com.example.notionreader.domain.settings.ReaderSettings

data class DiscoverySettingsUi(
    val modelDescription: String,
    val modelInstalled: Boolean = false,
    val downloading: Boolean = false,
    val wifiOnly: Boolean = true,
    val processing: com.example.notionreader.ui.DiscoveryStatusUi = com.example.notionreader.ui.DiscoveryStatusUi(),
    val error: String? = null,
    val downloadAllowed: Boolean = false,
    val downloadProgress: String? = null,
    val effort: DiscoveryEffort = DiscoveryEffort.BALANCED,
    val discoveryBytes: Long? = null,
    val modelBytes: Long? = null
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
    settings: ReaderSettings? = null,
    onStorageUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit = {},
    storageMessage: String? = null,
    extendedMaximumBytes: Long = 0L
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .semantics { paneTitle = "Discovery settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Discovery settings", Modifier.semantics { heading() })
        settings?.let { StoragePolicyExplanation(it, onStorageUpdate) }

        // Model & Setup Section
        Text("Model & setup", Modifier.semantics { heading() })
        Text(state.modelDescription)
        if (!state.modelInstalled && !state.downloading) {
            Text("Pack download is approximately 165 MB. Embeddings run on this device. Only cached Notes are indexed.")
        } else {
            Text("Embeddings run on this device. Only cached Notes are indexed.")
        }
        state.downloadProgress?.let { Text(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.wifiOnly,
                onCheckedChange = onWifiOnlyChange,
                modifier = Modifier.semantics { contentDescription = "Download using Wi-Fi only" }
            )
            Spacer(Modifier.width(8.dp))
            Text("Download using Wi-Fi only")
        }
        if (state.downloading) {
            Button(onClick = onCancelDownload) { Text("Cancel download") }
        } else if (!state.modelInstalled) {
            Button(onClick = onDownload, enabled = state.downloadAllowed) { Text("Download model") }
        }

        // Processing Section
        Text("Processing", Modifier.semantics { heading() })
        Text(state.processing.coverage)
        state.processing.message?.let { Text(it) }
        state.processing.workDescription?.let { Text(it) }
        Text("Preparation waits below 30% battery unless a charger is connected.")
        Text("Discovery effort", Modifier.semantics { heading() })
        DiscoveryEffort.entries.forEach { effort ->
            Row(
                Modifier
                    .selectable(
                        selected = state.effort == effort,
                        role = Role.RadioButton,
                        onClick = { onEffortChange(effort) }
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = state.effort == effort, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(effort.label)
            }
        }
        Text("Discovery continues automatically, including in the background when Android permits. Effort changes when work runs; quality and coverage stay the same.")
        val indexingAction = state.processing.indexingAction
        Button(
            onClick = if (indexingAction == com.example.notionreader.application.DiscoveryIndexingAction.Resume) onResume else onPause,
            enabled = indexingAction != null
        ) {
            Text(when (indexingAction) {
                com.example.notionreader.application.DiscoveryIndexingAction.Resume -> "Resume indexing"
                com.example.notionreader.application.DiscoveryIndexingAction.Pause -> "Pause indexing"
                null -> if (state.modelInstalled) "Checking indexing status…" else "Set up Discovery to index Notes"
            })
        }

        // Storage Section
        SettingsSection(title = "STORAGE") {
            val dataUsageText = when (state.discoveryBytes) {
                null -> "Usage unavailable"
                else -> "${formatStorageBytes(state.discoveryBytes)} used"
            }
            SettingsStatusRow(title = "Discovery data", value = dataUsageText)
            Text(
                text = "Helps find related Notes, topics and connections using your downloaded Notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            SettingsDivider()

            val modelUsageText = state.modelBytes?.let(::formatStorageBytes) ?: "Usage unavailable"
            SettingsStatusRow(title = "Downloaded model", value = modelUsageText)
            SettingsDivider()

            settings?.let { prefs ->
                Text(
                    text = "Storage management",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { heading() }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = prefs.discoveryStorageAutomatic,
                            role = Role.RadioButton,
                            onClick = { onStorageUpdate { it.copy(discoveryStorageAutomatic = true) } }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = prefs.discoveryStorageAutomatic, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Automatic (recommended)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Uses space as needed while keeping space free for your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = !prefs.discoveryStorageAutomatic,
                            role = Role.RadioButton,
                            onClick = { onStorageUpdate { it.copy(discoveryStorageAutomatic = false) } }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !prefs.discoveryStorageAutomatic, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Set a maximum", style = MaterialTheme.typography.bodyLarge)
                }

                if (!prefs.discoveryStorageAutomatic) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StorageLimitControl(
                            label = "Storage limit",
                            bytes = prefs.discoveryCustomLimitBytes,
                            extendedMaximum = if (prefs.extendedStorage) extendedMaximumBytes else 0L
                        ) { size -> onStorageUpdate { it.copy(discoveryCustomLimitBytes = size) } }
                        Text(
                            text = "When the limit is reached, preparation of additional Discovery data waits. Existing eligible results remain available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            storageMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            SettingsDivider()

            ExpandableStorageExplanation(
                collapsedSummary = if (settings?.extendedStorage == true) "Larger storage limits enabled" else null
            ) {
                Text("• Separate accounting: Installed model files and derived Discovery data are tracked separately. Model files do not count against your custom data limit.", style = MaterialTheme.typography.bodySmall)
                Text("• Cache-only eligibility: Only downloaded offline Notes are indexed for Discovery.", style = MaterialTheme.typography.bodySmall)
                Text("• Device reserve: Storage allocation always keeps the greater of 2 GiB or 10% of device capacity free for your device.", style = MaterialTheme.typography.bodySmall)
                Text("• Larger limits: Larger storage limits can be enabled under Offline & storage settings.${if (settings?.extendedStorage == true) " Larger limits are currently enabled." else ""}", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Management Section
        Text("Index management", Modifier.semantics { heading() })
        Button(onClick = onClearIndex) { Text("Clear index and pause indexing") }
        Button(onClick = onRebuild, enabled = state.modelInstalled) { Text("Rebuild index") }
        if (state.modelInstalled) {
            Button(onClick = onDeleteModel) { Text("Delete model and semantic index") }
        }

        state.error?.takeUnless { it == state.processing.message }?.let { Text(it) }
        Button(onClick = onBack) { Text("Back") }
    }
}
