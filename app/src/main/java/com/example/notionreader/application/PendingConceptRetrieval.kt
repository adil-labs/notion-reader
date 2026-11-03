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
    internal val supported: Set<String>, internal val uncertain: Set<String>, internal val epoch: Long?) {
    companion object { fun start() = PendingConceptNoteScan(null, null, emptySet(), emptySet(), null) }
    internal fun advance(binding: String, next: PendingRetrievalCursor, supported: Set<String>, uncertain: Set<String>, epoch: Long) =
        PendingConceptNoteScan(binding, next, supported, uncertain, epoch)
}
internal sealed interface PendingConceptNoteScanResult {
    data class Pending(val scan: PendingConceptNoteScan) : PendingConceptNoteScanResult
    data class Complete(val supported: Set<String>, val binding: String) : PendingConceptNoteScanResult
    data class Uncertain(val supported: Set<String>, val uncertain: Set<String>, val binding: String) : PendingConceptNoteScanResult
    data object Stale : PendingConceptNoteScanResult
    data class Failure(val page: PendingConceptRetrievalPage) : PendingConceptNoteScanResult
}

internal suspend fun pendingNoteScanBinding(concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord,
    verifier: TopicLabelIdentity, scopeSources: Map<String, String>): String {
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
    val fields = listOf("pending-note-scan-v2", vectors, concept.topicId, concept.workKey, concept.name, concept.definition,
        query.query.key, canonicalSemanticId(record.pageId), record.revision, passageDeltaFingerprint(record), verifier.toString()) +
        query.vector.map { it.toRawBits().toString() } + scopeSources.toSortedMap().flatMap { listOf(it.key, it.value) }
    return semanticHash(fields.joinToString("") { "${it.length}:$it" })
}

/** Complete means all candidates in this Note were checked, not that the corpus was scanned. */
internal suspend fun scanPendingConceptNote(concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord,
    index: SemanticIndexStore, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    scan: PendingConceptNoteScan, limit: Int, store: ProposalProgressStore, current: () -> Boolean, mayWork: () -> Boolean,
    scopeSources: Map<String, String> = sources,
    verify: suspend (TopicSupportRequest) -> TopicSupportRun): PendingConceptNoteScanResult {
    val noteId = canonicalSemanticId(record.pageId)
    if (scopeSources[noteId] != record.revision || scopeSources.any { (id, revision) -> sources[id] != revision })
        return PendingConceptNoteScanResult.Stale
    val binding = pendingNoteScanBinding(concept, query, record, verifier, scopeSources)
    if (scan.binding != null && (scan.binding != binding || scan.epoch != epoch)) return PendingConceptNoteScanResult.Stale
    return when (val page = retrievePendingConceptPage(concept, query, record, index, verifier, epoch, sources,
        scan.next, limit, store, current, mayWork, verify)) {
        is PendingConceptRetrievalPage.Ready -> {
            val supported = scan.supported + page.supported
            val uncertain = scan.uncertain + page.uncertain
            when {
                page.next != null -> PendingConceptNoteScanResult.Pending(scan.advance(binding, page.next, supported, uncertain, epoch))
                uncertain.isNotEmpty() -> PendingConceptNoteScanResult.Uncertain(supported, uncertain, binding)
                else -> PendingConceptNoteScanResult.Complete(supported, binding)
            }
        }
        PendingConceptRetrievalPage.Stale -> PendingConceptNoteScanResult.Stale
        else -> PendingConceptNoteScanResult.Failure(page)
    }
}

internal sealed interface PendingNoteReplayResult {
    data class Pending(val scan: PendingConceptNoteScan) : PendingNoteReplayResult
    data class Validated(val scan: PendingConceptNoteScan) : PendingNoteReplayResult
    data class Complete(val supported: Set<String>) : PendingNoteReplayResult
    data class Uncertain(val supported: Set<String>, val uncertain: Set<String>) : PendingNoteReplayResult
    data object Invalid : PendingNoteReplayResult
    data object Incompatible : PendingNoteReplayResult
    data object Stale : PendingNoteReplayResult
    data object Deferred : PendingNoteReplayResult
    data object Incomplete : PendingNoteReplayResult
}

