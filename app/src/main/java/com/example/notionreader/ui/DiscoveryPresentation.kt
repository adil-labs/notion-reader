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
    state.indexedCount < 3 -> DiscoveryProgressUi("Topics need at least three indexed Notes. You can still search individual Notes.")
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
