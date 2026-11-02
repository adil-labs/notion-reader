package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface PendingConceptCoverage {
    data class Complete(val assessments: List<TopicSupportAssessment>) : PendingConceptCoverage
    data class Pending(val missingEvidenceIds: Set<String>) : PendingConceptCoverage
    data object Stale : PendingConceptCoverage
    data object Invalid : PendingConceptCoverage
    data object Unavailable : PendingConceptCoverage
}

internal suspend fun readPendingConceptCoverage(proposal: IncrementalProposal, generated: ProposalGenerationResult.Completed,
    index: SemanticIndexStore, embedding: EmbeddingIdentity, generator: TopicLabelIdentity, verifier: TopicLabelIdentity,
    epoch: Long, sources: Map<String, String>, store: ProposalProgressStore, current: () -> Boolean): PendingConceptCoverage =
    run {
        val prepared = when (val result = prepareProposalEvidence(proposal, index, embedding, generator, epoch, sources, current)) {
            is ProposalEvidenceResult.Ready -> result.request
            ProposalEvidenceResult.Stale -> return PendingConceptCoverage.Stale
            ProposalEvidenceResult.Invalid -> return PendingConceptCoverage.Invalid
            ProposalEvidenceResult.Unavailable -> return PendingConceptCoverage.Unavailable
        }
        val concept = generated.response as? DiscoveryProposalResponse.Concept ?: return PendingConceptCoverage.Invalid
        val validated = validateDiscoveryProposal(prepared, concept, epoch, sources)
        if (validated !is DiscoveryProposalValidation.Accepted || validated.workKey != generated.workKey)
            return PendingConceptCoverage.Invalid
        val assessments = ArrayList<TopicSupportAssessment>(concept.evidenceIds.size)
        val missing = linkedSetOf<String>()
        for (evidenceId in concept.evidenceIds) {
            currentCoroutineContext().ensureActive()
            if (!current()) return PendingConceptCoverage.Stale
            val evidence = prepared.evidence.singleOrNull { it.passageId == evidenceId } ?: return PendingConceptCoverage.Invalid
            val request = TopicSupportRequest("pending:${generated.workKey}", generated.workKey, concept.name,
                concept.definition, evidence, verifier, epoch)
            if (validateTopicSupport(request, SupportDecision.UNCERTAIN, epoch, generated.workKey, sources) !is TopicSupportValidation.Accepted)
                return PendingConceptCoverage.Invalid
            val saved = store.readSupport(topicSupportWorkKey(request), sources)
            if (!current()) return PendingConceptCoverage.Stale
            if (saved == null) { missing += evidenceId; continue }
            val checked = validateTopicSupport(request, saved.decision, epoch, generated.workKey, sources)
            if (checked !is TopicSupportValidation.Accepted || checked.assessment != saved) return PendingConceptCoverage.Invalid
            assessments += saved
        }
        if (!current()) return PendingConceptCoverage.Stale
        if (missing.isNotEmpty()) PendingConceptCoverage.Pending(missing) else PendingConceptCoverage.Complete(assessments)
    }
