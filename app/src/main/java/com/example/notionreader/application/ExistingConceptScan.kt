package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ExistingConceptScanCursor(val scopeKey: String, val topicOffset: Int, val passage: TopicCandidateCursor?)
internal sealed interface ExistingConceptScanResult {
    data class Page(val supported: Set<String>, val uncertain: Set<String>, val next: ExistingConceptScanCursor?) : ExistingConceptScanResult
    data object Incomplete : ExistingConceptScanResult
    data object Stale : ExistingConceptScanResult
    data object Deferred : ExistingConceptScanResult
    data class Failure(val result: ExistingConceptEvidenceResult) : ExistingConceptScanResult
}

internal suspend fun scanExistingConceptPage(snapshot: TopicSnapshot, record: SemanticRecord, identity: EmbeddingIdentity,
    policy: TopicRetrievalPolicy, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    retrievals: TopicRetrievalStore, index: SemanticIndexStore, progress: ProposalProgressStore,
    cursor: ExistingConceptScanCursor?, limit: Int, current: () -> Boolean, mayWork: () -> Boolean,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): ExistingConceptScanResult {
    require(limit in 1..128)
    currentCoroutineContext().ensureActive()
    if (!current()) return ExistingConceptScanResult.Stale
    if (!mayWork()) return ExistingConceptScanResult.Deferred
    if (!snapshot.complete) return ExistingConceptScanResult.Incomplete
    if (snapshot.identity != identity || record.identity != identity || sources[canonicalSemanticId(record.pageId)] != record.revision)
        return ExistingConceptScanResult.Stale
    val topics = snapshot.topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
    if (topics.map { it.id }.toSet().size != topics.size) return ExistingConceptScanResult.Incomplete
    val fields = listOf("existing-concept-scan-v1", snapshot.generation.toString(), passageDeltaFingerprint(record),
        identity.toString(), policy.toString(), verifier.toString(), epoch.toString()) +
        sources.toSortedMap().entries.flatMap { listOf(it.key, it.value) } + topics.flatMap { topic ->
            listOf(topic.id, topic.createdOrder.toString(), topic.currentGeneratedLabel?.workKey.orEmpty()) +
                topic.members.sortedBy { it.pageId }.flatMap { listOf(it.pageId, it.revision, it.passage.id) }
        }
    val scope = semanticHash(fields.joinToString("") { "${it.length}:$it" })
    if (cursor != null && (cursor.scopeKey != scope || cursor.topicOffset !in 0..topics.size ||
            cursor.topicOffset == topics.size && cursor.passage != null)) return ExistingConceptScanResult.Stale
    val offset = cursor?.topicOffset ?: 0
    if (offset == topics.size) return ExistingConceptScanResult.Page(emptySet(), emptySet(), null)
    val entry = currentExistingConcept(topics[offset], identity, policy, sources, retrievals) { current() && mayWork() }
    if (!current()) return ExistingConceptScanResult.Stale
    if (!mayWork()) return ExistingConceptScanResult.Deferred
    val concept = when (entry) {
        is ExistingConceptCatalogResult.Ready -> entry
        ExistingConceptCatalogResult.Unavailable -> return ExistingConceptScanResult.Incomplete
        ExistingConceptCatalogResult.Stale -> return ExistingConceptScanResult.Stale
    }
    val result = verifyExistingConceptPage(concept.concept, concept.query, record, index, verifier, epoch, sources,
        cursor?.passage, limit, progress, current, mayWork, verify)
    return when (result) {
        is ExistingConceptEvidenceResult.Ready -> {
            val next = if (result.next != null) ExistingConceptScanCursor(scope, offset, result.next)
                else if (offset + 1 < topics.size) ExistingConceptScanCursor(scope, offset + 1, null) else null
            ExistingConceptScanResult.Page(result.supported, result.uncertain, next)
        }
        ExistingConceptEvidenceResult.Stale -> ExistingConceptScanResult.Stale
        ExistingConceptEvidenceResult.Deferred -> ExistingConceptScanResult.Deferred
        else -> ExistingConceptScanResult.Failure(result)
    }
}
