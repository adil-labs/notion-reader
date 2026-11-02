package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.ensureActive

internal data class PendingRetrievalCursor(val vectorKey: String, val passage: TopicCandidateCursor)
internal sealed interface PendingConceptRetrievalPage {
    data class Ready(val supported: Set<String>, val uncertain: Set<String>, val next: PendingRetrievalCursor?) : PendingConceptRetrievalPage
    data object Stale : PendingConceptRetrievalPage
    data object Invalid : PendingConceptRetrievalPage
    data object Deferred : PendingConceptRetrievalPage
    data class Failure(val result: ExistingConceptEvidenceResult) : PendingConceptRetrievalPage
}

/** Retrieve candidates for one Note; a cosine score never becomes a support decision. */
internal suspend fun retrievePendingConceptPage(concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord,
    index: SemanticIndexStore, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    cursor: PendingRetrievalCursor?, limit: Int, store: ProposalProgressStore, current: () -> Boolean, mayWork: () -> Boolean,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): PendingConceptRetrievalPage {
    val vectorKey = semanticHash((listOf("pending-query-vector-v1", query.query.key) + query.vector.map { it.toRawBits().toString() })
        .joinToString("") { "${it.length}:$it" })
    if (cursor != null && cursor.vectorKey != vectorKey) return PendingConceptRetrievalPage.Stale
    return when (val result = verifyExistingConceptPage(concept, query, record, index, verifier, epoch, sources,
        cursor?.passage, limit, store, current, mayWork, verify)) {
        is ExistingConceptEvidenceResult.Ready -> PendingConceptRetrievalPage.Ready(result.supported, result.uncertain,
            result.next?.let { PendingRetrievalCursor(vectorKey, it) })
        ExistingConceptEvidenceResult.Stale -> PendingConceptRetrievalPage.Stale
        ExistingConceptEvidenceResult.Invalid -> PendingConceptRetrievalPage.Invalid
        ExistingConceptEvidenceResult.Deferred -> PendingConceptRetrievalPage.Deferred
        else -> PendingConceptRetrievalPage.Failure(result)
    }
}

/** Ephemeral continuation; restart replays bounded pages using persisted support decisions. */
internal class PendingConceptNoteScan private constructor(internal val binding: String?, internal val next: PendingRetrievalCursor?,
    internal val supported: Set<String>, internal val uncertain: Set<String>) {
    companion object { fun start() = PendingConceptNoteScan(null, null, emptySet(), emptySet()) }
    internal fun advance(binding: String, next: PendingRetrievalCursor, supported: Set<String>, uncertain: Set<String>) =
        PendingConceptNoteScan(binding, next, supported, uncertain)
}
internal sealed interface PendingConceptNoteScanResult {
    data class Pending(val scan: PendingConceptNoteScan) : PendingConceptNoteScanResult
    data class Complete(val supported: Set<String>) : PendingConceptNoteScanResult
    data class Uncertain(val supported: Set<String>, val uncertain: Set<String>) : PendingConceptNoteScanResult
    data object Stale : PendingConceptNoteScanResult
    data class Failure(val page: PendingConceptRetrievalPage) : PendingConceptNoteScanResult
}

/** Complete means all candidates in this Note were checked, not that the corpus was scanned. */
internal suspend fun scanPendingConceptNote(concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord,
    index: SemanticIndexStore, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    scan: PendingConceptNoteScan, limit: Int, store: ProposalProgressStore, current: () -> Boolean, mayWork: () -> Boolean,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): PendingConceptNoteScanResult {
    val context = kotlinx.coroutines.currentCoroutineContext()
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    for (passage in record.passages) {
        context.ensureActive()
        for (value in passage.vector) {
            val bits = value.toRawBits()
            digest.update((bits ushr 24).toByte())
            digest.update((bits ushr 16).toByte())
            digest.update((bits ushr 8).toByte())
            digest.update(bits.toByte())
        }
    }
    val vectors = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val fields = listOf("pending-note-scan-v1", vectors, concept.topicId, concept.workKey, concept.name, concept.definition,
        query.query.key, canonicalSemanticId(record.pageId), record.revision, passageDeltaFingerprint(record), verifier.toString(), epoch.toString()) +
        query.vector.map { it.toRawBits().toString() } + sources.toSortedMap().flatMap { listOf(it.key, it.value) }
    val binding = semanticHash(fields.joinToString("") { "${it.length}:$it" })
    if (scan.binding != null && scan.binding != binding) return PendingConceptNoteScanResult.Stale
    return when (val page = retrievePendingConceptPage(concept, query, record, index, verifier, epoch, sources,
        scan.next, limit, store, current, mayWork, verify)) {
        is PendingConceptRetrievalPage.Ready -> {
            val supported = scan.supported + page.supported
            val uncertain = scan.uncertain + page.uncertain
            when {
                page.next != null -> PendingConceptNoteScanResult.Pending(scan.advance(binding, page.next, supported, uncertain))
                uncertain.isNotEmpty() -> PendingConceptNoteScanResult.Uncertain(supported, uncertain)
                else -> PendingConceptNoteScanResult.Complete(supported)
            }
        }
        PendingConceptRetrievalPage.Stale -> PendingConceptNoteScanResult.Stale
        else -> PendingConceptNoteScanResult.Failure(page)
    }
}

