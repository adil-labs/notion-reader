package com.example.notionreader.application

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiscoveryState(
    val query: String = "", val filters: BrowseQuery = BrowseQuery.Default,
    val relatedPageId: String? = null,
    val results: List<SemanticMatch> = emptyList(), val related: List<SemanticMatch> = emptyList(),
    val recommendations: List<SemanticMatch> = emptyList(), val older: List<SemanticMatch> = emptyList(),
    val preview: List<SemanticMatch> = emptyList(), val interests: List<DiscoveryInterest> = emptyList(),
    val cachedPageIds: Set<String> = emptySet(), val cachedTitles: Map<String, String> = emptyMap(), val indexedCount: Int = 0,
    val indexing: Boolean = false, val searching: Boolean = false, val paused: Boolean = true,
    val bytes: Long = 0, val message: String? = null,
    val topicSnapshot: TopicSnapshot = TopicSnapshot(), val topicsUpdated: Boolean = false,
    val publishedTopics: List<Pair<DiscoveredTopic, TopicPublicationStatus>> = emptyList(),
    val topicStatus: String? = null, val previewLabel: String = "Discovery recommendations",
    val unavailablePreviewIds: Set<String> = emptySet(), val previewStatus: String? = null,
    val selectedInterestId: String? = null, val interestMatches: List<SemanticMatch> = emptyList(),
    val interestLoading: Boolean = false, val interestMessage: String? = null,
    val cachedRevision: Long = 0, val indexedRevision: Long = 0,
    val cachedSourceIds: Map<String, String> = emptyMap(),
    val power: DiscoveryPowerState? = null,
    val embeddingWork: DiscoveryEmbeddingWork? = null,
    val effort: com.example.notionreader.domain.settings.DiscoveryEffort = com.example.notionreader.domain.settings.DiscoveryEffort.BALANCED,
    val waitingForExecution: Boolean = false
)

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
    private val passageProgressStore: SemanticIndexStore? = null
) {
    private class BulkWorkDeferred : RuntimeException()
    private val mutable = MutableStateFlow(DiscoveryState())
    private val timeEstimator = DiscoveryTimeEstimator()
    private val embeddingMemo = ExactEmbeddingMemo()
    private suspend fun embedExactInput(text: String): List<Float> {
        embeddingMemo.get(provider.identity, text)?.let { return it }
        val token = embeddingMemo.token()
        return provider.embed(text).also { embeddingMemo.put(provider.identity, text, it, token) }
    }
    fun updatePowerState(power: DiscoveryPowerState) { mutable.update { it.copy(power = power) } }
    fun updateWorkAvailability(effort: com.example.notionreader.domain.settings.DiscoveryEffort, waiting: Boolean) {
        mutable.update { it.copy(effort = effort, waitingForExecution = waiting) }
    }
    private fun mayContinueWork() = !mutable.value.paused && mutable.value.power?.allows(mutable.value.effort) != false && mayContinueIndexing()
    val state = mutable.asStateFlow()
    @Volatile private var reconciliationPending = true
    val hasPendingWork: Boolean get() = reconciliationPending || topics?.needsMoreWork == true ||
        state.value.indexedCount < state.value.cachedPageIds.size
    fun requestAutomaticContinuation() { wake.value++ }
    private val wake = MutableStateFlow(0L)
    val revision = wake.asStateFlow()
    private val generation = AtomicLong()
    private val queryGeneration = AtomicLong()
    private val relatedGeneration = AtomicLong()
    private val interestGeneration = AtomicLong()
    private val interestRequest = AtomicLong()
    @Volatile private var interestResultsKey: String? = null
    @Volatile private var interestResultsRevision: Long = -1
    private val foregroundQueries = AtomicInteger()
    private val inference = Mutex()
    private val bulkGate = Mutex()
    private val commits = Mutex()
    private val interestWrites = Mutex()
    @Volatile private var eligibility: Pair<Long, Map<String, String>>? = null
    private val topics = topicStore?.let { store -> TopicCoordinator(cache, index, store, topicCheckpointStore,
        { provider.identity }, topicPolicy, bulkGate = bulkGate, mayWork = ::mayContinueWork, waitForQueries = {
            while (foregroundQueries.get() > 0) delay(10)
            currentCoroutineContext().ensureActive()
        }, changed = { view -> mutable.update {
            // Publication belongs to the background generation, never to detail navigation.
            val published = if (it.topicSnapshot.topics == view.snapshot.topics) it.publishedTopics
                else publishableDiscoveryTopics(view.snapshot.topics).mapNotNull { topic ->
                    discoveryTopicPublicationStatus(topic)?.let { status -> topic to status }
                }
            it.copy(topicSnapshot = view.snapshot, publishedTopics = published,
                topicsUpdated = view.updated, topicStatus = view.status)
        } },
        invalidationAcknowledgementLimit = invalidationAcknowledgementLimit,
        formationWorkBudget = formationWorkBudget,
        shouldYield = { foregroundQueries.get() > 0 }) }

    suspend fun refreshTopics() = withContext(workDispatcher) { topics?.refresh() }
    fun sourcePageId(id: String): String = state.value.cachedSourceIds[canonicalSemanticId(id)] ?: id
    suspend fun loadInterestMatches(id: String, force: Boolean = false) {
        withContext(workDispatcher) { loadInterestMatchesInBackground(id, force) }
    }
    private suspend fun loadInterestMatchesInBackground(id: String, force: Boolean) {
        val interest = interestStore.read().firstOrNull { it.id == id }
        val sourceRevision = cache.revision.value
        val key = "$interest|$sourceRevision|${provider.identity}|${generation.get()}|${state.value.indexedRevision}"
        if (!force && interestResultsKey == key && state.value.selectedInterestId == id) return
        val request = interestRequest.incrementAndGet()
        val epoch = generation.get()
        val interestEpoch = interestGeneration.get()
        mutable.update { it.copy(selectedInterestId = id, interestMatches = emptyList(), interestLoading = true, interestMessage = null) }
        try {
            if (interest == null || !modelAvailable()) {
                mutable.update { it.copy(interestMessage = if (interest == null) "Interest unavailable." else "Set up Discovery in Settings to find matching Notes.") }
                return
            }
            foregroundQueries.incrementAndGet()
            val matches = try { bulkGate.withLock {
                val vector = if (interest.seedPageId != null) seedVector(interest.seedPageId)
                    else inference.withLock { embedExactInput(interest.text) }
                vector?.let { retrieve(it, BrowseQuery.Default, 20, interest.seedPageId, recommendationThreshold) }
            } } finally { foregroundQueries.decrementAndGet() }
            if (epoch == generation.get() && request == interestRequest.get() && interestEpoch == interestGeneration.get() && sourceRevision == cache.revision.value) {
                interestResultsKey = key
                interestResultsRevision = sourceRevision
                mutable.update { it.copy(interestMatches = matches.orEmpty(), interestMessage = when {
                    matches == null -> "Seed Note unavailable. Recache it in Browse to find matching Notes."
                    matches.isEmpty() -> "No matching indexed Notes for this interest. Try editing it or caching more Notes."
                    else -> null
                }) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (request == interestRequest.get() && epoch == generation.get()) mutable.update { it.copy(interestMessage = "Matching Notes are unavailable. Try again.") }
        } finally {
            if (request == interestRequest.get()) mutable.update { it.copy(interestLoading = false) }
        }
    }
    suspend fun followTopic(topicId: String, name: String, text: String) = interestWrites.withLock {
        if (name.isBlank() || text.isBlank()) return@withLock
        val current = interestStore.read()
        if (current.any { it.originTopicId == topicId }) return@withLock
        if (state.value.topicSnapshot.topics.none { it.id == topicId }) return@withLock
        val interest = DiscoveryInterest(UUID.randomUUID().toString(), text.trim(), displayName = name.trim(), originTopicId = topicId)
        val updated = current + interest
        interestStore.write(updated); interestGeneration.incrementAndGet()
        mutable.update { it.copy(interests = updated) }
    }
    suspend fun updateFollow(id: String, name: String, text: String) = interestWrites.withLock {
        if (name.isBlank() || text.isBlank()) return@withLock
        val current = interestStore.read()
        val updated = current.map {
            if (it.id == id && it.seedPageId == null) it.copy(displayName = name.trim(), text = text.trim()) else it
        }
        if (updated == current) return@withLock
        interestStore.write(updated); interestGeneration.incrementAndGet()
        mutable.update { it.copy(interests = updated, interestMatches = if (it.selectedInterestId == id) emptyList() else it.interestMatches) }
    }
    fun topicPreview(topicId: String): List<SemanticMatch> = state.value.topicSnapshot.topics
        .firstOrNull { it.id == topicId }?.members.orEmpty().take(5).map { it.toMatch() }
    suspend fun connectionWindow(snapshot: TopicSnapshot, offset: Int = 0, limit: Int = 20,
        selectedId: String? = null, expectedCacheRevision: Long? = null): ConnectionCatalogPageResult = withContext(workDispatcher) {
        val epoch = generation.get()
        val revision = cache.revision.value
        if (expectedCacheRevision != null && revision != expectedCacheRevision)
            return@withContext ConnectionCatalogPageResult(emptyList(), snapshot.catalogReference?.count ?: snapshot.connections.size, false)
        if (topicStore == null && snapshot.catalogReference != null)
            return@withContext ConnectionCatalogPageResult(emptyList(), snapshot.catalogReference.count, false)
        val page = if (selectedId != null) topicStore?.findConnection(snapshot, selectedId)
            ?: ConnectionCatalogPageResult(listOfNotNull(snapshot.connections.firstOrNull { it.id == selectedId }), snapshot.connections.size)
        else topicStore?.connectionPage(snapshot, offset, limit)
            ?: ConnectionCatalogPageResult(snapshot.connections.drop(offset).take(limit), snapshot.connections.size)
        val eligible = eligibleRevisions()
        // Clearing derived data does not mutate the source cache. Reject reads that
        // captured evidence before a reset, just as semantic retrieval does.
        if (generation.get() != epoch || cache.revision.value != revision)
            ConnectionCatalogPageResult(emptyList(), page.totalCount, false)
        else page.copy(connections = page.connections.filter {
            eligible[canonicalSemanticId(it.left.pageId)] == it.left.revision && eligible[canonicalSemanticId(it.right.pageId)] == it.right.revision
        })
    }
    suspend fun connectionPreview(connectionId: String, snapshot: TopicSnapshot = state.value.topicSnapshot,
        expectedCacheRevision: Long = state.value.cachedRevision): List<SemanticMatch> =
        connectionWindow(snapshot, selectedId = connectionId, expectedCacheRevision = expectedCacheRevision).connections.firstOrNull()
            ?.let { listOf(it.left.toMatch(), it.right.toMatch()) }.orEmpty()

    suspend fun reconcile(entries: List<ReaderIndexEntry>, mayIndex: Boolean) {
        withContext(workDispatcher) { reconcileInBackground(entries, mayIndex) }
    }
    private suspend fun reconcileInBackground(entries: List<ReaderIndexEntry>, mayIndex: Boolean) {
        val epoch = generation.get()
        try {
            var sourceGeneration = cache.revision.value
            var snapshots = bulkStep { cache.snapshot() }
            while (sourceGeneration != cache.revision.value) {
                currentCoroutineContext().ensureActive()
                sourceGeneration = cache.revision.value
                snapshots = bulkStep { cache.snapshot() }
            }
            val sources = snapshots.associateBy { canonicalSemanticId(it.document.pageId) }
            if (state.value.cachedRevision != sourceGeneration) {
                reconciliationPending = true
                embeddingMemo.clear()
                mutable.update { it.copy(embeddingWork = null) }
            }
            eligibility = sourceGeneration to sources.mapValues { semanticRevision(it.value.document) }
            val ids = sources.keys
            topics?.invalidate(sources.mapValues { semanticRevision(it.value.document) })
            commits.withLock { (index.pageIds() - ids).forEach { index.remove(it) } }
            passageProgressStore?.let { progress -> commits.withLock {
                for (id in progress.pageIds()) {
                    val saved = progress.read(id)
                    if (id !in ids || saved?.identity != provider.identity ||
                        saved.revision != sources[id]?.document?.let(::semanticRevision)) progress.remove(id)
                }
            } }
            val paused = index.isPaused()
            val interests = interestStore.read()
            val displayed = state.value
            val displayedIds = (displayed.results + displayed.related + displayed.recommendations +
                displayed.older + displayed.preview).map { it.pageId }.distinct()
            val currentRecords = mutableMapOf<String, SemanticRecord>()
            for (id in displayedIds) {
                val source = sources[id] ?: continue
                index.read(id)?.takeIf { it.identity == provider.identity && it.revision == semanticRevision(source.document) }
                    ?.let { currentRecords[id] = it }
            }
            fun currentEvidence(match: SemanticMatch): Boolean = currentRecords[match.pageId]?.let { record ->
                record.metadata == match.metadata && record.passages.any { it.passage == match.passage }
            } == true
            val preview = displayed.preview.map { match ->
                if (match.pageId in ids && match.pageId in displayed.unavailablePreviewIds) {
                    val record = currentRecords[match.pageId]
                    record?.passages?.find { it.passage.id == match.passage.id }?.let {
                        SemanticMatch(match.pageId, record.metadata, it.passage, match.score)
                    } ?: match
                } else if (!currentEvidence(match)) match.copy(metadata = SemanticMetadata("Unavailable Note"),
                    passage = match.passage.copy(sectionTitle = null, excerpt = "", input = "", start = 0, end = 0))
                else match
            }
            val unavailable = preview.filter { it.pageId !in ids || it.passage.excerpt.isEmpty() }.map { it.pageId }.toSet()
            mutable.update { s ->
                // Only reconcile the captured lists. Foreground queries and preview edits can
                // publish newer state while disk validation is suspended.
                val samePreview = s.preview === displayed.preview
                s.copy(cachedPageIds = ids, cachedSourceIds = sources.mapValues { it.value.document.pageId }, cachedRevision = sourceGeneration, indexedCount = minOf(s.indexedCount, ids.size), cachedTitles = sources.mapValues { it.value.document.title }, paused = paused, interests = interests,
                    interestMatches = if (s.interestMatches !== displayed.interestMatches) s.interestMatches
                        else if (interestResultsRevision != sourceGeneration) emptyList() else s.interestMatches.filter { it.pageId in ids },
                    results = if (s.results === displayed.results) s.results.filter(::currentEvidence) else s.results,
                    related = if (s.related === displayed.related) s.related.filter(::currentEvidence) else s.related,
                    recommendations = if (s.recommendations === displayed.recommendations) s.recommendations.filter(::currentEvidence) else s.recommendations,
                    older = if (s.older === displayed.older) s.older.filter(::currentEvidence) else s.older,
                    preview = if (samePreview) preview else s.preview,
                    unavailablePreviewIds = if (samePreview) unavailable else s.unavailablePreviewIds,
                    previewStatus = if (!samePreview) s.previewStatus else if (unavailable.isEmpty()) null
                        else "Some preview Notes are unavailable. Remove them or recache them before playing or saving.")
            }
            val metadata = entries.associateBy { canonicalSemanticId(it.pageId) }
            // Only metadata actually stored with eligible cached vectors affects grouping.
            // Browse order and uncached Notes must not restart the bounded candidate sweep.
            val cachedMetadata = sources.mapValues { (id, source) ->
                SemanticMetadata.from(metadata[id] ?: ReaderIndexEntry(id, source.document.title, source.document.lastEditedAt))
            }
            var indexed = 0
            val jobs = mutableListOf<Pair<String, Int>>()
            mutable.update { it.copy(message = null) }
            for ((id, source) in sources) {
                currentCoroutineContext().ensureActive()
                if (mayIndex && !mayContinueWork()) return
                while (foregroundQueries.get() > 0) delay(10)
                if (generation.get() != epoch) return
                val revision = semanticRevision(source.document)
                val old = bulkStep { index.read(id) }
                val meta = cachedMetadata.getValue(id)
                if (old?.revision == revision && old.identity == provider.identity) {
                    // A process may die between full-record commit and checkpoint retirement.
                    passageProgressStore?.remove(id)
                    indexed++
                    if (old.metadata != meta) {
                        reconciliationPending = true
                        commits.withLock {
                            if (generation.get() == epoch && cache.snapshot().any { canonicalSemanticId(it.document.pageId) == id }) {
                                if (index.write(old.copy(metadata = meta))) mutable.update { it.copy(indexedRevision = it.indexedRevision + 1) }
                            }
                        }
                    }
                    continue
                }
                if (!mayIndex || paused || !modelAvailable()) {
                    if (old != null) commits.withLock { index.remove(id) }
                    continue
                }
                // Keep only work counts, never a second library of embedded vectors.
                val resumed = passageProgressStore?.read(id)?.takeIf {
                    it.identity == provider.identity && it.revision == revision
                }?.passages.orEmpty()
                val reusable = (old?.takeIf { it.identity == provider.identity }?.passages.orEmpty() + resumed)
                    .mapTo(hashSetOf()) { semanticHash(it.passage.input) }
                val count = SemanticChunker(provider.tokenizer).chunk(source.document)
                    .count { semanticHash(it.input) !in reusable }
                jobs += id to count
            }
            var remainingPassages = jobs.sumOf { it.second }
            var remainingNotes = jobs.size
            val workKey = semanticHash(provider.identity.toString() + sources.mapValues { semanticRevision(it.value.document) }.toSortedMap() + epoch)
            timeEstimator.begin(workKey, remainingPassages)
            fun publishWork() { mutable.update { it.copy(embeddingWork = DiscoveryEmbeddingWork(
                remainingNotes, remainingPassages, timeEstimator.remaining(remainingPassages))) } }
            publishWork()
            mutable.update { it.copy(indexing = jobs.isNotEmpty()) }
            for ((id, _) in jobs) {
                currentCoroutineContext().ensureActive()
                if (generation.get() != epoch || cache.revision.value != sourceGeneration || index.isPaused() || !mayContinueWork()) return
                val source = sources.getValue(id)
                val revision = semanticRevision(source.document)
                val meta = cachedMetadata.getValue(id)
                val old = bulkStep { index.read(id) }
                // Retain compatibility reuse only within this one Note's bounded lifetime.
                val passages = SemanticChunker(provider.tokenizer).chunk(source.document)
                val resumed = passageProgressStore?.read(id)?.takeIf {
                    it.identity == provider.identity && it.revision == revision
                }?.passages.orEmpty()
                val reusable = (old?.takeIf { it.identity == provider.identity }?.passages.orEmpty() + resumed)
                    .associateBy { semanticHash(it.passage.input) }
                if (old != null) commits.withLock { index.remove(id) }
                val embedded = mutableListOf<EmbeddedPassage>()
                for (passage in passages) {
                    while (foregroundQueries.get() > 0) delay(10)
                    if (generation.get() != epoch || cache.revision.value != sourceGeneration || index.isPaused() || !mayContinueWork()) return
                    val reused = reusable[semanticHash(passage.input)]?.vector
                    val vector = reused ?: bulkStep { inference.withLock {
                        // Foreground queries can hold either mutex after the outer check.
                        if (generation.get() != epoch || cache.revision.value != sourceGeneration ||
                            index.isPaused() || !mayContinueWork()) throw BulkWorkDeferred()
                        embeddingMemo.get(provider.identity, passage.input) ?: run {
                            val started = monotonicNanos()
                            val memoToken = embeddingMemo.token()
                            val value = provider.embed(passage.input)
                            if (generation.get() == epoch) {
                                embeddingMemo.put(provider.identity, passage.input, value, memoToken)
                                timeEstimator.record(monotonicNanos() - started)
                            }
                            value
                        }
                    } }
                    if (generation.get() != epoch || cache.revision.value != sourceGeneration) return
                    embedded += EmbeddedPassage(passage, normalizeEmbedding(vector, provider.identity.dimensions))
                    if (passageProgressStore != null && reused == null) {
                        val saved = commits.withLock {
                            if (generation.get() != epoch || cache.revision.value != sourceGeneration) return@withLock null
                            passageProgressStore.write(SemanticRecord(id, revision, provider.identity, meta, embedded.toList()))
                        }
                        if (saved == null) return
                        if (!saved) {
                            pause()
                            mutable.update { it.copy(message = "Discovery storage is full. Passage progress is incomplete; indexing paused.") }
                            return
                        }
                    }
                    if (reused == null) { remainingPassages = (remainingPassages - 1).coerceAtLeast(0); publishWork() }
                }
                val committed = commits.withLock {
                    if (generation.get() != epoch || index.isPaused()) return@withLock null
                    val latest = cache.snapshot().firstOrNull { canonicalSemanticId(it.document.pageId) == id }
                    if (latest == null || semanticRevision(latest.document) != revision) return@withLock null
                    index.write(SemanticRecord(id, revision, provider.identity, meta, embedded))
                }
                if (committed == false) {
                    pause()
                    mutable.update { it.copy(message = "Discovery storage is full. Indexing paused; some cached Notes are not indexed.") }
                    break
                }
                if (committed == true) {
                    passageProgressStore?.remove(id)
                    indexed++; remainingNotes--; publishWork()
                    mutable.update { it.copy(indexedRevision = it.indexedRevision + 1) }
                }
                mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size)) }
            }
            mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size), indexing = false) }
            if (mayIndex && !index.isPaused() && modelAvailable()) withContext(Dispatchers.Default) {
                topics?.build(sources.mapValues { semanticRevision(it.value.document) }, semanticMetadataHash(cachedMetadata), sourceGeneration)
            }
            mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size),
                bytes = index.bytes() + (passageProgressStore?.bytes() ?: 0L) + (topics?.bytes() ?: 0L)) }
            if (mayIndex && !index.isPaused() && modelAvailable() && mayContinueWork() &&
                cache.revision.value == sourceGeneration && indexed == ids.size && topics?.needsMoreWork != true)
                reconciliationPending = false
            if (mayIndex && topics?.needsMoreWork == true) { delay(50); wake.value++ }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: BulkWorkDeferred) { /* Policy deferral is not an index failure. */ }
        catch (_: Exception) { mutable.update { it.copy(message = "Discovery index unavailable. Rebuild the index to retry.") } }
        finally { mutable.update { it.copy(indexing = false) } }
    }

    suspend fun search(text: String, filters: BrowseQuery = state.value.filters) {
        val started = System.nanoTime()
        val request = queryGeneration.incrementAndGet()
        val epoch = generation.get()
        mutable.update { it.copy(query = text, filters = filters, searching = true, message = null) }
        try { withContext(workDispatcher) { searchInBackground(text, filters, request, epoch, started) } }
        finally { mutable.update { if (request == queryGeneration.get()) it.copy(searching = false) else it } }
    }
    private suspend fun searchInBackground(text: String, filters: BrowseQuery, request: Long, epoch: Long, started: Long) {
        if (request != queryGeneration.get() || epoch != generation.get()) return
        var waitNs = 0L
        var embeddingNs = 0L
        var retrievalNs = 0L
        var completed = 0L
        try {
            if (text.isBlank()) { mutable.update { if (request == queryGeneration.get() && epoch == generation.get()) it.copy(results = emptyList()) else it }; return }
            if (!modelAvailable()) { mutable.update { if (request == queryGeneration.get() && epoch == generation.get()) it.copy(results = emptyList(), message = "Download the Discovery model to search cached Notes.") else it }; return }
            foregroundQueries.incrementAndGet()
            val results = try { bulkGate.withLock {
                val vector = inference.withLock {
                    waitNs = System.nanoTime() - started
                    val embeddingStarted = System.nanoTime()
                    embedExactInput(text).also { embeddingNs = System.nanoTime() - embeddingStarted }
                }
                val retrievalStarted = System.nanoTime()
                retrieve(vector, filters, 20).also { retrievalNs = System.nanoTime() - retrievalStarted }
            } } finally { foregroundQueries.decrementAndGet() }
            if (request == queryGeneration.get() && epoch == generation.get()) {
                mutable.update { if (request == queryGeneration.get() && epoch == generation.get()) it.copy(results = results) else it }
                completed = 1L
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { if (request == queryGeneration.get() && epoch == generation.get()) it.copy(message = "Semantic search failed. Try again or rebuild the index.") else it } }
        finally {
            recordTiming(mapOf("searchTotalNs" to System.nanoTime() - started, "waitNs" to waitNs,
                "embeddingNs" to embeddingNs, "retrievalNs" to retrievalNs, "completed" to completed))
        }
    }

    /** Optional numeric diagnostics must never change search behavior or expose source/query text. */
    private fun recordTiming(timing: Map<String, Long>) {
        try { queryTimings(timing) } catch (_: Exception) { /* Diagnostic observer only. */ }
    }

    private suspend fun <T> bulkStep(action: suspend () -> T): T {
        while (true) {
            while (foregroundQueries.get() > 0) delay(10)
            bulkGate.lock()
            if (foregroundQueries.get() == 0) {
                try { return action() } finally { bulkGate.unlock() }
            }
            bulkGate.unlock()
        }
    }

    /** Retains hashes/identities only. Every cache mutation invalidates this map. */
    private suspend fun eligibleRevisions(): Map<String, String> {
        val revision = cache.revision.value
        eligibility?.takeIf { it.first == revision }?.let { return it.second }
        val sources = cache.snapshot().associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        if (cache.revision.value == revision) eligibility = revision to sources
        return sources
    }

    private suspend fun retrieve(vector: List<Float>, filters: BrowseQuery, limit: Int,
        exclude: String? = null, threshold: Float = -1f, olderThan: Instant? = null): List<SemanticMatch> = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val epoch = generation.get()
        val revision = cache.revision.value
        val sources = eligibleRevisions()
        val sourceReadNanos = System.nanoTime() - started
        var recordReadNanos = 0L
        var validationNanos = 0L
        var rankingNanos = 0L
        var best = emptyList<SemanticMatch>()
        val catalogStarted = System.nanoTime()
        val pageIds = index.pageIds().sorted()
        val catalogNanos = System.nanoTime() - catalogStarted
        for (id in pageIds) {
            currentCoroutineContext().ensureActive()
            val source = sources[id] ?: continue
            val readStarted = System.nanoTime()
            val record = index.read(id) ?: continue
            recordReadNanos += System.nanoTime() - readStarted
            val validationStarted = System.nanoTime()
            val sourceMatches = record.revision == source
            validationNanos += System.nanoTime() - validationStarted
            if (!sourceMatches) continue
            if (olderThan != null && record.metadata.lastEditedAt?.let { date -> runCatching { !Instant.parse(date).isAfter(olderThan) }.getOrDefault(false) } != true) continue
            val rankingStarted = System.nanoTime()
            val matches = rankSemanticRecords(vector, provider.identity, sequenceOf(record), filters, limit, exclude, threshold)
            best = (best + matches).distinctBy { it.pageId }.sortedWith(compareByDescending<SemanticMatch> { it.score }.thenBy { it.pageId }.thenBy { it.passage.order }).take(limit)
            rankingNanos += System.nanoTime() - rankingStarted
        }
        // Refuse stale publication even if cleanup is queued behind this query.
        if (generation.get() != epoch || cache.revision.value != revision) return@withContext emptyList()
        recordTiming(mapOf("sourceReadNs" to sourceReadNanos, "catalogNs" to catalogNanos,
            "recordReadNs" to recordReadNanos, "validationNs" to validationNanos, "rankingNs" to rankingNanos,
            "totalNs" to System.nanoTime() - started))
        best
    }

    private suspend fun seedVector(pageId: String): List<Float>? = withContext(Dispatchers.Default) {
        val id = canonicalSemanticId(pageId)
        val source = cache.snapshot().firstOrNull { canonicalSemanticId(it.document.pageId) == id } ?: return@withContext null
        val record = index.read(id)?.takeIf { it.identity == provider.identity && it.revision == semanticRevision(source.document) } ?: return@withContext null
        if (record.passages.isEmpty()) return@withContext null
        normalizeEmbedding(List(provider.identity.dimensions) { d -> record.passages.sumOf { it.vector[d].toDouble() }.toFloat() }, provider.identity.dimensions)
    }

    suspend fun relatedNotes(pageId: String) {
        val epoch = generation.get()
        val request = relatedGeneration.incrementAndGet()
        val sourceRevision = cache.revision.value
        mutable.update { it.copy(relatedPageId = canonicalSemanticId(pageId), related = emptyList(), message = "Finding related Notes…") }
        try {
            val vector = seedVector(pageId)
            val matches = vector?.let { retrieve(it, BrowseQuery.Default, 5, pageId, recommendationThreshold) }.orEmpty()
            if (epoch == generation.get() && request == relatedGeneration.get() && sourceRevision == cache.revision.value) mutable.update { it.copy(related = matches,
                message = if (vector == null) "This Note must be cached and indexed before related Notes are available." else null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(related = emptyList(), message = "Related Notes are unavailable. Rebuild the index to retry.") } }
    }

    suspend fun refreshRecommendations() {
        withContext(workDispatcher) { refreshRecommendationsInBackground() }
    }
    private suspend fun refreshRecommendationsInBackground() {
        if (!modelAvailable()) return
        val epoch = generation.get()
        val interestsEpoch = interestGeneration.get()
        val sourceRevision = cache.revision.value
        try {
            val recommendations = mutableListOf<SemanticMatch>()
            val olderMatches = mutableListOf<SemanticMatch>()
            val cutoff = clock().minusSeconds(90L * 86400)
            for (interest in interestStore.read()) {
                val vector = if (interest.seedPageId != null) seedVector(interest.seedPageId) else {
                    foregroundQueries.incrementAndGet()
                    try { inference.withLock { embedExactInput(interest.text) } } finally { foregroundQueries.decrementAndGet() }
                }
                if (vector != null) {
                    recommendations += retrieve(vector, BrowseQuery.Default, 5, interest.seedPageId, recommendationThreshold)
                    olderMatches += retrieve(vector, BrowseQuery.Default, 5, interest.seedPageId, recommendationThreshold, olderThan = cutoff)
                }
            }
            val ordered = recommendations.sortedWith(compareByDescending<SemanticMatch> { it.score }.thenBy { it.pageId }).distinctBy { it.pageId }
            val older = olderMatches.sortedWith(compareByDescending<SemanticMatch> { it.score }.thenBy { it.pageId }).distinctBy { it.pageId }.take(5)
            if (epoch == generation.get() && interestsEpoch == interestGeneration.get() && sourceRevision == cache.revision.value) mutable.update { it.copy(recommendations = ordered.take(5), older = older) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(message = "Interest recommendations are unavailable. Try again.") } }
    }

    suspend fun saveInterest(id: String?, text: String, seedPageId: String? = null) = interestWrites.withLock {
        val label = text.trim()
        if (seedPageId == null && label.isBlank()) return@withLock
        val current = interestStore.read()
        val old = current.firstOrNull { it.id == id }
        val interest = DiscoveryInterest(id ?: UUID.randomUUID().toString(), if (seedPageId == null) label else "", seedPageId?.let(::canonicalSemanticId),
            old?.displayName, old?.originTopicId)
        if (old == interest) return@withLock
        val updated = current.filterNot { it.id == interest.id } + interest
        interestStore.write(updated)
        interestGeneration.incrementAndGet()
        mutable.update { it.copy(interests = updated, interestMatches = if (it.selectedInterestId == interest.id) emptyList() else it.interestMatches) }
    }
    suspend fun removeInterest(id: String) = interestWrites.withLock {
        embeddingMemo.clear()
        val updated = interestStore.read().filterNot { it.id == id }
        interestStore.write(updated)
        interestGeneration.incrementAndGet()
        mutable.update { it.copy(interests = updated, recommendations = emptyList(), older = emptyList(),
            interestMatches = if (it.selectedInterestId == id) emptyList() else it.interestMatches) }
    }
    suspend fun clearInterests() = interestWrites.withLock { embeddingMemo.clear(); interestStore.write(emptyList()); interestGeneration.incrementAndGet(); mutable.update { it.copy(interests = emptyList(), recommendations = emptyList(), older = emptyList(), selectedInterestId = null, interestMatches = emptyList()) } }
    suspend fun pause() { index.setPaused(true); mutable.update { it.copy(paused = true) }; wake.value++ }
    suspend fun resume() { reconciliationPending = true; index.setPaused(false); mutable.update { it.copy(paused = false) }; wake.value++ }
    suspend fun clearIndex(preserveTopicContinuity: Boolean = false) = commits.withLock {
        embeddingMemo.clear()
        generation.incrementAndGet(); queryGeneration.incrementAndGet(); relatedGeneration.incrementAndGet(); index.clear()
        passageProgressStore?.clear()
        interestResultsKey = null; interestRequest.incrementAndGet()
        if (preserveTopicContinuity) topics?.invalidate(emptyMap()) else topics?.clear()
        mutable.update { it.copy(relatedPageId = null, results = emptyList(), related = emptyList(), recommendations = emptyList(), older = emptyList(),
            selectedInterestId = null, interestMatches = emptyList(), interestLoading = false, interestMessage = null, indexedRevision = it.indexedRevision + 1,
            preview = emptyList(), indexedCount = 0, embeddingWork = null, paused = true, searching = false, bytes = index.bytes()) }
        wake.value++
    }
    fun preparePreview(matches: List<SemanticMatch>, label: String = "Discovery recommendations") {
        mutable.update { it.copy(preview = matches.distinctBy { it.pageId }.take(5), previewLabel = label,
            unavailablePreviewIds = emptySet(), previewStatus = null) }
    }
    fun removePreview(pageId: String) { mutable.update {
        val unavailable = it.unavailablePreviewIds - pageId
        it.copy(preview = it.preview.filterNot { match -> match.pageId == pageId }, unavailablePreviewIds = unavailable,
            previewStatus = it.previewStatus.takeUnless { unavailable.isEmpty() })
    } }
    fun movePreview(pageId: String, delta: Int) { mutable.update { s ->
        val list = s.preview.toMutableList(); val from = list.indexOfFirst { it.pageId == pageId }; val to = from + delta
        if (from >= 0 && to in list.indices) { val item = list.removeAt(from); list.add(to, item) }
        s.copy(preview = list)
    } }
    fun releaseModel() { topics?.releaseMemory(); embeddingMemo.clear(); provider.release(); index.releaseMemory(); passageProgressStore?.releaseMemory() }
}