/** Rebuild one candidate at a time from cached, independently validated support decisions. */
internal suspend fun replayPendingNoteScan(saved: PendingNoteScanRecord, replay: PendingConceptNoteScan,
    concept: SupportConcept, query: TopicQueryEmbedding, record: SemanticRecord, index: SemanticIndexStore,
    verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>, store: ProposalProgressStore,
    current: () -> Boolean, mayWork: () -> Boolean, dependencies: Map<String, String> = sources): PendingNoteReplayResult {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (!current()) return PendingNoteReplayResult.Stale
    if (!mayWork()) return PendingNoteReplayResult.Deferred
    val id = canonicalSemanticId(record.pageId)
    if (saved.formatVersion != 2) return PendingNoteReplayResult.Incomplete
    if (saved.workKey != concept.workKey || saved.pageId != id || saved.revision != record.revision ||
        saved.sourceRevisions != dependencies || dependencies.any { (page, revision) -> sources[page] != revision } || sources[id] != record.revision) return PendingNoteReplayResult.Stale
    if (store.readPendingNoteScan(saved.workKey, id, sources) != saved || !current()) return PendingNoteReplayResult.Stale
    if (!mayWork()) return PendingNoteReplayResult.Deferred
    if (saved.binding != pendingNoteScanBinding(concept, query, record, verifier, dependencies))
        return if (current()) PendingNoteReplayResult.Incompatible else PendingNoteReplayResult.Stale
    val result = scanPendingConceptNote(concept, query, record, index, verifier, epoch, sources, replay, 1,
        store, current, mayWork, scopeSources = dependencies) { TopicSupportRun.Unavailable }
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
        is PendingConceptNoteScanResult.Complete -> if (saved.cursor == null && result.binding == saved.binding &&
            result.supported == saved.supported && saved.uncertain.isEmpty()) PendingNoteReplayResult.Complete(result.supported)
            else PendingNoteReplayResult.Invalid
        is PendingConceptNoteScanResult.Uncertain -> if (saved.cursor == null && result.binding == saved.binding &&
            result.supported == saved.supported && result.uncertain == saved.uncertain)
                PendingNoteReplayResult.Uncertain(result.supported, result.uncertain) else PendingNoteReplayResult.Invalid
        PendingConceptNoteScanResult.Stale -> PendingNoteReplayResult.Stale
        is PendingConceptNoteScanResult.Failure -> PendingNoteReplayResult.Incomplete
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
        scan.supported, scan.uncertain, formatVersion = 2)
    return store.replacePendingNoteScan(expected, checkpoint, current)
}

/** Persist a completed result as untrusted coverage; replay is required before reuse. */
internal suspend fun savePendingNoteResult(concept: SupportConcept, record: SemanticRecord, sources: Map<String, String>,
    result: PendingConceptNoteScanResult, expected: PendingNoteScanRecord?, store: ProposalProgressStore,
    current: () -> Boolean): ProposalProgressWriteResult {
    if (result is PendingConceptNoteScanResult.Pending)
        return savePendingNoteScan(concept, record, sources, result.scan, expected, store, current)
    val binding: String
    val supported: Set<String>
    val uncertain: Set<String>
    when (result) {
        is PendingConceptNoteScanResult.Complete -> { binding = result.binding; supported = result.supported; uncertain = emptySet() }
        is PendingConceptNoteScanResult.Uncertain -> { binding = result.binding; supported = result.supported; uncertain = result.uncertain }
        else -> return ProposalProgressWriteResult.Invalid
    }
    val id = canonicalSemanticId(record.pageId)
    if (sources[id] != record.revision) return ProposalProgressWriteResult.Stale
    return store.replacePendingNoteScan(expected, PendingNoteScanRecord(concept.workKey, id, record.revision,
        sources.toMap(), binding, null, supported, uncertain, formatVersion = 2), current)
}
/** In-memory aggregation of independently replayed Note checkpoints, tied to one live coordinator scope. */
internal class PendingCorpusReplay private constructor(internal val scope: String?, internal val anchor: Any?,
    internal val offset: Int, internal val note: PendingConceptNoteScan,
    internal val supported: Map<String, Set<String>>, internal val uncertain: Map<String, Set<String>>) {
    companion object { fun start() = PendingCorpusReplay(null, null, 0, PendingConceptNoteScan.start(), emptyMap(), emptyMap()) }
    internal fun advance(scope: String, anchor: Any?, offset: Int, note: PendingConceptNoteScan,
        supported: Map<String, Set<String>>, uncertain: Map<String, Set<String>>) =
        PendingCorpusReplay(scope, anchor, offset, note, supported, uncertain)
}
internal sealed interface PendingCorpusReplayResult {
    data class Pending(val replay: PendingCorpusReplay) : PendingCorpusReplayResult
    data class Incomplete(val pageId: String, val replay: PendingCorpusReplay) : PendingCorpusReplayResult
    data class Complete(val supported: Map<String, Set<String>>) : PendingCorpusReplayResult
    data class Uncertain(val supported: Map<String, Set<String>>, val uncertain: Map<String, Set<String>>) : PendingCorpusReplayResult
    data object Stale : PendingCorpusReplayResult
    data object Deferred : PendingCorpusReplayResult
    data object Invalid : PendingCorpusReplayResult
}

