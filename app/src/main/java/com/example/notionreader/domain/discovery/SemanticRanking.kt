package com.example.notionreader.domain.discovery

import kotlin.math.sqrt

fun normalizeEmbedding(vector: List<Float>, dimensions: Int): List<Float> {
    require(vector.size == dimensions && dimensions > 0 && vector.all { it.isFinite() })
    val magnitude = sqrt(vector.sumOf { it.toDouble() * it })
    require(magnitude > 0 && magnitude.isFinite())
    return PackedNormalizedVector.fromNormalized(FloatArray(vector.size) { (vector[it] / magnitude).toFloat() })
}

fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    require(a.isNotEmpty() && a.size == b.size)
    if (a is PackedNormalizedVector && b is PackedNormalizedVector) return a.cosine(b)
    var dot = 0.0
    var aSquared = 0.0
    var bSquared = 0.0
    for (index in a.indices) {
        val x = a[index].toDouble()
        val y = b[index].toDouble()
        require(x.isFinite() && y.isFinite())
        dot += x * y
        aSquared += x * x
        bSquared += y * y
    }
    require(aSquared > 0 && bSquared > 0)
    return (dot / sqrt(aSquared * bSquared)).toFloat().coerceIn(-1f, 1f)
}

fun BrowseQuery.matchesSemanticMetadata(metadata: SemanticMetadata): Boolean = copy(text = "")
    .applyTo(listOf(metadata.entry("candidate"))).entries.isNotEmpty()

private val matchOrder = compareByDescending<SemanticMatch> { it.score }
    .thenBy { it.pageId }.thenBy { it.passage.order }

/** Streams one document at a time; retains only the bounded best Note matches. */
fun rankSemanticRecords(
    queryVector: List<Float>, identity: EmbeddingIdentity, records: Sequence<SemanticRecord>,
    filters: BrowseQuery = BrowseQuery.Default, limit: Int = 20, excludePageId: String? = null,
    threshold: Float = -1f
): List<SemanticMatch> {
    require(limit in 1..20)
    val normalized = normalizeEmbedding(queryVector, identity.dimensions)
    val best = mutableMapOf<String, SemanticMatch>()
    records.forEach { record ->
        val id = canonicalSemanticId(record.pageId)
        if (record.identity != identity || id == excludePageId?.let(::canonicalSemanticId) || !filters.matchesSemanticMetadata(record.metadata)) return@forEach
        var hit: SemanticMatch? = null
        for (embedded in record.passages) {
            if (embedded.vector.size != identity.dimensions) continue
            val score = runCatching { cosineSimilarity(normalized, embedded.vector) }.getOrNull() ?: continue
            if (score < threshold) continue
            val current = hit
            if (current == null || score > current.score ||
                (score == current.score && embedded.passage.order < current.passage.order)) {
                hit = SemanticMatch(id, record.metadata, embedded.passage, score)
            }
        }
        val candidate = hit ?: return@forEach
        val old = best[id]
        if (old == null || matchOrder.compare(candidate, old) < 0) best[id] = candidate
        if (best.size > limit) best.remove(best.values.maxWith(matchOrder).pageId)
    }
    return best.values.sortedWith(matchOrder)
}
