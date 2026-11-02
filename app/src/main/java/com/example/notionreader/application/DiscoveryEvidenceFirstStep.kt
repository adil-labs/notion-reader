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
    data class NoteRetrievalScan(val generation: ProposalGenerationResult.Completed, val scan: PendingConceptNoteScanResult,
        val window: IncrementalProposalStepResult.Ready) : EvidenceFirstNoteStep
    data class PendingNoteReplay(val generation: ProposalGenerationResult.Completed, val replay: PendingNoteReplayResult,
        val window: IncrementalProposalStepResult.Ready) : EvidenceFirstNoteStep
    data class PendingNoteStorageFailure(val reason: com.example.notionreader.domain.discovery.ProposalProgressWriteResult) : EvidenceFirstNoteStep
    data class CorpusReplay(val generation: ProposalGenerationResult.Completed, val replay: PendingCorpusReplayResult,
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

internal enum class PendingCorpusWorkPhase { CORPUS, ASSESS_CITATION, REPLAY_NOTE, SCAN_NOTE }
internal data class PendingCorpusWork(val pageId: String, val proposalIndex: Int,
    val phase: PendingCorpusWorkPhase, val corpus: PendingCorpusReplay, val target: String?,
    val note: PendingConceptNoteScan, val existingReplay: ConceptScanReplay?, val generationKey: String?,
    val explicitPageIds: Set<String>, val structureComplete: Boolean, val anchorOffset: Int, val citationIndex: Int?) {
    companion object {
        fun start(pageId: String, proposalIndex: Int, explicitPageIds: Set<String> = emptySet(),
            structureComplete: Boolean = false, anchorOffset: Int = 0): PendingCorpusWork {
            require(pageId.isNotBlank() && proposalIndex >= 0 && anchorOffset >= 0)
            return PendingCorpusWork(pageId, proposalIndex, PendingCorpusWorkPhase.CORPUS,
                PendingCorpusReplay.start(), null, PendingConceptNoteScan.start(), null, null,
                explicitPageIds.toSet(), structureComplete, anchorOffset, null)
        }
    }
}
internal sealed interface PendingCorpusWorkResult {
    data class Pending(val work: PendingCorpusWork) : PendingCorpusWorkResult
    data class Done(val coverage: PendingCorpusReplayResult) : PendingCorpusWorkResult
    data class Abstained(val reason: com.example.notionreader.domain.discovery.DiscoveryAbstentionReason) : PendingCorpusWorkResult
    data class Stopped(val reason: EvidenceFirstNoteStep, val work: PendingCorpusWork) : PendingCorpusWorkResult
}
