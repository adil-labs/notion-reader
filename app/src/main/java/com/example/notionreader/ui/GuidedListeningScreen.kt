package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun GuidedListeningScreen(
    notes: List<DiscoveryNoteUi>,
    onRemove: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    unavailableIds: Set<String> = emptySet(),
    status: String? = null
) {
    val preview = notes.distinctBy { it.id }.take(5)
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp)
        .semantics { paneTitle = "Guided listening" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Guided listening", Modifier.semantics { heading() })
        Text("Preview the listening order. Play starts a new queue from the beginning.")
        status?.let { Text(it) }
        if (preview.isEmpty()) Text("No Notes in this preview.")
        preview.forEachIndexed { index, note -> key(note.id) {
            Text("${index + 1}. ${note.title}", Modifier.semantics { heading() })
            if (note.id in unavailableIds) Text("Note unavailable. Remove it or recache it to use this preview.")
            if (note.sectionLabel.isNotBlank()) Text(note.sectionLabel)
            Text(note.excerpt)
            TextButton(onClick = { onMoveUp(note.id) }, enabled = index > 0) { Text("Move ${note.title} up") }
            TextButton(onClick = { onMoveDown(note.id) }, enabled = index < preview.lastIndex) { Text("Move ${note.title} down") }
            TextButton(onClick = { onRemove(note.id) }) { Text("Remove ${note.title}") }
        } }
        Button(onClick = onPlay, enabled = preview.isNotEmpty() && unavailableIds.isEmpty(),
            modifier = Modifier.restoreFocusOn(focusTarget, "preview_play", onFocusHandled)) { Text("Play") }
        Button(onClick = onSave, enabled = preview.isNotEmpty() && unavailableIds.isEmpty(),
            modifier = Modifier.restoreFocusOn(focusTarget, "preview_save", onFocusHandled)) { Text("Save to queue") }
        Button(onClick = onBack) { Text("Back") }
    }
}
