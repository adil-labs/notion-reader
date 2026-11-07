package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Why a bounded scan stopped. Uncertainty is evidence state and never a termination value. */
internal enum class ConceptScanTermination { SUPPORTED_EARLY, EXHAUSTED }

internal sealed interface ExistingConceptEvidenceResult {
    data class Ready(
        val supported: Set<String>,
        val uncertain: Set<String>,
        val next: TopicCandidateCursor?,
        val assessments: List<TopicSupportAssessment> = emptyList(),
        val termination: ConceptScanTermination = ConceptScanTermination.EXHAUSTED,
        /** Set only when [termination] is [ConceptScanTermination.SUPPORTED_EARLY]. */
        val earlyStoppedWorkKey: String? = null,
        /** Threshold-eligible candidates offered in this page; never proof of support. */
        val retrieved: Int = 0,
        /** Candidates presented to the verifier or answered from a valid durable decision. */
        val examined: Int = 0
    ) : ExistingConceptEvidenceResult
    data object Stale : ExistingConceptEvidenceResult
    data object Invalid : ExistingConceptEvidenceResult
    data object Deferred : ExistingConceptEvidenceResult
    data class VerificationFailure(val result: SupportVerificationResult) : ExistingConceptEvidenceResult
}

internal suspend fun verifyExistingConceptPage(concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord,
    index: SemanticIndexStore, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    cursor: TopicCandidateCursor?, limit: Int, store: ProposalProgressStore, current: () -> Boolean, mayWork: () -> Boolean,
    stopAfterSupport: Boolean = true, verify: suspend (TopicSupportRequest) -> TopicSupportRun): ExistingConceptEvidenceResult {
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
    var termination = ConceptScanTermination.EXHAUSTED
    var earlyStoppedWorkKey: String? = null
    var examined = 0
    // Candidates arrive strongest first under the versioned query policy. A verified supported
    // decision establishes membership; the remaining candidates are deliberately left unchecked
    // rather than submitted merely to collect more proof.
    for ((candidateIndex, candidate) in candidates.candidates.withIndex()) {
        context.ensureActive()
        if (!current()) return ExistingConceptEvidenceResult.Stale
        if (!mayWork()) return ExistingConceptEvidenceResult.Deferred
        val result = verifyRetrievedPassage(concept, candidate, index, record.identity, verifier, epoch, sources,
            { current() && mayWork() }, store, verify)
        if (!current()) return ExistingConceptEvidenceResult.Stale
        if (!mayWork()) return ExistingConceptEvidenceResult.Deferred
        when (result) {
            is SupportVerificationResult.Completed -> {
                examined++
                if (result.assessment.decision != SupportDecision.UNSUPPORTED) assessments += result.assessment
                when (result.assessment.decision) {
                    SupportDecision.SUPPORTED -> {
                        supported += candidate.passageId
                        // Stop unless this verified support was the final eligible candidate.
                        if (stopAfterSupport && (candidateIndex < candidates.candidates.lastIndex || candidates.next != null)) {
                            termination = ConceptScanTermination.SUPPORTED_EARLY
                            earlyStoppedWorkKey = concept.workKey
                            break
                        }
                    }
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
    // Termination is the reason the scan stopped. Uncertainty is retained separately in
    // [uncertain]/[assessments] and may coexist with either termination value.
    val next = if (termination == ConceptScanTermination.SUPPORTED_EARLY) null else candidates.next
    currentCoroutineContext()[DiscoveryWorkDiagnostics]?.let { diagnostics ->
        diagnostics.record("supportCandidatesRetrieved", candidates.candidates.size.toLong())
        diagnostics.record("supportCandidatesChecked", examined.toLong())
        when (termination) {
            ConceptScanTermination.SUPPORTED_EARLY -> diagnostics.record("conceptScanEarlyStopped")
            ConceptScanTermination.EXHAUSTED -> diagnostics.record("conceptScanExhausted")
        }
        if (uncertain.isNotEmpty()) diagnostics.record("conceptScanUncertain")
    }
    return ExistingConceptEvidenceResult.Ready(supported, uncertain, next, assessments, termination,
        earlyStoppedWorkKey, candidates.candidates.size, examined)
}
