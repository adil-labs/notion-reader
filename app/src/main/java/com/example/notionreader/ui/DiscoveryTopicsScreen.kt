package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderApp

/**
 * Screen displaying the complete catalog of discovered topics.
 */
@Composable
internal fun DiscoveryTopicsScreen(app: ReaderApp) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopicPreviews(app, all = true)
        Button(onClick = { app.back() }) {
            Text("Back")
        }
    }
}
