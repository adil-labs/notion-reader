package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

/** Compact checkpoint for one changed Note and one bounded anchor window. */
@Serializable
data class ProposalProgressRecord(val pageId: String, val revision: String, val anchorOffset: Int,
    val embedding: EmbeddingIdentity, val checkpoint: ProposalScanCheckpoint)

/** Key covers the exact offered bodies and complete generator identity; bodies are not stored. */
@Serializable
data class ProposalAbstentionRecord(val workKey: String, val evidence: List<ProposalEvidenceRef>,
    val reason: DiscoveryAbstentionReason)

enum class ProposalProgressWriteResult { Written, Stale, Invalid, StorageRefused, IoFailure, RecordMemoryLimit }

interface ProposalProgressStore {
    suspend fun read(pageId: String, anchorOffset: Int, sources: Map<String, String>): ProposalProgressRecord?
    suspend fun write(record: ProposalProgressRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun readAbstention(workKey: String, sources: Map<String, String>): ProposalAbstentionRecord?
    suspend fun writeAbstention(record: ProposalAbstentionRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun reconcile(sources: Map<String, String>)
    suspend fun clear()
    suspend fun bytes(): Long
}
