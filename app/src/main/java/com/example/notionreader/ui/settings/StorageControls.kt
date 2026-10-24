package com.example.notionreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.document.StorageDefaults
import com.example.notionreader.domain.settings.ReaderSettings
import kotlin.math.roundToInt
import java.util.Locale

/** Usage and maximums both use binary units; a maximum never implies preallocation. */
fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes bytes"
}

fun storageSize(bytes: Long): String = if (bytes >= 1024 * StorageDefaults.MIB && bytes % (1024 * StorageDefaults.MIB) == 0L)
    "${bytes / (1024 * StorageDefaults.MIB)} GiB" else "${bytes / StorageDefaults.MIB} MiB"

@Composable
fun StorageLimitControl(label: String, bytes: Long, extendedMaximum: Long = 0L, onCommit: (Long) -> Unit) {
    val choices = remember(bytes, extendedMaximum) {
        (StorageDefaults.sizeSteps + listOf(bytes) + listOfNotNull(extendedMaximum.takeIf { it > StorageDefaults.sizeSteps.last() }))
            .distinct().sorted()
    }
    var position by remember(bytes, choices) { mutableStateOf(choices.indexOf(bytes).toFloat()) }
    fun commit(index: Int) {
        onCommit(choices[index])
        // Until a confirmed/persisted value arrives, display the authoritative choice.
        position = choices.indexOf(bytes).toFloat()
    }
    Text("$label: ${storageSize(choices[position.roundToInt().coerceIn(choices.indices)])}")
    Slider(value = position, onValueChange = { position = it },
        onValueChangeFinished = { commit(position.roundToInt().coerceIn(choices.indices)) },
        valueRange = 0f..choices.lastIndex.toFloat(), steps = (choices.size - 2).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = label
            stateDescription = storageSize(choices[position.roundToInt().coerceIn(choices.indices)])
            setProgress { target -> commit(target.roundToInt().coerceIn(choices.indices)); true }
        })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { commit((position.roundToInt() - 1).coerceAtLeast(0)) }, enabled = position > 0f) { Text("Decrease $label") }
        OutlinedButton(onClick = { commit((position.roundToInt() + 1).coerceAtMost(choices.lastIndex)) }, enabled = position < choices.lastIndex) { Text("Increase $label") }
    }
}

@Composable
fun StoragePolicyExplanation(settings: ReaderSettings, onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    if (!settings.storagePolicyExplained) {
        Column {
            Text("Local storage for your Notes", Modifier.semantics { heading() })
            Text("Notes use a 128 MiB cache by default. Discovery data grows automatically as cached Notes are indexed, while keeping space free for your device. You can change limits in settings. Increasing a limit does not download more Notes by itself.")
            TextButton(onClick = { onUpdate { it.copy(storagePolicyExplained = true) } }) { Text("Got it") }
        }
    }
}

@Composable
fun ExpandableStorageExplanation(
    collapsedSummary: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "How storage works",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!expanded && !collapsedSummary.isNullOrBlank()) {
                    Text(
                        text = collapsedSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = if (expanded) "▴" else "▾",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}
