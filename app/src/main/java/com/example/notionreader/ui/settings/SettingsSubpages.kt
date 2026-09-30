package com.example.notionreader.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.domain.settings.AnnouncementVerbosity
import com.example.notionreader.domain.settings.AppTheme
import com.example.notionreader.domain.settings.DownloadNetworkPolicy
import com.example.notionreader.domain.settings.NavigationUnit
import com.example.notionreader.domain.settings.NotePauseDuration
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.ReaderSpacing
import com.example.notionreader.domain.settings.SpeechRate
import com.example.notionreader.domain.settings.VoicePitch

@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "Navigate back to Settings" }
        ) {
            Text("←", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun SettingsToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .semantics {
                contentDescription = "$title. Switch, ${if (checked) "on" else "off"}. $subtitle."
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null // Click handled by parent semantic card
            )
        }
    }
}

// ----------------------------------------------------------------------------
// 1. Playback Settings Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsPlaybackScreen(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "Playback settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "Playback", onBack = onBack)

        // Preference: Default navigation unit
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Default navigation unit",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Starting step mode when opening the Player",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NavigationUnit.values().forEach { unit ->
                        FilterChip(
                            selected = settings.defaultNavigationUnit == unit,
                            onClick = { onUpdate { it.copy(defaultNavigationUnit = unit) } },
                            label = { Text(unit.label) }
                        )
                    }
                }
            }
        }

        // Preference: Continue to next Note
        SettingsToggleCard(
            title = "Continue to next Note",
            subtitle = "When on, playback continues automatically through the queue. When off, playback pauses at the end of each Note.",
            checked = settings.continueToNextNote,
            onCheckedChange = { checked -> onUpdate { it.copy(continueToNextNote = checked) } }
        )

        // Preference: Pause between Notes
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Pause between Notes",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NotePauseDuration.values().forEach { duration ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.pauseBetweenNotes == duration,
                                    onClick = { onUpdate { it.copy(pauseBetweenNotes = duration) } },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.pauseBetweenNotes == duration,
                                onClick = null
                            )
                            Text(
                                text = duration.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Preference: Keep screen awake while playing
        SettingsToggleCard(
            title = "Keep screen awake while playing",
            subtitle = "Prevents the device display from sleeping during active playback.",
            checked = settings.keepScreenAwake,
            onCheckedChange = { checked -> onUpdate { it.copy(keepScreenAwake = checked) } }
        )
    }
}

// ----------------------------------------------------------------------------
// 2. Speech Settings Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsSpeechScreen(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "Speech settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "Speech", onBack = onBack)

        // Use system speech settings
        SettingsToggleCard(
            title = "Use system speech settings",
            subtitle = "Voice, pitch, and language come from Android's TTS configuration.",
            checked = settings.useSystemSpeechSettings,
            onCheckedChange = { checked -> onUpdate { it.copy(useSystemSpeechSettings = checked) } }
        )

        // System TTS Launcher
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) {
                    try {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    } catch (_: Exception) { }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Android Text-to-Speech settings")
        }

        // Speech rate
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Speech rate",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SpeechRate.values().forEach { rate ->
                        FilterChip(
                            selected = settings.speechRate == rate,
                            onClick = { onUpdate { it.copy(speechRate = rate) } },
                            label = { Text(rate.label) }
                        )
                    }
                }
            }
        }

        // Custom pitch if system settings disabled
        if (!settings.useSystemSpeechSettings) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Voice pitch",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VoicePitch.values().forEach { pitch ->
                            FilterChip(
                                selected = settings.voicePitch == pitch,
                                onClick = { onUpdate { it.copy(voicePitch = pitch) } },
                                label = { Text(pitch.label) }
                            )
                        }
                    }
                }
            }
        }

        // Playback announcements verbosity
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Playback announcements",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Amount of spoken navigation and state feedback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AnnouncementVerbosity.values().forEach { verbosity ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.playbackAnnouncements == verbosity,
                                    onClick = { onUpdate { it.copy(playbackAnnouncements = verbosity) } },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.playbackAnnouncements == verbosity,
                                onClick = null
                            )
                            Text(
                                text = verbosity.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Android TTS Settings Intent
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (_: Exception) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text("Open Android Text-to-Speech Settings")
        }
    }
}

