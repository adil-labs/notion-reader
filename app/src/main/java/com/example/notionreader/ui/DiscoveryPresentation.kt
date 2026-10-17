package com.example.notionreader.ui

import com.example.notionreader.application.DiscoveryState
import com.example.notionreader.application.HistoryEntry

internal enum class DiscoveryProgressAction { Refresh, Resume }
internal data class DiscoveryProgressUi(val message: String? = null, val action: DiscoveryProgressAction? = null)

internal fun discoveryProgress(state: DiscoveryState): DiscoveryProgressUi = when {
    state.topicsUpdated -> DiscoveryProgressUi("New discoveries available", DiscoveryProgressAction.Refresh)
    state.message != null -> DiscoveryProgressUi() // Already displayed with the search/setup state.
    state.paused -> DiscoveryProgressUi("Discovery is paused. Resume indexing in Settings.")
    state.indexing -> DiscoveryProgressUi("Preparing cached Notes…")
    state.topicStatus?.contains("next eligible foreground") == true ->
        DiscoveryProgressUi("More discoveries can be prepared.", DiscoveryProgressAction.Resume)
    state.topicStatus?.startsWith("Topic coverage is incomplete.") == true -> DiscoveryProgressUi(state.topicStatus)
    state.topicSnapshot.engineVersion > 0 && state.topicSnapshot.formationProgress != null -> {
        val progress = state.topicSnapshot.formationProgress
        val candidates = progress.candidateGroups.ifEmpty { progress.clusters }
        if (!progress.groupingComplete) DiscoveryProgressUi(
            "Comparing ${progress.totalSections} eligible sections; ${progress.comparisonsCompleted} comparisons completed. 0 of ${progress.totalSections} sections finalized.")
        else DiscoveryProgressUi("Checking topic evidence: ${candidates.take(progress.projectionCluster).count { it.isNotEmpty() }} of ${candidates.count { it.isNotEmpty() }} candidates; ${progress.totalSections} of ${progress.totalSections} sections finalized.")
    }
    state.indexedCount < 3 && state.topicSnapshot.engineVersion == 0 -> DiscoveryProgressUi("Topics need at least three indexed Notes. You can still search individual Notes.")
    state.topicStatus?.startsWith("Finding topics") == true -> DiscoveryProgressUi("Updating discoveries…")
    state.topicStatus != null -> DiscoveryProgressUi(state.topicStatus)
    !state.topicSnapshot.complete -> DiscoveryProgressUi("Finding topics and connections…")
    state.topicSnapshot.topics.isEmpty() && state.topicSnapshot.connections.isEmpty() ->
        DiscoveryProgressUi("No supported groups yet. Search your Notes or add an interest.")
    else -> DiscoveryProgressUi()
}

/** Presentation descriptors only; persisted labels and topic/follow identities are untouched. */
internal fun topicPreviewTitle(label: String): String = when {
    label.startsWith("Notes related to ") -> "Related Notes"
    label.trim().lowercase() in setOf("related knowledge", "notes", "summary", "overview", "details", "content") -> "Similar Notes"
    else -> label
}

internal fun recentListeningTitle(entry: HistoryEntry): String = entry.originLabel
    ?.takeUnless { it == "Single Note" || it == "Browse selection" } ?: entry.title

/** Attribution, not an inferred replacement topic name. Resolved only from eligible members. */
internal fun topicSourceContext(topic: com.example.notionreader.domain.discovery.DiscoveredTopic): String? =
    if (topicPreviewTitle(topic.label) == topic.label) null else topic.members.firstOrNull {
        com.example.notionreader.domain.discovery.canonicalSemanticId(it.pageId) ==
            com.example.notionreader.domain.discovery.canonicalSemanticId(topic.labelSource.pageId)
    }?.metadata?.title?.takeIf { it.isNotBlank() }

/** Reference cards quote the instruction, even when canonical pair order puts its target first. */
internal fun connectionPreviewExcerpt(connection: com.example.notionreader.domain.discovery.NoteConnection,
    evidence: com.example.notionreader.domain.discovery.ConnectionEvidence): String {
    val source = evidence.references.firstOrNull()?.sourcePageId
    return if (connection.independentNamedReference && source != null)
        listOf(connection.left, connection.right).firstOrNull {
            com.example.notionreader.domain.discovery.canonicalSemanticId(it.pageId) == source
        }?.passage?.excerpt ?: connection.left.passage.excerpt
    else connection.left.passage.excerpt
}

/** An expired explicit-reference proof cannot turn into an unsupported similarity claim. */
internal fun visibleDiscoveryConnections(connections: List<com.example.notionreader.domain.discovery.NoteConnection>,
    titles: Map<String, String>): List<com.example.notionreader.domain.discovery.NoteConnection> = connections.filter {
    !it.independentNamedReference ||
        com.example.notionreader.domain.discovery.classifyConnectionEvidence(it, titles).kind ==
        com.example.notionreader.domain.discovery.ConnectionEvidenceKind.NamedReference
}
