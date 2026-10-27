package com.example.notionreader.domain.structure

/**
 * Persistence port for authoritative structural facts.
 * Decoupled from the storage format and filesystem details.
 */
interface StructuralKnowledgeStore {
    suspend fun initSource(sourceIdentity: String, sourceEpoch: Long)
    suspend fun readPage(pageId: String): StructuralPageRecord?
    suspend fun writePage(record: StructuralPageRecord, sourceIdentity: String, sourceEpoch: Long)
    suspend fun applyObservation(observation: StructuralObservation, sourceIdentity: String, sourceEpoch: Long): Boolean
    suspend fun removePage(pageId: String, sourceIdentity: String, sourceEpoch: Long, reason: PageAccessibilityState)
    suspend fun clearSource(sourceIdentity: String, sourceEpoch: Long)
    suspend fun writeContainer(container: StructuralContainer, sourceIdentity: String, sourceEpoch: Long)
    suspend fun invalidateTargets(pages: Map<String, PageAccessibilityState>, containers: Set<String>, sourceIdentity: String, sourceEpoch: Long)
    suspend fun readIndex(): StructuralIndex
    suspend fun rebuildIndex(): StructuralIndex
    suspend fun readPendingWork(): List<PendingStructuralWork>
    suspend fun readResourcePosition(): String?
    suspend fun recordResourcePosition(sourceId: String, sourceIdentity: String, sourceEpoch: Long)
    suspend fun recordPendingWork(work: PendingStructuralWork)
    suspend fun completePendingWork(pageId: String)
    suspend fun blockPageWork(pageId: String, sourceRevision: String, sourceIdentity: String, sourceEpoch: Long)
    suspend fun blockedSourceRevision(pageId: String): String?
    suspend fun releaseMemory() {}
}

class StructuralPageLimitExceeded(val sourceRevision: String) : RuntimeException("Structural page exceeds its supported limit")

data class StructuralPageRecord(
    val page: StructuralPage,
    val blocks: List<StructuralBlock>,
    val sections: List<StructuralSection>,
    val outgoingRelationships: List<ExplicitRelationship>,
    val schemaVersion: Int = 1,
    val sourceIdentity: String = ""
)

/** One bounded source observation; its related metadata shares the page request's admission ticket. */
data class StructuralObservation(
    val record: StructuralPageRecord,
    val containers: List<StructuralContainer> = emptyList(),
    val targetPages: List<StructuralPage> = emptyList()
)

data class StructuralIndex(
    val schemaVersion: Int = 1,
    val sourceIdentity: String = "",
    val sourceEpoch: Long = 0L,
    val pages: Map<String, PageStructuralSummary> = emptyMap(),
    val parentChildIndex: Map<String, List<String>> = emptyMap(),
    val incomingEdgeMap: Map<String, List<ExplicitRelationship>> = emptyMap(),
    val blockAnchorIndex: Map<String, StructuralNavigationAnchor> = emptyMap()
)

data class PageStructuralSummary(
    val id: String,
    val title: String,
    val parent: StructuralParent,
    val databaseId: String?,
    val dataSourceId: String?,
    val sourceIdentity: String = "",
    val lastEditedAtEpochMilli: Long?,
    val accessibilityState: PageAccessibilityState,
    val sourceRevision: String,
    val syncStatus: SyncCompleteness
)

data class PendingStructuralWork(
    val pageId: String,
    val requestedAtEpochMilli: Long,
    val reason: String,
    val sourceIdentity: String = ""
)
