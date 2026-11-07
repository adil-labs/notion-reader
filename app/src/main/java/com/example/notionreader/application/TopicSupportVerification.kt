package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SupportConcept(val topicId: String, val workKey: String, val name: String, val definition: String)
internal sealed interface SupportVerificationResult {
    data class Completed(val assessment: TopicSupportAssessment, val reused: Boolean = false) : SupportVerificationResult
    data object Stale : SupportVerificationResult
    data object Invalid : SupportVerificationResult
    data object Unavailable : SupportVerificationResult
    data object Cancelled : SupportVerificationResult
    data class Failed(val reason: TopicLabelRuntimeFailure) : SupportVerificationResult
    data class StorageFailure(val reason: ProposalProgressWriteResult) : SupportVerificationResult
}

internal suspend fun verifyRetrievedPassage(concept: SupportConcept, candidate: TopicMatchReference,
    index: SemanticIndexStore, embedding: EmbeddingIdentity, verifier: TopicLabelIdentity, epoch: Long,
    sources: Map<String, String>, current: () -> Boolean, store: ProposalProgressStore? = null,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): SupportVerificationResult {
    val result = verifyRetrievedPassageImpl(concept, candidate, index, embedding, verifier, epoch,
        sources, current, store, verify)
    currentCoroutineContext()[DiscoveryWorkDiagnostics]?.let { diagnostics ->
        when (result) {
            is SupportVerificationResult.Completed -> {
                diagnostics.record(if (result.reused) "supportReused" else "supportFreshDecisions")
                when (result.assessment.decision) {
                    SupportDecision.SUPPORTED -> diagnostics.record("supportSupported")
                    SupportDecision.UNSUPPORTED -> diagnostics.record("supportUnsupported")
                    SupportDecision.UNCERTAIN -> diagnostics.record("supportUncertain")
                }
            }
            SupportVerificationResult.Stale -> diagnostics.record("staleResultDiscards")
            SupportVerificationResult.Invalid -> diagnostics.record("supportInvalidAssessments")
            else -> Unit
        }
    }
    return result
}

private suspend fun verifyRetrievedPassageImpl(concept: SupportConcept, candidate: TopicMatchReference,
    index: SemanticIndexStore, embedding: EmbeddingIdentity, verifier: TopicLabelIdentity, epoch: Long,
    sources: Map<String, String>, current: () -> Boolean, store: ProposalProgressStore?,
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
    val saved = store?.readSupport(topicSupportWorkKey(request), sources)
    currentCoroutineContext().ensureActive()
    if (!current()) return SupportVerificationResult.Stale
    if (saved != null) {
        val checked = validateTopicSupport(request, saved.decision, epoch, concept.workKey, sources)
        if (checked is TopicSupportValidation.Accepted && checked.assessment == saved)
            return SupportVerificationResult.Completed(saved, reused = true)
    }
    // Admission changes the catalog ID, not the concept or evidence that was assessed.
    // An existing canonical decision always wins, including rejection and uncertainty.
    val pendingId = "pending:${concept.workKey}"
    if (store != null && saved == null && concept.topicId != pendingId) {
        val pendingRequest = request.copy(topicId = pendingId)
        val pending = store.readSupport(topicSupportWorkKey(pendingRequest), sources)
        currentCoroutineContext().ensureActive()
        if (!current()) return SupportVerificationResult.Stale
        if (pending != null) {
            val checked = validateTopicSupport(pendingRequest, pending.decision, epoch, concept.workKey, sources)
            if (checked is TopicSupportValidation.Accepted && checked.assessment == pending) {
                val canonical = validateTopicSupport(request, pending.decision, epoch, concept.workKey, sources)
                    as TopicSupportValidation.Accepted
                val written = store.writeSupport(canonical.assessment, current)
                currentCoroutineContext().ensureActive()
                if (!current() || written == ProposalProgressWriteResult.Stale) return SupportVerificationResult.Stale
                if (written != ProposalProgressWriteResult.Written) return SupportVerificationResult.StorageFailure(written)
                return SupportVerificationResult.Completed(canonical.assessment, reused = true)
            }
        }
    }
    val result = verify(request)
    currentCoroutineContext().ensureActive()
    if (!current()) return SupportVerificationResult.Stale
    return when (result) {
        TopicSupportRun.Cancelled -> SupportVerificationResult.Cancelled
        TopicSupportRun.Unavailable -> SupportVerificationResult.Unavailable
        is TopicSupportRun.Failed -> SupportVerificationResult.Failed(result.reason)
        is TopicSupportRun.Completed -> when (val checked = validateTopicSupport(request, result.decision, epoch, concept.workKey, sources)) {
            is TopicSupportValidation.Accepted -> {
                if (store != null) {
                    val written = store.writeSupport(checked.assessment, current)
                    if (!current() || written == ProposalProgressWriteResult.Stale) return SupportVerificationResult.Stale
                    if (written != ProposalProgressWriteResult.Written) return SupportVerificationResult.StorageFailure(written)
                }
                SupportVerificationResult.Completed(checked.assessment)
            }
            TopicSupportValidation.Stale -> SupportVerificationResult.Stale
            TopicSupportValidation.Invalid -> SupportVerificationResult.Invalid
        }
    }
}
