package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * `Create smart queue`: name and query fields, and a control that is disabled until both are usable.
 */
@Composable
fun CreateSmartQueueScreen(
    onCreate: (name: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
    initialName: String = "",
    initialQuery: String = ""
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }

    val isCompletable = name.trim().isNotEmpty() && query.trim().isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = "Create smart queue" }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Create smart queue",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = "Give the queue a name and filter query. Matches against note titles, kinds, areas, and topics in your workspace will automatically be included when this queue is played.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Queue name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter query") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onCreate(name.trim(), query.trim()) },
            enabled = isCompletable
        ) {
            Text("Create")
        }
    }
}
