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
import com.example.notionreader.application.PublishedTopic
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.launch

/**
 * Screen displaying details for a single discovered topic.
 */
@Composable
internal fun TopicDetailsScreen(
    app: ReaderApp,
    onOpenNote: (String) -> Unit
) {
    val controller = app.discovery ?: return
    val state by controller.state.collectAsState()
    val nav by app.navigator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val subject = nav.currentSubjectId

    val published = state.publishedTopics
    val titles = state.cachedTitles
    val relationships = state.topicSnapshot.subjectRelationships
    val visibleRelationships = remember(published, relationships, titles) {
        val ids = published.mapTo(hashSetOf()) { it.topic.id }
        relationships.filter { edge ->
            canonicalSemanticId(edge.evidence.pageId) in titles &&
                edge.parentId in ids && edge.childId in ids
        }
    }

    var following by rememberSaveable(subject) { mutableStateOf(false) }
    var visibleMembers by rememberSaveable(subject) { mutableStateOf(20) }
    var evidenceExpanded by rememberSaveable(subject) { mutableStateOf(false) }
    var labelSourceActivated by rememberSaveable(subject) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val topic = state.topicSnapshot.topics.firstOrNull { it.id == subject }
        val edges = visibleRelationships.filter { it.parentId == subject || it.childId == subject }
        val relationshipTargets = edges.flatMap { listOf("relationship:${it.id}", "relationship_source:${it.id}") }.toSet()

        Text(
            text = topic?.let { topicPreviewTitle(it.label) } ?: "Topic currently unavailable",
            modifier = Modifier.restoreFocusOn(
                nav.focusTarget?.takeIf { target ->
                    target != "preview_guided" && target != "topic_follow:$subject" &&
                        target !in relationshipTargets && topic?.members.orEmpty().none { it.pageId == target }
                },
                nav.focusTarget,
                app::focusHandled
            ).semantics {
                heading()
                paneTitle = "Topic details"
            },
            style = MaterialTheme.typography.headlineMedium
        )

        DiscoveryStatusNotice(state)
        if (topic != null) {
            if (published.any { it.topic.id == topic.id && it.status == TopicPublicationStatus.Emerging }) {
                Text("Emerging topic", style = MaterialTheme.typography.labelLarge)
            }
            topicSourceContext(topic)?.let {
                Text(
                    text = "Source: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(topicNoteCount(topic), style = MaterialTheme.typography.labelLarge)

            SubjectRelationshipRows(app, topic, published, visibleRelationships, titles)

            Button(
                onClick = { app.prepareDiscoveryPreview(controller.topicPreview(topic.id), "Topic: ${topic.label}") },
                modifier = Modifier.restoreFocusOn(nav.focusTarget, "preview_guided", app::focusHandled)
            ) {
                Text("Preview listening")
            }

            val follow = state.interests.firstOrNull { it.originTopicId == topic.id }
            OutlinedButton(
                onClick = {
                    if (follow == null) following = true
                    else app.navigator.open(ReaderDestination.DiscoveryInterest, "topic_follow:${topic.id}", follow.id)
                },
                modifier = Modifier.restoreFocusOn(nav.focusTarget, "topic_follow:${topic.id}", app::focusHandled)
            ) {
                Text(if (follow == null) "Follow topic" else "View followed interest")
            }

            Text(
                "Follow saves an interest. Only Play in the listening preview starts a new session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(
                onClick = { evidenceExpanded = !evidenceExpanded },
                modifier = Modifier.semantics {
                    stateDescription = if (evidenceExpanded) "Expanded" else "Collapsed"
                }
            ) {
                Text("Why these Notes?")
            }

            if (evidenceExpanded) {
                Text(
                    "Grouped by similar indexed passages. The label comes from a source Note.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${state.indexedCount} of ${state.cachedPageIds.size} cached Notes indexed",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Label source", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                topic.labelSource.sectionTitle?.takeIf { it.isNotBlank() }?.let { Text(it) }
                Text(topic.labelSource.excerpt)
                TextButton(
                    onClick = {
                        labelSourceActivated = true
                        onOpenNote(topic.labelSource.pageId)
                    },
                    modifier = Modifier.restoreFocusOn(nav.focusTarget.takeIf { labelSourceActivated }, topic.labelSource.pageId, app::focusHandled)
                ) {
                    Text("Open label source Note")
                }
                topic.members.map { it.anchor }.distinctBy { it.id }.filter { it.id != topic.labelSource.id }.forEach { anchor ->
                    Text("Comparison context", style = MaterialTheme.typography.titleSmall)
                    anchor.sectionTitle?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    Text(anchor.excerpt)
                }
            }

            Text("Notes in this topic", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            val members = topic.members.distinctBy { it.pageId }
            Column(
                modifier = Modifier.semantics { collectionInfo = CollectionInfo(minOf(members.size, visibleMembers), 1) },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                members.take(visibleMembers).forEachIndexed { index, member ->
                    key(member.pageId) {
                        if (index < 3) {
                            Text(
                                "Representative",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TopicMemberRow(
                            member = member,
                            onOpenNote = {
                                labelSourceActivated = false
                                onOpenNote(it)
                            },
                            focusTarget = nav.focusTarget.takeUnless { labelSourceActivated },
                            onFocusHandled = app::focusHandled
                        )
                    }
                }
            }

            if (members.size > visibleMembers) {
                Button(onClick = { visibleMembers += 20 }) {
                    Text("More member Notes")
                }
            }

            if (following) {
                InterestDefinitionDialog(
                    initialName = topic.label,
                    initialText = topic.label,
                    title = "Follow topic",
                    onCancel = { following = false }
                ) { name, text ->
                    scope.launch {
                        controller.followTopic(topic.id, name, text)
                        following = false
                    }
                }
            }
        }

        Button(onClick = { app.back() }) {
            Text("Back")
        }
    }
}

@Composable
private fun SubjectRelationshipRows(
    app: ReaderApp,
    topic: DiscoveredTopic,
    publishedTopics: List<PublishedTopic>,
    relationships: List<SubjectRelationship>,
    sourceTitles: Map<String, String>
) {
    val nav by app.navigator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val edges = relationships.filter { it.parentId == topic.id || it.childId == topic.id }
    val groups = listOf("Broader subjects", "Subtopics", "Related subjects")

    for (title in groups) {
        val selected = edges.filter { edge ->
            when (title) {
                "Broader subjects" -> edge.kind == SubjectRelationshipKind.ParentChild && edge.childId == topic.id
                "Subtopics" -> edge.kind == SubjectRelationshipKind.ParentChild && edge.parentId == topic.id
                else -> edge.kind == SubjectRelationshipKind.Related
            }
        }
        if (selected.isEmpty()) continue

        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Column(
            modifier = Modifier.semantics { collectionInfo = CollectionInfo(selected.size, 1) },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            selected.forEach { edge ->
                key(edge.id) {
                    val otherId = if (edge.parentId == topic.id) edge.childId else edge.parentId
                    val other = publishedTopics.first { it.topic.id == otherId }.topic
                    val action = when (title) {
                        "Broader subjects" -> "broader subject"
                        "Subtopics" -> "subtopic"
                        else -> "related subject"
                    }
                    TextButton(
                        onClick = { app.navigator.open(ReaderDestination.DiscoveryTopic, "relationship:${edge.id}", other.id) },
                        modifier = Modifier.restoreFocusOn(nav.focusTarget, "relationship:${edge.id}", app::focusHandled)
                    ) {
                        Text("Open $action: ${other.label}")
                    }
                    Text(edge.evidence.excerpt, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Source Note: ${sourceTitles[canonicalSemanticId(edge.evidence.pageId)]}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    edge.evidence.sectionTitle?.let { Text("Section: $it", style = MaterialTheme.typography.bodySmall) }
                    TextButton(
                        onClick = { scope.launch { app.openNote(edge.evidence.pageId, fromFocusId = "relationship_source:${edge.id}") } },
                        modifier = Modifier.restoreFocusOn(nav.focusTarget, "relationship_source:${edge.id}", app::focusHandled)
                    ) {
                        Text("Open relationship source Note")
                    }
                }
            }
        }
    }
}
