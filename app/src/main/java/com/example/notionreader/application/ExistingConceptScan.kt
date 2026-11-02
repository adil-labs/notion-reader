package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ExistingConceptScanResult {
    data class Page(val supported: Set<String>, val uncertain: Set<String>, val next: ExistingConceptScanCursor?,
        val scopeKey: String, val assessments: List<TopicSupportAssessment> = emptyList()) : ExistingConceptScanResult
    data object Incomplete : ExistingConceptScanResult
    data object Stale : ExistingConceptScanResult
    data object Deferred : ExistingConceptScanResult
    data class Failure(val result: ExistingConceptEvidenceResult) : ExistingConceptScanResult
}

internal fun existingConceptScanScope(snapshot: TopicSnapshot, record: SemanticRecord, identity: EmbeddingIdentity,
    policy: TopicRetrievalPolicy, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>, queryTicket: String): String {
    val topics = snapshot.topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
    val version = if (snapshot.complete) "existing-concept-scan-v1" else "known-concept-scan-v1"
    val fields = listOf(version, snapshot.generation.toString(), passageDeltaFingerprint(record),
        identity.toString(), policy.toString(), verifier.toString(), epoch.toString(), queryTicket) +
        sources.toSortedMap().entries.flatMap { listOf(it.key, it.value) } + topics.flatMap { topic ->
            listOf(topic.id, topic.createdOrder.toString(), topic.currentGeneratedLabel?.workKey.orEmpty()) +
                topic.members.sortedBy { it.pageId }.flatMap { listOf(it.pageId, it.revision, it.passage.id) }
        }
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

internal suspend fun scanExistingConceptPage(snapshot: TopicSnapshot, record: SemanticRecord, identity: EmbeddingIdentity,
    policy: TopicRetrievalPolicy, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    retrievals: TopicRetrievalStore, index: SemanticIndexStore, progress: ProposalProgressStore,
    cursor: ExistingConceptScanCursor?, limit: Int, current: () -> Boolean, mayWork: () -> Boolean,
    allowIncompleteCatalog: Boolean = false, verify: suspend (TopicSupportRequest) -> TopicSupportRun): ExistingConceptScanResult {
    require(limit in 1..128)
    currentCoroutineContext().ensureActive()
    if (!current()) return ExistingConceptScanResult.Stale
    if (!mayWork()) return ExistingConceptScanResult.Deferred
    val queryTicket = retrievals.queryTicket() ?: return ExistingConceptScanResult.Deferred
    fun valid() = current() && retrievals.queryTicket() == queryTicket
    if (!snapshot.complete && !allowIncompleteCatalog) return ExistingConceptScanResult.Incomplete
    if (snapshot.identity != identity || record.identity != identity || sources[canonicalSemanticId(record.pageId)] != record.revision)
        return ExistingConceptScanResult.Stale
    val topics = snapshot.topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
    if (topics.map { it.id }.toSet().size != topics.size) return ExistingConceptScanResult.Incomplete
    val scope = existingConceptScanScope(snapshot, record, identity, policy, verifier, epoch, sources, queryTicket)
    if (cursor != null && (cursor.scopeKey != scope || cursor.topicOffset !in 0..topics.size ||
            cursor.topicOffset == topics.size && cursor.passage != null)) return ExistingConceptScanResult.Stale
    val offset = cursor?.topicOffset ?: 0
    if (offset == topics.size) return ExistingConceptScanResult.Page(emptySet(), emptySet(), null, scope)
    val entry = currentExistingConcept(topics[offset], identity, policy, sources, retrievals) { valid() && mayWork() }
    if (!valid()) return ExistingConceptScanResult.Stale
    if (!mayWork()) return ExistingConceptScanResult.Deferred
    val concept = when (entry) {
        is ExistingConceptCatalogResult.Ready -> entry
        ExistingConceptCatalogResult.Unavailable -> return ExistingConceptScanResult.Incomplete
        ExistingConceptCatalogResult.Stale -> return ExistingConceptScanResult.Stale
    }
    val result = verifyExistingConceptPage(concept.concept, concept.query, record, index, verifier, epoch, sources,
        cursor?.passage, limit, progress, ::valid, mayWork, verify)
    if (!valid()) return ExistingConceptScanResult.Stale
    return when (result) {
        is ExistingConceptEvidenceResult.Ready -> {
            val next = if (result.next != null) ExistingConceptScanCursor(scope, offset, result.next)
                else if (offset + 1 < topics.size) ExistingConceptScanCursor(scope, offset + 1, null) else null
            ExistingConceptScanResult.Page(result.supported, result.uncertain, next, scope, result.assessments)
        }
        ExistingConceptEvidenceResult.Stale -> ExistingConceptScanResult.Stale
        ExistingConceptEvidenceResult.Deferred -> ExistingConceptScanResult.Deferred
        else -> ExistingConceptScanResult.Failure(result)
    }
}

/** Persisted assessments are not safe for proposal suppression until separately revalidated. */
internal sealed interface DurableConceptScanResult {
    data object Pending : DurableConceptScanResult
    data class AwaitingValidation(val record: ExistingConceptScanRecord) : DurableConceptScanResult
    data object Incomplete : DurableConceptScanResult
    data object Stale : DurableConceptScanResult
    data object Deferred : DurableConceptScanResult
    data class StorageFailure(val reason: ProposalProgressWriteResult) : DurableConceptScanResult
    data class Failure(val reason: ExistingConceptEvidenceResult) : DurableConceptScanResult
}
internal suspend fun advanceDurableConceptScan(snapshot: TopicSnapshot, record: SemanticRecord, identity: EmbeddingIdentity,
    policy: TopicRetrievalPolicy, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    retrievals: TopicRetrievalStore, index: SemanticIndexStore, progress: ProposalProgressStore, limit: Int,
    current: () -> Boolean, mayWork: () -> Boolean, restart: Boolean = false, verify: suspend (TopicSupportRequest) -> TopicSupportRun): DurableConceptScanResult {
    currentCoroutineContext().ensureActive()
    if (!current()) return DurableConceptScanResult.Stale
    if (!mayWork()) return DurableConceptScanResult.Deferred
    val queryTicket = retrievals.queryTicket() ?: return DurableConceptScanResult.Deferred
    fun valid() = current() && retrievals.queryTicket() == queryTicket
    val id = canonicalSemanticId(record.pageId)
    if (sources[id] != record.revision) return DurableConceptScanResult.Stale
    val saved = progress.readConceptScan(id, sources)
    if (!valid()) return DurableConceptScanResult.Stale
    if (!mayWork()) return DurableConceptScanResult.Deferred
    // A finished record is intentionally untrusted until its assessments and scope are revalidated.
    if (!restart && saved != null && saved.next == null) return DurableConceptScanResult.AwaitingValidation(saved)
    val result = scanExistingConceptPage(snapshot, record, identity, policy, verifier, epoch, sources,
        retrievals, index, progress, if (restart) null else saved?.next, limit, ::valid, mayWork, verify = verify)
    if (!valid()) return DurableConceptScanResult.Stale
    if (!mayWork()) return DurableConceptScanResult.Deferred
    return when (result) {
        is ExistingConceptScanResult.Page -> {
            val assessments = ((if (restart) emptyList() else saved?.assessments.orEmpty()) + result.assessments).distinctBy { it.workKey }
            val next = ExistingConceptScanRecord(id, record.revision, sources.toMap(), result.scopeKey, result.next, assessments)
            val written = progress.replaceConceptScan(saved, next) { valid() && mayWork() }
            if (!valid()) return DurableConceptScanResult.Stale
            if (!mayWork()) return DurableConceptScanResult.Deferred
            when (written) {
                ProposalProgressWriteResult.Written -> if (next.next == null) DurableConceptScanResult.AwaitingValidation(next)
                    else DurableConceptScanResult.Pending
                ProposalProgressWriteResult.Stale -> DurableConceptScanResult.Stale
                else -> DurableConceptScanResult.StorageFailure(written)
            }
        }
        ExistingConceptScanResult.Incomplete -> DurableConceptScanResult.Incomplete
        ExistingConceptScanResult.Stale -> DurableConceptScanResult.Stale
        ExistingConceptScanResult.Deferred -> DurableConceptScanResult.Deferred
        is ExistingConceptScanResult.Failure -> DurableConceptScanResult.Failure(result.result)
    }
}
