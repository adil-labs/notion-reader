package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface IncrementalProposalStepResult {
    data class Ready(val window: ProposalWindow, val scanPending: Boolean) : IncrementalProposalStepResult
    data object Stale : IncrementalProposalStepResult
    data object Deferred : IncrementalProposalStepResult
    data class StorageFailure(val reason: ProposalProgressWriteResult) : IncrementalProposalStepResult
}
private class ProposalStepInterrupted : RuntimeException()

/** One bounded operation for the existing process coordinator; this owns no job or catalog. */
internal suspend fun advanceIncrementalProposalWindow(changed: SemanticRecord, changedPassageIds: Set<String>,
    explainedPassageIds: Set<String>, sources: Map<String, String>, explicitPageIds: Set<String>,
    structureComplete: Boolean, anchorOffset: Int, store: ProposalProgressStore, maxRecords: Int,
    current: () -> Boolean, mayWork: () -> Boolean, read: suspend (String) -> SemanticRecord?): IncrementalProposalStepResult {
    require(maxRecords in 1..128 && anchorOffset >= 0)
    val context = currentCoroutineContext()
    context.ensureActive()
    if (!current()) return IncrementalProposalStepResult.Stale
    if (!mayWork()) return IncrementalProposalStepResult.Deferred
    val pageId = canonicalSemanticId(changed.pageId)
    if (sources[pageId] != changed.revision || sources.any { (id, revision) ->
            id.isBlank() || id != canonicalSemanticId(id) || revision.isBlank()
        }) return IncrementalProposalStepResult.Stale
    val saved = store.read(pageId, anchorOffset, sources)?.takeIf {
        it.revision == changed.revision && it.embedding == changed.identity
    }
    if (!current()) return IncrementalProposalStepResult.Stale
    fun window(previous: ProposalScanCheckpoint?, records: Sequence<SemanticRecord>): ProposalWindow? = try {
        proposeDiscoveryWindow(changed, changedPassageIds, explainedPassageIds, records, sources,
            explicitPageIds, structureComplete, scanComplete = true, anchorOffset = anchorOffset,
            checkActive = {
                context.ensureActive()
                if (!current() || !mayWork()) throw ProposalStepInterrupted()
            }, previous = previous)
    } catch (_: ProposalStepInterrupted) { null }
    fun interrupted() = if (current()) IncrementalProposalStepResult.Deferred else IncrementalProposalStepResult.Stale
    var resumed = saved
    var result = window(saved?.checkpoint, emptySequence()) ?: return interrupted()
    if (result.stale && saved != null) {
        // A changed source set or policy invalidates this checkpoint, not the current Note.
        resumed = null
        result = window(null, emptySequence()) ?: return interrupted()
    }
    if (result.stale || !current()) return IncrementalProposalStepResult.Stale
    if (result.proposals.isEmpty()) return IncrementalProposalStepResult.Ready(result, scanPending = false)
    var checkpoint = checkNotNull(result.checkpoint)
    val ordered = sources.keys.filter { it != pageId }.sorted()
    val last = resumed?.lastAttemptedId
    val rotation = if (last == null) ordered else ordered.filter { it > last } + ordered.filter { it <= last }
    var lastAttempted = last
    var attempted = 0
    for (id in rotation) {
        currentCoroutineContext().ensureActive()
        if (!current()) return IncrementalProposalStepResult.Stale
        if (!mayWork() || attempted >= maxRecords) break
        if (checkpoint.scannedRevisions[id] == sources[id]) continue
        lastAttempted = id; attempted++
        val record = read(id)
        if (!current()) return IncrementalProposalStepResult.Stale
        if (!mayWork()) return IncrementalProposalStepResult.Deferred
        if (record != null) {
            result = window(checkpoint, sequenceOf(record)) ?: return interrupted()
            if (result.stale || !current()) return IncrementalProposalStepResult.Stale
            checkpoint = checkNotNull(result.checkpoint)
        }
    }
    val savedRecord = ProposalProgressRecord(pageId, changed.revision, anchorOffset, changed.identity, checkpoint, lastAttempted)
    val written = store.write(savedRecord, current)
    if (!current() || written == ProposalProgressWriteResult.Stale) return IncrementalProposalStepResult.Stale
    if (written != ProposalProgressWriteResult.Written) return IncrementalProposalStepResult.StorageFailure(written)
    val expected = sources.filterKeys { it != pageId }
    return IncrementalProposalStepResult.Ready(result, checkpoint.scannedRevisions != expected)
}