// ----------------------------------------------------------------------------
// 3. Offline & Storage Settings Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsOfflineScreen(
    settings: ReaderSettings,
    cachedNotesCount: Int,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirmation by remember { mutableStateOf(false) }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear cached Notes?") },
            text = {
                Text("This removes downloaded offline content. Your Notion pages and saved queues will not be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmation = false
                        onClearCache()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear cache")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "Offline and storage settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "Offline & storage", onBack = onBack)

        SettingsToggleCard(
            title = "Cache Notes for offline playback",
            subtitle = "A Note that has been read becomes available offline.",
            checked = settings.cacheNotesForOffline,
            onCheckedChange = { checked -> onUpdate { it.copy(cacheNotesForOffline = checked) } }
        )

        SettingsToggleCard(
            title = "Prefetch upcoming Notes",
            subtitle = "The app downloads upcoming Notes in the current queue in advance.",
            checked = settings.prefetchUpcomingNotes,
            onCheckedChange = { checked -> onUpdate { it.copy(prefetchUpcomingNotes = checked) } }
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Download on",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DownloadNetworkPolicy.values().forEach { policy ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.downloadNetworkPolicy == policy,
                                    onClick = { onUpdate { it.copy(downloadNetworkPolicy = policy) } },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.downloadNetworkPolicy == policy,
                                onClick = null
                            )
                            Text(
                                text = policy.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$cachedNotesCount Notes currently cached offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { showClearConfirmation = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear cache")
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 4. Accessibility Settings Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsAccessibilityScreen(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "Accessibility settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "Accessibility", onBack = onBack)

        SettingsToggleCard(
            title = "Keep screen awake while playing",
            subtitle = "Holds display active while speech is playing to prevent screen timeout.",
            checked = settings.keepScreenAwake,
            onCheckedChange = { checked -> onUpdate { it.copy(keepScreenAwake = checked) } }
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Reader spacing",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Visual paragraph spacing in the Note inspection screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderSpacing.values().forEach { spacing ->
                        FilterChip(
                            selected = settings.readerSpacing == spacing,
                            onClick = { onUpdate { it.copy(readerSpacing = spacing) } },
                            label = { Text(spacing.label) }
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 5. Appearance Settings Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsAppearanceScreen(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "Appearance settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "Appearance", onBack = onBack)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppTheme.values().forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.appTheme == theme,
                                    onClick = { onUpdate { it.copy(appTheme = theme) } },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.appTheme == theme,
                                onClick = null
                            )
                            Text(
                                text = theme.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Reader spacing",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderSpacing.values().forEach { spacing ->
                        FilterChip(
                            selected = settings.readerSpacing == spacing,
                            onClick = { onUpdate { it.copy(readerSpacing = spacing) } },
                            label = { Text(spacing.label) }
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 6. Notion Settings Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsNotionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "Notion settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "Notion", onBack = onBack)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Connected workspace",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Personal Workspace",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Connected and active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Manage page access")
        }

        OutlinedButton(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reconnect")
        }

        Button(
            onClick = { },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Disconnect Notion")
        }
    }
}

// ----------------------------------------------------------------------------
// 7. Privacy & Data Settings Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsPrivacyScreen(
    historyCount: Int,
    cachedNotesCount: Int,
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
            title = { Text("Clear listening history?") },
            text = {
                Text("This removes your Continue Listening and recently completed history. Saved Queues and downloaded Notes will not be removed.")
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

        "cache" -> AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text("Clear cached Notes?") },
            text = {
                Text("This removes downloaded offline content. Your Notion pages and saved queues will not be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmAction = null
                        onClearCache()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear cache")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancel") }
            }
        )

        "all" -> AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text("Clear all local data?") },
            text = {
                Text("This removes:\n• listening history\n• saved queues\n• smart queues\n• cached Notes\n• offline downloads\n• app preferences\n\nYour Notion pages are not deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmAction = null
                        onClearAllData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear all data")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "Privacy and data settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "Privacy & data", onBack = onBack)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Local Storage",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Listening history: $historyCount sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Offline content: $cachedNotesCount cached Notes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(
            onClick = { confirmAction = "history" },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear listening history")
        }

        OutlinedButton(
            onClick = { confirmAction = "cache" },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear cached Notes")
        }

        Button(
            onClick = { confirmAction = "all" },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear all local data")
        }
    }
}

// ----------------------------------------------------------------------------
// 8. About Screen
// ----------------------------------------------------------------------------

@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { paneTitle = "About Notion Reader" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsTopBar(title = "About", onBack = onBack)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Notion Reader",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Version 1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "A TalkBack-first listening reader turning Notion pages into sequential, hands-free spoken playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open-source licenses")
        }

        OutlinedButton(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Privacy policy")
        }

        OutlinedButton(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Report a problem")
        }
    }
}
