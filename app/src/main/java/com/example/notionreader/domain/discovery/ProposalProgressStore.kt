package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

/** Compact checkpoint for one changed Note and one bounded anchor window. */
@Serializable
data class ProposalProgressRecord(val pageId: String, val revision: String, val anchorOffset: Int,
    val embedding: EmbeddingIdentity, val checkpoint: ProposalScanCheckpoint)

enum class ProposalProgressWriteResult { Written, Stale, Invalid, StorageRefused, IoFailure, RecordMemoryLimit }

interface ProposalProgressStore {
    suspend fun read(pageId: String, anchorOffset: Int, sources: Map<String, String>): ProposalProgressRecord?
    suspend fun write(record: ProposalProgressRecord, stillCurrent: () -> Boolean): ProposalProgressWriteResult
    suspend fun reconcile(sources: Map<String, String>)
    suspend fun clear()
    suspend fun bytes(): Long
}