/** One candidate or one Note per call; checkpoints and support are always replayed before aggregation. */
internal suspend fun replayPendingCorpus(replay: PendingCorpusReplay, scopeTicket: String, concept: SupportConcept,
    query: TopicQueryEmbedding, verifier: TopicLabelIdentity, epoch: Long, sources: Map<String, String>,
    index: SemanticIndexStore, store: ProposalProgressStore, current: () -> Boolean, mayWork: () -> Boolean,
    anchor: Any? = null, conceptDependencies: Map<String, String> = sources): PendingCorpusReplayResult {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (!current()) return PendingCorpusReplayResult.Stale
    if (!mayWork()) return PendingCorpusReplayResult.Deferred
    val fields = listOf("pending-corpus-replay-v1", scopeTicket, concept.topicId, concept.workKey, concept.name,
        concept.definition, query.query.key, verifier.toString(), epoch.toString(), sources.size.toString(), conceptDependencies.size.toString()) +
        query.vector.map { it.toRawBits().toString() } + sources.toSortedMap().flatMap { listOf(it.key, it.value) } +
        conceptDependencies.toSortedMap().flatMap { listOf(it.key, it.value) }
    val scope = semanticHash(fields.joinToString("") { "${it.length}:$it" })
    if (replay.scope != null && (replay.scope != scope || replay.anchor !== anchor)) return PendingCorpusReplayResult.Stale
    val ids = sources.keys.sorted()
    if (ids.any { it.isBlank() || canonicalSemanticId(it) != it } || replay.offset !in 0..ids.size)
        return PendingCorpusReplayResult.Invalid
    if (conceptDependencies.any { (page, revision) -> sources[page] != revision }) return PendingCorpusReplayResult.Stale
    if (replay.offset == ids.size) return if (replay.uncertain.isEmpty()) PendingCorpusReplayResult.Complete(replay.supported)
        else PendingCorpusReplayResult.Uncertain(replay.supported, replay.uncertain)
    val id = ids[replay.offset]
    val bound = replay.advance(scope, anchor, replay.offset, replay.note, replay.supported, replay.uncertain)
    val record = index.read(id)
    if (!current()) return PendingCorpusReplayResult.Stale
    if (!mayWork()) return PendingCorpusReplayResult.Deferred
    if (record == null || record.revision != sources[id] || record.identity != query.query.embedding)
        return PendingCorpusReplayResult.Incomplete(id, bound)
    val dependencies = conceptDependencies + (id to record.revision)
    val saved = store.readPendingNoteScan(concept.workKey, id, sources)
    if (!current()) return PendingCorpusReplayResult.Stale
    if (!mayWork()) return PendingCorpusReplayResult.Deferred
    if (saved == null || saved.formatVersion != 2 || saved.cursor != null) return PendingCorpusReplayResult.Incomplete(id, bound)
    if (saved.sourceRevisions != dependencies) return PendingCorpusReplayResult.Stale
    val result = replayPendingNoteScan(saved, replay.note, concept, query, record, index, verifier, epoch, sources, store, current, mayWork, dependencies)
    if (!current()) return PendingCorpusReplayResult.Stale
    if (!mayWork()) return PendingCorpusReplayResult.Deferred
    if (index.read(id) != record || !current()) return PendingCorpusReplayResult.Stale
    if (!mayWork()) return PendingCorpusReplayResult.Deferred
    return when (result) {
        is PendingNoteReplayResult.Pending -> PendingCorpusReplayResult.Pending(bound.advance(scope, anchor, replay.offset,
            result.scan, replay.supported, replay.uncertain))
        is PendingNoteReplayResult.Complete, is PendingNoteReplayResult.Uncertain -> {
            val supported = when (result) { is PendingNoteReplayResult.Complete -> result.supported
                is PendingNoteReplayResult.Uncertain -> result.supported; else -> error("unreachable") }
            val uncertain = (result as? PendingNoteReplayResult.Uncertain)?.uncertain.orEmpty()
            val accumulated = if (supported.isEmpty()) replay.supported else replay.supported + (id to supported)
            val unknown = if (uncertain.isEmpty()) replay.uncertain else replay.uncertain + (id to uncertain)
            val next = bound.advance(scope, anchor, replay.offset + 1, PendingConceptNoteScan.start(), accumulated, unknown)
            if (next.offset < ids.size) PendingCorpusReplayResult.Pending(next)
            else if (unknown.isEmpty()) PendingCorpusReplayResult.Complete(accumulated)
            else PendingCorpusReplayResult.Uncertain(accumulated, unknown)
        }
        PendingNoteReplayResult.Stale -> PendingCorpusReplayResult.Stale
        PendingNoteReplayResult.Deferred -> PendingCorpusReplayResult.Deferred
        PendingNoteReplayResult.Incomplete, PendingNoteReplayResult.Incompatible -> PendingCorpusReplayResult.Incomplete(id, bound)
        else -> PendingCorpusReplayResult.Invalid
    }
}
