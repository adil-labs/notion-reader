package com.example.notionreader.application

internal sealed interface EvidenceFirstNoteStep {
    data object PendingScan : EvidenceFirstNoteStep
    data class PendingReplay(val replay: ConceptScanReplay) : EvidenceFirstNoteStep
    data class Proposals(val window: IncrementalProposalStepResult.Ready) : EvidenceFirstNoteStep
    data class Generated(val generation: ProposalGenerationResult.Completed, val window: IncrementalProposalStepResult.Ready) : EvidenceFirstNoteStep
    data class Assessed(val generation: ProposalGenerationResult.Completed,
        val assessment: com.example.notionreader.domain.discovery.TopicSupportAssessment,
        val evidenceIndex: Int, val window: IncrementalProposalStepResult.Ready) : EvidenceFirstNoteStep
    data class CitationCoverage(val generation: ProposalGenerationResult.Completed, val coverage: PendingConceptCoverage,
        val window: IncrementalProposalStepResult.Ready) : EvidenceFirstNoteStep
    data class Retrieved(val generation: ProposalGenerationResult.Completed, val page: PendingConceptRetrievalPage.Ready,
        val window: IncrementalProposalStepResult.Ready) : EvidenceFirstNoteStep
    data class RetrievalFailure(val result: PendingConceptRetrievalPage) : EvidenceFirstNoteStep
    data class AssessmentFailure(val result: SupportVerificationResult) : EvidenceFirstNoteStep
    data class GenerationFailure(val result: ProposalGenerationResult) : EvidenceFirstNoteStep
    data class Uncertain(val passageIds: Set<String>) : EvidenceFirstNoteStep
    data object Incomplete : EvidenceFirstNoteStep
    data object Stale : EvidenceFirstNoteStep
    data object Deferred : EvidenceFirstNoteStep
    data class ScanFailure(val result: DurableConceptScanResult) : EvidenceFirstNoteStep
    data class ProposalFailure(val result: PendingProposalStepResult) : EvidenceFirstNoteStep
}
