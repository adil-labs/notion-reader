package com.example.notionreader.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.notionreader.domain.settings.NavigationUnit
import com.example.notionreader.domain.settings.NotePauseDuration
import com.example.notionreader.domain.settings.ReaderSettings

/**
 * Playback settings subpage.
 *
 * Configures default navigation granularity, queue continuity, note pause duration,
 * and screen wake behavior during active audio playback.
 */
@Composable
fun SettingsPlaybackScreen(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showNavDialog by remember { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }

    if (showNavDialog) {
        SettingsSingleChoiceDialog(
            title = "Default navigation",
            options = NavigationUnit.values().toList(),
            selectedOption = settings.defaultNavigationUnit,
            optionLabel = { it.label },
            onOptionSelected = { unit -> onUpdate { it.copy(defaultNavigationUnit = unit) } },
            onDismissRequest = { showNavDialog = false }
        )
    }

    if (showPauseDialog) {
        SettingsSingleChoiceDialog(
            title = "Pause between Notes",
            options = NotePauseDuration.values().toList(),
            selectedOption = settings.pauseBetweenNotes,
            optionLabel = { it.label },
            onOptionSelected = { duration -> onUpdate { it.copy(pauseBetweenNotes = duration) } },
            onDismissRequest = { showPauseDialog = false }
        )
    }

    SettingsScreenLayout(
        title = "Playback",
        paneTitle = "Playback settings",
        onBack = onBack,
        modifier = modifier
    ) {
        SettingsSection(title = "NAVIGATION") {
            SettingsValueRow(
                title = "Default navigation",
                value = settings.defaultNavigationUnit.label,
                onClick = { showNavDialog = true }
            )
        }

        SettingsSection(title = "QUEUE PLAYBACK") {
            SettingsSwitchRow(
                title = "Continue to next Note",
                checked = settings.continueToNextNote,
                onCheckedChange = { checked -> onUpdate { it.copy(continueToNextNote = checked) } },
                summary = "Automatically starts the next Note in the current queue."
            )
            SettingsDivider()
            val pauseSummary = if (settings.continueToNextNote) {
                null
            } else {
                "Available when Continue to next Note is on."
            }
            SettingsValueRow(
                title = "Pause between Notes",
                value = settings.pauseBetweenNotes.label,
                summary = pauseSummary,
                enabled = settings.continueToNextNote,
                onClick = { showPauseDialog = true }
            )
        }

        SettingsSection(title = "DEVICE") {
            SettingsSwitchRow(
                title = "Keep screen awake while playing",
                checked = settings.keepScreenAwake,
                onCheckedChange = { checked -> onUpdate { it.copy(keepScreenAwake = checked) } },
                summary = "Prevents the display from sleeping during playback."
            )
        }
    }
}
