package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** In-memory replay state; only the replay operation can advance it. */
internal class ConceptScanReplay private constructor(internal val binding: String?, internal val next: ExistingConceptScanCursor?,
    internal val seen: Set<String>, internal val supported: Set<String>, internal val uncertain: Set<String>,
    internal val earlyWorkKeys: Set<String>) {
    companion object { fun start() = ConceptScanReplay(null, null, emptySet(), emptySet(), emptySet(), emptySet()) }
    internal fun advance(binding: String, next: ExistingConceptScanCursor, seen: Set<String>, supported: Set<String>,
        uncertain: Set<String>, earlyWorkKeys: Set<String>) =
        ConceptScanReplay(binding, next, seen, supported, uncertain, earlyWorkKeys)
}
internal sealed interface ConceptScanReplayResult {
    data class Pending(val replay: ConceptScanReplay) : ConceptScanReplayResult
    data class Validated(val supported: Set<String>, val uncertain: Set<String>) : ConceptScanReplayResult
    data object Invalid : ConceptScanReplayResult
    data object Stale : ConceptScanReplayResult
    data object Incomplete : ConceptScanReplayResult
    data object Deferred : ConceptScanReplayResult
}
internal suspend fun replayConceptScanPage(saved: ExistingConceptScanRecord, replay: ConceptScanReplay,
    snapshot: TopicSnapshot, record: SemanticRecord, identity: EmbeddingIdentity, policy: TopicRetrievalPolicy,
    verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>, retrievals: TopicRetrievalStore,
    index: SemanticIndexStore, progress: ProposalProgressStore, limit: Int, current: () -> Boolean,
    mayWork: () -> Boolean): ConceptScanReplayResult {
    currentCoroutineContext().ensureActive()
    if (!current()) return ConceptScanReplayResult.Stale
    if (!mayWork()) return ConceptScanReplayResult.Deferred
    val queryTicket = retrievals.queryTicket() ?: return ConceptScanReplayResult.Deferred
    fun valid() = current() && retrievals.queryTicket() == queryTicket
    val id = canonicalSemanticId(record.pageId)
    if (saved.next != null || saved.pageId != id || saved.revision != record.revision || saved.sourceRevisions != sources ||
        sources[id] != record.revision) return ConceptScanReplayResult.Stale
    // A checkpoint without the current completion contract cannot prove how it stopped.
    if (saved.formatVersion != CONCEPT_SCAN_FORMAT_VERSION || saved.completion == null) return ConceptScanReplayResult.Stale
    if (saved.scopeKey != existingConceptScanScope(snapshot, record, identity, policy, verifier, sources, queryTicket))
        return ConceptScanReplayResult.Stale
    if (saved.earlyConceptWorkKeys.any { key ->
            saved.assessments.none { it.conceptWorkKey == key && it.decision == SupportDecision.SUPPORTED }
        }) return ConceptScanReplayResult.Invalid
    if (saved.completion == ConceptScanCompletion.EXHAUSTED && saved.earlyConceptWorkKeys.isNotEmpty())
        return ConceptScanReplayResult.Invalid
    if (saved.completion == ConceptScanCompletion.SUPPORTED_EARLY && saved.earlyConceptWorkKeys.isEmpty())
        return ConceptScanReplayResult.Invalid
    if (saved.assessments.map { it.workKey }.toSet().size != saved.assessments.size) return ConceptScanReplayResult.Invalid
    val bindingFields = listOf("concept-replay-v1", saved.scopeKey, saved.pageId, saved.revision) +
        saved.assessments.sortedBy { it.workKey }.flatMap { listOf(it.workKey, it.decision.name) }
    val binding = semanticHash(bindingFields.joinToString("") { "${it.length}:$it" })
    if (replay.binding != null && replay.binding != binding) return ConceptScanReplayResult.Stale
    if (progress.readConceptScan(id, sources) != saved) return ConceptScanReplayResult.Stale
    val indexed = index.read(id)
    if (!valid()) return ConceptScanReplayResult.Stale
    if (!mayWork()) return ConceptScanReplayResult.Deferred
    if (indexed == null || indexed.identity != record.identity || indexed.revision != record.revision ||
        passageDeltaFingerprint(indexed) != passageDeltaFingerprint(record)) return ConceptScanReplayResult.Stale
    // Replay never invokes a model. Missing exact-input support means the checkpoint cannot be validated.
    val result = scanExistingConceptPage(snapshot, record, identity, policy, verifier, epoch, sources, retrievals,
        index, progress, replay.next, limit, ::valid, mayWork) { TopicSupportRun.Unavailable }
    if (!valid()) return ConceptScanReplayResult.Stale
    if (!mayWork()) return ConceptScanReplayResult.Deferred
    if (progress.readConceptScan(id, sources) != saved) return ConceptScanReplayResult.Stale
    if (!valid()) return ConceptScanReplayResult.Stale
    if (!mayWork()) return ConceptScanReplayResult.Deferred
    return when (result) {
        is ExistingConceptScanResult.Page -> {
            if (result.scopeKey != saved.scopeKey) return ConceptScanReplayResult.Stale
            val expected = saved.assessments.associateBy { it.workKey }
            if (result.assessments.any { expected[it.workKey] != it || it.workKey in replay.seen })
                return ConceptScanReplayResult.Invalid
            val seen = replay.seen + result.assessments.map { it.workKey }
            val supported = replay.supported + result.supported
            val uncertain = replay.uncertain + result.uncertain
            val early = replay.earlyWorkKeys + listOfNotNull(result.earlyStoppedWorkKey)
            if (result.next != null) ConceptScanReplayResult.Pending(
                replay.advance(binding, result.next, seen, supported, uncertain, early))
            else if (seen != expected.keys) ConceptScanReplayResult.Invalid
            else if (early != saved.earlyConceptWorkKeys) ConceptScanReplayResult.Invalid
            else when (saved.completion) {
                // Early support and unresolved evidence may coexist. Termination only records why
                // the scan stopped; the replay validates the assessments independently.
                ConceptScanCompletion.SUPPORTED_EARLY -> if (early.isEmpty() || supported.isEmpty())
                    ConceptScanReplayResult.Invalid else ConceptScanReplayResult.Validated(supported, uncertain)
                ConceptScanCompletion.EXHAUSTED -> if (early.isNotEmpty())
                    ConceptScanReplayResult.Invalid else ConceptScanReplayResult.Validated(supported, uncertain)
            }
        }
        ExistingConceptScanResult.Stale -> ConceptScanReplayResult.Stale
        ExistingConceptScanResult.Deferred -> ConceptScanReplayResult.Deferred
        ExistingConceptScanResult.Incomplete -> ConceptScanReplayResult.Incomplete
        is ExistingConceptScanResult.Failure -> ConceptScanReplayResult.Incomplete
    }
}
