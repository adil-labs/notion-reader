package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class DiscoveryNoteUi(val id: String, val title: String, val excerpt: String, val sectionLabel: String)
data class DiscoveryInterestUi(val id: String, val label: String, val available: Boolean = true, val seed: Boolean = false)

@Composable
fun DiscoveryScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    indexedCount: Int,
    cachedCount: Int,
    results: List<DiscoveryNoteUi>,
    interests: List<DiscoveryInterestUi>,
    onSearch: () -> Unit,
    onOpenNote: (String) -> Unit,
    onAddInterest: (String) -> Unit,
    onEditInterest: (String, String) -> Unit,
    onRemoveInterest: (String) -> Unit,
    onSelectSeed: () -> Unit,
    modifier: Modifier = Modifier,
    onRelated: ((String) -> Unit)? = null,
    onGuided: (() -> Unit)? = null,
    olderNotes: List<DiscoveryNoteUi> = emptyList(),
    filterControls: @Composable () -> Unit = {},
    retainedContent: @Composable () -> Unit = {},
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    status: String? = null,
    recommendations: List<DiscoveryNoteUi> = emptyList(),
    onManageDiscovery: (() -> Unit)? = null,
    onEditSeed: ((String) -> Unit)? = null,
    topicsContent: @Composable () -> Unit = {},
    continueContent: @Composable () -> Unit = {},
    submittedSummary: String? = null,
    onViewInterest: ((String) -> Unit)? = null,
    semanticAvailable: Boolean = true,
    onBrowseNotes: (() -> Unit)? = null,
    onViewInterests: (() -> Unit)? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submitSearch = { keyboard?.hide(); onSearch() }
    var draft by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var describing by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp)
        .semantics { paneTitle = "Discover" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Discover", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        continueContent()
        if (!semanticAvailable || cachedCount == 0) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (!semanticAvailable) "Discover more in your Notes" else "Start with a few Notes",
                        Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text("$indexedCount of $cachedCount cached Notes indexed", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (!semanticAvailable) status ?: "Set up the optional on-device model to find topics and connections."
                        else "Open Notes in Browse to make them available for local search and discovery.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (!semanticAvailable) onManageDiscovery?.let { action ->
                        Button(onClick = action, modifier = Modifier.restoreFocusOn(focusTarget, "discovery_settings", onFocusHandled)) {
                            Text("Set up Discovery")
                        }
                    } else onBrowseNotes?.let { action -> TextButton(onClick = action) { Text("Browse Notes") } }
                }
            }
        }
        if (semanticAvailable && cachedCount > 0) {
        OutlinedTextField(query, onQueryChange, label = { Text("Search your cached Notes") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            trailingIcon = { if (query.isNotBlank()) IconButton(onClick = submitSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search cached Notes")
            } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submitSearch() }))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$indexedCount of $cachedCount cached Notes indexed", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { filtersOpen = !filtersOpen }) { Text(if (filtersOpen) "Hide filters" else "Filters") }
        }
        status?.let { Text(it) }
        onManageDiscovery?.let { action -> TextButton(onClick = action, modifier = Modifier.restoreFocusOn(focusTarget, "discovery_settings", onFocusHandled)) { Text("Manage discovery") } }
        if (filtersOpen) filterControls()
        submittedSummary?.let { Text(it) }
        if (results.isNotEmpty()) {
            Text("Search results", Modifier.semantics { heading() })
            results.distinctBy { it.id }.take(20).forEach { note -> key(note.id) {
                DiscoveryNoteRow(note, onOpenNote, focusTarget, onFocusHandled)
                onRelated?.let { action -> TextButton(onClick = { action(note.id) },
                    modifier = Modifier.restoreFocusOn(focusTarget, "discovery_related:${note.id}", onFocusHandled)) { Text("Notes related to ${note.title}") } }
            } }
        }
        topicsContent()
        }
        if (onViewInterests != null || semanticAvailable || interests.isNotEmpty()) {
        if (onViewInterests != null) {
            TextButton(onClick = onViewInterests, modifier = Modifier.restoreFocusOn(focusTarget, "discover_interests", onFocusHandled)) { Text("View your interests") }
        } else {
        Text("Your interests", Modifier.restoreFocusOn(focusTarget?.takeIf { target ->
            target.startsWith("discovery_interest:") && interests.none { "discovery_interest:${it.id}" == target }
        }, focusTarget, onFocusHandled).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (interests.isEmpty()) Text("Follow a topic or add your own interest to find related Notes.")
        interests.forEach { interest -> key(interest.id) {
            if (onViewInterest != null) {
                OutlinedCard(onClick = { onViewInterest(interest.id) }, modifier = Modifier.fillMaxWidth()
                    .restoreFocusOn(focusTarget, "discovery_interest:${interest.id}", onFocusHandled)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(interest.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (!interest.available) "Seed Note unavailable until recached" else "Matching Notes and listening preview",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
            OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(interest.label, style = MaterialTheme.typography.titleMedium)
            if (!interest.available) Text("Seed Note unavailable until recached")
            TextButton(onClick = {
                if (interest.seed) onEditSeed?.invoke(interest.id)
                else { editingId = interest.id; draft = interest.label }
            }, enabled = !interest.seed || onEditSeed != null,
                modifier = Modifier.restoreFocusOn(focusTarget, "discovery_interest:${interest.id}", onFocusHandled)) { Text("Edit ${interest.label}") }
            TextButton(onClick = { onRemoveInterest(interest.id) }) { Text("Remove ${interest.label}") }
            }
            }
            }
        } }
        Button(onClick = { adding = true }, modifier = Modifier.restoreFocusOn(focusTarget, "discovery_seed", onFocusHandled)) { Text("Add interest") }
        if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add interest") },
            text = { Column {
                TextButton(onClick = { adding = false; describing = true }) { Text("Describe a topic") }
                Text("Choose a Note uses cached Notes only.")
                TextButton(onClick = { adding = false; onSelectSeed() }) { Text("Choose a Note") }
            } }, confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
        if (describing || editingId != null) AlertDialog(onDismissRequest = { describing = false; editingId = null; draft = "" },
            title = { Text(if (editingId == null) "Describe a topic" else "Edit interest") },
            text = { OutlinedTextField(draft, { draft = it }, label = { Text(if (editingId == null) "New interest" else "Matching text") }) },
            confirmButton = { TextButton(onClick = {
                val value = draft.trim()
                editingId?.let { onEditInterest(it, value) } ?: onAddInterest(value)
                draft = ""; editingId = null; describing = false
            }, enabled = draft.isNotBlank()) { Text("Save interest") } },
            dismissButton = { TextButton(onClick = { describing = false; editingId = null; draft = "" }) { Text("Cancel") } })
        }
        if (recommendations.isNotEmpty()) {
            Text("Related knowledge", Modifier.semantics { heading() })
            recommendations.distinctBy { it.id }.filter { candidate -> results.none { it.id == candidate.id } }.take(5).forEach { note -> key("interest:${note.id}") {
                DiscoveryNoteRow(note, onOpenNote, focusTarget, onFocusHandled)
            } }
        }
        onGuided?.let { Button(onClick = it, modifier = Modifier.restoreFocusOn(focusTarget, "preview_guided", onFocusHandled)) { Text("Preview guided listening") } }
        if (olderNotes.isNotEmpty()) {
            Text("Older matching Notes", Modifier.semantics { heading() })
            Text("Last edited at least 90 days ago")
            olderNotes.distinctBy { it.id }.take(5).forEach { note -> key("older:${note.id}") {
                DiscoveryNoteRow(note, onOpenNote,
                    focusTarget.takeUnless { target -> results.any { it.id == target } || recommendations.any { it.id == target } }, onFocusHandled)
            } }
        }
        }
        retainedContent()
    }
}

@Composable
internal fun DiscoveryNoteRow(
    note: DiscoveryNoteUi,
    onOpenNote: (String) -> Unit,
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    focusPrefix: String = "",
    excerptMaxLines: Int = 4
) {
    OutlinedCard(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(note.title, style = MaterialTheme.typography.titleMedium)
    if (note.sectionLabel.isNotBlank()) Text(note.sectionLabel, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(note.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = excerptMaxLines, overflow = TextOverflow.Ellipsis)
    TextButton(onClick = { onOpenNote(note.id) }, modifier = Modifier.restoreFocusOn(
        focusTarget, focusPrefix + note.id, onFocusHandled)) { Text("Open ${note.title}") }
    }
    }
}
