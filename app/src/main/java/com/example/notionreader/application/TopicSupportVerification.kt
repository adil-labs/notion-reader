package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SupportConcept(val topicId: String, val workKey: String, val name: String, val definition: String)
internal sealed interface SupportVerificationResult {
    data class Completed(val assessment: TopicSupportAssessment) : SupportVerificationResult
    data object Stale : SupportVerificationResult
    data object Invalid : SupportVerificationResult
    data object Unavailable : SupportVerificationResult
    data object Cancelled : SupportVerificationResult
    data class Failed(val reason: TopicLabelRuntimeFailure) : SupportVerificationResult
}

internal suspend fun verifyRetrievedPassage(concept: SupportConcept, candidate: TopicMatchReference,
    index: SemanticIndexStore, embedding: EmbeddingIdentity, verifier: TopicLabelIdentity, epoch: Long,
    sources: Map<String, String>, current: () -> Boolean,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): SupportVerificationResult {
    currentCoroutineContext().ensureActive()
    if (!current()) return SupportVerificationResult.Stale
    if (candidate.pageId.isBlank() || candidate.pageId != canonicalSemanticId(candidate.pageId) ||
        candidate.revision.isBlank() || candidate.passageId.isBlank() || !candidate.score.isFinite() ||
        candidate.score !in -1f..1f) return SupportVerificationResult.Invalid
    if (sources[candidate.pageId] != candidate.revision) return SupportVerificationResult.Stale
    val record = index.read(candidate.pageId)
    if (!current()) return SupportVerificationResult.Stale
    if (record == null) return SupportVerificationResult.Unavailable
    if (record.identity != embedding || record.revision != candidate.revision ||
        canonicalSemanticId(record.pageId) != candidate.pageId) return SupportVerificationResult.Stale
    val passage = record.passages.map { it.passage }.singleOrNull { it.id == candidate.passageId }
        ?: return SupportVerificationResult.Invalid
    if (!passage.isBodyEvidence() || canonicalSemanticId(passage.pageId) != candidate.pageId) return SupportVerificationResult.Invalid
    val request = TopicSupportRequest(concept.topicId, concept.workKey, concept.name, concept.definition,
        TopicLabelEvidence(passage.id, candidate.pageId, candidate.revision, passage.excerpt), verifier, epoch)
    if (validateTopicSupport(request, SupportDecision.UNCERTAIN, epoch, concept.workKey, sources) !is TopicSupportValidation.Accepted)
        return SupportVerificationResult.Invalid
    currentCoroutineContext().ensureActive()
    if (!current()) return SupportVerificationResult.Stale
    val result = verify(request)
    currentCoroutineContext().ensureActive()
    if (!current()) return SupportVerificationResult.Stale
    return when (result) {
        TopicSupportRun.Cancelled -> SupportVerificationResult.Cancelled
        TopicSupportRun.Unavailable -> SupportVerificationResult.Unavailable
        is TopicSupportRun.Failed -> SupportVerificationResult.Failed(result.reason)
        is TopicSupportRun.Completed -> when (val checked = validateTopicSupport(request, result.decision, epoch, concept.workKey, sources)) {
            is TopicSupportValidation.Accepted -> SupportVerificationResult.Completed(checked.assessment)
            TopicSupportValidation.Stale -> SupportVerificationResult.Stale
            TopicSupportValidation.Invalid -> SupportVerificationResult.Invalid
        }
    }
}
