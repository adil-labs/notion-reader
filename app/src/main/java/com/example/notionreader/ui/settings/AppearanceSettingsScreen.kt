package com.example.notionreader.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.notionreader.domain.settings.AppTheme
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.ReaderSpacing

/**
 * Appearance settings subpage.
 *
 * Configures application theme and reader text spacing.
 */
@Composable
fun SettingsAppearanceScreen(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSpacingDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        SettingsSingleChoiceDialog(
            title = "Theme",
            options = AppTheme.values().toList(),
            selectedOption = settings.appTheme,
            optionLabel = { it.label },
            onOptionSelected = { theme -> onUpdate { it.copy(appTheme = theme) } },
            onDismissRequest = { showThemeDialog = false }
        )
    }

    if (showSpacingDialog) {
        SettingsSingleChoiceDialog(
            title = "Reader spacing",
            options = ReaderSpacing.values().toList(),
            selectedOption = settings.readerSpacing,
            optionLabel = { it.label },
            onOptionSelected = { spacing -> onUpdate { it.copy(readerSpacing = spacing) } },
            onDismissRequest = { showSpacingDialog = false }
        )
    }

    SettingsScreenLayout(
        title = "Appearance",
        paneTitle = "Appearance settings",
        onBack = onBack,
        modifier = modifier
    ) {
        SettingsSection(title = "APP") {
            SettingsValueRow(
                title = "Theme",
                value = settings.appTheme.label,
                onClick = { showThemeDialog = true }
            )
        }

        SettingsSection(title = "READING") {
            SettingsValueRow(
                title = "Reader spacing",
                value = settings.readerSpacing.label,
                onClick = { showSpacingDialog = true }
            )
        }
    }
}
