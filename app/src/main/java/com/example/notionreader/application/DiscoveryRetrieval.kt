package com.example.notionreader.application

import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads current evidence without owning inference, processing loops or presentation state. */
internal class DiscoveryRetrieval(
    private val cache: DocumentCache,
    private val index: SemanticIndexStore,
    private val provider: EmbeddingProvider,
    private val topicStore: TopicStore?,
    private val validity: DiscoveryValidity,
    private val workDispatcher: CoroutineDispatcher,
    private val recordTiming: (Map<String, Long>) -> Unit
) {
    suspend fun connectionWindow(snapshot: TopicSnapshot, offset: Int = 0, limit: Int = 20,
        selectedId: String? = null, expectedCacheRevision: Long? = null): ConnectionCatalogPageResult = withContext(workDispatcher) {
        val token = validity.capture()
        val revision = token.cacheRevision
        if (expectedCacheRevision != null && revision != expectedCacheRevision)
            return@withContext ConnectionCatalogPageResult(emptyList(), snapshot.catalogReference?.count ?: snapshot.connections.size, false)
        if (topicStore == null && snapshot.catalogReference != null)
            return@withContext ConnectionCatalogPageResult(emptyList(), snapshot.catalogReference.count, false)
        val page = if (selectedId != null) topicStore?.findConnection(snapshot, selectedId)
            ?: ConnectionCatalogPageResult(listOfNotNull(snapshot.connections.firstOrNull { it.id == selectedId }), snapshot.connections.size)
        else topicStore?.connectionPage(snapshot, offset, limit)
            ?: ConnectionCatalogPageResult(snapshot.connections.drop(offset).take(limit), snapshot.connections.size)
        val eligible = validity.eligibleRevisions()
        // Clearing derived data does not mutate the source cache. Reject reads that
        // captured evidence before a reset, just as semantic retrieval does.
        if (!validity.isCurrent(token))
            ConnectionCatalogPageResult(emptyList(), page.totalCount, false)
        else page.copy(connections = page.connections.filter {
            eligible[canonicalSemanticId(it.left.pageId)] == it.left.revision && eligible[canonicalSemanticId(it.right.pageId)] == it.right.revision
        })
    }
    suspend fun retrieve(vector: List<Float>, filters: BrowseQuery, limit: Int,
        exclude: String? = null, threshold: Float = -1f, olderThan: Instant? = null): List<SemanticMatch> = withContext(workDispatcher) {
        val started = System.nanoTime()
        val token = validity.capture()
        val sources = validity.eligibleRevisions()
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
        if (!validity.isCurrent(token)) return@withContext emptyList()
        recordTiming(mapOf("sourceReadNs" to sourceReadNanos, "catalogNs" to catalogNanos,
            "recordReadNs" to recordReadNanos, "validationNs" to validationNanos, "rankingNs" to rankingNanos,
            "totalNs" to System.nanoTime() - started))
        best
    }

    suspend fun seedVector(pageId: String): List<Float>? = withContext(workDispatcher) {
        val id = canonicalSemanticId(pageId)
        val source = cache.snapshot().firstOrNull { canonicalSemanticId(it.document.pageId) == id } ?: return@withContext null
        val record = index.read(id)?.takeIf { it.identity == provider.identity && it.revision == semanticRevision(source.document) } ?: return@withContext null
        if (record.passages.isEmpty()) return@withContext null
        normalizeEmbedding(List(provider.identity.dimensions) { d -> record.passages.sumOf { it.vector[d].toDouble() }.toFloat() }, provider.identity.dimensions)
    }

}
