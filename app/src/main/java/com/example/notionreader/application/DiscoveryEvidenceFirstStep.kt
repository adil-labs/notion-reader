package com.example.notionreader.application

internal sealed interface EvidenceFirstNoteStep {
    data object PendingScan : EvidenceFirstNoteStep
    data class PendingKnownCatalog(val scan: KnownCatalogScan) : EvidenceFirstNoteStep
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
    data class CorpusReplay(val candidate: PendingConceptCandidate, val replay: PendingCorpusReplayResult,
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

/** Evidence retained for a later coordinator commit; this is not a canonical topic or a commit ticket. */
internal data class PendingConceptCandidate(
    val proposal: com.example.notionreader.domain.discovery.IncrementalProposal,
    val generation: ProposalGenerationResult.Completed,
    val citations: List<com.example.notionreader.domain.discovery.TopicSupportAssessment>,
    val sources: Map<String, String>,
    val embedding: com.example.notionreader.domain.discovery.EmbeddingIdentity,
    val verifier: com.example.notionreader.domain.discovery.TopicLabelIdentity,
    val generator: com.example.notionreader.domain.discovery.TopicLabelIdentity,
    val query: com.example.notionreader.domain.discovery.TopicQueryEmbedding,
    val catalogTicket: TopicCatalogTicket?
)

internal sealed interface CanonicalAdmissionResult {
    data class Pending(val replay: PendingCorpusReplay) : CanonicalAdmissionResult
    data class Admitted(val topicId: String) : CanonicalAdmissionResult
    data object Stale : CanonicalAdmissionResult
    data object Deferred : CanonicalAdmissionResult
    data object Incomplete : CanonicalAdmissionResult
    data class StorageFailure(val result: com.example.notionreader.domain.discovery.TopicWriteResult.Rejected) : CanonicalAdmissionResult
}

internal enum class PendingCorpusWorkPhase { CORPUS, ASSESS_CITATION, REPLAY_NOTE, SCAN_NOTE }
internal data class PendingCorpusWork(val pageId: String, val proposalIndex: Int,
    val phase: PendingCorpusWorkPhase, val corpus: PendingCorpusReplay, val target: String?,
    val note: PendingConceptNoteScan, val existingReplay: ConceptScanReplay?, val generationKey: String?,
    val explicitPageIds: Set<String>, val structureComplete: Boolean, val anchorOffset: Int, val citationIndex: Int?,
    val knownCatalogScan: KnownCatalogScan?) {
    companion object {
        fun start(pageId: String, proposalIndex: Int, explicitPageIds: Set<String> = emptySet(),
            structureComplete: Boolean = false, anchorOffset: Int = 0): PendingCorpusWork {
            require(pageId.isNotBlank() && proposalIndex >= 0 && anchorOffset >= 0)
            return PendingCorpusWork(pageId, proposalIndex, PendingCorpusWorkPhase.CORPUS,
                PendingCorpusReplay.start(), null, PendingConceptNoteScan.start(), null, null,
                explicitPageIds.toSet(), structureComplete, anchorOffset, null, null)
        }
    }
}
internal sealed interface PendingCorpusWorkResult {
    data class Pending(val work: PendingCorpusWork) : PendingCorpusWorkResult
    data class Done(val coverage: PendingCorpusReplayResult, val candidate: PendingConceptCandidate) : PendingCorpusWorkResult
    data class Incomplete(val coverage: PendingCorpusReplayResult, val candidate: PendingConceptCandidate,
        val reasons: Set<com.example.notionreader.domain.discovery.ProposalIncompleteReason>,
        val scanPending: Boolean) : PendingCorpusWorkResult
    data class Abstained(val reason: com.example.notionreader.domain.discovery.DiscoveryAbstentionReason,
        val reasons: Set<com.example.notionreader.domain.discovery.ProposalIncompleteReason>,
        val scanPending: Boolean) : PendingCorpusWorkResult
    data class Stopped(val reason: EvidenceFirstNoteStep, val work: PendingCorpusWork) : PendingCorpusWorkResult
}

/** Complete source retrieval cannot hide uncertain evidence or an incomplete proposal. */
internal fun pendingCorpusIsIncomplete(coverage: PendingCorpusReplayResult,
    reasons: Set<com.example.notionreader.domain.discovery.ProposalIncompleteReason>, scanPending: Boolean): Boolean {
    require(coverage is PendingCorpusReplayResult.Complete || coverage is PendingCorpusReplayResult.Uncertain)
    return coverage is PendingCorpusReplayResult.Uncertain || scanPending || reasons.isNotEmpty()
}
/** Process-local cursor for known concepts in an explicitly incomplete catalog. */
internal class KnownCatalogScan private constructor(internal val ticket: TopicCatalogTicket?,
    internal val verifier: com.example.notionreader.domain.discovery.TopicLabelIdentity?, internal val indexedRevision: Long,
    internal val scope: String?, internal val cursor: com.example.notionreader.domain.discovery.ExistingConceptScanCursor?,
    internal val supported: Set<String>, internal val uncertain: Set<String>) {
    companion object { fun start() = KnownCatalogScan(null, null, 0, null, null, emptySet(), emptySet()) }
    internal fun advance(ticket: TopicCatalogTicket, verifier: com.example.notionreader.domain.discovery.TopicLabelIdentity,
        indexedRevision: Long, scope: String, cursor: com.example.notionreader.domain.discovery.ExistingConceptScanCursor,
        supported: Set<String>, uncertain: Set<String>) = KnownCatalogScan(ticket, verifier, indexedRevision, scope, cursor, supported, uncertain)
}
