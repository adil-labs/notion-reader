package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*

internal data class PendingRetrievalCursor(val vectorKey: String, val passage: TopicCandidateCursor)
internal sealed interface PendingConceptRetrievalPage {
    data class Ready(val supported: Set<String>, val uncertain: Set<String>, val next: PendingRetrievalCursor?) : PendingConceptRetrievalPage
    data object Stale : PendingConceptRetrievalPage
    data object Invalid : PendingConceptRetrievalPage
    data object Deferred : PendingConceptRetrievalPage
    data class Failure(val result: ExistingConceptEvidenceResult) : PendingConceptRetrievalPage
}

/** Retrieve candidates for one Note; a cosine score never becomes a support decision. */
internal suspend fun retrievePendingConceptPage(concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord,
    index: SemanticIndexStore, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    cursor: PendingRetrievalCursor?, limit: Int, store: ProposalProgressStore, current: () -> Boolean, mayWork: () -> Boolean,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): PendingConceptRetrievalPage {
    val vectorKey = semanticHash((listOf("pending-query-vector-v1", query.query.key) + query.vector.map { it.toRawBits().toString() })
        .joinToString("") { "${it.length}:$it" })
    if (cursor != null && cursor.vectorKey != vectorKey) return PendingConceptRetrievalPage.Stale
    return when (val result = verifyExistingConceptPage(concept, query, record, index, verifier, epoch, sources,
        cursor?.passage, limit, store, current, mayWork, verify)) {
        is ExistingConceptEvidenceResult.Ready -> PendingConceptRetrievalPage.Ready(result.supported, result.uncertain,
            result.next?.let { PendingRetrievalCursor(vectorKey, it) })
        ExistingConceptEvidenceResult.Stale -> PendingConceptRetrievalPage.Stale
        ExistingConceptEvidenceResult.Invalid -> PendingConceptRetrievalPage.Invalid
        ExistingConceptEvidenceResult.Deferred -> PendingConceptRetrievalPage.Deferred
        else -> PendingConceptRetrievalPage.Failure(result)
    }
}
