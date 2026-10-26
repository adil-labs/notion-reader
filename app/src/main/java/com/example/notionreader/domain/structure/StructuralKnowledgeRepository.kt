package com.example.notionreader.domain.structure

/**
 * Domain query interface for authoritative Notion structural facts.
 * Usable by future Browse, Explore, Search, and Reader surfaces.
 */
interface StructuralKnowledgeRepository {
    suspend fun getPage(pageId: String): StructuralPage?
    suspend fun getContainer(containerId: String): StructuralContainer?
    suspend fun getAncestors(pageId: String): List<StructuralPage>
    suspend fun getDirectChildren(pageId: String): List<StructuralPage>
    suspend fun getBlocks(pageId: String): List<StructuralBlock>
    suspend fun getSections(pageId: String): List<StructuralSection>
    suspend fun getOutgoingRelationships(sourceId: String): List<ExplicitRelationship>
    suspend fun getIncomingRelationships(targetId: String): List<ExplicitRelationship>
    suspend fun resolveNavigationAnchor(sectionKey: String): StructuralNavigationAnchor?
    suspend fun resolveBlockAnchor(blockId: String): StructuralNavigationAnchor?
    suspend fun getSyncStatus(pageId: String): SyncCompleteness
    suspend fun getUnresolvedRelationships(pageId: String): List<ExplicitRelationship> =
        getOutgoingRelationships(pageId).filter { it.resolutionStatus !in setOf(TargetResolutionStatus.RESOLVED, TargetResolutionStatus.EXTERNAL) }
}