internal sealed interface PendingNoteReplayResult {
    data class Pending(val scan: PendingConceptNoteScan) : PendingNoteReplayResult
    data class Validated(val scan: PendingConceptNoteScan) : PendingNoteReplayResult
    data object Invalid : PendingNoteReplayResult
    data object Stale : PendingNoteReplayResult
    data object Deferred : PendingNoteReplayResult
    data object Incomplete : PendingNoteReplayResult
}

/** Rebuild one candidate at a time from cached, independently validated support decisions. */
internal suspend fun replayPendingNoteScan(saved: PendingNoteScanRecord, replay: PendingConceptNoteScan,
    concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord, index: SemanticIndexStore,
    verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>, store: ProposalProgressStore,
    current: () -> Boolean, mayWork: () -> Boolean): PendingNoteReplayResult {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (!current()) return PendingNoteReplayResult.Stale
    if (!mayWork()) return PendingNoteReplayResult.Deferred
    val id = canonicalSemanticId(record.pageId)
    if (saved.workKey != concept.workKey || saved.pageId != id || saved.revision != record.revision ||
        saved.sourceRevisions != sources || sources[id] != record.revision) return PendingNoteReplayResult.Stale
    if (saved.cursor == null) return PendingNoteReplayResult.Incomplete
    if (store.readPendingNoteScan(saved.workKey, id, sources) != saved || !current()) return PendingNoteReplayResult.Stale
    if (!mayWork()) return PendingNoteReplayResult.Deferred
    val result = scanPendingConceptNote(concept, query, record, index, verifier, epoch, sources, replay, 1,
        store, current, mayWork) { TopicSupportRun.Unavailable }
    if (!current()) return PendingNoteReplayResult.Stale
    if (!mayWork()) return PendingNoteReplayResult.Deferred
    if (store.readPendingNoteScan(saved.workKey, id, sources) != saved || !current()) return PendingNoteReplayResult.Stale
    if (!mayWork()) return PendingNoteReplayResult.Deferred
    return when (result) {
        is PendingConceptNoteScanResult.Pending -> {
            val scan = result.scan
            if (scan.binding != saved.binding || !saved.supported.containsAll(scan.supported) ||
                !saved.uncertain.containsAll(scan.uncertain)) PendingNoteReplayResult.Invalid
            else if (scan.next?.passage == saved.cursor) {
                if (scan.supported == saved.supported && scan.uncertain == saved.uncertain) PendingNoteReplayResult.Validated(scan)
                else PendingNoteReplayResult.Invalid
            } else PendingNoteReplayResult.Pending(scan)
        }
        PendingConceptNoteScanResult.Stale -> PendingNoteReplayResult.Stale
        is PendingConceptNoteScanResult.Failure -> PendingNoteReplayResult.Incomplete
        else -> PendingNoteReplayResult.Invalid
    }
}

/** Save a partial scan only; replay is required before accepting it after restart. */
internal suspend fun savePendingNoteScan(concept: SupportConcept, record: SemanticRecord, sources: Map<String, String>,
    scan: PendingConceptNoteScan, expected: PendingNoteScanRecord?, store: ProposalProgressStore,
    current: () -> Boolean): ProposalProgressWriteResult {
    val binding = scan.binding ?: return ProposalProgressWriteResult.Invalid
    val cursor = scan.next?.passage ?: return ProposalProgressWriteResult.Invalid
    val id = canonicalSemanticId(record.pageId)
    if (sources[id] != record.revision) return ProposalProgressWriteResult.Stale
    val checkpoint = PendingNoteScanRecord(concept.workKey, id, record.revision, sources.toMap(), binding, cursor,
        scan.supported, scan.uncertain)
    return store.replacePendingNoteScan(expected, checkpoint, current)
}
