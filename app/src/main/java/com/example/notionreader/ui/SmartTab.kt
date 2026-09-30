package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderFocus
import com.example.notionreader.application.SmartQueueCard

/**
 * The Smart tab: persistent, query-defined queues.
 */
@Composable
fun SmartTab(
    queues: List<SmartQueueCard>,
    onOpenQueue: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    focusTarget: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    LaunchedEffect(focusTarget, queues) {
        if (focusTarget != null && queues.none { it.queueId == focusTarget }) onFocusHandled()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (queues.isEmpty()) {
            Text(
                text = "No smart queues yet.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            Text(
                text = "A smart queue automatically collects Notes matching a search query or filter. Create one to keep an up-to-date queue.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = "Smart queues",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )

            queues.forEach { queue ->
                SmartQueueRow(
                    queue = queue,
                    onOpen = { onOpenQueue(queue.queueId) },
                    focusTarget = focusTarget,
                    onFocusHandled = onFocusHandled
                )
            }
        }

        Button(
            onClick = onCreate,
            modifier = Modifier.restoreFocusOn(
                target = focusTarget,
                id = ReaderFocus.SmartQueueCreate,
                onHandled = onFocusHandled
            )
        ) {
            Text("Create smart queue")
        }
    }
}

@Composable
private fun SmartQueueRow(
    queue: SmartQueueCard,
    onOpen: () -> Unit,
    focusTarget: String?,
    onFocusHandled: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .restoreFocusOn(
                target = focusTarget,
                id = queue.queueId,
                onHandled = onFocusHandled
            )
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = queue.name,
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Query: \"${queue.queryText}\" • ${queue.memberDescription}",
            style = MaterialTheme.typography.labelMedium
        )
    }
}
