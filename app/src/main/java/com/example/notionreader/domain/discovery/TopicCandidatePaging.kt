package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

@Serializable
data class TopicCandidateCursor(val queryKey: String, val pageId: String, val revision: String,
    val score: Float, val order: Int, val passageId: String)

sealed interface TopicCandidatePage {
    data class Ready(val candidates: List<TopicMatchReference>, val next: TopicCandidateCursor?) : TopicCandidatePage
    data object Stale : TopicCandidatePage
    data object Invalid : TopicCandidatePage
}

/** Candidates are retrieval results, never membership or proof of support. */
fun topicCandidatePage(query: TopicQueryEmbedding, record: SemanticRecord, currentRevision: String?,
    limit: Int, after: TopicCandidateCursor? = null, checkActive: () -> Unit = {}): TopicCandidatePage {
    require(limit in 1..128)
    checkActive()
    val pageId = canonicalSemanticId(record.pageId)
    if (pageId.isBlank() || currentRevision.isNullOrBlank() || currentRevision != record.revision ||
        record.identity != query.query.embedding) return TopicCandidatePage.Stale
    if (after != null && (after.queryKey != query.query.key || after.pageId != pageId ||
            after.revision != record.revision || !after.score.isFinite() || after.score !in -1f..1f ||
            after.passageId.isBlank())) return TopicCandidatePage.Stale
    data class Ranked(val ref: TopicMatchReference, val order: Int)
    val ranking = compareByDescending<Ranked> { it.ref.score }.thenBy { it.order }.thenBy { it.ref.passageId }
    val cursorRank = after?.let { Ranked(TopicMatchReference(pageId, record.revision, it.passageId, it.score), it.order) }
    var foundCursor = after == null
    val seen = mutableSetOf<String>()
    val best = ArrayList<Ranked>(limit + 1)
    for (embedded in record.passages) {
        checkActive()
        val passage = embedded.passage
        if (!passage.isBodyEvidence()) continue
        if (passage.id.isBlank() || !seen.add(passage.id) || canonicalSemanticId(passage.pageId) != pageId ||
            embedded.vector.size != query.vector.size || embedded.vector.any { !it.isFinite() } || embedded.vector.all { it == 0f })
            return TopicCandidatePage.Invalid
        val score = cosineSimilarity(query.vector, embedded.vector)
        if (score < query.query.policy.minimumScore) continue
        val candidate = Ranked(TopicMatchReference(pageId, record.revision, passage.id, score), passage.order)
        if (cursorRank != null) {
            val comparison = ranking.compare(candidate, cursorRank)
            if (comparison == 0) foundCursor = true
            if (comparison <= 0) continue
        }
        best.add(candidate); best.sortWith(ranking)
        if (best.size > limit + 1) best.removeAt(best.lastIndex)
    }
    if (!foundCursor) return TopicCandidatePage.Stale
    val shown = best.take(limit)
    val next = if (best.size > limit) shown.lastOrNull()?.let {
        TopicCandidateCursor(query.query.key, pageId, record.revision, it.ref.score, it.order, it.ref.passageId)
    } else null
    return TopicCandidatePage.Ready(shown.map { it.ref }, next)
}
