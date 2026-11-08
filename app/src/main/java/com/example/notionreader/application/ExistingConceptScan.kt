package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ExistingConceptScanResult {
    data class Page(val supported: Set<String>, val uncertain: Set<String>, val next: ExistingConceptScanCursor?,
        val scopeKey: String, val assessments: List<TopicSupportAssessment> = emptyList(),
        val termination: ConceptScanTermination = ConceptScanTermination.EXHAUSTED, val earlyStoppedWorkKey: String? = null,
        val retrieved: Int = 0, val examined: Int = 0) : ExistingConceptScanResult
    data object Incomplete : ExistingConceptScanResult
    data object Stale : ExistingConceptScanResult
    data object Deferred : ExistingConceptScanResult
    data class Failure(val result: ExistingConceptEvidenceResult) : ExistingConceptScanResult
}

internal fun existingConceptScanScope(snapshot: TopicSnapshot, record: SemanticRecord, identity: EmbeddingIdentity,
    policy: TopicRetrievalPolicy, verifier: TopicLabelIdentity, sources: Map<String, String>, queryTicket: String): String {
    val topics = snapshot.topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
    // Version 3 uses the same termination vocabulary as the runtime scan: early support or
    // exhaustion. Uncertainty lives in the assessments and never changes termination.
    val version = if (snapshot.complete || snapshot.canonicalCatalogReady) "existing-concept-scan-v3" else "known-concept-scan-v3"
    // Neither the global catalog generation nor the in-memory catalog epoch is part of the
    // durable scope. Both advance for unrelated catalog activity (labels, retrieval restores,
    // membership commits for another Note), and including them discards an in-flight scan after
    // every such change. The fields below are the actual dependency: which topics exist, their
    // queries/vectors, verifier/policy, the record content, and the per-Note unchecked state.
    // The epoch is still passed to support verification, which binds each request at runtime.
    val fields = listOf(version, passageDeltaFingerprint(record),
        identity.toString(), policy.toString(), verifier.toString(), queryTicket) +
        sources.toSortedMap().entries.flatMap { listOf(it.key, it.value) } + topics.flatMap { topic ->
            listOf(topic.id, topic.createdOrder.toString(), topic.currentGeneratedLabel?.workKey.orEmpty(),
                topic.currentCanonicalProjection?.workKey.orEmpty(),
                topic.canonicalConcept?.uncheckedRevisions?.get(canonicalSemanticId(record.pageId)).orEmpty(), topic.canonicalConcept?.queryEmbedding?.query?.key.orEmpty()) +
                topic.canonicalConcept?.queryEmbedding?.vector.orEmpty().map { it.toRawBits().toString() } +
                topic.members.sortedBy { it.pageId }.flatMap { listOf(it.pageId, it.revision, it.passage.id) }
        }
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

internal suspend fun pendingConceptCandidatePassages(query: TopicQueryEmbedding, record: SemanticRecord,
    sources: Map<String, String>, assessedPassageIds: Set<String>, limit: Int = 8,
    maxCandidates: Int = 256): Set<String>? {
    require(limit in 1..128 && maxCandidates in 1..4096)
    val id = canonicalSemanticId(record.pageId)
    if (sources[id] != record.revision || query.query.embedding != record.identity) return null
    val context = currentCoroutineContext()
    val out = linkedSetOf<String>()
    var cursor: TopicCandidateCursor? = null
    while (out.size + assessedPassageIds.size < maxCandidates) {
        val page = when (val candidate = topicCandidatePage(query, record, sources[id], limit, cursor,
            checkActive = { context.ensureActive() })) {
            is TopicCandidatePage.Ready -> candidate
            TopicCandidatePage.Stale, TopicCandidatePage.Invalid -> return null
        }
        page.candidates.forEach { candidate ->
            if (candidate.passageId !in assessedPassageIds) out += candidate.passageId
        }
        cursor = page.next ?: break
    }
    return out
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
    if (!snapshot.complete && !snapshot.canonicalCatalogReady && !allowIncompleteCatalog) return ExistingConceptScanResult.Incomplete
    if (snapshot.identity != identity || record.identity != identity || sources[canonicalSemanticId(record.pageId)] != record.revision)
        return ExistingConceptScanResult.Stale
    val topics = snapshot.topics.sortedWith(compareBy<DiscoveredTopic> { it.createdOrder }.thenBy { it.id })
    if (topics.map { it.id }.toSet().size != topics.size) return ExistingConceptScanResult.Incomplete
    val scope = existingConceptScanScope(snapshot, record, identity, policy, verifier, sources, queryTicket)
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
        cursor?.passage, limit, progress, ::valid, mayWork,
        stopAfterSupport = topics[offset].canonicalConcept?.uncheckedRevisions?.get(canonicalSemanticId(record.pageId)) != record.revision, verify = verify)
    if (!valid()) return ExistingConceptScanResult.Stale
    return when (result) {
        is ExistingConceptEvidenceResult.Ready -> {
            val next = if (result.next != null) ExistingConceptScanCursor(scope, offset, result.next)
                else if (offset + 1 < topics.size) ExistingConceptScanCursor(scope, offset + 1, null) else null
            ExistingConceptScanResult.Page(result.supported, result.uncertain, next, scope, result.assessments,
                result.termination, result.earlyStoppedWorkKey, result.retrieved, result.examined)
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
    val diagnostics = currentCoroutineContext()[DiscoveryWorkDiagnostics]
    val queryTicket = retrievals.queryTicket() ?: return DurableConceptScanResult.Deferred
    fun valid() = current() && retrievals.queryTicket() == queryTicket
    val id = canonicalSemanticId(record.pageId)
    if (sources[id] != record.revision) return DurableConceptScanResult.Stale
    val saved = if (diagnostics != null) diagnostics.timed("checkpointReadNanos") { progress.readConceptScan(id, sources) }
        else progress.readConceptScan(id, sources)
    if (!valid()) return DurableConceptScanResult.Stale
    if (!mayWork()) return DurableConceptScanResult.Deferred
    // A completed early-support checkpoint with a pending unchecked tail is not a finished tail
    // pass: it must restart exhaustively. A partial tail checkpoint (next != null) resumes from
    // its cursor; a completed exhaustive checkpoint is committed without rescanning.
    val tailPending = snapshot.topics.mapNotNull { it.canonicalConcept }.any {
        it.uncheckedRevisions[id] == record.revision
    }
    val completedEarlyWithTail = saved != null && saved.next == null &&
        saved.formatVersion == CONCEPT_SCAN_FORMAT_VERSION && tailPending &&
        saved.completion == ConceptScanCompletion.SUPPORTED_EARLY
    // A finished record is intentionally untrusted until its assessments and scope are revalidated.
    if (!restart && saved != null && saved.next == null && saved.formatVersion == CONCEPT_SCAN_FORMAT_VERSION &&
        !completedEarlyWithTail) {
        diagnostics?.record("conceptScanCompletedReuse")
        return DurableConceptScanResult.AwaitingValidation(saved)
    }
    // A checkpoint from an older derived-state contract is rebuilt under the current one. Its
    // exact-input support decisions stay in their own store and are reused without inference.
    val resumableSaved = if (!restart && saved != null && saved.formatVersion == CONCEPT_SCAN_FORMAT_VERSION &&
        !completedEarlyWithTail) saved else null
    if (diagnostics != null) {
        diagnostics.record(if (restart) "conceptScanRestarts"
        else if (resumableSaved != null) "conceptScanResumes" else "conceptScanStarts")
        if (completedEarlyWithTail) diagnostics.record("conceptScanEarlyTailRestarts")
    }
    val result = scanExistingConceptPage(snapshot, record, identity, policy, verifier, epoch, sources,
        retrievals, index, progress, resumableSaved?.next, limit, ::valid, mayWork, verify = verify)
    if (!valid()) return DurableConceptScanResult.Stale
    if (!mayWork()) return DurableConceptScanResult.Deferred
    return when (result) {
        is ExistingConceptScanResult.Page -> {
            val priorAssessments = resumableSaved?.assessments.orEmpty()
            val assessments = (priorAssessments + result.assessments).distinctBy { it.workKey }
            val early = resumableSaved?.earlyConceptWorkKeys.orEmpty().toMutableSet()
            result.earlyStoppedWorkKey?.let(early::add)
            val completed = result.next == null
            // Termination is only why the scan stopped. Unresolved evidence stays in [assessments]
            // and may coexist with SUPPORTED_EARLY; it is never folded into the completion value.
            val completion = if (!completed) null
                else if (early.isNotEmpty()) ConceptScanCompletion.SUPPORTED_EARLY
                else ConceptScanCompletion.EXHAUSTED
            val retrieved = (resumableSaved?.retrievedCandidates ?: 0) + result.retrieved
            val examined = (resumableSaved?.examinedCandidates ?: 0) + result.examined
            val next = ExistingConceptScanRecord(id, record.revision, sources.toMap(), result.scopeKey, result.next,
                assessments, formatVersion = CONCEPT_SCAN_FORMAT_VERSION, completion = completion, earlyConceptWorkKeys = early.toSet(),
                retrievedCandidates = retrieved, examinedCandidates = examined)
            val written = if (diagnostics != null)
                diagnostics.timed("checkpointWriteNanos") { progress.replaceConceptScan(saved, next) { valid() && mayWork() } }
            else progress.replaceConceptScan(saved, next) { valid() && mayWork() }
            if (!valid()) return DurableConceptScanResult.Stale
            if (!mayWork()) return DurableConceptScanResult.Deferred
            when (written) {
                ProposalProgressWriteResult.Written -> {
                    if (diagnostics != null) {
                        if (next.next == null) {
                            diagnostics.record("conceptScanTerminalCommits")
                            if (next.completion == ConceptScanCompletion.SUPPORTED_EARLY)
                                diagnostics.record("conceptScanTerminalEarly")
                            else diagnostics.record("conceptScanTerminalExhausted")
                        } else diagnostics.record("conceptScanCheckpointAdvances")
                        diagnostics.record("conceptScanAssessments", next.assessments.size.toLong())
                        diagnostics.record("conceptScanPartialCommit", if (next.next != null) 1L else 0L)
                    }
                    if (next.next == null) DurableConceptScanResult.AwaitingValidation(next)
                    else DurableConceptScanResult.Pending
                }
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
