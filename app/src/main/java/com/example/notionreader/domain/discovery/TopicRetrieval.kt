package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

/** Inclusion is independently versioned from seed formation and generative naming. */
@Serializable
data class TopicRetrievalPolicy(val minimumScore: Float, val version: Int = 1) {
    init { require(minimumScore.isFinite() && minimumScore in 0f..1f && version > 0) }
}

@Serializable
data class TopicRetrievalQuery(val topicId: String, val conceptWorkKey: String, val text: String,
    val embedding: EmbeddingIdentity, val policy: TopicRetrievalPolicy) {
    init {
        require(topicId.length in 1..512 && Regex("[0-9a-f]{64}").matches(conceptWorkKey))
        require(text.isNotBlank() && text.length <= 1024 && embedding.dimensions > 0)
    }
    val key: String get() = semanticHash(listOf("topic-retrieval-v1", topicId, conceptWorkKey, text,
        embedding.toString(), policy.toString()).joinToString("") { "${it.length}:$it" })
}

@Serializable
data class TopicQueryEmbedding(val query: TopicRetrievalQuery, val vector: List<Float>) {
    init {
        require(vector.size == query.embedding.dimensions && vector.all(Float::isFinite))
        require(vector.sumOf { it.toDouble() * it } in .99..1.01)
    }
}

/** Source references only: neither passage bodies nor source vectors are duplicated here. */
@Serializable
data class TopicMatchReference(val pageId: String, val revision: String, val passageId: String, val score: Float)

@Serializable
data class TopicRetrievalProgress(val queryEmbedding: TopicQueryEmbedding,
    val scannedRevisions: Map<String, String> = emptyMap(),
    val matches: Map<String, TopicMatchReference> = emptyMap(), val complete: Boolean = false,
    val lastAttemptedId: String? = null)

/** No seed membership parameter: matching Notes are selected from current source evidence. */
fun matchTopicRecord(query: TopicQueryEmbedding, record: SemanticRecord, currentRevision: String?): TopicMatchReference? {
    if (currentRevision.isNullOrBlank() || record.revision != currentRevision || record.identity != query.query.embedding) return null
    var best: EmbeddedPassage? = null
    var bestScore = query.query.policy.minimumScore
    for (candidate in record.passages) {
        if (!candidate.passage.isBodyEvidence() || candidate.vector.size != query.vector.size ||
            !candidate.vector.all(Float::isFinite) || candidate.vector.all { it == 0f }) continue
        val score = cosineSimilarity(query.vector, candidate.vector)
        if (score < query.query.policy.minimumScore) continue
        if (best == null || score > bestScore || score == bestScore && candidate.passage.order < best.passage.order) {
            best = candidate; bestScore = score
        }
    }
    return best?.let { TopicMatchReference(canonicalSemanticId(record.pageId), record.revision, it.passage.id, bestScore) }
}

/** Completed coverage, not a UI page or seed count, establishes reusable matching results. */
fun topicRetrievalIsCurrent(progress: TopicRetrievalProgress, query: TopicRetrievalQuery,
    sources: Map<String, String>): Boolean = progress.complete && progress.queryEmbedding.query.key == query.key &&
    progress.scannedRevisions == sources && progress.matches.all { (id, match) ->
        id == canonicalSemanticId(id) && match.pageId == id && sources[id] == match.revision &&
            match.passageId.isNotBlank() && match.score.isFinite() && match.score in query.policy.minimumScore..1f
    }

enum class TopicRetrievalWriteResult { Written, Stale, Invalid, StorageRefused, IoFailure, RecordMemoryLimit }

/** Lookup only by canonical topic identity; this store cannot introduce another catalog. */
interface TopicRetrievalStore {
    suspend fun read(topicId: String): TopicRetrievalProgress?
    suspend fun write(progress: TopicRetrievalProgress, stillCurrent: () -> Boolean): TopicRetrievalWriteResult
    suspend fun remove(topicId: String)
    suspend fun prune(topicIds: Set<String>)
    suspend fun clear()
    suspend fun bytes(): Long
}
