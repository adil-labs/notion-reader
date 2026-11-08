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
    deferredPassageIds: Set<String> = emptySet(),
    nowEpochMillis: Long = System.currentTimeMillis(),
    deferralTtlMillis: Long = 10 * 60 * 1000L,
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

    // Deferral is derived from the current existing-concept tail, never from wording or
    // similarity. The key is persisted with the bounded proposal checkpoint so restart,
    // automatic continuation, tail resolution and the bounded expiry all agree.
    val currentKey = if (deferredPassageIds.isEmpty()) null else {
        val fields = mutableListOf("proposal-deferred-anchors-v1", deferredPassageIds.size.toString())
        deferredPassageIds.sorted().forEach { fields += "${it.length}:$it" }
        semanticHash(fields.joinToString(""))
    }
    val direct = store.read(pageId, anchorOffset, sources)
    val atZero = if (anchorOffset == 0) direct else store.read(pageId, 0, sources)
    val state = direct ?: atZero
    val releasedKey = currentKey?.let { "released:$it" }
    val recordKey = state?.deferralKey
    val released = currentKey != null && recordKey == releasedKey
    val sameKey = currentKey != null && recordKey == currentKey
    val since = when {
        released || sameKey -> state?.deferralSinceEpochMillis ?: nowEpochMillis
        else -> nowEpochMillis
    }
    val expired = currentKey != null && sameKey && !released && nowEpochMillis - since >= deferralTtlMillis
    val effectiveDeferred = if (released || expired) emptySet() else deferredPassageIds
    val effectiveKey = when {
        released -> releasedKey
        expired -> releasedKey
        else -> currentKey
    }
    val effectiveSince = if (released || expired) since else since
    val effectiveOffset = if (effectiveKey != recordKey) 0 else anchorOffset
    val eligibleCount = record.passages.count { embedded ->
        val p = embedded.passage
        p.isBodyEvidence() && canonicalSemanticId(p.pageId) == pageId && embedded.vector.size == record.identity.dimensions &&
            embedded.vector.all(Float::isFinite) && embedded.vector.any { it != 0f } &&
            p.id in delta.delta.changed && p.id !in explainedPassageIds && p.id !in effectiveDeferred
    }
    currentCoroutineContext()[DiscoveryWorkDiagnostics]?.let { diagnostics ->
        diagnostics.record("proposalEligibleAnchors", eligibleCount.toLong())
        diagnostics.record("proposalAnchorOffset", effectiveOffset.toLong())
        diagnostics.record("proposalExplainedPassages", explainedPassageIds.size.toLong())
        diagnostics.record("proposalDeferredAnchors", effectiveDeferred.size.toLong())
        if (eligibleCount == 0 && effectiveDeferred.isNotEmpty()) diagnostics.record("proposalWindowDeferredOnly")
        if (released) diagnostics.record("proposalDeferralReleased")
        else if (expired) diagnostics.record("proposalDeferralExpired")
    }
    if (effectiveOffset < 0 || effectiveOffset > eligibleCount) return PendingProposalStepResult.Stale
    val result = advanceIncrementalProposalWindow(record, delta.delta.changed, explainedPassageIds, sources,
        explicitPageIds, structureComplete, effectiveOffset, store, maxRecords, { current() && mayWork() }, mayWork,
        deferredPassageIds = effectiveDeferred, deferralKey = effectiveKey, deferralSinceEpochMillis = effectiveSince,
        read = read)
    if (!current()) return PendingProposalStepResult.Stale
    if (!mayWork()) return PendingProposalStepResult.Deferred
    return when (result) {
        is IncrementalProposalStepResult.Ready -> PendingProposalStepResult.Ready(result)
        IncrementalProposalStepResult.Stale -> PendingProposalStepResult.Stale
        IncrementalProposalStepResult.Deferred -> PendingProposalStepResult.Deferred
        is IncrementalProposalStepResult.StorageFailure -> PendingProposalStepResult.StorageFailure(result.reason)
    }
}
