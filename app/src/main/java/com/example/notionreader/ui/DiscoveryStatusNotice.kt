package com.example.notionreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.example.notionreader.application.DiscoveryState

@Composable
internal fun DiscoveryStatusNotice(state: DiscoveryState, includeWork: Boolean = false) {
    val status = discoveryStatus(state)
    if (status.incomplete) status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(status.coverage, style = MaterialTheme.typography.bodySmall)
    if (includeWork) status.workDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
