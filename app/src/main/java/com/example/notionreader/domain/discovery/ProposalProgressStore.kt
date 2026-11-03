package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

/** Compact checkpoint for one changed Note and one bounded anchor window. */
@Serializable
data class ProposalProgressRecord(val pageId: String, val revision: String, val anchorOffset: Int,
    val embedding: EmbeddingIdentity, val checkpoint: ProposalScanCheckpoint, val lastAttemptedId: String? = null)

/** Key covers the exact offered bodies and complete generator identity; bodies are not stored. */
@Serializable
data class ProposalAbstentionRecord(val workKey: String, val evidence: List<ProposalEvidenceRef>,
    val reason: DiscoveryAbstentionReason)

@Serializable
data class ProposalConceptRecord(val workKey: String, val evidence: List<ProposalEvidenceRef>,
    val name: String, val definition: String, val listeningDescription: String, val evidenceIds: List<String>)

/** Checkpointed values are untrusted until revalidated against the current catalog and source bodies. */
@Serializable
data class ExistingConceptScanCursor(val scopeKey: String, val topicOffset: Int, val passage: TopicCandidateCursor?)
@Serializable
data class ExistingConceptScanRecord(val pageId: String, val revision: String, val sourceRevisions: Map<String, String>,
    val scopeKey: String, val next: ExistingConceptScanCursor?, val assessments: List<TopicSupportAssessment>)

/** Untrusted pending-concept progress; replay against current sources before use. */
@Serializable
data class PendingNoteScanRecord(val workKey: String, val pageId: String, val revision: String,
    val sourceRevisions: Map<String, String>, val binding: String, val cursor: TopicCandidateCursor?,
    val supported: Set<String>, val uncertain: Set<String>, val formatVersion: Int = 1)
enum class ProposalProgressWriteResult { Written, Stale, Invalid, StorageRefused, IoFailure, RecordMemoryLimit }

interface ProposalProgressStore {
    /** Conservative proof that no concept, scan or support checkpoint is present. */
    suspend fun hasOnlySourceDeltas(): Boolean = false
    suspend fun read(pageId: String, anchorOffset: Int, sources: Map<String, String>): ProposalProgressRecord?
    suspend fun write(record: ProposalProgressRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun readAbstention(workKey: String, sources: Map<String, String>): ProposalAbstentionRecord?
    suspend fun writeAbstention(record: ProposalAbstentionRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun readConcept(workKey: String, sources: Map<String, String>): ProposalConceptRecord?
    suspend fun writeConcept(record: ProposalConceptRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun readDelta(pageId: String, revision: String, embedding: EmbeddingIdentity): PassageDeltaRecord?
    suspend fun writeDelta(record: PassageDeltaRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun readConceptScan(pageId: String, sources: Map<String, String>): ExistingConceptScanRecord?
    suspend fun replaceConceptScan(expected: ExistingConceptScanRecord?, record: ExistingConceptScanRecord,
        stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun readPendingNoteScan(workKey: String, pageId: String, sources: Map<String, String>): PendingNoteScanRecord?
    suspend fun replacePendingNoteScan(expected: PendingNoteScanRecord?, record: PendingNoteScanRecord,
        stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun readSupport(workKey: String, sources: Map<String, String>): TopicSupportAssessment?
    suspend fun writeSupport(record: TopicSupportAssessment, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun reconcile(sources: Map<String, String>)
    suspend fun clear()
    suspend fun bytes(): Long
}
