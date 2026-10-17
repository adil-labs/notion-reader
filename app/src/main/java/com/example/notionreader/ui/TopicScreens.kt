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
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.launch

@Composable
internal fun TopicPreviews(app: ReaderApp, all: Boolean = false) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val topics = publishableDiscoveryTopics(state.topicSnapshot.topics)
    val connections = visibleDiscoveryConnections(state.topicSnapshot.connections, state.cachedTitles)
    val established = topics.filter { discoveryTopicPublicationStatus(it) == TopicPublicationStatus.Established }
    val emerging = topics.filter { discoveryTopicPublicationStatus(it) == TopicPublicationStatus.Emerging }
    Text("Topics in your Notes", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { target ->
        (target == "all_topics" && topics.isEmpty()) ||
            (target == "all_connections" && connections.isEmpty()) ||
            (target.startsWith("topic:") && topics.none { "topic:${it.id}" == target }) ||
            (target.startsWith("connection:") && connections.none { "connection:${it.id}" == target })
    }, nav.focusTarget, app::focusHandled).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    val progress = discoveryProgress(state)
    if (established.isEmpty()) Text("No supported topics to show yet. Search and individual connections remain available.",
        style = MaterialTheme.typography.bodyMedium)
    val scope = rememberCoroutineScope()
    progress.message?.let { message ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (progress.action) {
                DiscoveryProgressAction.Refresh -> TextButton(onClick = controller::refreshTopics) { Text("Refresh") }
                DiscoveryProgressAction.Resume -> TextButton(onClick = { scope.launch { controller.resume() } }) { Text("Continue discovery") }
                null -> Unit
            }
        }
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
    if (!all && connections.isNotEmpty()) {
        Text("Connections between Notes", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { target ->
            target.startsWith("connection:") && connections.none { "connection:${it.id}" == target }
        }, nav.focusTarget, app::focusHandled).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        ConnectionRows(app, connections.take(2))
        if (connections.isNotEmpty()) TextButton(onClick = {
            app.navigator.open(ReaderDestination.DiscoveryConnections, "all_connections")
        }, modifier = Modifier.restoreFocusOn(nav.focusTarget, "all_connections", app::focusHandled)) { Text("See all connections") }
    }
}

