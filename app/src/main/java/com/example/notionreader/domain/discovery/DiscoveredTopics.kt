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
    val subjectVector: List<Float> = anchor.vector,
    @kotlinx.serialization.Transient val generatedLabel: GeneratedTopicLabel? = null,
    @kotlinx.serialization.Transient val topicMatches: TopicMatches? = null) {
    internal val labelEvidenceFingerprint by lazy { computeTopicLabelFingerprint(this) }
    private val checkedGeneratedLabel by lazy { generatedLabel?.let { reusableTopicLabel(this, it, it.identity) } }
    /** Cluster wording is evidence, never a provisional display name. */
    val effectiveLabel: String get() = checkedGeneratedLabel?.text ?: "Topic name pending"
    val currentGeneratedLabel: GeneratedTopicLabel? get() = checkedGeneratedLabel
    val currentTopicMatches: TopicMatches? get() = topicMatches?.takeIf { matches ->
        val concept = currentGeneratedLabel
        concept != null && matches.query.topicId == id && matches.query.conceptWorkKey == concept.workKey &&
            matches.query.text == topicConceptQuery(concept)
    }
    /** Seed memberships are proposal evidence; they are never fallback retrieved Notes. */
    val matchingMembers: List<TopicMembership> get() = currentTopicMatches?.members.orEmpty()
}
data class TopicMatches(val query: TopicRetrievalQuery, val members: List<TopicMembership>)
@Serializable
data class TopicContinuity(val id: String, val createdOrder: Long, val pageIds: Set<String> = emptySet(),
    val sourceIds: Set<String> = emptySet(), val replacementId: String? = null,
    val anchorSourceId: String? = null)
@Serializable
data class NoteConnection(val id: String, val left: TopicMembership, val right: TopicMembership, val score: Float,
    val independentNamedReference: Boolean = false, val evidenceKind: ConnectionEvidenceKind? = null)
@Serializable
data class ConnectionCatalogReference(val key: String, val count: Int, val namedReferences: Int = 0,
    val fingerprint: String = "")
data class ConnectionCatalogPageResult(val connections: List<NoteConnection>, val totalCount: Int,
    val available: Boolean = true)
@Serializable
data class TopicSnapshot(val topics: List<DiscoveredTopic> = emptyList(), val continuity: List<TopicContinuity> = emptyList(),
    val identity: EmbeddingIdentity? = null, val generation: Long = 0, val complete: Boolean = false,
    val message: String? = null, val connections: List<NoteConnection> = emptyList(),
    val acceptedRevisions: Map<String, String> = emptyMap(),
    val policyVersion: Int = 0, val connectionCursor: Int = 0, val connectionPassageCursor: Int = 0, val connectionPassComplete: Boolean = false,
    val provisionalTopics: List<DiscoveredTopic> = emptyList(), val sourceSignature: String? = null,
    val engineVersion: Int = SectionHierarchicalEngine.VERSION, val formationProgress: TopicFormationProgress? = null,
    val schemaVersion: Int = 1,
    val subjectRelationships: List<SubjectRelationship> = emptyList(),
    val connectionTargetCursor: Int = 0,
    val connectionTargetCount: Int = 0,
    val catalogReference: ConnectionCatalogReference? = null,
    val referenceTitles: Map<String, String> = emptyMap(),
    val connectionWork: ConnectionWork = ConnectionWork(),
    val formationReuse: FormationReuse? = null)
/** Input identities and directional work are persisted together; all keys are opaque hashes. */
@Serializable
data class ConnectionWork(val inputs: Map<String, String> = emptyMap(),
    val titleKeys: Map<String, String> = emptyMap(), val changedIds: Set<String> = emptySet(),
    val unfinishedSeedIds: Set<String> = emptySet())

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
enum class TopicWriteFailure { BudgetExceeded, RecordMemoryLimit, InvalidSnapshot, IoFailure, Unknown }
enum class TopicValidationReason {
    CandidateBound, DuplicateTopicId, InvalidGeneration, ContinuityBound, InvalidIdentity,
    InvalidTopic, InvalidMember, InvalidConnection, InvalidSnapshotProgress, InvalidRelationship
}
sealed interface TopicWriteResult {
    data object Written : TopicWriteResult
    data class Rejected(val failure: TopicWriteFailure, val validationReason: TopicValidationReason? = null) : TopicWriteResult
}
interface TopicStore {
    suspend fun read(): TopicSnapshot?
    suspend fun readMetadata(): TopicSnapshot? = read()
    /** Worker-only access; implementations may return a paged view with synchronous reads. */
    suspend fun connectionCatalog(snapshot: TopicSnapshot): List<NoteConnection> {
        check(snapshot.catalogReference == null) { "This store must implement referenced catalog access" }
        return snapshot.connections
    }
    suspend fun connectionPage(snapshot: TopicSnapshot, offset: Int, limit: Int): ConnectionCatalogPageResult {
        require(offset >= 0 && limit in 1..100)
        snapshot.catalogReference?.let { return ConnectionCatalogPageResult(emptyList(), it.count, false) }
        return ConnectionCatalogPageResult(snapshot.connections.drop(offset).take(limit), snapshot.connections.size)
    }
    suspend fun findConnection(snapshot: TopicSnapshot, id: String): ConnectionCatalogPageResult {
        snapshot.catalogReference?.let { return ConnectionCatalogPageResult(emptyList(), it.count, false) }
        return ConnectionCatalogPageResult(listOfNotNull(snapshot.connections.firstOrNull { it.id == id }), snapshot.connections.size)
    }
    suspend fun pinInspection(snapshot: TopicSnapshot): Boolean = true
    suspend fun reconcileCatalogs(sources: Map<String, String>, model: EmbeddingIdentity,
        titles: Map<String, String>): Boolean = true
    suspend fun refreshCatalogReference(snapshot: TopicSnapshot): TopicSnapshot = snapshot
    suspend fun readConnections(offset: Int, limit: Int): List<NoteConnection> {
        require(offset >= 0 && limit in 1..100)
        return read()?.connections.orEmpty().drop(offset).take(limit)
    }
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
data class TopicPolicy(val retention: Float = .59f, val version: Int = 14, val connection: Float = .5f,
    val formationDistance: Float = .35f, val membership: Float = .60f) {
    init { require(retention in 0f..1f && connection in 0f..1f && formationDistance in 0f..2f && membership in 0f..1f) }
}
