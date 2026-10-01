package com.example.notionreader.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Privacy & data settings subpage.
 *
 * Displays local data footprint and provides explicit controls to clear history,
 * cached notes, and all local application data.
 */
@Composable
fun SettingsPrivacyScreen(
    historyCount: Int,
    cachedNotesCount: Int,
    cacheSizeBytes: Long = 0L,
    onClearHistory: () -> Unit,
    onClearCache: () -> Unit,
    onClearAllData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmAction by remember { mutableStateOf<String?>(null) }

    when (confirmAction) {
        "history" -> AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = {
                Text(
                    text = "Clear listening history?",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                Text("This removes Continue Listening and recently completed sessions.\n\nSaved Queues, Smart Queues, cached Notes, and your Notion pages will not be removed.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmAction = null
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear history")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancel") }
            }
        )

        "cache" -> ClearCacheConfirmationDialog(
            onConfirm = onClearCache,
            onDismissRequest = { confirmAction = null }
        )

        "all" -> AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = {
                Text(
                    text = "Clear all local app data?",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                Text(
                    "This will remove:\n" +
                    "• listening history\n" +
                    "• Saved Queues\n" +
                    "• Smart Queues\n" +
                    "• cached Notes\n" +
                    "• offline downloads\n" +
                    "• app preferences\n\n" +
                    "Your Notion pages will not be deleted.\n\n" +
                    "Your Notion connection will remain configured."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmAction = null
                        onClearAllData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear all local data")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancel") }
            }
        )
    }

    SettingsScreenLayout(
        title = "Privacy & data",
        paneTitle = "Privacy & data settings",
        onBack = onBack,
        modifier = modifier
    ) {
        SettingsSection(title = "ON THIS DEVICE") {
            SettingsStatusRow(
                title = "Listening history",
                value = "$historyCount sessions"
            )
            SettingsDivider()
            val cachedText = if (cachedNotesCount > 0) {
                "$cachedNotesCount Notes · ${formatBytes(cacheSizeBytes)}"
            } else {
                "No Notes cached"
            }
            SettingsStatusRow(
                title = "Cached Notes",
                value = cachedText
            )
        }

        SettingsSection(title = "DATA MANAGEMENT") {
            SettingsActionRow(
                title = "Clear listening history",
                enabled = historyCount > 0,
                onClick = { confirmAction = "history" }
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Clear cached Notes",
                enabled = cachedNotesCount > 0,
                onClick = { confirmAction = "cache" }
            )
        }

        SettingsSection(title = "DELETE") {
            SettingsDestructiveRow(
                title = "Clear all local app data",
                onClick = { confirmAction = "all" }
            )
        }
    }
}
