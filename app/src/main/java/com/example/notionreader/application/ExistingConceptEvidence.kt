package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ExistingConceptEvidenceResult {
    data class Ready(val supported: Set<String>, val uncertain: Set<String>, val next: TopicCandidateCursor?, val assessments: List<TopicSupportAssessment> = emptyList()) : ExistingConceptEvidenceResult
    data object Stale : ExistingConceptEvidenceResult
    data object Invalid : ExistingConceptEvidenceResult
    data object Deferred : ExistingConceptEvidenceResult
    data class VerificationFailure(val result: SupportVerificationResult) : ExistingConceptEvidenceResult
}

internal suspend fun verifyExistingConceptPage(concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord,
    index: SemanticIndexStore, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    cursor: TopicCandidateCursor?, limit: Int, store: ProposalProgressStore, current: () -> Boolean, mayWork: () -> Boolean,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): ExistingConceptEvidenceResult {
    require(limit in 1..128)
    val context = currentCoroutineContext()
    context.ensureActive()
    if (!current()) return ExistingConceptEvidenceResult.Stale
    if (!mayWork()) return ExistingConceptEvidenceResult.Deferred
    if (query.query.topicId != concept.topicId || query.query.conceptWorkKey != concept.workKey)
        return ExistingConceptEvidenceResult.Invalid
    val pageId = canonicalSemanticId(record.pageId)
    if (sources[pageId] != record.revision) return ExistingConceptEvidenceResult.Stale
    val actual = index.read(pageId)
    if (!current()) return ExistingConceptEvidenceResult.Stale
    if (!mayWork()) return ExistingConceptEvidenceResult.Deferred
    if (actual == null || actual.identity != record.identity || actual.revision != record.revision ||
        passageDeltaFingerprint(actual) != passageDeltaFingerprint(record)) return ExistingConceptEvidenceResult.Stale
    val candidates = when (val page = topicCandidatePage(query, record, sources[pageId], limit, cursor,
        checkActive = { context.ensureActive() })) {
        is TopicCandidatePage.Ready -> page
        TopicCandidatePage.Stale -> return ExistingConceptEvidenceResult.Stale
        TopicCandidatePage.Invalid -> return ExistingConceptEvidenceResult.Invalid
    }
    val supported = linkedSetOf<String>()
    val uncertain = linkedSetOf<String>()
    val assessments = mutableListOf<TopicSupportAssessment>()
    for (candidate in candidates.candidates) {
        context.ensureActive()
        if (!current()) return ExistingConceptEvidenceResult.Stale
        if (!mayWork()) return ExistingConceptEvidenceResult.Deferred
        val result = verifyRetrievedPassage(concept, candidate, index, record.identity, verifier, epoch, sources,
            { current() && mayWork() }, store, verify)
        if (!current()) return ExistingConceptEvidenceResult.Stale
        if (!mayWork()) return ExistingConceptEvidenceResult.Deferred
        when (result) {
            is SupportVerificationResult.Completed -> {
                if (result.assessment.decision != SupportDecision.UNSUPPORTED) assessments += result.assessment
                when (result.assessment.decision) {
                SupportDecision.SUPPORTED -> supported += candidate.passageId
                SupportDecision.UNSUPPORTED -> Unit
                SupportDecision.UNCERTAIN -> uncertain += candidate.passageId
                }
            }
            SupportVerificationResult.Stale -> return ExistingConceptEvidenceResult.Stale
            SupportVerificationResult.Invalid -> return ExistingConceptEvidenceResult.Invalid
            else -> return ExistingConceptEvidenceResult.VerificationFailure(result)
        }
    }
    val latest = index.read(pageId)
    if (!current()) return ExistingConceptEvidenceResult.Stale
    if (!mayWork()) return ExistingConceptEvidenceResult.Deferred
    if (latest == null || latest.identity != record.identity || latest.revision != record.revision ||
        passageDeltaFingerprint(latest) != passageDeltaFingerprint(record)) return ExistingConceptEvidenceResult.Stale
    return ExistingConceptEvidenceResult.Ready(supported, uncertain, candidates.next, assessments)
}
