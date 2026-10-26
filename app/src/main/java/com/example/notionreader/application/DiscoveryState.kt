package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*

sealed interface DiscoveryCacheStatus {
    data object Checking : DiscoveryCacheStatus
    data object Ready : DiscoveryCacheStatus
    data class Failed(val message: String) : DiscoveryCacheStatus
}

data class InterestMatchKey(
    val interest: DiscoveryInterest,
    val cacheRevision: Long,
    val reset: Long,
    val indexedRevision: Long,
    val model: EmbeddingIdentity
)

sealed interface InterestMatchOutcome {
    data object Idle : InterestMatchOutcome
    data object Loading : InterestMatchOutcome
    data class Ready(val key: InterestMatchKey, val matches: List<SemanticMatch>, val message: String? = null) : InterestMatchOutcome
    data class Unavailable(val message: String) : InterestMatchOutcome
    data class Failed(val message: String) : InterestMatchOutcome
}

/** A cached success and its evidence are one value; loading/cancellation cannot retain a detached success key. */
data class InterestMatchState(val interestId: String? = null, val outcome: InterestMatchOutcome = InterestMatchOutcome.Idle) {
    val matches: List<SemanticMatch> get() = (outcome as? InterestMatchOutcome.Ready)?.matches.orEmpty()
    val loading: Boolean get() = outcome == InterestMatchOutcome.Loading
    val message: String? get() = when (val value = outcome) {
        is InterestMatchOutcome.Ready -> value.message
        is InterestMatchOutcome.Unavailable -> value.message
        is InterestMatchOutcome.Failed -> value.message
        else -> null
    }

    internal fun reconcile(cacheRevision: Long, eligibleIds: Set<String>): InterestMatchState {
        val ready = outcome as? InterestMatchOutcome.Ready ?: return this
        return if (ready.key.cacheRevision != cacheRevision) copy(outcome = InterestMatchOutcome.Idle)
        else copy(outcome = ready.copy(matches = ready.matches.filter { it.pageId in eligibleIds }))
    }
}

/** A published topic and its verified publication status. */
data class PublishedTopic(
    val topic: DiscoveredTopic,
    val status: TopicPublicationStatus
)

/**
 * Discovery presentation and execution state.
 */
enum class DiscoveryExecutionAvailability { Available, WaitingForAndroid, WaitingForPlayback, WaitingForResources }
enum class DiscoveryProcessingStatus { Checking, LoadingSavedDiscoveries, SetupRequired, Paused, WaitingForPower, WaitingForAndroid,
    WaitingForPlayback, WaitingForResources, Processing, Ready, CacheUnavailable, RecoveryRequired }
enum class DiscoveryIndexingAction { Pause, Resume }
enum class DiscoveryCoverageStatus { Checking, Ready }
enum class TopicNamingStatus { Pending, Running, Ready, Unavailable }

data class DiscoveryState(
    val cacheStatus: DiscoveryCacheStatus = DiscoveryCacheStatus.Checking,
    val query: String = "", val filters: BrowseQuery = BrowseQuery.Default,
    val relatedPageId: String? = null,
    val results: List<SemanticMatch> = emptyList(), val related: List<SemanticMatch> = emptyList(),
    val recommendations: List<SemanticMatch> = emptyList(), val older: List<SemanticMatch> = emptyList(),
    val preview: List<SemanticMatch> = emptyList(), val interests: List<DiscoveryInterest> = emptyList(),
    val cachedPageIds: Set<String> = emptySet(), val cachedTitles: Map<String, String> = emptyMap(), val indexedCount: Int = 0,
    val indexing: Boolean = false, val searching: Boolean = false, val paused: Boolean = true,
    val searchFailure: String? = null,
    val searchCompleted: Boolean = false,
    val bytes: Long = 0, val message: String? = null,
    val indexFailure: String? = null, val topicFailure: String? = null,
    val topicSnapshot: TopicSnapshot = TopicSnapshot(), val topicsUpdated: Boolean = false,
    val publishedTopics: List<PublishedTopic> = emptyList(),
    val topicStatus: String? = null, val previewLabel: String = "Discovery recommendations",
    val topicNaming: TopicNamingStatus? = null,
    val unavailablePreviewIds: Set<String> = emptySet(), val previewStatus: String? = null,
    val interestMatch: InterestMatchState = InterestMatchState(),
    val cachedRevision: Long = 0, val indexedRevision: Long = 0,
    val cachedSourceIds: Map<String, String> = emptyMap(),
    val power: DiscoveryPowerState? = null,
    val embeddingWork: DiscoveryEmbeddingWork? = null,
    val effort: com.example.notionreader.domain.settings.DiscoveryEffort = com.example.notionreader.domain.settings.DiscoveryEffort.BALANCED,
    val executionAvailability: DiscoveryExecutionAvailability = DiscoveryExecutionAvailability.Available,
    val modelAvailable: Boolean = true,
    val restoringSavedDiscoveries: Boolean = false,
    val indexCoverage: DiscoveryCoverageStatus = if (cacheStatus == DiscoveryCacheStatus.Ready)
        DiscoveryCoverageStatus.Ready else DiscoveryCoverageStatus.Checking,
    val workPending: Boolean = indexing || indexedCount < cachedPageIds.size || !topicSnapshot.complete ||
        (maxOf(indexedCount, cachedPageIds.size) > 1 && !topicSnapshot.connectionPassComplete)
) {
    val processingFailure: String? get() = indexFailure ?: topicFailure
    val processingStatus: DiscoveryProcessingStatus get() = when {
        cacheStatus == DiscoveryCacheStatus.Checking -> DiscoveryProcessingStatus.Checking
        cacheStatus is DiscoveryCacheStatus.Failed -> DiscoveryProcessingStatus.CacheUnavailable
        !modelAvailable -> DiscoveryProcessingStatus.SetupRequired
        processingFailure != null -> DiscoveryProcessingStatus.RecoveryRequired
        paused -> DiscoveryProcessingStatus.Paused
        indexCoverage == DiscoveryCoverageStatus.Checking -> DiscoveryProcessingStatus.Checking
        restoringSavedDiscoveries -> DiscoveryProcessingStatus.LoadingSavedDiscoveries
        !workPending && !indexing -> DiscoveryProcessingStatus.Ready
        power?.allows(effort) == false -> DiscoveryProcessingStatus.WaitingForPower
        executionAvailability == DiscoveryExecutionAvailability.WaitingForPlayback -> DiscoveryProcessingStatus.WaitingForPlayback
        executionAvailability == DiscoveryExecutionAvailability.WaitingForResources -> DiscoveryProcessingStatus.WaitingForResources
        executionAvailability == DiscoveryExecutionAvailability.WaitingForAndroid -> DiscoveryProcessingStatus.WaitingForAndroid
        else -> DiscoveryProcessingStatus.Processing
    }
    val indexingAction: DiscoveryIndexingAction? get() =
        if (cacheStatus != DiscoveryCacheStatus.Ready || !modelAvailable) null
        else if (paused) DiscoveryIndexingAction.Resume else DiscoveryIndexingAction.Pause
    val canSearch: Boolean get() = cacheStatus == DiscoveryCacheStatus.Ready && modelAvailable && cachedPageIds.isNotEmpty()
}
