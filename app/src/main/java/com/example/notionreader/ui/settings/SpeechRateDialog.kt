package com.example.notionreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.settings.SpeechRate
import kotlin.math.roundToInt

/**
 * Accessible dialog for configuring speech rate.
 *
 * Supports selecting the platform system default or adjusting a discrete slider
 * from 0.50× to 2.00× in 0.05× increments with TalkBack-accessible progress semantics.
 */
@Composable
fun SpeechRateDialog(
    currentRate: SpeechRate,
    onRateSelected: (SpeechRate) -> Unit,
    onDismissRequest: () -> Unit
) {
    var isSystemDefault by remember {
        mutableStateOf(currentRate is SpeechRate.SystemDefault)
    }

    val initialMultiplier = when (currentRate) {
        is SpeechRate.Multiplier -> currentRate.value
        is SpeechRate.SystemDefault -> 1.0f
    }

    var sliderValue by remember {
        mutableFloatStateOf(initialMultiplier.coerceIn(0.5f, 2.0f))
    }

    fun formatMultiplier(value: Float): String {
        val stepped = (value * 20.0f).roundToInt() / 20.0f
        return SpeechRate.Multiplier(stepped).label
    }

    val currentFormatted = formatMultiplier(sliderValue)
    val stateDescriptionText = if (isSystemDefault) {
        "System default"
    } else {
        "${currentFormatted.replace("×", "")} times"
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Speech rate",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Rate value readout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSystemDefault) "System default" else currentFormatted,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!isSystemDefault) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Slower",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = sliderValue,
                                onValueChange = { newValue ->
                                    val stepped = (newValue * 20.0f).roundToInt() / 20.0f
                                    sliderValue = stepped.coerceIn(0.5f, 2.0f)
                                },
                                valueRange = 0.5f..2.0f,
                                steps = 29, // 30 intervals in [0.5, 2.0] at 0.05 step
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Speech rate"
                                        stateDescription = stateDescriptionText
                                        setProgress { targetValue ->
                                            val stepped = (targetValue * 20.0f).roundToInt() / 20.0f
                                            sliderValue = stepped.coerceIn(0.5f, 2.0f)
                                            true
                                        }
                                    }
                            )
                            Text(
                                text = "Faster",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // System default toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isSystemDefault,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                isSystemDefault = checked
                            }
                        )
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) { },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Use system default",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isSystemDefault,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalRate = if (isSystemDefault) {
                        SpeechRate.SystemDefault
                    } else {
                        val stepped = (sliderValue * 20.0f).roundToInt() / 20.0f
                        SpeechRate.Multiplier(stepped)
                    }
                    onRateSelected(finalRate)
                    onDismissRequest()
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
