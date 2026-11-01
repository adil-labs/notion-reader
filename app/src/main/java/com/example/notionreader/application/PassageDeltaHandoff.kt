package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface PassageDeltaHandoff {
    data class Ready(val record: PassageDeltaRecord) : PassageDeltaHandoff
    data object Stale : PassageDeltaHandoff
    data class StorageFailure(val reason: ProposalProgressWriteResult) : PassageDeltaHandoff
}

internal suspend fun capturePassageDelta(currentRecord: SemanticRecord, index: SemanticIndexStore,
    store: ProposalProgressStore, previousCovered: Boolean = false, current: () -> Boolean): PassageDeltaHandoff {
    currentCoroutineContext().ensureActive()
    if (!current()) return PassageDeltaHandoff.Stale
    val pageId = canonicalSemanticId(currentRecord.pageId)
    val fingerprint = passageDeltaFingerprint(currentRecord)
    val saved = store.readDelta(pageId, currentRecord.revision, currentRecord.identity)
    if (!current()) return PassageDeltaHandoff.Stale
    if (saved != null && saved.fingerprint == fingerprint) return PassageDeltaHandoff.Ready(saved)
    val old = index.read(pageId)
    if (!current()) return PassageDeltaHandoff.Stale
    // Earlier pending work must not disappear when another edit arrives. Reconsider all
    // current passages if an earlier revision still has an unconsumed handoff.
    val pending = old?.let { store.readDelta(pageId, it.revision, it.identity) }
    if (!current()) return PassageDeltaHandoff.Stale
    val delta = if (pending != null || !previousCovered || old?.revision == currentRecord.revision) {
        val fresh = passageDelta(null, currentRecord)
        fresh.copy(removed = old?.passages?.map { it.passage.id }.orEmpty().toSet() +
            pending?.delta?.removed.orEmpty() + pending?.delta?.rebound?.keys.orEmpty())
    } else passageDelta(old, currentRecord)
    val record = PassageDeltaRecord(pageId, currentRecord.revision, currentRecord.identity, fingerprint, delta)
    val result = store.writeDelta(record, current)
    if (!current() || result == ProposalProgressWriteResult.Stale) return PassageDeltaHandoff.Stale
    return if (result == ProposalProgressWriteResult.Written) PassageDeltaHandoff.Ready(record)
        else PassageDeltaHandoff.StorageFailure(result)
}
