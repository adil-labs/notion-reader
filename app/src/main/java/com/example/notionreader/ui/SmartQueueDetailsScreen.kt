package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.application.SmartQueueCard
import com.example.notionreader.domain.ReaderIndexEntry

/**
 * Details for a single Smart Queue: query definition, matching notes preview, and playback/editing actions.
 */
@Composable
fun SmartQueueDetailsScreen(
    queue: SmartQueueCard?,
    matchingNotes: List<ReaderIndexEntry>,
    onOpenNote: (String) -> Unit,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    report: String? = null,
    onReportShown: () -> Unit = {},
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    if (queue == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .semantics { paneTitle = "Queue not found" }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Queue no longer exists",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "This smart queue is no longer available. Press Back to return to Queues.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    val currentOnReportShown by rememberUpdatedState(onReportShown)
    DisposableEffect(Unit) {
        onDispose { currentOnReportShown() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "${queue.name} details" }
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = queue.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = "Query: \"${queue.queryText}\"",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = queue.memberDescription,
            style = MaterialTheme.typography.labelMedium
        )

        if (report != null) {
            Text(
                text = report,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        Button(
            onClick = { onPlay(queue.queueId) },
            modifier = Modifier.restoreFocusOn(
                target = focusTarget,
                id = ReaderFocus.SmartQueuePlay,
                onHandled = onFocusHandled
            )
        ) {
            Text("Play")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    renameText = queue.name
                    showRenameDialog = true
                },
                modifier = Modifier.restoreFocusOn(
                    target = focusTarget,
                    id = ReaderFocus.SmartQueueRename,
                    onHandled = onFocusHandled
                )
            ) {
                Text("Rename")
            }

            Button(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.restoreFocusOn(
                    target = focusTarget,
                    id = ReaderFocus.SmartQueueDelete,
                    onHandled = onFocusHandled
                )
            ) {
                Text("Delete")
            }
        }

        Text(
            text = "Matching notes (${matchingNotes.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        if (matchingNotes.isEmpty()) {
            Text(
                text = "No notes currently match this query in your workspace.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            matchingNotes.forEach { note ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNote(note.pageId) }
                        .semantics(mergeDescendants = true) {}
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = "Rename smart queue",
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Queue name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            onRename(queue.queueId, trimmed)
                            showRenameDialog = false
                        }
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Delete smart queue",
                    modifier = Modifier.semantics { heading() }
                )
            },
            text = {
                Text("Delete smart queue \"${queue.name}\"?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(queue.queueId)
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
