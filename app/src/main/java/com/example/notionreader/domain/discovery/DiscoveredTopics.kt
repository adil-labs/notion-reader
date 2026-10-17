package com.example.notionreader.domain.discovery

import kotlinx.serialization.Serializable

@Serializable
data class TopicMembership(val pageId: String, val metadata: SemanticMetadata, val passage: SemanticPassage,
    val score: Float, val anchor: SemanticPassage, val revision: String = "") {
    fun toMatch() = SemanticMatch(pageId, metadata, passage, score)
}
@Serializable
data class DiscoveredTopic(val id: String, val label: String, val labelSource: SemanticPassage,
    val anchor: EmbeddedPassage, val members: List<TopicMembership>, val createdOrder: Long,
    val subjectVector: List<Float> = anchor.vector)
@Serializable
data class TopicContinuity(val id: String, val createdOrder: Long, val pageIds: Set<String> = emptySet(),
    val sourceIds: Set<String> = emptySet(), val replacementId: String? = null)
@Serializable
data class NoteConnection(val id: String, val left: TopicMembership, val right: TopicMembership, val score: Float,
    val independentNamedReference: Boolean = false)
@Serializable
data class TopicSnapshot(val topics: List<DiscoveredTopic> = emptyList(), val continuity: List<TopicContinuity> = emptyList(),
    val identity: EmbeddingIdentity? = null, val generation: Long = 0, val complete: Boolean = false,
    val message: String? = null, val connections: List<NoteConnection> = emptyList(),
    val acceptedRevisions: Map<String, String> = emptyMap(),
    val policyVersion: Int = 0, val connectionCursor: Int = 0, val connectionPassageCursor: Int = 0, val connectionPassComplete: Boolean = false,
    val provisionalTopics: List<DiscoveredTopic> = emptyList(), val sourceSignature: String? = null,
    val engineVersion: Int = SectionHierarchicalEngine.VERSION, val formationProgress: TopicFormationProgress? = null,
    val schemaVersion: Int = 1)
/** Private, versioned work state. Indices refer to canonical eligible sections/passages, never a truncated input. */
@Serializable
data class TopicFormationProgress(
    val inputSignature: String,
    val totalSections: Int,
    val clusters: List<List<Int>>,
    val chain: List<Int> = emptyList(),
    val outerCursor: Int = 0,
    val neighborCursor: Int = 0,
    val bestNeighbor: Int = -1,
    val bestDistance: Float = 2f,
    val groupingComplete: Boolean = false,
    val projectionCluster: Int = 0,
    val projectionPassage: Int = 0,
    val projectionMembers: List<TopicMembership> = emptyList(),
    val comparisonsCompleted: Long = 0,
    val emptyProjectionClusters: List<Int> = emptyList(),
    val candidateGroups: List<List<Int>> = emptyList(),
    val totalOpportunities: Int = totalSections
)
enum class TopicWriteFailure { BudgetExceeded, InvalidSnapshot, IoFailure, Unknown }
enum class TopicValidationReason {
    CandidateBound, DuplicateTopicId, InvalidGeneration, ContinuityBound, InvalidIdentity,
    InvalidTopic, InvalidMember, InvalidConnection, InvalidSnapshotProgress
}
sealed interface TopicWriteResult {
    data object Written : TopicWriteResult
    data class Rejected(val failure: TopicWriteFailure, val validationReason: TopicValidationReason? = null) : TopicWriteResult
}
interface TopicStore {
    suspend fun read(): TopicSnapshot?
    suspend fun write(snapshot: TopicSnapshot): Boolean
    suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult =
        if (write(snapshot)) TopicWriteResult.Written else TopicWriteResult.Rejected(TopicWriteFailure.Unknown)
    suspend fun clear()
    suspend fun bytes(): Long
}
/** Production mapper uses the heading block as section identity. Keep it searchable,
 * but a repeated heading alone is not evidence that two Notes share body content. */
fun SemanticPassage.isBodyEvidence(): Boolean = sourceBlockId != sectionId && substantiveDiscoveryEvidence(this)
/** Separate clustering policy: retrieval thresholds are deliberately not reused. */
data class TopicPolicy(val retention: Float = .59f, val version: Int = 11, val connection: Float = .5f) {
    init { require(retention in 0f..1f && connection in 0f..1f) }
}
