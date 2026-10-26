package com.example.notionreader.application

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retrieval and derived-data lifecycle have no reference to the listening session. */
class DiscoveryController(
    private val cache: DocumentCache,
    private val index: SemanticIndexStore,
    private val interestStore: DiscoveryInterestStore,
    private val provider: EmbeddingProvider,
    private val modelAvailable: () -> Boolean = { true },
    private val recommendationThreshold: Float = .5f,
    private val clock: () -> Instant = { Instant.now() },
    private val queryTimings: (Map<String, Long>) -> Unit = {},
    private val topicStore: TopicStore? = null,
    private val topicCheckpointStore: TopicStore? = null,
    private val topicPolicy: TopicPolicy = TopicPolicy(),
    private val mayContinueIndexing: () -> Boolean = { true },
    private val invalidationAcknowledgementLimit: () -> Long = { Long.MAX_VALUE },
    private val formationWorkBudget: Int = 250_000,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val passageProgressStore: SemanticIndexStore? = null,
    topicLabelRuntime: TopicLabelRuntime? = null,
    topicLabelModelAvailable: () -> Boolean = { false },
    topicLabelStore: TopicLabelStore? = null,
    topicLabelIdentity: () -> TopicLabelIdentity? = { null },
    topicLabelUnavailableMessage: () -> String = { "Topic naming is unavailable. Install the topic naming model in Discovery settings." },
    topicRetrievalStore: TopicRetrievalStore? = null,
    topicRetrievalPolicy: TopicRetrievalPolicy = TopicRetrievalPolicy(.85f)
) {
    private val mutable = MutableStateFlow(DiscoveryState())
    private val runtime = DiscoveryRuntime(cache, provider, topicLabelRuntime, topicLabelModelAvailable)
    fun updatePowerState(power: DiscoveryPowerState) {
        mutable.update { it.copy(power = power) }
        if (!mayContinueWork()) runtime.interruptLabeling()
    }
    fun updateWorkAvailability(effort: com.example.notionreader.domain.settings.DiscoveryEffort, availability: DiscoveryExecutionAvailability) {
        mutable.update { it.copy(effort = effort, executionAvailability = availability) }
        if (!mayContinueWork()) runtime.interruptLabeling()
    }
    private fun mayContinueWork() = !mutable.value.paused && mutable.value.power?.allows(mutable.value.effort) != false && mayContinueIndexing()
    val state = mutable.asStateFlow()
    val embeddingRuntime: kotlinx.coroutines.flow.StateFlow<EmbeddingRuntimeState> =
        (provider as? ObservableEmbeddingRuntime)?.runtimeState ?: MutableStateFlow(EmbeddingRuntimeState())
    val hasPendingWork: Boolean get() = indexing.reconciliationPending || topics?.needsMoreWork == true ||
        state.value.indexedCount < state.value.cachedPageIds.size || state.value.cachedRevision != cache.revision.value
    fun requestAutomaticContinuation() { wake.value++ }
    suspend fun releaseTopicLabelModel() { runtime.closeLabels(); requestAutomaticContinuation() }
    private val wake = MutableStateFlow(0L)
    val revision = wake.asStateFlow()
    private val retrieval = DiscoveryRetrieval(cache, index, provider, topicStore, runtime.validity, workDispatcher, ::recordTiming)
    private val connectionProjection = ConnectionCatalogProjection { snapshot, offset, limit ->
        retrieval.connectionWindow(snapshot, offset, limit)
    }
    private val topics = topicStore?.let { store -> TopicCoordinator(cache, index, store, topicCheckpointStore,
        { provider.identity }, topicPolicy, bulkGate = runtime.bulkGate, mayWork = ::mayContinueWork, waitForQueries = {
            while (runtime.foregroundQueries.get() > 0) delay(10)
            currentCoroutineContext().ensureActive()
        }, changed = { view -> mutable.update {
            // Publication belongs to the background runtime.generation, never to detail navigation.
            val published = if (it.topicSnapshot.topics == view.snapshot.topics) it.publishedTopics
                else publishableDiscoveryTopics(view.snapshot.topics).mapNotNull { topic ->
                    discoveryTopicPublicationStatus(topic)?.let { status -> PublishedTopic(topic, status) }
                }
            it.copy(topicSnapshot = view.snapshot, publishedTopics = published,
                topicsUpdated = view.updated, topicStatus = view.status, topicFailure = view.failure,
                topicNaming = view.naming, workPending = hasPendingWork)
        } },
        invalidationAcknowledgementLimit = invalidationAcknowledgementLimit,
        formationWorkBudget = formationWorkBudget,
        shouldYield = { runtime.foregroundQueries.get() > 0 }, operationTimings = ::recordTiming,
        labels = topicLabelStore?.let { labelStore -> TopicLabelCoordinator(labelStore, topicLabelIdentity,
            topicLabelModelAvailable, { request -> runtime.labelTopic(request, ::mayContinueWork) }, ::mayContinueWork,
            waitForQueries = {
                while (runtime.foregroundQueries.get() > 0) delay(10)
                currentCoroutineContext().ensureActive()
            }, unavailableMessage = topicLabelUnavailableMessage) },
        retrievals = topicRetrievalStore?.let { matches -> TopicRetrievalCoordinator(index, matches,
            { provider.identity }, topicRetrievalPolicy, embed = { text ->
                runtime.bulkStep { runtime.inference.withLock { runtime.embedExactInput(text) } }
            }, mayWork = ::mayContinueWork, waitForQueries = {
                while (runtime.foregroundQueries.get() > 0) delay(10)
                currentCoroutineContext().ensureActive()
            }, bulkGate = runtime.bulkGate) }) }

    private val interests = DiscoveryInterests(cache, interestStore, provider, mutable, runtime, retrieval,
        workDispatcher, modelAvailable, recommendationThreshold, clock)
    private val indexing = DiscoveryIndexing(cache, index, passageProgressStore, interestStore, provider,
        mutable, runtime, topics, workDispatcher, modelAvailable, ::mayContinueWork, ::pause,
        { wake.value++ }, monotonicNanos)
    private val previews = DiscoveryPreviews(mutable, retrieval)
    private val search = DiscoverySearch(runtime, retrieval, mutable, workDispatcher, modelAvailable,
        recommendationThreshold, ::recordTiming)

    suspend fun refreshTopics() = withContext(workDispatcher) { topics?.refresh() }
    suspend fun setTopicsInspected(active: Boolean) = withContext(workDispatcher) { topics?.setInspected(active) }
    fun sourcePageId(id: String): String = state.value.cachedSourceIds[canonicalSemanticId(id)] ?: id
    suspend fun loadInterestMatches(id: String, force: Boolean = false) = interests.loadInterestMatches(id, force)
    suspend fun followTopic(topicId: String, name: String, text: String) = interests.followTopic(topicId, name, text)
    suspend fun updateFollow(id: String, name: String, text: String) = interests.updateFollow(id, name, text)
    fun topicPreview(topicId: String): List<SemanticMatch> = previews.topicPreview(topicId)
    suspend fun connectionWindow(snapshot: TopicSnapshot, offset: Int = 0, limit: Int = 20,
        selectedId: String? = null, expectedCacheRevision: Long? = null): ConnectionCatalogPageResult =
        retrieval.connectionWindow(snapshot, offset, limit, selectedId, expectedCacheRevision)
    suspend fun groupedConnectionWindow(snapshot: TopicSnapshot, offset: Int = 0, limit: Int = 20,
        expectedCacheRevision: Long = state.value.cachedRevision): GroupedConnectionPage = withContext(workDispatcher) {
        val token = runtime.validity.capture()
        if (token.cacheRevision != expectedCacheRevision)
            GroupedConnectionPage(ConnectionCatalogPageResult(emptyList(), snapshot.catalogReference?.count ?: snapshot.connections.size, false), 0)
        else connectionProjection.read(snapshot, state.value.cachedTitles, token, offset, limit) {
            runtime.validity.isCurrent(token)
        }
    }
    suspend fun connectionPreview(connectionId: String, snapshot: TopicSnapshot = state.value.topicSnapshot,
        expectedCacheRevision: Long = state.value.cachedRevision): List<SemanticMatch> =
        previews.connectionPreview(connectionId, snapshot, expectedCacheRevision)

    suspend fun reconcile(entries: List<ReaderIndexEntry>, mayIndex: Boolean) {
        withContext(workDispatcher) {
            mutable.update { it.copy(workPending = hasPendingWork) }
            try { indexing.reconcileInBackground(entries, mayIndex) }
            finally { mutable.update { it.copy(workPending = hasPendingWork) } }
        }
    }
    suspend fun search(text: String, filters: BrowseQuery = state.value.filters) = search.search(text, filters)

    /** Optional numeric diagnostics must never change search behavior or expose source/query text. */
    private fun recordTiming(timing: Map<String, Long>) {
        try { queryTimings(timing) } catch (_: Exception) { /* Diagnostic observer only. */ }
    }

    suspend fun relatedNotes(pageId: String) = search.relatedNotes(pageId)

    suspend fun refreshRecommendations() = interests.refreshRecommendations()
    suspend fun saveInterest(id: String?, text: String, seedPageId: String? = null) = interests.saveInterest(id, text, seedPageId)
    suspend fun removeInterest(id: String) = interests.removeInterest(id)
    suspend fun clearInterests() = interests.clearInterests()
    suspend fun pause() {
        runtime.commits.withLock { index.setPaused(true); mutable.update { it.copy(paused = true) }; wake.value++ }
        runtime.interruptLabeling()
        runtime.closeLabels()
    }
    suspend fun resume() = runtime.commits.withLock { indexing.reconciliationPending = true; index.setPaused(false); mutable.update { it.copy(paused = false) }; wake.value++ }
    suspend fun clearIndex(preserveTopicContinuity: Boolean = false) = runtime.commits.withLock {
        connectionProjection.clear()
        runtime.embeddingMemo.clear()
        runtime.generation.incrementAndGet(); search.reset(); index.clear()
        passageProgressStore?.clear()
        interests.resetRequests()
        if (preserveTopicContinuity) topics?.invalidate(emptyMap()) else topics?.clear()
        mutable.update { it.copy(relatedPageId = null, results = emptyList(), related = emptyList(), recommendations = emptyList(), older = emptyList(),
            interestMatch = InterestMatchState(), indexedRevision = it.indexedRevision + 1,
            preview = emptyList(), indexedCount = 0, indexCoverage = DiscoveryCoverageStatus.Ready, embeddingWork = null, paused = true, indexFailure = null, topicFailure = null, searching = false,
            searchFailure = null, searchCompleted = false, message = null, bytes = index.bytes()) }
        wake.value++
    }
    fun preparePreview(matches: List<SemanticMatch>, label: String = "Discovery recommendations") =
        previews.preparePreview(matches, label)
    fun removePreview(pageId: String) = previews.removePreview(pageId)
    fun movePreview(pageId: String, delta: Int) = previews.movePreview(pageId, delta)
    suspend fun releaseModel() { runtime.releaseEngines(); connectionProjection.clear(); topics?.releaseMemory(); runtime.embeddingMemo.clear(); index.releaseMemory(); passageProgressStore?.releaseMemory() }
}
