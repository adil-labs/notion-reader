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
    val topicStatus: String? = null, val previewLabel: String = "Discovery recommendations",
    val unavailablePreviewIds: Set<String> = emptySet(), val previewStatus: String? = null,
    val selectedInterestId: String? = null, val interestMatches: List<SemanticMatch> = emptyList(),
    val interestLoading: Boolean = false, val interestMessage: String? = null,
    val cachedRevision: Long = 0, val indexedRevision: Long = 0,
    val cachedSourceIds: Map<String, String> = emptyMap()
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
    private val useExperimentalClustering: Boolean = false
) {
    private val mutable = MutableStateFlow(DiscoveryState())
    val state = mutable.asStateFlow()
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
        { provider.identity }, topicPolicy, bulkGate = bulkGate, mayWork = mayContinueIndexing, waitForQueries = {
            while (foregroundQueries.get() > 0) delay(10)
            currentCoroutineContext().ensureActive()
        }, changed = { view -> mutable.update { it.copy(topicSnapshot = view.snapshot,
            topicsUpdated = view.updated, topicStatus = view.status) } },
        invalidationAcknowledgementLimit = invalidationAcknowledgementLimit,
        engine = if (useExperimentalClustering) SectionHierarchicalEngine(provider.identity) else null) }

    fun refreshTopics() { topics?.refresh() }
    fun sourcePageId(id: String): String = state.value.cachedSourceIds[canonicalSemanticId(id)] ?: id
    suspend fun loadInterestMatches(id: String, force: Boolean = false) {
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
                    else inference.withLock { provider.embed(interest.text) }
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
    fun connectionPreview(connectionId: String): List<SemanticMatch> = state.value.topicSnapshot.connections
        .firstOrNull { it.id == connectionId }?.let { listOf(it.left.toMatch(), it.right.toMatch()) }.orEmpty()

    suspend fun reconcile(entries: List<ReaderIndexEntry>, mayIndex: Boolean) {
        val epoch = generation.get()
        topics?.eligibility(mayIndex)
        try {
            var sourceGeneration = cache.revision.value
            var snapshots = bulkStep { cache.snapshot() }
            while (sourceGeneration != cache.revision.value) {
                currentCoroutineContext().ensureActive()
                sourceGeneration = cache.revision.value
                snapshots = bulkStep { cache.snapshot() }
            }
            val sources = snapshots.associateBy { canonicalSemanticId(it.document.pageId) }
            eligibility = sourceGeneration to sources.mapValues { semanticRevision(it.value.document) }
            val ids = sources.keys
            topics?.invalidate(sources.mapValues { semanticRevision(it.value.document) })
            commits.withLock { (index.pageIds() - ids).forEach { index.remove(it) } }
            val paused = index.isPaused()
            val interests = interestStore.read()
            val preview = state.value.preview.map { match ->
                if (match.pageId !in ids) match.copy(metadata = SemanticMetadata("Unavailable Note"),
                    passage = match.passage.copy(sectionTitle = null, excerpt = "", input = "", start = 0, end = 0))
                else if (match.pageId in state.value.unavailablePreviewIds) {
                    val record = index.read(match.pageId)?.takeIf { it.identity == provider.identity && it.revision == semanticRevision(sources.getValue(match.pageId).document) }
                    record?.passages?.find { it.passage.id == match.passage.id }?.let {
                        SemanticMatch(match.pageId, record.metadata, it.passage, match.score)
                    } ?: match
                } else match
            }
            val unavailable = preview.filter { it.pageId !in ids || it.passage.excerpt.isEmpty() }.map { it.pageId }.toSet()
            mutable.update { s -> s.copy(cachedPageIds = ids, cachedSourceIds = sources.mapValues { it.value.document.pageId }, cachedRevision = sourceGeneration, indexedCount = minOf(s.indexedCount, ids.size), cachedTitles = sources.mapValues { it.value.document.title }, paused = paused, interests = interests,
                interestMatches = if (interestResultsRevision != sourceGeneration) emptyList() else s.interestMatches.filter { it.pageId in ids },
                results = s.results.filter { it.pageId in ids }, related = s.related.filter { it.pageId in ids },
                recommendations = s.recommendations.filter { it.pageId in ids }, older = s.older.filter { it.pageId in ids },
                preview = preview, unavailablePreviewIds = unavailable,
                previewStatus = if (unavailable.isEmpty()) null else "Some preview Notes are unavailable. Remove them or recache them before playing or saving.") }
            val metadata = entries.associateBy { canonicalSemanticId(it.pageId) }
            // Only metadata actually stored with eligible cached vectors affects grouping.
            // Browse order and uncached Notes must not restart the bounded candidate sweep.
            val cachedMetadata = sources.mapValues { (id, source) ->
                SemanticMetadata.from(metadata[id] ?: ReaderIndexEntry(id, source.document.title, source.document.lastEditedAt))
            }
            var indexed = 0
            mutable.update { it.copy(indexing = mayIndex && !paused && modelAvailable(), message = null) }
            for ((id, source) in sources) {
                currentCoroutineContext().ensureActive()
                if (mayIndex && !mayContinueIndexing()) return
                while (foregroundQueries.get() > 0) delay(10)
                if (generation.get() != epoch) return
                val revision = semanticRevision(source.document)
                val old = bulkStep { index.read(id) }
                val meta = cachedMetadata.getValue(id)
                if (old?.revision == revision && old.identity == provider.identity) {
                    indexed++
                    if (old.metadata != meta) commits.withLock {
                        if (generation.get() == epoch && cache.snapshot().any { canonicalSemanticId(it.document.pageId) == id }) {
                            if (index.write(old.copy(metadata = meta))) mutable.update { it.copy(indexedRevision = it.indexedRevision + 1) }
                        }
                    }
                    continue
                }
                // Incompatible/changed source is never searched as the new revision.
                if (old != null) commits.withLock { index.remove(id) }
                if (!mayIndex || paused || !modelAvailable()) continue
                val passages = SemanticChunker(provider.tokenizer).chunk(source.document)
                val reusable = old?.takeIf { it.identity == provider.identity }?.passages
                    ?.associateBy { semanticHash(it.passage.input) }.orEmpty()
                val embedded = mutableListOf<EmbeddedPassage>()
                for (passage in passages) {
                    while (foregroundQueries.get() > 0) delay(10)
                    if (generation.get() != epoch || index.isPaused() || !mayContinueIndexing()) return
                    val vector = reusable[semanticHash(passage.input)]?.vector
                        ?: bulkStep { inference.withLock { provider.embed(passage.input) } }
                    embedded += EmbeddedPassage(passage, normalizeEmbedding(vector, provider.identity.dimensions))
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
                if (committed == true) { indexed++; mutable.update { it.copy(indexedRevision = it.indexedRevision + 1) } }
                mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size)) }
            }
            mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size), indexing = false) }
            if (mayIndex && !index.isPaused() && modelAvailable()) withContext(Dispatchers.Default) {
                topics?.build(sources.mapValues { semanticRevision(it.value.document) }, semanticMetadataHash(cachedMetadata), sourceGeneration)
            }
            mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size), bytes = index.bytes() + (topics?.bytes() ?: 0L)) }
            if (mayIndex && topics?.needsMoreWork == true) { delay(50); wake.value++ }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(message = "Discovery index unavailable. Rebuild the index to retry.") } }
        finally { mutable.update { it.copy(indexing = false) } }
    }

    suspend fun search(text: String, filters: BrowseQuery = state.value.filters) {
        val started = System.nanoTime()
        var waitNs = 0L
        var embeddingNs = 0L
        var retrievalNs = 0L
        var completed = 0L
        val request = queryGeneration.incrementAndGet()
        val epoch = generation.get()
        mutable.update { it.copy(query = text, filters = filters, searching = true, message = null) }
        try {
            if (text.isBlank()) { mutable.update { it.copy(results = emptyList()) }; return }
            if (!modelAvailable()) { mutable.update { it.copy(results = emptyList(), message = "Download the Discovery model to search cached Notes.") }; return }
            foregroundQueries.incrementAndGet()
            val results = try { bulkGate.withLock {
                val vector = inference.withLock {
                    waitNs = System.nanoTime() - started
                    val embeddingStarted = System.nanoTime()
                    provider.embed(text).also { embeddingNs = System.nanoTime() - embeddingStarted }
                }
                val retrievalStarted = System.nanoTime()
                retrieve(vector, filters, 20).also { retrievalNs = System.nanoTime() - retrievalStarted }
            } } finally { foregroundQueries.decrementAndGet() }
            if (request == queryGeneration.get() && epoch == generation.get()) {
                mutable.update { it.copy(results = results) }
                completed = 1L
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (request == queryGeneration.get()) mutable.update { it.copy(message = "Semantic search failed. Try again or rebuild the index.") } }
        finally {
            if (request == queryGeneration.get()) mutable.update { it.copy(searching = false) }
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
                    try { inference.withLock { provider.embed(interest.text) } } finally { foregroundQueries.decrementAndGet() }
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
        val updated = interestStore.read().filterNot { it.id == id }
        interestStore.write(updated)
        interestGeneration.incrementAndGet()
        mutable.update { it.copy(interests = updated, recommendations = emptyList(), older = emptyList(),
            interestMatches = if (it.selectedInterestId == id) emptyList() else it.interestMatches) }
    }
    suspend fun clearInterests() = interestWrites.withLock { interestStore.write(emptyList()); interestGeneration.incrementAndGet(); mutable.update { it.copy(interests = emptyList(), recommendations = emptyList(), older = emptyList(), selectedInterestId = null, interestMatches = emptyList()) } }
    suspend fun pause() { index.setPaused(true); mutable.update { it.copy(paused = true) }; wake.value++ }
    suspend fun resume() { index.setPaused(false); topics?.resetWorkBudget(); mutable.update { it.copy(paused = false) }; wake.value++ }
    suspend fun clearIndex(preserveTopicContinuity: Boolean = false) = commits.withLock {
        generation.incrementAndGet(); queryGeneration.incrementAndGet(); relatedGeneration.incrementAndGet(); index.clear()
        interestResultsKey = null; interestRequest.incrementAndGet()
        if (preserveTopicContinuity) topics?.invalidate(emptyMap()) else topics?.clear()
        mutable.update { it.copy(relatedPageId = null, results = emptyList(), related = emptyList(), recommendations = emptyList(), older = emptyList(),
            selectedInterestId = null, interestMatches = emptyList(), interestLoading = false, interestMessage = null, indexedRevision = it.indexedRevision + 1,
            preview = emptyList(), indexedCount = 0, paused = true, searching = false, bytes = index.bytes()) }
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
    fun releaseModel() { provider.release(); index.releaseMemory() }
}
