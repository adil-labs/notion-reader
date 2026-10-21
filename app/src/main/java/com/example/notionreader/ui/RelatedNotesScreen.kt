package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun RelatedNotesScreen(
    notes: List<DiscoveryNoteUi>,
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onGuided: (() -> Unit)? = null,
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {},
    status: String? = null,
    discovery: DiscoveryStatusUi? = null
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp)
        .semantics { paneTitle = "Related Notes" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Related Notes", Modifier.semantics { heading() })
        discovery?.let { shared ->
            if (shared.incomplete) shared.message?.let { Text(it) }
            Text(shared.coverage)
        }
        status?.let { Text(it) }
        if (notes.isEmpty() && status == null) Text(if (discovery?.incomplete == true)
            "No related matches in the indexed Notes yet." else "No related cached Notes found.")
        notes.distinctBy { it.id }.take(5).forEach { note -> key(note.id) {
            DiscoveryNoteRow(note, onOpenNote, focusTarget, onFocusHandled)
        } }
        onGuided?.let { Button(onClick = it, enabled = notes.isNotEmpty(),
            modifier = Modifier.restoreFocusOn(focusTarget, "preview_guided", onFocusHandled)) { Text("Preview guided listening") } }
        Button(onClick = onBack) { Text("Back") }
    }
}
