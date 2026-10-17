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
    private val formationWorkBudget: Int = 250_000,
    private val shouldYield: () -> Boolean = { false }
) {
    private class WorkPaused : RuntimeException()
    private val writes = Mutex()
    private var loaded = false
    @Volatile private var latest = TopicSnapshot()
    @Volatile private var view = TopicView()
    @Volatile private var epoch = 0L
    private var connectionCursor = 0
    private var connectionPassageCursor = 0
    private var pendingFormation: TopicSnapshot? = null
    var needsMoreWork: Boolean = false
        private set
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
        fun capped(snapshot: TopicSnapshot) = snapshot.copy(connections = selectDiscoveryConnections(snapshot.connections))
        var next = capped(remove(latest, stale, permanent = false))
        var shown = capped(remove(view.snapshot, stale, permanent = false))
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
            pendingFormation = null
            checkpoint?.clear()
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
        buildGeneration(sources, metadataVersion, cacheRevision,
            SectionHierarchicalEngine(identity(), workQuantum = formationWorkBudget))
    }
    /** Formation is bounded by comparisons, never by admitted source sections. */
    private suspend fun buildGeneration(sources: Map<String, String>, metadataVersion: Int,
        cacheRevision: Long, worker: SectionHierarchicalEngine) {
        val model = identity()
        val token = semanticHash(model.toString() + policy.toString() + metadataVersion +
            sources.toSortedMap().toString() + "hierarchical:" + SectionHierarchicalEngine.VERSION)
        if (latest.sourceSignature == token && latest.complete && latest.connectionPassComplete) {
            needsMoreWork = false; return
        }
        val startEpoch = epoch
        val context = currentCoroutineContext()
        val resume = (checkpoint?.read() ?: pendingFormation)?.takeIf { it.sourceSignature == token && it.identity == model &&
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
            try {
                bulk { worker.step(records, resume ?: latest, policy, checkActive = {
                    context.ensureActive()
                    if (!mayWork() || shouldYield() || startEpoch != epoch || cache.revision.value != cacheRevision) throw WorkPaused()
                }) }.copy(sourceSignature = token)
            } catch (_: WorkPaused) {
                // Keep the last durable quantum. A policy pause must not cancel the service collector.
                needsMoreWork = true
                return
            }
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
                pendingFormation = grouped
                changed(view.copy(status = grouped.message))
            }
            yield(); return
        }
        connectionCursor = if (latest.sourceSignature == token) latest.connectionCursor else 0
        connectionPassageCursor = if (latest.sourceSignature == token) latest.connectionPassageCursor else 0
        val semantic = try { connections(grouped, sources, model) }
            catch (_: WorkPaused) { needsMoreWork = true; return }
        val explicit = try {
            referenceRecords?.let { discoverExplicitNoteReferences(it) {
                context.ensureActive()
                if (!mayWork() || shouldYield() || startEpoch != epoch || cache.revision.value != cacheRevision) throw WorkPaused()
            } } ?: latest.connections.filter { it.independentNamedReference }
        } catch (_: WorkPaused) { needsMoreWork = true; return }
        val connected = selectDiscoveryConnections((explicit + semantic).distinctBy { it.id })
        val result = grouped.copy(connections = connected, connectionCursor = connectionCursor, connectionPassageCursor = connectionPassageCursor,
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
            latest = result; pendingFormation = null
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
        var remaining = 256
        while (connectionCursor < ids.size && remaining > 0) {
            val seedId = ids[connectionCursor]
            val seed = readRecord(seedId)?.takeIf { it.identity == model && sources[seedId] == it.revision }
            val passages = seed?.passages.orEmpty().filter { it.passage.isBodyEvidence() }.sortedBy { it.passage.id }
            if (seed == null || connectionPassageCursor >= passages.size) {
                connectionCursor++; connectionPassageCursor = 0; continue
            }
            val passage = passages[connectionPassageCursor]
                waitForQueries(); currentCoroutineContext().ensureActive()
                var matches = emptyList<SemanticMatch>()
                val revisions = mutableMapOf<String, String>()
                for (id in ids) {
                    if (!mayWork()) throw WorkPaused()
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
            connectionPassageCursor++; remaining--
            if (connectionPassageCursor >= passages.size) { connectionCursor++; connectionPassageCursor = 0 }
        }
        return selectDiscoveryConnections(pairs.values)
    }

    suspend fun clear() = writes.withLock {
        epoch++; store.clear(); checkpoint?.clear(); pendingFormation = null; latest = TopicSnapshot()
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
