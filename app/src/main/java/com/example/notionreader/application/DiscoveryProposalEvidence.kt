package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ProposalEvidenceResult {
    data class Ready(val request: DiscoveryProposalRequest, val incomplete: Set<ProposalIncompleteReason>) : ProposalEvidenceResult
    data object Stale : ProposalEvidenceResult
    data object Invalid : ProposalEvidenceResult
    data object Unavailable : ProposalEvidenceResult
}

internal sealed interface ProposalGenerationResult {
    data class Completed(val response: DiscoveryProposalResponse, val workKey: String,
        val incomplete: Set<ProposalIncompleteReason>) : ProposalGenerationResult
    data object Stale : ProposalGenerationResult
    data object Invalid : ProposalGenerationResult
    data object Unavailable : ProposalGenerationResult
    data object Cancelled : ProposalGenerationResult
    data class Failed(val reason: TopicLabelRuntimeFailure) : ProposalGenerationResult
}

internal suspend fun generateProposal(proposal: IncrementalProposal, index: SemanticIndexStore,
    identity: EmbeddingIdentity, generator: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    current: () -> Boolean, generate: suspend (DiscoveryProposalRequest) -> DiscoveryProposalRun): ProposalGenerationResult {
    val prepared = when (val result = prepareProposalEvidence(proposal, index, identity, generator, epoch, sources, current)) {
        is ProposalEvidenceResult.Ready -> result
        ProposalEvidenceResult.Stale -> return ProposalGenerationResult.Stale
        ProposalEvidenceResult.Invalid -> return ProposalGenerationResult.Invalid
        ProposalEvidenceResult.Unavailable -> return ProposalGenerationResult.Unavailable
    }
    currentCoroutineContext().ensureActive()
    if (!current()) return ProposalGenerationResult.Stale
    val result = generate(prepared.request)
    currentCoroutineContext().ensureActive()
    if (!current()) return ProposalGenerationResult.Stale
    return when (result) {
        DiscoveryProposalRun.Cancelled -> ProposalGenerationResult.Cancelled
        DiscoveryProposalRun.Unavailable -> ProposalGenerationResult.Unavailable
        is DiscoveryProposalRun.Failed -> ProposalGenerationResult.Failed(result.reason)
        is DiscoveryProposalRun.Completed -> when (val validated = validateDiscoveryProposal(prepared.request,
            result.response, epoch, sources)) {
            is DiscoveryProposalValidation.Accepted -> ProposalGenerationResult.Completed(validated.response,
                validated.workKey, prepared.incomplete)
            DiscoveryProposalValidation.Stale -> ProposalGenerationResult.Stale
            DiscoveryProposalValidation.Invalid -> ProposalGenerationResult.Invalid
        }
    }
}

internal suspend fun prepareProposalEvidence(proposal: IncrementalProposal, index: SemanticIndexStore,
    identity: EmbeddingIdentity, generator: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    current: () -> Boolean): ProposalEvidenceResult {
    currentCoroutineContext().ensureActive()
    if (!current()) return ProposalEvidenceResult.Stale
    val refs = proposal.evidence
    if (proposal.proposalKey.isBlank() || refs.size !in 1..16 || refs.any {
            it.pageId.isBlank() || it.pageId != canonicalSemanticId(it.pageId) || it.revision.isBlank() || it.passageId.isBlank()
        } || refs.map { it.passageId }.toSet().size != refs.size) return ProposalEvidenceResult.Invalid
    if (refs.any { sources[it.pageId] != it.revision }) return ProposalEvidenceResult.Stale
    val evidence = ArrayList<TopicLabelEvidence>(refs.size)
    for (ref in refs) {
        currentCoroutineContext().ensureActive()
        if (!current()) return ProposalEvidenceResult.Stale
        val record = index.read(ref.pageId)
        if (!current()) return ProposalEvidenceResult.Stale
        if (record == null) return ProposalEvidenceResult.Unavailable
        if (record.identity != identity || record.revision != ref.revision || canonicalSemanticId(record.pageId) != ref.pageId)
            return ProposalEvidenceResult.Stale
        val passage = record.passages.map { it.passage }.singleOrNull { it.id == ref.passageId }
            ?: return ProposalEvidenceResult.Invalid
        if (!passage.isBodyEvidence() || canonicalSemanticId(passage.pageId) != ref.pageId) return ProposalEvidenceResult.Invalid
        evidence += TopicLabelEvidence(passage.id, ref.pageId, ref.revision, passage.excerpt)
    }
    if (!current()) return ProposalEvidenceResult.Stale
    return ProposalEvidenceResult.Ready(DiscoveryProposalRequest(proposal.proposalKey, epoch, evidence, generator), proposal.incomplete)
}
