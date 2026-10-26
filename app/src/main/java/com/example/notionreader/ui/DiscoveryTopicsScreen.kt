package com.example.notionreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.domain.discovery.*

private data class TopicCatalogRow(val key: String, val topic: DiscoveredTopic? = null,
    val heading: String? = null)

/**
 * Screen displaying the complete catalog of discovered topics.
 */
@Composable
internal fun DiscoveryTopicsScreen(app: ReaderApp) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val published = state.publishedTopics
    val rows = remember(published, state.topicSnapshot.subjectRelationships, state.cachedTitles) {
        val ids = published.mapTo(hashSetOf()) { it.topic.id }
        val parents = state.topicSnapshot.subjectRelationships.filter {
            it.kind == SubjectRelationshipKind.ParentChild && it.parentId in ids && it.childId in ids &&
                canonicalSemanticId(it.evidence.pageId) in state.cachedTitles
        }.mapTo(hashSetOf()) { it.parentId }
        val major = published.filter { it.topic.id in parents }
        val established = published.filter { it.topic.id !in parents && it.status == TopicPublicationStatus.Established }
        val emerging = published.filter { it.topic.id !in parents && it.status == TopicPublicationStatus.Emerging }
        val preparing = published.filter { it.topic.id !in parents && it.status in setOf(TopicPublicationStatus.Pending, TopicPublicationStatus.NoMatches) }
        buildList {
            if (major.isNotEmpty()) {
                add(TopicCatalogRow("major", heading = "Major subjects"))
                major.forEach { add(TopicCatalogRow("topic:${it.topic.id}", topic = it.topic)) }
                if (established.isNotEmpty()) add(TopicCatalogRow("other", heading = "Other subjects"))
            }
            established.forEach { add(TopicCatalogRow("topic:${it.topic.id}", topic = it.topic)) }
            if (emerging.isNotEmpty()) {
                add(TopicCatalogRow("emerging", heading = "Emerging topics"))
                emerging.forEach { add(TopicCatalogRow("topic:${it.topic.id}", topic = it.topic)) }
            }
            if (preparing.isNotEmpty()) {
                add(TopicCatalogRow("matching", heading = "Topics and matching progress"))
                preparing.forEach { add(TopicCatalogRow("topic:${it.topic.id}", topic = it.topic)) }
            }
        }
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(nav.focusTarget, rows) {
        val index = rows.indexOfFirst { it.key == nav.focusTarget }
        if (index >= 0) scroll.scrollToItem(index + 1) // The status/title item precedes catalog rows.
    }
    LazyColumn(state = scroll,
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Topic catalog" },
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("status") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Topics in your Notes", Modifier.restoreFocusOn(
                    nav.focusTarget?.takeIf { target -> rows.none { it.key == target } },
                    nav.focusTarget, app::focusHandled).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                if (!discoveryStatus(state).incomplete && published.none { it.status == TopicPublicationStatus.Established } &&
                    rows.none { it.key == "major" })
                    Text("No supported topics to show yet. Search and individual connections remain available.",
                        style = MaterialTheme.typography.bodyMedium)
                TopicProgressNotice(state) { controller.refreshTopics() }
            }
        }
        items(rows, key = { it.key }, contentType = { if (it.topic != null) "topic" else "heading" }) { row ->
            if (row.topic != null) TopicNavigationRow(row.topic, {
                app.navigator.open(ReaderDestination.DiscoveryTopic, row.key, row.topic.id)
            }, nav.focusTarget, app::focusHandled, displayName = topicDisplayName(row.topic, state.interests))
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(checkNotNull(row.heading), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                if (row.key == "emerging") Text("Subjects supported by one cached Note.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item("back") {
            Button(onClick = { app.back() }) { Text("Back") }
        }
    }
}
