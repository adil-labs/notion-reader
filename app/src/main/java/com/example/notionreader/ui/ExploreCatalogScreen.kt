package com.example.notionreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ExploreCard
import com.example.notionreader.application.ExploreCatalog

@Composable
fun ExploreCatalogScreen(catalog: ExploreCatalog, onSelect: (ExploreCard) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
            .semantics { paneTitle = "Explore Catalog" },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Explore", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        ExploreSection("Areas", catalog.areas, onSelect)
        ExploreSection("Topics", catalog.topics, onSelect)
    }
}

@Composable
private fun ExploreSection(title: String, cards: List<ExploreCard>, onSelect: (ExploreCard) -> Unit) {
    if (cards.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    cards.forEach { card ->
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onSelect(card) }
                .semantics(mergeDescendants = true) {}.padding(vertical = 12.dp)
        ) {
            Text(card.title, style = MaterialTheme.typography.bodyLarge)
            Text("${card.noteCount} notes", style = MaterialTheme.typography.labelMedium)
        }
    }
}
