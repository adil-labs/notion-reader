package com.example.notionreader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.application.DiscoveryState
import com.example.notionreader.application.DiscoveryController
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.launch

private data class PreparedDiscovery(
    val topics: List<com.example.notionreader.application.PublishedTopic>,
    val connections: List<NoteConnection>,
    val evidence: Map<String, ConnectionEvidence>,
    val relationships: List<SubjectRelationship>,
    val sourceTitles: Map<String, String>,
    val loadingConnection: Boolean = false
)

@Composable
private fun preparedDiscovery(state: DiscoveryState, controller: DiscoveryController,
    selectedConnectionId: String? = null, previewConnections: Boolean = false): PreparedDiscovery {
    val published = state.publishedTopics
    val connections = state.topicSnapshot.connections
    val titles = state.cachedTitles
    val relationships = state.topicSnapshot.subjectRelationships
    val reference = state.topicSnapshot.catalogReference
    val storedSelection = reference != null && selectedConnectionId != null
    // Only saved catalog I/O can wait here. Topic publication was completed by the worker.
    // Source changes immediately discard loaded evidence, including while a read is suspended.
    var page by remember(connections, reference, titles, state.cachedRevision, selectedConnectionId) {
        mutableStateOf(if (storedSelection) null else ConnectionCatalogPageResult(connections, connections.size))
    }
    LaunchedEffect(connections, reference, titles, state.cachedRevision, selectedConnectionId) {
        if (storedSelection) page = controller.connectionWindow(state.topicSnapshot, selectedId = selectedConnectionId,
            expectedCacheRevision = state.cachedRevision)
    }
    val presentation = remember(page, titles, previewConnections, selectedConnectionId) {
        val rows = page?.connections.orEmpty()
        if (previewConnections) prepareConnectionPreview(rows, titles)
            else prepareConnectionWindow(rows, titles, limit = 0, selectedId = selectedConnectionId)
    }
    val visibleRelationships = remember(published, relationships, titles) {
        val ids = published.mapTo(hashSetOf()) { it.topic.id }
        relationships.filter { edge -> canonicalSemanticId(edge.evidence.pageId) in titles &&
            edge.parentId in ids && edge.childId in ids
        }
    }
    return PreparedDiscovery(published, presentation.connections, presentation.evidence,
        visibleRelationships, titles, storedSelection && page == null)
}

