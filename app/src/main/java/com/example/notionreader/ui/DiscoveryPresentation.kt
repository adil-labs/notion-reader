package com.example.notionreader.ui

import com.example.notionreader.application.*
import com.example.notionreader.domain.discovery.DiscoveryModelState
import com.example.notionreader.application.HistoryEntry

internal enum class DiscoveryProgressAction { Refresh }
internal data class DiscoveryProgressUi(val message: String? = null, val action: DiscoveryProgressAction? = null)

data class DiscoveryStatusUi(
    val message: String? = null,
    val coverage: String = "Checking cached Notes…",
    val workDescription: String? = null,
    val indexingAction: DiscoveryIndexingAction? = null,
    val manageAvailable: Boolean = false,
    val incomplete: Boolean = true
)

/** Every destination derives status, coverage and controls from the same process-owned snapshot. */
internal fun discoveryStatus(state: DiscoveryState): DiscoveryStatusUi {
    val status = state.processingStatus
    val message = when (status) {
        DiscoveryProcessingStatus.Checking -> "Checking Discovery…"
        DiscoveryProcessingStatus.LoadingSavedDiscoveries -> "Loading saved discoveries…"
        DiscoveryProcessingStatus.CacheUnavailable -> (state.cacheStatus as? DiscoveryCacheStatus.Failed)?.message
        DiscoveryProcessingStatus.RecoveryRequired -> state.processingFailure
        DiscoveryProcessingStatus.SetupRequired -> "Set up Discovery in Settings to prepare cached Notes."
        DiscoveryProcessingStatus.Paused -> "Discovery is paused. Resume indexing in Settings."
        DiscoveryProcessingStatus.WaitingForPower -> if (state.effort == com.example.notionreader.domain.settings.DiscoveryEffort.CHARGING_ONLY)
            "Waiting for power: Charging only is selected. Connect a charger."
            else "Waiting for power: connect a charger or reach 30% battery."
        DiscoveryProcessingStatus.WaitingForAndroid -> "Waiting for an Android processing window. Discovery resumes automatically."
        DiscoveryProcessingStatus.WaitingForPlayback -> "Waiting for listening to pause. Discovery resumes automatically."
        DiscoveryProcessingStatus.WaitingForResources -> "Waiting for device resources. Discovery resumes automatically."
        DiscoveryProcessingStatus.Ready -> if (state.topicSnapshot.topics.isEmpty() && state.topicSnapshot.connections.isEmpty() &&
            (state.topicSnapshot.catalogReference?.count ?: 0) == 0 && !state.topicsUpdated)
            "No supported groups yet. Search your Notes or add an interest." else null
        else -> discoveryProcessingDescription(state)
    }
    return DiscoveryStatusUi(message,
        when {
            state.cacheStatus == DiscoveryCacheStatus.Checking -> "Checking cached Notes…"
            state.cacheStatus is DiscoveryCacheStatus.Failed -> "Cached Note coverage is unavailable."
            state.indexCoverage == DiscoveryCoverageStatus.Checking -> "Checking indexed Note coverage…"
            else -> "${state.indexedCount} of ${state.cachedPageIds.size} cached Notes indexed"
        },
        if (status in setOf(DiscoveryProcessingStatus.Checking, DiscoveryProcessingStatus.SetupRequired,
                DiscoveryProcessingStatus.CacheUnavailable)) null else discoveryWorkDescription(state),
        state.indexingAction,
        status in setOf(DiscoveryProcessingStatus.SetupRequired, DiscoveryProcessingStatus.Paused,
            DiscoveryProcessingStatus.CacheUnavailable, DiscoveryProcessingStatus.RecoveryRequired, DiscoveryProcessingStatus.WaitingForPower) || state.message != null,
        status != DiscoveryProcessingStatus.Ready)
}

internal fun discoveryProgress(state: DiscoveryState): DiscoveryProgressUi =
    if (state.topicsUpdated) DiscoveryProgressUi("New discoveries available", DiscoveryProgressAction.Refresh)
    else DiscoveryProgressUi(discoveryStatus(state).message)

private fun discoveryProcessingDescription(state: DiscoveryState): String? = when {
    state.indexing -> "Preparing cached Notes…"
    state.topicStatus?.contains("next eligible foreground") == true -> "Discovery continues automatically when processing is available."
    state.topicStatus?.startsWith("Topic coverage is incomplete.") == true -> state.topicStatus
    state.topicSnapshot.engineVersion > 0 && state.topicSnapshot.formationProgress != null -> {
        val progress = state.topicSnapshot.formationProgress
        val candidates = progress.candidateGroups.ifEmpty { progress.clusters }
        if (!progress.groupingComplete)
            "Finding topics in ${state.indexedCount} indexed Notes (${progress.totalOpportunities} passage groups across ${progress.totalSections} sections). ${progress.comparisonsCompleted} similarity checks completed so far. Progress is saved."
        else "Checking topic evidence: ${candidates.take(progress.projectionCluster).count { it.isNotEmpty() }} of ${candidates.count { it.isNotEmpty() }} candidates; ${progress.totalSections} of ${progress.totalSections} sections finalized."
    }
    state.indexedCount < 3 && state.topicSnapshot.engineVersion == 0 -> "Topics need at least three indexed Notes. You can still search individual Notes."
    state.topicStatus?.startsWith("Finding topics") == true -> "Updating discoveries…"
    state.topicStatus != null -> state.topicStatus
    !state.topicSnapshot.complete || (maxOf(state.indexedCount, state.cachedPageIds.size) > 1 && !state.topicSnapshot.connectionPassComplete) -> "Finding topics and connections…"
    state.workPending && state.topicSnapshot.complete -> "Updating discoveries…"
    state.topicSnapshot.topics.isEmpty() && state.topicSnapshot.connections.isEmpty() && (state.topicSnapshot.catalogReference?.count ?: 0) == 0 ->
        "No supported groups yet. Search your Notes or add an interest."
    else -> null
}

internal fun discoveryWorkDescription(state: DiscoveryState): String? {
    val work = state.embeddingWork ?: return if (state.indexing) "Estimating on this device…" else null
    if (work.remainingNotes == 0) return null
    val duration = work.estimate?.let {
        val low = ((it.minimumSeconds + 59) / 60).coerceAtLeast(1)
        val high = ((it.maximumSeconds + 59) / 60).coerceAtLeast(low)
        when {
            it.maximumSeconds == 0L -> "No new embeddings needed."
            it.maximumSeconds < 60 -> "Less than a minute of embedding work."
            low == high -> "About $high ${if (high == 1L) "minute" else "minutes"} of embedding work."
            else -> "About $low–$high minutes of embedding work."
        }
    } ?: "Estimating on this device…"
    return "${work.remainingNotes} Notes, ${work.remainingPassages} passages remaining. $duration Subject and connection analysis is additional; its time is not yet estimated."
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
internal fun discoveryModelDownloadProgress(model: DiscoveryModelState?): String? {
    if (model?.downloading != true) return null
    if (model.totalBytes <= 0) return "Downloading model…"
    val downloaded = model.downloadedBytes.coerceIn(0, model.totalBytes)
    val percent = (downloaded.toDouble() / model.totalBytes * 100).toInt().coerceIn(0, 100)
    return "Downloading model: ${downloaded / 1_000_000} of ${model.totalBytes / 1_000_000} MB ($percent%)."
}
