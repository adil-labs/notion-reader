package com.example.notionreader.application

import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

internal data class TopicView(val snapshot: TopicSnapshot = TopicSnapshot(), val updated: Boolean = false,
    val status: String? = null)

/** Owns completed generations, not a screen or a listening session. */
internal class TopicCoordinator(
    private val cache: DocumentCache,
    private val index: SemanticIndexStore,
    private val store: TopicStore,
    private val checkpoint: TopicStore?,
    private val identity: () -> EmbeddingIdentity,
    private val policy: TopicPolicy,
    private val bulkGate: Mutex,
    private val mayWork: () -> Boolean,
    private val waitForQueries: suspend () -> Unit,
    private val changed: (TopicView) -> Unit,
    private val invalidationAcknowledgementLimit: () -> Long = { Long.MAX_VALUE },
    private val engine: TopicDiscoveryEngine? = null
) {
    private val writes = Mutex()
    private var loaded = false
    @Volatile private var latest = TopicSnapshot()
    @Volatile private var view = TopicView()
    @Volatile private var epoch = 0L
    private var builtToken: String? = null
    private var connectionCursor = 0
    private var experimentalPending: TopicSnapshot? = null
    var needsMoreWork: Boolean = false
        private set
    private var roundsThisForeground = 0
    private var wasEligible = false
    fun resetWorkBudget() { roundsThisForeground = 0 }
    fun eligibility(eligible: Boolean) {
        if (eligible && !wasEligible) resetWorkBudget()
        wasEligible = eligible
    }

    fun refresh() { view = TopicView(latest, status = latest.message); changed(view) }

    private suspend fun <T> bulk(action: suspend () -> T): T {
        waitForQueries()
        return bulkGate.withLock { action() }
    }
    private suspend fun readRecord(id: String) = bulk { index.read(id) }

    suspend fun invalidate(sources: Map<String, String>) = writes.withLock {
        if (!loaded) {
            latest = store.read() ?: TopicSnapshot()
            view = TopicView(latest, status = latest.message)
            loaded = true
        }
        val events = cache.invalidations.value
        val evidence = (latest.topics + latest.provisionalTopics + view.snapshot.topics + view.snapshot.provisionalTopics).flatMap { it.members } +
            (latest.connections + view.snapshot.connections).flatMap { listOf(it.left, it.right) }
        val known = evidence.map { it.pageId }.toSet()
        val stale = evidence.filter { latest.identity != identity() || sources[it.pageId] != it.revision }.map { it.pageId }.toSet() +
            (known - sources.keys)
        suspend fun remove(snapshot: TopicSnapshot, ids: Set<String>, permanent: Boolean): TopicSnapshot {
            val replacements = mutableMapOf<String, EmbeddedPassage>()
            for (topic in snapshot.topics + snapshot.provisionalTopics) {
                if (topic.anchor.passage.pageId !in ids && topic.labelSource.pageId !in ids) continue
                for (member in topic.members.filter { it.pageId !in ids }) {
                    val record = readRecord(member.pageId)?.takeIf { it.identity == identity() && sources[it.pageId] == it.revision } ?: continue
                    val replacement = record.passages.find { it.passage.id == member.passage.id } ?: continue
                    replacements[topic.id] = replacement; break
                }
            }
            return invalidateTopics(snapshot, ids, permanent, replacements)
        }
        var next = remove(latest, stale, permanent = false)
        var shown = remove(view.snapshot, stale, permanent = false)
        if ((next.connections + shown.connections).any { it.independentNamedReference }) {
            val currentTitles = linkedMapOf<String, String>()
            for (id in sources.keys.sorted()) {
                val record = readRecord(id)?.takeIf { it.identity == identity() && sources[id] == it.revision } ?: continue
                currentTitles[canonicalSemanticId(id)] = record.metadata.title
            }
            // Cache titles can be fresher than unchanged embedded content metadata.
            cache.snapshot().forEach { cached ->
                val id = canonicalSemanticId(cached.document.pageId)
                if (id in sources) currentTitles[id] = cached.document.title
            }
            fun withdrawAmbiguous(snapshot: TopicSnapshot) = snapshot.copy(connections = snapshot.connections.filter {
                !it.independentNamedReference || classifyConnectionEvidence(it, currentTitles).kind == ConnectionEvidenceKind.NamedReference
            })
            next = withdrawAmbiguous(next); shown = withdrawAmbiguous(shown)
        }
        for (event in events.sortedBy { it.generation }) {
            if (event.reason == CacheRemovalReason.RESET) {
                next = TopicSnapshot(); shown = TopicSnapshot()
            } else {
                val ids = event.pageId?.let { setOf(canonicalSemanticId(it)) } ?: known
                val permanent = event.reason == CacheRemovalReason.DELETED || event.reason == CacheRemovalReason.PERMISSION_DENIED
                next = remove(next, ids, permanent)
                shown = remove(shown, ids, permanent)
            }
        }
        if (next != latest || events.isNotEmpty()) {
            epoch++
            experimentalPending = null
            checkpoint?.clear()
            builtToken = null
            // Withdraw immediately even when a storage error prevents acknowledgement.
            latest = next
            view = view.copy(snapshot = shown)
            changed(view)
            val invalidationWrite = store.writeDetailed(next)
            if (invalidationWrite is TopicWriteResult.Rejected) {
                // A rejected write must not leave removed private passages on disk.
                store.clear()
                val continuityOnly = next.copy(topics = emptyList(), connections = emptyList(), complete = false)
                store.writeDetailed(continuityOnly)
                changed(view.copy(status = persistenceFailureMessage(invalidationWrite)))
                return@withLock
            }
            events.maxOfOrNull { it.generation }?.let {
                cache.acknowledgeInvalidations(minOf(it, invalidationAcknowledgementLimit()))
            }
        }
        view = view.copy(snapshot = shown)
        changed(view)
    }

    suspend fun build(sources: Map<String, String>, metadataVersion: Int, cacheRevision: Long) {
        if (cache.revision.value != cacheRevision || !mayWork()) return
        if (engine is SectionHierarchicalEngine) {
            buildExperimental(sources, metadataVersion, cacheRevision, engine)
            return
        }
        val token = semanticHash(identity().toString() + policy.toString() + metadataVersion + sources.toSortedMap().toString())
        if (builtToken == null && latest.sourceSignature == token) builtToken = token
        // Sweep bounded candidate opportunities, then stop unchanged work. A full
        // set of supported anchors cannot admit additional candidates.
        val canAdvanceCandidates = latest.groupingPass < latest.candidatePassLimit && latest.topics.size < policy.maxAnchors
        val retryGrouping = builtToken == token && canAdvanceCandidates &&
            (!latest.complete || latest.provisionalTopics.isNotEmpty())
        if (builtToken == token && latest.connectionPassComplete && !retryGrouping) { needsMoreWork = false; return }
        if (roundsThisForeground >= 16) {
            needsMoreWork = false
            changed(view.copy(status = "Topic coverage is incomplete. Processing resumes on the next eligible foreground or Resume indexing."))
            return
        }
        roundsThisForeground++
        val startEpoch = epoch
        val model = identity()
        val saved = checkpoint?.read()?.takeIf { it.identity == model && it.policyVersion == policy.version && !it.complete }?.let { saved ->
            invalidateTopics(saved, saved.topics.flatMap { it.members }.filter { sources[it.pageId] != it.revision }.map { it.pageId }.toSet(), false)
        }
        val builder = TopicBuilder(saved ?: latest.copy(complete = true, acceptedRevisions = emptyMap(), pendingRevisions = emptyMap()), model, policy)
        var processed = 0
        var candidatePassLimit = maxOf(saved?.candidatePassLimit ?: 2, if (builtToken == token) latest.candidatePassLimit else 2)
        val candidateRound = if (builtToken == token) latest.groupingPass else 0
        changed(view.copy(status = "Finding topics in indexed cached Notes…"))
        val allRecords = index.pageIds().sorted()
        val cursor = latest.candidateCursor.coerceAtLeast(0) % maxOf(1, allRecords.size)
        val records = allRecords.drop(cursor) + allRecords.take(cursor)
        val continueConnections = builtToken == token && !(retryGrouping && latest.connectionPassComplete)
        val grouped = if (continueConnections) {
            latest
        } else if (engine != null) {
            val recordList = mutableListOf<SemanticRecord>()
            for (id in records) {
                currentCoroutineContext().ensureActive()
                waitForQueries()
                if (startEpoch != epoch || index.isPaused() || !mayWork()) return
                val record = readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision } ?: continue
                recordList.add(record)
            }
            bulk { engine.discover(recordList, saved ?: latest, policy) }
        } else {
            for (id in records) {
                currentCoroutineContext().ensureActive()
                waitForQueries()
                if (startEpoch != epoch || index.isPaused() || !mayWork()) return
                val record = readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision } ?: continue
                if (saved?.acceptedRevisions?.get(id) == record.revision) continue
                val body = record.passages.filter { it.passage.isBodyEvidence() }
                candidatePassLimit = maxOf(candidatePassLimit, (body.size + 1) / 2 + 1)
                // Rotate candidate opportunity within long Notes; all passages still match
                // every retained anchor, independent of this traversal order.
                val offset = if (body.isEmpty()) 0 else (candidateRound * 2) % body.size
                val ordered = body.drop(offset) + body.take(offset)
                if (ordered.isEmpty()) builder.accept(record.copy(passages = emptyList()))
                for ((partIndex, passages) in ordered.chunked(64).withIndex()) {
                    if (!mayWork()) return
                    waitForQueries()
                    bulk { builder.acceptPartial(record.copy(passages = passages), completed = (partIndex + 1) * 64 >= ordered.size) }
                    processed += passages.size
                    if (processed >= 64) {
                        if (cache.revision.value != cacheRevision || startEpoch != epoch) return
                        val checkpointWritten = writes.withLock {
                            if (cache.revision.value != cacheRevision || startEpoch != epoch) return
                            bulk { checkpoint?.writeDetailed(builder.checkpoint().copy(candidatePassLimit = candidatePassLimit)) }
                        }
                        if (checkpointWritten is TopicWriteResult.Rejected) {
                            changed(view.copy(status = persistenceFailureMessage(checkpointWritten, checkpoint = true))); return
                        }
                        processed = 0
                        yield()
                    }
                }
            }
            if (builder.reanchorMissing()) {
                for (id in records) {
                    if (!mayWork()) return
                    currentCoroutineContext().ensureActive()
                    val record = readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision } ?: continue
                    for ((part, passages) in record.passages.chunked(64).withIndex()) {
                        bulk { builder.acceptPartial(record.copy(passages = passages), (part + 1) * 64 >= record.passages.size) }
                    }
                }
            }
            bulk { builder.finish() }
        }
        // Connection retrieval depends on current records and published memberships,
        // not provisional anchors or the grouping pass number. Preserve a completed
        // sweep when those inputs are unchanged, including after process recreation.
        val reuseConnections = !continueConnections && builtToken == token && latest.connectionPassComplete &&
            connectionMemberships(latest) == connectionMemberships(grouped)
        connectionCursor = if (reuseConnections) sources.size else if (continueConnections) latest.connectionCursor else 0
        val connections = if (reuseConnections) latest.connections else connections(grouped, sources, model)
        val complete = grouped.copy(connections = connections,
            generation = latest.generation + 1, connectionCursor = connectionCursor,
            connectionPassComplete = connectionCursor >= sources.size,
            candidateCursor = if (allRecords.isEmpty()) 0 else if (continueConnections) cursor else (cursor + 1) % allRecords.size,
            groupingPass = if (continueConnections) latest.groupingPass else if (builtToken == token) latest.groupingPass + 1 else 1,
            sourceSignature = token, candidatePassLimit = candidatePassLimit)
        writes.withLock {
            if (startEpoch != epoch || cache.revision.value != cacheRevision || identity() != model || index.isPaused() || !mayWork()) return
            val publicationWrite = bulk { store.writeDetailed(complete) }
            if (publicationWrite is TopicWriteResult.Rejected) {
                changed(view.copy(status = persistenceFailureMessage(publicationWrite))); return
            }
            if (cache.revision.value != cacheRevision || startEpoch != epoch) {
                store.clear(); return
            }
            checkpoint?.clear()
            val initial = view.snapshot.topics.isEmpty() && view.snapshot.generation == 0L
            latest = complete
            builtToken = token
            needsMoreWork = !complete.connectionPassComplete || complete.topics.size < policy.maxAnchors && complete.groupingPass < complete.candidatePassLimit &&
                (!complete.complete || complete.provisionalTopics.isNotEmpty())
            val visibleChange = view.snapshot.topics != complete.topics || view.snapshot.connections != complete.connections ||
                view.snapshot.complete != complete.complete || view.snapshot.connectionPassComplete != complete.connectionPassComplete
            view = if (initial) TopicView(complete, status = complete.message)
                else view.copy(updated = visibleChange, status = complete.message)
            changed(view)
        }
    }

    /** Experimental formation is bounded by comparisons, never by admitted source sections. */
    private suspend fun buildExperimental(sources: Map<String, String>, metadataVersion: Int,
        cacheRevision: Long, worker: SectionHierarchicalEngine) {
        val model = identity()
        val token = semanticHash(model.toString() + policy.toString() + metadataVersion +
            sources.toSortedMap().toString() + "hierarchical:" + SectionHierarchicalEngine.VERSION)
        if (latest.sourceSignature == token && latest.complete && latest.connectionPassComplete) {
            needsMoreWork = false; return
        }
        val startEpoch = epoch
        val context = currentCoroutineContext()
        val resume = (checkpoint?.read() ?: experimentalPending)?.takeIf { it.sourceSignature == token && it.identity == model &&
            it.policyVersion == policy.version && it.engineVersion == SectionHierarchicalEngine.VERSION &&
            it.formationProgress != null }
        var referenceRecords: List<SemanticRecord>? = null
        val grouped = if (latest.sourceSignature == token && latest.complete && latest.engineVersion == SectionHierarchicalEngine.VERSION) latest
        else {
            val records = mutableListOf<SemanticRecord>()
            for (id in index.pageIds().sorted()) {
                context.ensureActive(); waitForQueries()
                if (startEpoch != epoch || index.isPaused() || !mayWork()) return
                readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision }?.let { records += it }
            }
            referenceRecords = records
            changed(view.copy(status = "Finding topics in all indexed cached Notes…"))
            bulk { worker.step(records, resume ?: latest, policy, checkActive = { context.ensureActive() }) }
                .copy(sourceSignature = token)
        }
        if (!grouped.complete) {
            writes.withLock {
                if (startEpoch != epoch || cache.revision.value != cacheRevision || index.isPaused() || !mayWork()) return
                val result = bulk { checkpoint?.writeDetailed(grouped) }
                if (result is TopicWriteResult.Rejected) {
                    needsMoreWork = false
                    changed(view.copy(status = persistenceFailureMessage(result, checkpoint = true))); return
                }
                needsMoreWork = true
                experimentalPending = grouped
                changed(view.copy(status = grouped.message))
            }
            yield(); return
        }
        connectionCursor = if (latest.sourceSignature == token) latest.connectionCursor else 0
        val semantic = connections(grouped, sources, model)
        val explicit = referenceRecords?.let { discoverExplicitNoteReferences(it) { context.ensureActive() } }
            ?: latest.connections.filter { it.independentNamedReference }
        val connected = (explicit + semantic).distinctBy { it.id }
        val result = grouped.copy(connections = connected, connectionCursor = connectionCursor,
            connectionPassComplete = connectionCursor >= sources.size, generation = latest.generation + 1,
            sourceSignature = token)
        writes.withLock {
            if (startEpoch != epoch || cache.revision.value != cacheRevision || identity() != model || index.isPaused() || !mayWork()) return
            val saved = bulk { store.writeDetailed(result) }
            if (saved is TopicWriteResult.Rejected) {
                needsMoreWork = false; changed(view.copy(status = persistenceFailureMessage(saved))); return
            }
            checkpoint?.clear()
            val initial = view.snapshot.generation == 0L && view.snapshot.topics.isEmpty()
            latest = result; builtToken = token; experimentalPending = null
            needsMoreWork = !result.connectionPassComplete
            val visibleChange = view.snapshot.topics != result.topics || view.snapshot.connections != result.connections ||
                view.snapshot.complete != result.complete || view.snapshot.connectionPassComplete != result.connectionPassComplete
            view = if (initial) TopicView(result, status = result.message)
                else view.copy(updated = visibleChange, status = result.message)
            changed(view)
        }
    }
    /** Bounded seed round; exact disk retrieval retains one best result per other Note. */
    private fun connectionMemberships(snapshot: TopicSnapshot): Map<String, Set<String>> =
        publishableDiscoveryTopics(snapshot.topics).flatMap { topic -> topic.members.map { it.pageId to topic.id } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    private suspend fun connections(snapshot: TopicSnapshot, sources: Map<String, String>, model: EmbeddingIdentity): List<NoteConnection> {
        val memberships = connectionMemberships(snapshot)
        val ids = sources.keys.sorted()
        if (ids.isEmpty()) return emptyList()
        fun crosses(left: String, right: String): Boolean {
            val a = memberships[left].orEmpty(); val b = memberships[right].orEmpty()
            return a.isEmpty() || b.isEmpty() || ((a - b).isNotEmpty() && (b - a).isNotEmpty())
        }
        val pairs = latest.connections.filter { it.left.passage.isBodyEvidence() && it.right.passage.isBodyEvidence() && sources[it.left.pageId] == it.left.revision && sources[it.right.pageId] == it.right.revision && crosses(it.left.pageId, it.right.pageId) }
            .associateBy { it.id }.toMutableMap()
        repeat(minOf(4, ids.size - connectionCursor.coerceAtMost(ids.size))) { offset ->
            val seedId = ids[(connectionCursor + offset) % ids.size]
            val seed = readRecord(seedId)?.takeIf { it.identity == model && sources[seedId] == it.revision } ?: return@repeat
            for (passage in seed.passages.filter { it.passage.isBodyEvidence() }.distinctBy { it.passage.sectionId }.take(3)) {
                waitForQueries(); currentCoroutineContext().ensureActive()
                var matches = emptyList<SemanticMatch>()
                val revisions = mutableMapOf<String, String>()
                for (id in ids) {
                    if (!mayWork()) return emptyList()
                    waitForQueries()
                    if (id == seedId || !crosses(seedId, id)) continue
                    val other = readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision } ?: continue
                    revisions[id] = other.revision
                    matches = (matches + bulk { rankSemanticRecords(passage.vector, model, sequenceOf(other.copy(passages = other.passages.filter { it.passage.isBodyEvidence() })),
                        limit = 5, excludePageId = seedId, threshold = policy.connection) }).sortedWith(
                        compareByDescending<SemanticMatch> { it.score }.thenBy { it.pageId }).take(5)
                }
                for (match in matches) {
                    val left = TopicMembership(seedId, seed.metadata, passage.passage, match.score, match.passage, seed.revision)
                    val right = TopicMembership(match.pageId, match.metadata, match.passage, match.score, passage.passage, revisions[match.pageId].orEmpty())
                    val ordered = listOf(left, right).sortedBy { it.pageId }
                    val pairId = semanticHash(ordered.joinToString("|") { it.pageId })
                    val candidate = NoteConnection(pairId, ordered[0], ordered[1], match.score)
                    if (candidate.score > (pairs[pairId]?.score ?: -1f)) pairs[pairId] = candidate
                }
                if (pairs.size > 20) {
                    val keep = selectDiscoveryConnections(pairs.values)
                    pairs.clear(); keep.forEach { pairs[it.id] = it }
                }
            }
        }
        connectionCursor = minOf(connectionCursor + 4, ids.size)
        return selectDiscoveryConnections(pairs.values)
    }

    suspend fun clear() = writes.withLock {
        epoch++; store.clear(); checkpoint?.clear(); experimentalPending = null; latest = TopicSnapshot(); builtToken = null
        view = TopicView(); changed(view)
    }
    suspend fun bytes(): Long = store.bytes() + (checkpoint?.bytes() ?: 0)
}

/** Recovery descriptions contain structural categories only, never source evidence. */
internal fun persistenceFailureMessage(result: TopicWriteResult.Rejected, checkpoint: Boolean = false): String {
    val subject = if (checkpoint) "Topic checkpoint" else "Topic"
    return when (result.failure) {
        TopicWriteFailure.BudgetExceeded -> "$subject storage is full; discovery coverage is incomplete. Free derived storage and retry."
        TopicWriteFailure.InvalidSnapshot -> "$subject validation failed; previous discoveries remain available. Retry processing."
        TopicWriteFailure.IoFailure -> "$subject I/O failure; previous discoveries remain available. Retry saving."
        TopicWriteFailure.Unknown -> "$subject could not be saved for an unknown reason; previous discoveries remain available. Retry saving."
    }
}
