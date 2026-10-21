package com.example.notionreader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.document.StorageDefaults
import com.example.notionreader.domain.settings.ReaderSettings
import kotlin.math.roundToInt

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
