package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface PendingProposalStepResult {
    data class Ready(val step: IncrementalProposalStepResult.Ready) : PendingProposalStepResult
    data object Missing : PendingProposalStepResult
    data object Stale : PendingProposalStepResult
    data object Deferred : PendingProposalStepResult
    data class StorageFailure(val reason: ProposalProgressWriteResult) : PendingProposalStepResult
}

internal suspend fun advancePendingProposalWindow(record: SemanticRecord, explainedPassageIds: Set<String>,
    sources: Map<String, String>, explicitPageIds: Set<String>, structureComplete: Boolean, anchorOffset: Int,
    store: ProposalProgressStore, maxRecords: Int, current: () -> Boolean, mayWork: () -> Boolean,
    read: suspend (String) -> SemanticRecord?): PendingProposalStepResult {
    currentCoroutineContext().ensureActive()
    if (!current()) return PendingProposalStepResult.Stale
    if (!mayWork()) return PendingProposalStepResult.Deferred
    val pageId = canonicalSemanticId(record.pageId)
    if (pageId.isBlank() || sources[pageId] != record.revision) return PendingProposalStepResult.Stale
    val delta = store.readDelta(pageId, record.revision, record.identity) ?: return if (current())
        PendingProposalStepResult.Missing else PendingProposalStepResult.Stale
    currentCoroutineContext().ensureActive()
    if (!current()) return PendingProposalStepResult.Stale
    if (!mayWork()) return PendingProposalStepResult.Deferred
    val ids = record.passages.map { it.passage.id }
    if (delta.fingerprint != passageDeltaFingerprint(record) || ids.size != ids.toSet().size ||
        !ids.containsAll(delta.delta.changed)) return PendingProposalStepResult.Stale
    val eligibleCount = record.passages.count { embedded ->
        val p = embedded.passage
        p.isBodyEvidence() && canonicalSemanticId(p.pageId) == pageId && embedded.vector.size == record.identity.dimensions &&
            embedded.vector.all(Float::isFinite) && embedded.vector.any { it != 0f } &&
            p.id in delta.delta.changed && p.id !in explainedPassageIds
    }
    if (anchorOffset < 0 || anchorOffset > eligibleCount) return PendingProposalStepResult.Stale
    val result = advanceIncrementalProposalWindow(record, delta.delta.changed, explainedPassageIds, sources,
        explicitPageIds, structureComplete, anchorOffset, store, maxRecords, { current() && mayWork() }, mayWork, read)
    if (!current()) return PendingProposalStepResult.Stale
    if (!mayWork()) return PendingProposalStepResult.Deferred
    return when (result) {
        is IncrementalProposalStepResult.Ready -> PendingProposalStepResult.Ready(result)
        IncrementalProposalStepResult.Stale -> PendingProposalStepResult.Stale
        IncrementalProposalStepResult.Deferred -> PendingProposalStepResult.Deferred
        is IncrementalProposalStepResult.StorageFailure -> PendingProposalStepResult.StorageFailure(result.reason)
    }
}
