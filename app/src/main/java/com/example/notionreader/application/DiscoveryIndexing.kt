package com.example.notionreader.application

import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.example.notionreader.domain.ReaderIndexEntry

/** Cache/index reconciliation and bounded embedding work, called only by the process coordinator. */
internal class DiscoveryIndexing(
    private val cache: DocumentCache,
    private val index: SemanticIndexStore,
    private val passageProgressStore: SemanticIndexStore?,
    private val interestStore: DiscoveryInterestStore,
    private val provider: EmbeddingProvider,
    private val mutable: MutableStateFlow<DiscoveryState>,
    private val runtime: DiscoveryRuntime,
    private val topics: TopicCoordinator?,
    private val workDispatcher: CoroutineDispatcher,
    private val modelAvailable: () -> Boolean,
    private val mayContinueWork: () -> Boolean,
    private val pause: suspend () -> Unit,
    private val wake: () -> Unit,
    private val monotonicNanos: () -> Long
) {
    private class BulkWorkDeferred : RuntimeException()
    private val timeEstimator = DiscoveryTimeEstimator()
    @Volatile var reconciliationPending = true
    private val state get() = mutable
    private fun writeFailure(store: SemanticIndexStore): String =
        if (store.lastWriteFailure == SemanticWriteFailure.RecordMemoryLimit)
            "One Note exceeds the safe indexing record size. Discovery coverage is incomplete; other offline Notes remain available."
        else "Discovery needs more storage. Coverage is incomplete; indexing is waiting for space or a higher limit."

    suspend fun reconcileInBackground(entries: List<ReaderIndexEntry>, mayIndex: Boolean) {
        val epoch = runtime.generation.get()
        try {
            var sourceGeneration = cache.revision.value
            var snapshots = runtime.bulkStep { cache.snapshot() }
            while (sourceGeneration != cache.revision.value) {
                currentCoroutineContext().ensureActive()
                sourceGeneration = cache.revision.value
                snapshots = runtime.bulkStep { cache.snapshot() }
            }
            val sources = snapshots.associateBy { canonicalSemanticId(it.document.pageId) }
            if (state.value.cachedRevision != sourceGeneration) {
                reconciliationPending = true
                runtime.embeddingMemo.clear()
                mutable.update { it.copy(embeddingWork = null, indexCoverage = DiscoveryCoverageStatus.Checking) }
            }
            runtime.validity.remember(sourceGeneration, sources.mapValues { semanticRevision(it.value.document) })
            val ids = sources.keys
            val available = modelAvailable()
            val initialInventory = state.value.cacheStatus != DiscoveryCacheStatus.Ready
            // Startup inventory must not wait for saved-generation I/O or publication.
            // Later reconciliations retain the atomic evidence-withdrawal path below.
            runtime.commits.withLock {
                val paused = index.isPaused()
                mutable.update { current ->
                    if (current.cacheStatus != DiscoveryCacheStatus.Ready) current.copy(
                        cachedPageIds = ids,
                        cachedTitles = sources.mapValues { it.value.document.title },
                        cachedSourceIds = sources.mapValues { it.value.document.pageId },
                        cachedRevision = sourceGeneration,
                        cacheStatus = DiscoveryCacheStatus.Ready, paused = paused, modelAvailable = available
                    ) else current.copy(paused = paused, modelAvailable = available)
                }
            }
            if (!initialInventory) topics?.invalidate(sources.mapValues { semanticRevision(it.value.document) })
            runtime.commits.withLock { (index.pageIds() - ids).forEach { index.remove(it) } }
            passageProgressStore?.let { progress -> runtime.commits.withLock {
                for (id in progress.pageIds()) {
                    val saved = progress.read(id)
                    if (id !in ids || saved?.identity != provider.identity) progress.remove(id)
                }
            } }
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
                s.copy(cachedPageIds = ids, cachedSourceIds = sources.mapValues { it.value.document.pageId }, cachedRevision = sourceGeneration, indexedCount = minOf(s.indexedCount, ids.size), cachedTitles = sources.mapValues { it.value.document.title }, interests = interests,
                    interestMatch = if (s.interestMatch !== displayed.interestMatch) s.interestMatch
                        else s.interestMatch.reconcile(sourceGeneration, ids),
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
            mutable.update { it.copy(message = it.searchFailure) }
            for ((id, source) in sources) {
                currentCoroutineContext().ensureActive()
                while (runtime.foregroundQueries.get() > 0) delay(10)
                if (runtime.generation.get() != epoch) return
                val revision = semanticRevision(source.document)
                val old = runtime.bulkStep { index.read(id) }
                val meta = cachedMetadata.getValue(id)
                if (old?.revision == revision && old.identity == provider.identity) {
                    // A process may die between full-record commit and checkpoint retirement.
                    passageProgressStore?.remove(id)
                    indexed++
                    if (old.metadata != meta) {
                        reconciliationPending = true
                        runtime.commits.withLock {
                            if (runtime.generation.get() == epoch && cache.snapshot().any { canonicalSemanticId(it.document.pageId) == id }) {
                                if (index.write(old.copy(metadata = meta))) mutable.update { it.copy(indexedRevision = it.indexedRevision + 1) }
                            }
                        }
                    }
                    continue
                }
                // Withdraw stale evidence only after compatible vectors have been rebound to
                // current source passages. This durable handoff also covers policy deferral.
                val savedProgress = passageProgressStore?.read(id)
                if (passageProgressStore != null && modelAvailable() &&
                    (old?.identity == provider.identity || savedProgress?.identity == provider.identity && savedProgress.revision != revision)) {
                    val candidates = (old?.takeIf { it.identity == provider.identity }?.passages.orEmpty() +
                        savedProgress?.takeIf { it.identity == provider.identity }?.passages.orEmpty())
                        .associateBy { semanticHash(it.passage.input) }
                    val retained = SemanticChunker(provider.tokenizer).chunk(source.document).mapNotNull { passage ->
                        candidates[semanticHash(passage.input)]?.let { EmbeddedPassage(passage, it.vector) }
                    }
                    val saved = runtime.commits.withLock {
                        if (runtime.generation.get() != epoch || cache.revision.value != sourceGeneration) return
                        val result = passageProgressStore.write(SemanticRecord(id, revision, provider.identity, meta, retained))
                        if (old != null) index.remove(id)
                        result
                    }
                    if (!saved) {
                        // Capacity waiting never persists explicit user pause.
                        val failure = writeFailure(passageProgressStore)
                        mutable.update { it.copy(message = failure, indexFailure = failure) }
                        return
                    }
                }
                if (!modelAvailable() && savedProgress != null && savedProgress.revision != revision)
                    passageProgressStore?.remove(id)
                if (!mayIndex || state.value.paused || !modelAvailable() || !mayContinueWork()) {
                    if (old != null) runtime.commits.withLock { index.remove(id) }
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
            mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size),
                indexCoverage = DiscoveryCoverageStatus.Ready) }
            if (initialInventory && topics != null) {
                mutable.update { it.copy(restoringSavedDiscoveries = true) }
                try { topics.invalidate(sources.mapValues { semanticRevision(it.value.document) }) }
                finally { mutable.update { it.copy(restoringSavedDiscoveries = false) } }
            }
            var remainingPassages = jobs.sumOf { it.second }
            var remainingNotes = jobs.size
            val workKey = semanticHash(provider.identity.toString() + sources.mapValues { semanticRevision(it.value.document) }.toSortedMap() + epoch)
            timeEstimator.begin(workKey, remainingPassages)
            fun publishWork() { mutable.update { it.copy(embeddingWork = DiscoveryEmbeddingWork(
                remainingNotes, remainingPassages, timeEstimator.remaining(remainingPassages))) } }
            if (jobs.isNotEmpty()) publishWork()
            mutable.update { it.copy(indexing = jobs.isNotEmpty()) }
            for ((id, _) in jobs) {
                currentCoroutineContext().ensureActive()
                if (runtime.generation.get() != epoch || cache.revision.value != sourceGeneration || index.isPaused() || !mayContinueWork()) return
                val source = sources.getValue(id)
                val revision = semanticRevision(source.document)
                val meta = cachedMetadata.getValue(id)
                val old = runtime.bulkStep { index.read(id) }
                // Retain compatibility reuse only within this one Note's bounded lifetime.
                val passages = SemanticChunker(provider.tokenizer).chunk(source.document)
                val resumed = passageProgressStore?.read(id)?.takeIf {
                    it.identity == provider.identity && it.revision == revision
                }?.passages.orEmpty()
                val reusable = (old?.takeIf { it.identity == provider.identity }?.passages.orEmpty() + resumed)
                    .associateBy { semanticHash(it.passage.input) }
                if (old != null) runtime.commits.withLock { index.remove(id) }
                val embedded = mutableListOf<EmbeddedPassage>()
                for (passage in passages) {
                    while (runtime.foregroundQueries.get() > 0) delay(10)
                    if (runtime.generation.get() != epoch || cache.revision.value != sourceGeneration || index.isPaused() || !mayContinueWork()) return
                    val reused = reusable[semanticHash(passage.input)]?.vector
                    val vector = reused ?: runtime.bulkStep { runtime.inference.withLock {
                        // Foreground queries can hold either mutex after the outer check.
                        if (runtime.generation.get() != epoch || cache.revision.value != sourceGeneration ||
                            index.isPaused() || !mayContinueWork()) throw BulkWorkDeferred()
                        runtime.embeddingMemo.get(provider.identity, passage.input) ?: run {
                            val started = monotonicNanos()
                            val memoToken = runtime.embeddingMemo.token()
                            val value = provider.embed(passage.input)
                            if (runtime.generation.get() == epoch) {
                                runtime.embeddingMemo.put(provider.identity, passage.input, value, memoToken)
                                timeEstimator.record(monotonicNanos() - started)
                            }
                            value
                        }
                    } }
                    if (runtime.generation.get() != epoch || cache.revision.value != sourceGeneration) return
                    embedded += EmbeddedPassage(passage, normalizeEmbedding(vector, provider.identity.dimensions))
                    if (passageProgressStore != null && reused == null) {
                        val saved = runtime.commits.withLock {
                            if (runtime.generation.get() != epoch || cache.revision.value != sourceGeneration) return@withLock null
                            // Replacement is destructive: keep reusable evidence beyond this
                            // prefix as well as newly completed inference.
                            val remainingReusable = passages.drop(embedded.size).mapNotNull { remaining ->
                                reusable[semanticHash(remaining.input)]?.let { EmbeddedPassage(remaining, it.vector) }
                            }
                            passageProgressStore.write(SemanticRecord(id, revision, provider.identity, meta,
                                embedded.toList() + remainingReusable))
                        }
                        if (saved == null) return
                        if (!saved) {
                            // Capacity waiting never persists explicit user pause.
                            val failure = writeFailure(passageProgressStore)
                            mutable.update { it.copy(message = failure, indexFailure = failure) }
                            return
                        }
                    }
                    if (reused == null) { remainingPassages = (remainingPassages - 1).coerceAtLeast(0); publishWork() }
                }
                val committed = runtime.commits.withLock {
                    if (runtime.generation.get() != epoch || index.isPaused()) return@withLock null
                    val latest = cache.snapshot().firstOrNull { canonicalSemanticId(it.document.pageId) == id }
                    if (latest == null || semanticRevision(latest.document) != revision) return@withLock null
                    index.write(SemanticRecord(id, revision, provider.identity, meta, embedded))
                }
                if (committed == false) {
                    // Capacity waiting never persists explicit user pause.
                    val failure = writeFailure(index)
                    mutable.update { it.copy(message = failure, indexFailure = failure) }
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
            if (mayIndex && !index.isPaused() && modelAvailable()) withContext(workDispatcher) {
                topics?.build(sources.mapValues { semanticRevision(it.value.document) }, semanticMetadataHash(cachedMetadata), sourceGeneration)
            }
            mutable.update { it.copy(indexedCount = minOf(indexed, it.cachedPageIds.size),
                bytes = index.bytes() + (passageProgressStore?.bytes() ?: 0L) + (topics?.bytes() ?: 0L)) }
            if (mayIndex && !index.isPaused() && modelAvailable() && mayContinueWork() &&
                cache.revision.value == sourceGeneration && indexed == ids.size && topics?.needsMoreWork != true) {
                reconciliationPending = false
                mutable.update { it.copy(indexFailure = null) }
            }
            if (mayIndex && topics?.needsMoreWork == true) { delay(50); wake() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: BulkWorkDeferred) { /* Policy deferral is not an index failure. */ }
        catch (_: Exception) { mutable.update {
            if (runtime.generation.get() != epoch) it
            else if (it.cacheStatus != DiscoveryCacheStatus.Ready) {
                val message = "Cached Notes could not be checked. Try again from Discovery settings."
                it.copy(cacheStatus = DiscoveryCacheStatus.Failed(message), message = message)
            } else it.copy(message = "Discovery index unavailable. Rebuild the index to retry.", indexFailure = "Discovery index unavailable. Rebuild the index to retry.")
        } }
        finally { mutable.update { it.copy(indexing = false) } }
    }

}