@Composable
internal fun TopicPreviews(app: ReaderApp, all: Boolean = false) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val prepared = preparedDiscovery(state, controller, previewConnections = !all)
    val published = prepared.topics
    val topics = published.map { it.topic }
    val connections = prepared.connections
    val parentIds = if (all) prepared.relationships.filter { it.kind == SubjectRelationshipKind.ParentChild }.map { it.parentId }.toSet() else emptySet()
    val major = topics.filter { it.id in parentIds }
    val established = published.filter { it.status == TopicPublicationStatus.Established && it.topic.id !in parentIds }.map { it.topic }
    val emerging = published.filter { it.status == TopicPublicationStatus.Emerging && it.topic.id !in parentIds }.map { it.topic }
    Text("Topics in your Notes", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { target ->
        (target == "all_topics" && topics.isEmpty()) ||
            (target == "all_connections" && connections.isEmpty()) ||
            (target.startsWith("topic:") && topics.none { "topic:${it.id}" == target }) ||
            (target.startsWith("connection:") && connections.none { "connection:${it.id}" == target })
    }, nav.focusTarget, app::focusHandled).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    val progress = discoveryProgress(state)
    if (!discoveryStatus(state).incomplete && established.isEmpty() && major.isEmpty()) Text("No supported topics to show yet. Search and individual connections remain available.",
        style = MaterialTheme.typography.bodyMedium)
    val scope = rememberCoroutineScope()
    progress.message?.let { message ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (progress.action) {
                DiscoveryProgressAction.Refresh -> TextButton(onClick = { scope.launch { controller.refreshTopics() } }) { Text("Refresh") }
                null -> Unit
            }
        }
    }
    if (state.topicsUpdated && discoveryStatus(state).incomplete)
        discoveryStatus(state).message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    discoveryStatus(state).workDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (major.isNotEmpty()) {
        Text("Major subjects", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.semantics { collectionInfo = CollectionInfo(major.size, 1) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            major.forEach { topic -> key(topic.id) {
                TopicNavigationRow(topic, { app.navigator.open(ReaderDestination.DiscoveryTopic, "topic:${topic.id}", topic.id) },
                    nav.focusTarget, app::focusHandled)
            } }
        }
        if (established.isNotEmpty()) Text("Other subjects", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    }
    Column(Modifier.semantics { collectionInfo = CollectionInfo(if (all) established.size else minOf(3, established.size), 1) },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (if (all) established else established.take(3)).forEach { topic -> key(topic.id) {
            TopicNavigationRow(topic, { app.navigator.open(ReaderDestination.DiscoveryTopic, "topic:${topic.id}", topic.id) },
                nav.focusTarget, app::focusHandled)
        } }
    }
    if (emerging.isNotEmpty()) {
        Text("Emerging topics", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text("Subjects supported by one cached Note.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.semantics { collectionInfo = CollectionInfo(if (all) emerging.size else minOf(3, emerging.size), 1) },
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (if (all) emerging else emerging.take(3)).forEach { topic -> key(topic.id) {
                TopicNavigationRow(topic, { app.navigator.open(ReaderDestination.DiscoveryTopic, "topic:${topic.id}", topic.id) },
                    nav.focusTarget, app::focusHandled)
            } }
        }
    }
    if (!all && topics.isNotEmpty()) TextButton(onClick = {
        app.navigator.open(ReaderDestination.DiscoveryTopics, "all_topics")
    }, modifier = Modifier.restoreFocusOn(nav.focusTarget, "all_topics", app::focusHandled)) { Text("See all topics") }
    val connectionTotal = state.topicSnapshot.catalogReference?.count ?: state.topicSnapshot.connections.size
    if (!all && connectionTotal > 0) {
        Text("Connections between Notes", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { target ->
            target.startsWith("connection:") && connections.none { "connection:${it.id}" == target }
        }, nav.focusTarget, app::focusHandled).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(connectionPreviewCount(connections.size, connectionTotal), style = MaterialTheme.typography.bodySmall)
        ConnectionRows(app, connections, prepared.evidence)
        TextButton(onClick = {
            app.navigator.open(ReaderDestination.DiscoveryConnections, "all_connections")
        }, modifier = Modifier.restoreFocusOn(nav.focusTarget, "all_connections", app::focusHandled)) { Text("See all connections") }
    }
}

@Composable
private fun ConnectionRows(app: ReaderApp, connections: List<NoteConnection>, evidenceById: Map<String, ConnectionEvidence>) {
    val nav by app.navigator.state.collectAsState()
    Column(Modifier.semantics { collectionInfo = CollectionInfo(connections.size, 1) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        connections.forEach { connection -> key(connection.id) {
            val evidence = evidenceById.getValue(connection.id)
            ConnectionNavigationRow(connection, {
                app.navigator.open(ReaderDestination.DiscoveryConnection, "connection:${connection.id}", connection.id)
            }, nav.focusTarget, app::focusHandled, evidence.kind, connectionPreviewExcerpt(connection, evidence))
        } }
    }
}

@Composable
internal fun ConnectionNavigationRow(connection: NoteConnection, onOpen: () -> Unit,
    focusTarget: String? = null, onFocusHandled: () -> Unit = {},
    evidenceKind: ConnectionEvidenceKind = ConnectionEvidenceKind.SimilarPassages,
    previewExcerpt: String = connection.left.passage.excerpt) {
    val left = connection.left.metadata.title
    val right = connection.right.metadata.title
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()
        .restoreFocusOn(focusTarget, "connection:${connection.id}", onFocusHandled)
        .semantics { onClick(label = "Compare passages", action = null) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(evidenceKind.label(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(left, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(right, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(previewExcerpt, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("Compare passages", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal fun ConnectionEvidenceKind.label(): String = when (this) {
    ConnectionEvidenceKind.NamedReference -> "Named reference"
    ConnectionEvidenceKind.SimilarPassages -> "Similar passages"
}

@Composable
internal fun TopicDestinationContent(app: ReaderApp, onOpenNote: (String) -> Unit) {
    val nav by app.navigator.state.collectAsState()
    when (nav.current) {
        ReaderDestination.DiscoveryTopics -> DiscoveryTopicsScreen(app)
        ReaderDestination.DiscoveryTopic -> TopicDetailsScreen(app, onOpenNote)
        ReaderDestination.DiscoveryConnection -> ConnectionDetailsScreen(app, onOpenNote)
        ReaderDestination.DiscoveryConnections -> ConnectionCatalogContent(app)
        ReaderDestination.DiscoveryInterest -> InterestDetailsScreen(app, onOpenNote)
        else -> Unit
    }
}

@Composable
internal fun InterestDefinitionDialog(initialName: String, initialText: String, title: String,
    onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = {
        Column {
            Text("Recommendations follow the saved matching text, independently of changing topic membership.")
            OutlinedTextField(name, { name = it }, label = { Text("Interest name") })
            OutlinedTextField(text, { text = it }, label = { Text("Matching text") })
        }
    }, confirmButton = { TextButton(onClick = { onSave(name.trim(), text.trim()) }, enabled = name.isNotBlank() && text.isNotBlank()) { Text("Save interest") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

@Composable
internal fun TopicNavigationRow(topic: DiscoveredTopic, onOpen: () -> Unit,
    focusTarget: String? = null, onFocusHandled: () -> Unit = {}) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()
        .restoreFocusOn(focusTarget, "topic:${topic.id}", onFocusHandled)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(topicPreviewTitle(topic.label), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            topicSourceContext(topic)?.let { Text("Source: $it", style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(topicNoteCount(topic), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            topic.members.distinctBy { it.pageId }.let { members ->
                listOfNotNull(members.firstOrNull { it.metadata.title != topicSourceContext(topic) } ?: members.firstOrNull())
            }.forEach { Text(it.metadata.title,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}

internal fun topicNoteCount(topic: DiscoveredTopic): String {
    val count = topic.members.distinctBy { canonicalSemanticId(it.pageId) }.size
    return "$count available ${if (count == 1) "Note" else "Notes"}"
}

@Composable
internal fun TopicMemberRow(member: TopicMembership, onOpenNote: (String) -> Unit,
    focusTarget: String? = null, onFocusHandled: () -> Unit = {}) {
    DiscoveryNoteRow(member.toMatch().noteUi(), onOpenNote, focusTarget, onFocusHandled)

}