@Composable
private fun ConnectionRows(app: ReaderApp, connections: List<NoteConnection>) {
    val nav by app.navigator.state.collectAsState()
    val cachedTitles = app.discovery?.state?.collectAsState()?.value?.cachedTitles.orEmpty()
    Column(Modifier.semantics { collectionInfo = CollectionInfo(connections.size, 1) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        connections.forEach { connection -> key(connection.id) {
            val evidence = classifyConnectionEvidence(connection, cachedTitles)
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
        .semantics { onClick(label = "Compare passages between $left and $right", action = null) }) {
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

private fun ConnectionEvidenceKind.label(): String = when (this) {
    ConnectionEvidenceKind.NamedReference -> "Named reference"
    ConnectionEvidenceKind.SimilarPassages -> "Similar passages"
}

@Composable
internal fun TopicDestinationContent(app: ReaderApp, onOpenNote: (String) -> Unit) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val subject = nav.currentSubjectId
    val connections = visibleDiscoveryConnections(state.topicSnapshot.connections, state.cachedTitles)
    var following by rememberSaveable(subject) { mutableStateOf(false) }
    var visibleMembers by rememberSaveable(subject) { mutableStateOf(20) }
    var evidenceExpanded by rememberSaveable(subject) { mutableStateOf(false) }
    var labelSourceActivated by rememberSaveable(subject) { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (nav.current) {
            ReaderDestination.DiscoveryTopics -> TopicPreviews(app, all = true)
            ReaderDestination.DiscoveryConnections -> {
                Text("Connections between Notes", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { target ->
                    target.startsWith("connection:") && connections.none { "connection:${it.id}" == target }
                }, nav.focusTarget, app::focusHandled).semantics { heading(); paneTitle = "Connections between Notes" })
                val groups = connections.groupBy { classifyConnectionEvidence(it, state.cachedTitles).kind }
                listOf(ConnectionEvidenceKind.SimilarPassages to "Similar passages",
                    ConnectionEvidenceKind.NamedReference to "Named references").forEach { (kind, title) ->
                    val pairs = groups[kind].orEmpty()
                    if (pairs.isNotEmpty()) {
                        Text("$title (${pairs.size})", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        ConnectionRows(app, pairs)
                    }
                }
                if (groups.isEmpty()) Text("No connections in this snapshot.")
            }
            ReaderDestination.DiscoveryTopic -> {
                val topic = state.topicSnapshot.topics.firstOrNull { it.id == subject }
                Text(topic?.let { topicPreviewTitle(it.label) } ?: "Topic currently unavailable", Modifier.restoreFocusOn(
                    nav.focusTarget?.takeIf { target -> target != "preview_guided" && target != "topic_follow:$subject" && topic?.members.orEmpty().none { it.pageId == target } },
                    nav.focusTarget, app::focusHandled).semantics { heading(); paneTitle = "Topic details" }, style = MaterialTheme.typography.headlineMedium)
                if (topic != null) {
                    if (discoveryTopicPublicationStatus(topic) == TopicPublicationStatus.Emerging)
                        Text("Emerging topic", style = MaterialTheme.typography.labelLarge)
                    topicSourceContext(topic)?.let { Text("Source: $it", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(topicNoteCount(topic), style = MaterialTheme.typography.labelLarge)
                    Button(onClick = { app.prepareDiscoveryPreview(controller.topicPreview(topic.id), "Topic: ${topic.label}") },
                        modifier = Modifier.restoreFocusOn(nav.focusTarget, "preview_guided", app::focusHandled)) { Text("Preview listening") }
                    val follow = state.interests.firstOrNull { it.originTopicId == topic.id }
                    OutlinedButton(onClick = {
                        if (follow == null) following = true else app.navigator.open(ReaderDestination.DiscoveryInterest, "topic_follow:${topic.id}", follow.id)
                    }, modifier = Modifier.restoreFocusOn(nav.focusTarget, "topic_follow:${topic.id}", app::focusHandled)) {
                        Text(if (follow == null) "Follow topic" else "View followed interest")
                    }
                    Text("Follow saves an interest. Only Play in the listening preview starts a new session.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { evidenceExpanded = !evidenceExpanded }, modifier = Modifier
                        .semantics { stateDescription = if (evidenceExpanded) "Expanded" else "Collapsed" }) { Text("Why these Notes?") }
                    if (evidenceExpanded) {
                        Text("Grouped by similar indexed passages. The label comes from a source Note.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${state.indexedCount} of ${state.cachedPageIds.size} cached Notes indexed",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Label source", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        topic.labelSource.sectionTitle?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        Text(topic.labelSource.excerpt)
                        TextButton(onClick = { labelSourceActivated = true; onOpenNote(topic.labelSource.pageId) },
                            modifier = Modifier.restoreFocusOn(nav.focusTarget.takeIf { labelSourceActivated }, topic.labelSource.pageId, app::focusHandled)) { Text("Open label source Note") }
                        topic.members.map { it.anchor }.distinctBy { it.id }.filter { it.id != topic.labelSource.id }.forEach { anchor ->
                            Text("Comparison context", style = MaterialTheme.typography.titleSmall)
                            anchor.sectionTitle?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            Text(anchor.excerpt)
                        }
                    }
                    Text("Notes in this topic", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    val members = topic.members.distinctBy { it.pageId }
                    Column(Modifier.semantics { collectionInfo = CollectionInfo(minOf(members.size, visibleMembers), 1) },
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.take(visibleMembers).forEachIndexed { index, member -> key(member.pageId) {
                        if (index < 3) Text("Representative", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TopicMemberRow(member, { labelSourceActivated = false; onOpenNote(it) },
                            nav.focusTarget.takeUnless { labelSourceActivated }, app::focusHandled)
                    } }
                    }
                    if (members.size > visibleMembers) Button(onClick = { visibleMembers += 20 }) { Text("More member Notes") }
                    if (following) InterestDefinitionDialog(topic.label, topic.label, "Follow topic", { following = false }) { name, text ->
                        scope.launch { controller.followTopic(topic.id, name, text); following = false }
                    }
                }
            }
            ReaderDestination.DiscoveryConnection -> {
                val connection = connections.firstOrNull { it.id == subject }
                Text("Compare passages", Modifier.restoreFocusOn(nav.focusTarget?.takeIf { connection == null },
                    nav.focusTarget, app::focusHandled).semantics { heading(); paneTitle = "Connection details" }, style = MaterialTheme.typography.headlineMedium)
                if (connection == null) Text("Connection currently unavailable") else {
                    val evidence = classifyConnectionEvidence(connection, state.cachedTitles)
                    val kind = evidence.kind
                    Text(kind.label(), style = MaterialTheme.typography.titleSmall)
                    Text(if (connection.independentNamedReference && kind == ConnectionEvidenceKind.NamedReference)
                        "A source passage explicitly names the other cached Note by title. Target context does not establish agreement or a verified Notion hyperlink."
                        else if (kind == ConnectionEvidenceKind.NamedReference)
                        "A source passage explicitly names the other cached Note by title."
                        else "These indexed passages matched by similarity.", style = MaterialTheme.typography.bodyMedium)
                    listOf(connection.left, connection.right).forEach { member ->
                        if (connection.independentNamedReference && evidence.references.any {
                            it.targetPageId == canonicalSemanticId(member.pageId)
                        }) Text("Target context; not evidence of thematic similarity", style = MaterialTheme.typography.labelLarge)
                        DiscoveryNoteRow(member.toMatch().noteUi(), onOpenNote, nav.focusTarget, app::focusHandled, excerptMaxLines = Int.MAX_VALUE)
                    }
                    Button(onClick = { app.prepareDiscoveryPreview(controller.connectionPreview(connection.id), kind.label()) },
                        modifier = Modifier.restoreFocusOn(nav.focusTarget, "preview_guided", app::focusHandled)) { Text("Preview listening") }
                }
            }
            ReaderDestination.DiscoveryInterest -> {
                val interest = state.interests.firstOrNull { it.id == subject }
                val modelInstalled = app.discoveryModel?.state?.collectAsState()?.value?.installed ?: (app.discoveryModel == null)
                LaunchedEffect(subject, interest, modelInstalled, state.cachedRevision, state.indexedRevision) {
                    subject?.let { controller.loadInterestMatches(it) }
                }
                val name = interest?.displayName ?: interest?.seedPageId?.let { state.cachedTitles[canonicalSemanticId(it)] ?: "Seed Note" } ?: interest?.text ?: "Your interest"
                Text(name, Modifier.semantics { heading(); paneTitle = "Interest details" }, style = MaterialTheme.typography.headlineMedium)
                if (interest == null) Text("Interest unavailable") else {
                    var editing by rememberSaveable(subject) { mutableStateOf(false) }
                    var olderOnly by rememberSaveable(subject) { mutableStateOf(false) }
                    val matches = state.interestMatches.takeIf { state.selectedInterestId == subject }.orEmpty()
                    val cutoff = java.time.Instant.now().minusSeconds(90L * 86400)
                    val older = matches.filter { match -> match.metadata.lastEditedAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.let { !it.isAfter(cutoff) } == true }
                    val visible = if (olderOnly) older else matches
                    Text(if (interest.seedPageId == null) interest.text else "Matches the cached seed Note. The seed is excluded.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (interest.seedPageId == null) Text("Matched against your saved text.", style = MaterialTheme.typography.bodySmall)
                    if (state.selectedInterestId == subject && state.interestLoading) Text("Finding matching cached Notes…")
                    if (state.selectedInterestId == subject) state.interestMessage?.let { Text(it) }
                    if (!modelInstalled) TextButton(onClick = { app.navigator.open(ReaderDestination.SettingsDiscovery, "interest_model") },
                        modifier = Modifier.restoreFocusOn(nav.focusTarget, "interest_model", app::focusHandled)) { Text("Set up Discovery") }
                    if (visible.isNotEmpty()) {
                        Button(onClick = { app.prepareDiscoveryPreview(visible, "Interest: $name" + if (olderOnly) " · Older matching Notes" else "") },
                            modifier = Modifier.restoreFocusOn(nav.focusTarget, "preview_guided", app::focusHandled)) { Text("Preview listening") }
                    }
                    if (older.isNotEmpty() || olderOnly) TextButton(onClick = { olderOnly = !olderOnly }) {
                        Text(if (olderOnly) "All matching Notes" else "Older matching Notes")
                    }
                    Text("Matching Notes", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (olderOnly) Text("Last edited at least 90 days ago; unknown dates excluded.")
                    Text("${visible.size} matching cached Notes", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.semantics { collectionInfo = CollectionInfo(visible.size, 1) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        visible.forEach { match -> key(match.pageId) { DiscoveryNoteRow(match.noteUi(), onOpenNote, nav.focusTarget, app::focusHandled) } }
                    }
                    TextButton(onClick = { scope.launch { controller.loadInterestMatches(interest.id, force = true) } }) { Text("Refresh matches") }
                    if (interest.seedPageId != null) TextButton(onClick = {
                        app.navigator.open(ReaderDestination.DiscoverySeeds, "interest_seed:${interest.id}", interest.id)
                    }, modifier = Modifier.restoreFocusOn(nav.focusTarget, "interest_seed:${interest.id}", app::focusHandled)) { Text("Change seed Note") }
                    else TextButton(onClick = { editing = true }) { Text("Edit interest") }
                    TextButton(onClick = { scope.launch { controller.removeInterest(interest.id); app.back() } }) { Text("Remove interest") }
                    if (editing) InterestDefinitionDialog(interest.displayName ?: interest.text, interest.text, "Edit interest", { editing = false }) { name, text ->
                        scope.launch { controller.updateFollow(interest.id, name, text); editing = false }
                    }
                }
            }
            else -> Unit
        }
        Button(onClick = { app.back() }) { Text("Back") }
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

private fun topicNoteCount(topic: DiscoveredTopic): String {
    val count = topic.members.distinctBy { canonicalSemanticId(it.pageId) }.size
    return "$count available ${if (count == 1) "Note" else "Notes"}"
}

@Composable
internal fun TopicMemberRow(member: TopicMembership, onOpenNote: (String) -> Unit,
    focusTarget: String? = null, onFocusHandled: () -> Unit = {}) {
    DiscoveryNoteRow(member.toMatch().noteUi(), onOpenNote, focusTarget, onFocusHandled)

}
