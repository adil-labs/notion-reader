package com.example.notionreader.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.settings.AnnouncementVerbosity
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.VoicePitch
import com.example.notionreader.player.SpeechEngineInfo
import com.example.notionreader.player.SpeechEngineState

/**
 * Speech settings subpage.
 *
 * Configures TTS engine, rate, pitch, playback announcements, and links to Android system TTS settings.
 */
@Composable
fun SettingsSpeechScreen(
    settings: ReaderSettings,
    engineState: SpeechEngineState = SpeechEngineState(),
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showEngineDialog by remember { mutableStateOf(false) }
    var showRateDialog by remember { mutableStateOf(false) }
    var showPitchDialog by remember { mutableStateOf(false) }
    var showAnnouncementsDialog by remember { mutableStateOf(false) }

    val currentEngineLabel = when {
        engineState.isFallback -> "Unavailable engine · using system default"
        settings.ttsEnginePackage == null -> "System default"
        else -> engineState.availableEngines.firstOrNull { it.packageName == settings.ttsEnginePackage }?.label
            ?: settings.ttsEnginePackage
    }

    if (showEngineDialog) {
        val engineOptions = listOf<SpeechEngineInfo?>(null) + engineState.availableEngines
        val selectedOption = engineOptions.firstOrNull { it?.packageName == settings.ttsEnginePackage }
        SettingsSingleChoiceDialog(
            title = "Text-to-speech engine",
            options = engineOptions,
            selectedOption = selectedOption,
            optionLabel = { it?.label ?: "System default" },
            onOptionSelected = { chosen ->
                onUpdate { it.copy(ttsEnginePackage = chosen?.packageName) }
            },
            onDismissRequest = { showEngineDialog = false }
        )
    }

    if (showRateDialog) {
        SpeechRateDialog(
            currentRate = settings.speechRate,
            onRateSelected = { rate -> onUpdate { it.copy(speechRate = rate) } },
            onDismissRequest = { showRateDialog = false }
        )
    }

    if (showPitchDialog) {
        SettingsSingleChoiceDialog(
            title = "Voice pitch",
            options = VoicePitch.values().toList(),
            selectedOption = settings.voicePitch,
            optionLabel = { it.label },
            onOptionSelected = { pitch -> onUpdate { it.copy(voicePitch = pitch) } },
            onDismissRequest = { showPitchDialog = false }
        )
    }

    if (showAnnouncementsDialog) {
        SettingsSingleChoiceDialog(
            title = "Playback announcements",
            options = AnnouncementVerbosity.values().toList(),
            selectedOption = settings.playbackAnnouncements,
            optionLabel = { it.label },
            onOptionSelected = { verbosity -> onUpdate { it.copy(playbackAnnouncements = verbosity) } },
            onDismissRequest = { showAnnouncementsDialog = false }
        )
    }

    SettingsScreenLayout(
        title = "Speech",
        paneTitle = "Speech settings",
        onBack = onBack,
        modifier = modifier
    ) {
        // Fallback warning if selected engine is unavailable
        if (engineState.isFallback) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Selected speech engine is unavailable. Using the system default engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        SettingsSection(title = "VOICE") {
            SettingsValueRow(
                title = "Text-to-speech engine",
                value = currentEngineLabel,
                onClick = { showEngineDialog = true }
            )
            SettingsDivider()
            SettingsValueRow(
                title = "Speech rate",
                value = settings.speechRate.label,
                onClick = { showRateDialog = true }
            )
            SettingsDivider()
            SettingsValueRow(
                title = "Voice pitch",
                value = settings.voicePitch.label,
                onClick = { showPitchDialog = true }
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Open Android text-to-speech settings",
                onClick = {
                    try {
                        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
            )
        }

        SettingsSection(title = "FEEDBACK") {
            SettingsValueRow(
                title = "Playback announcements",
                value = settings.playbackAnnouncements.label,
                summary = "Controls how much spoken playback and navigation feedback the app provides.",
                onClick = { showAnnouncementsDialog = true }
            )
        }
    }
}
