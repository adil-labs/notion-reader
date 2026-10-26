package com.example.notionreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.BuildConfig

/**
 * About settings subpage.
 *
 * Displays runtime version metadata, open-source notices, and privacy disclosures without dead click targets.
 */
@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = {
                Text(
                    text = "Open-source licenses",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Notion Reader utilizes the following open-source software:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• Jetpack Compose & AndroidX (Apache 2.0)\n" +
                            "• AndroidX Media3 (Apache 2.0)\n" +
                            "• Kotlin & Kotlinx Coroutines (Apache 2.0)\n" +
                            "• Kotlinx Serialization (Apache 2.0)\n" +
                            "• Ktor HTTP Client (Apache 2.0)\n" +
                            "• AndroidX DataStore (Apache 2.0)\n" +
                            "• LiteRT-LM\n" +
                            "• Optional EmbeddingGemma and Gemma models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = {
                Text(
                    text = "Privacy policy",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Notion Reader processes your content entirely on-device and communicates directly with Notion's official API.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• No analytics, telemetry, or user tracking are collected.\n" +
                            "• Text-to-Speech synthesis is performed locally by your device's TTS engine.\n" +
                            "• Cached notes, listening sessions, and user preferences are stored exclusively in private application sandbox storage.\n" +
                            "• The optional embedding model is downloaded from Hugging Face only on request. Notes and semantic queries stay on this device.\n" +
                            "• Discovery vectors and excerpts are removed with their cached Notes. Interests are explicit and can be cleared with local data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    SettingsScreenLayout(
        title = "About",
        paneTitle = "About settings",
        onBack = onBack,
        modifier = modifier
    ) {
        SettingsSection(title = "APPLICATION") {
            SettingsStatusRow(
                title = "Notion Reader",
                value = "Version ${BuildConfig.VERSION_NAME}"
            )
            SettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "A TalkBack-first reader for listening to Notion content.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsSection(title = "LEGAL") {
            SettingsValueRow(
                title = "Open-source licenses",
                summary = "Third-party software libraries and licenses",
                onClick = { showLicensesDialog = true }
            )
            SettingsDivider()
            SettingsValueRow(
                title = "Privacy policy",
                summary = "On-device processing and data storage disclosures",
                onClick = { showPrivacyDialog = true }
            )
        }
    }
}
