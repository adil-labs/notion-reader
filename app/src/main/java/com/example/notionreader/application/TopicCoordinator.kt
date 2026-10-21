package com.example.notionreader.application

import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong

internal data class TopicView(val snapshot: TopicSnapshot = TopicSnapshot(), val updated: Boolean = false,
    val status: String? = null, val failure: String? = null)

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
    private val shouldYield: () -> Boolean = { false },
    private val operationTimings: (Map<String, Long>) -> Unit = {}
) {
    private class WorkPaused : RuntimeException()
    private val writes = Mutex()
    private var loaded = false
    @Volatile private var latest = TopicSnapshot()
    @Volatile private var view = TopicView()
    private val epoch = AtomicLong()
    private var connectionCursor = 0
    private var connectionPassageCursor = 0
    private var connectionTargetCursor = 0
    @Volatile private var pendingFormation: TopicSnapshot? = null
    private class FormationWorkspace(val token: String, val cacheRevision: Long, val epoch: Long,
        val records: List<SemanticRecord>, val engine: SectionHierarchicalEngine)
    private val workspaceLock = Any()
    @Volatile private var formationWorkspace: FormationWorkspace? = null
    var needsMoreWork: Boolean = false
        private set
    suspend fun refresh() = writes.withLock {
        val pinned = bulk { store.pinInspection(latest) }
        view = TopicView(latest, status = if (pinned) latest.message else "Connection catalog is unavailable. Retry processing.",
            failure = if (pinned) view.failure else "Connection catalog is unavailable. Retry processing.")
        changed(view)
    }

    private fun failed(message: String) {
        view = view.copy(status = message, failure = message)
        changed(view)
    }

    private suspend fun <T> bulk(action: suspend () -> T): T {
        waitForQueries()
        return bulkGate.withLock { action() }
    }
    private suspend fun readRecord(id: String) = bulk { index.read(id) }

    private suspend fun <T> restoreStep(name: String, action: suspend () -> T): T {
        fun report(values: Map<String, Long>) {
            try { operationTimings(values) } catch (_: Exception) { /* Numeric diagnostics only. */ }
        }
        report(mapOf("${name}Started" to 1L))
        val start = System.nanoTime()
        try { return action() }
        finally { report(mapOf("${name}Ns" to (System.nanoTime() - start))) }
    }

    suspend fun invalidate(sources: Map<String, String>) = writes.withLock {
        if (!loaded) {
            latest = restoreStep("restoreMetadata") { store.readMetadata() } ?: TopicSnapshot()
            view = TopicView(latest, status = latest.message)
            loaded = true
        }
        store.pinInspection(view.snapshot)
        val events = cache.invalidations.value
        val evidence = (latest.topics + latest.provisionalTopics + view.snapshot.topics + view.snapshot.provisionalTopics)
            .asSequence().flatMap { it.members.asSequence() } +
            (latest.connections.asSequence() + view.snapshot.connections.asSequence()).flatMap { sequenceOf(it.left, it.right) }
        val relationships = latest.subjectRelationships + view.snapshot.subjectRelationships
        val tracked = listOf(latest, view.snapshot)
        val known = evidence.map { it.pageId }.toSet() + relationships.map { it.evidence.pageId } +
            tracked.flatMap { it.acceptedRevisions.keys + it.referenceTitles.keys }
        val stale = evidence.filter { latest.identity != identity() || sources[it.pageId] != it.revision }.map { it.pageId }.toSet() +
            (known - sources.keys) + relationships.filter { latest.identity != identity() ||
                sources[it.evidence.pageId] != it.revision }.map { it.evidence.pageId } +
            tracked.flatMap { snapshot -> snapshot.acceptedRevisions.filter { (id, revision) ->
                snapshot.identity != identity() || sources[id] != revision }.keys }
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
            return invalidateTopics(snapshot, ids, permanent, replacements, retentionThreshold = policy.retention)
        }
        var next = restoreStep("restoreLatestEligibility") { remove(latest, stale, permanent = false) }
        var shown = restoreStep("restoreInspectedEligibility") { remove(view.snapshot, stale, permanent = false) }
        val currentTitles = linkedMapOf<String, String>()
        if ((next.connections.asSequence() + shown.connections.asSequence()).any { it.independentNamedReference } ||
            (next.catalogReference?.namedReferences ?: 0) > 0 || (shown.catalogReference?.namedReferences ?: 0) > 0) {
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
        val pendingCatalogEvents = events + cache.invalidations.value
        val removedCatalogIds = pendingCatalogEvents.mapNotNull { it.pageId?.let(::canonicalSemanticId) }.toSet()
        val catalogSources = if (pendingCatalogEvents.any { it.pageId == null }) emptyMap()
            else sources.filterKeys { it !in removedCatalogIds }
        val catalogRetained = restoreStep("restoreCatalogEligibility") { store.reconcileCatalogs(catalogSources, identity(), currentTitles) }
        next = store.refreshCatalogReference(next)
        shown = store.refreshCatalogReference(shown)
        if (!catalogRetained) {
            val recovery = "Connection storage needs recovery; discovery coverage is incomplete. Retry processing."
            next = next.copy(connections = emptyList(), catalogReference = null, complete = false,
                connectionPassComplete = false, sourceSignature = null, message = recovery)
            shown = shown.copy(connections = emptyList(), catalogReference = null, complete = false,
                connectionPassComplete = false, message = recovery)
            view = view.copy(status = recovery, failure = recovery)
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
            epoch.incrementAndGet()
            formationWorkspace = null
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
                val continuityOnly = next.copy(topics = emptyList(), connections = emptyList(), catalogReference = null, subjectRelationships = emptyList(), complete = false)
                store.writeDetailed(continuityOnly)
                failed(persistenceFailureMessage(invalidationWrite))
                return@withLock
            }
            events.maxOfOrNull { it.generation }?.let {
                cache.acknowledgeInvalidations(minOf(it, invalidationAcknowledgementLimit()))
            }
        }
        view = view.copy(snapshot = shown)
        store.pinInspection(view.snapshot)
        restoreStep("restorePublication") { changed(view) }
    }

    suspend fun build(sources: Map<String, String>, metadataVersion: Int, cacheRevision: Long) {
        if (cache.revision.value != cacheRevision || !mayWork()) return
        buildGeneration(sources, metadataVersion, cacheRevision)
    }
    /** Formation is bounded by comparisons, never by admitted source sections. */
    private suspend fun buildGeneration(sources: Map<String, String>, metadataVersion: Int,
        cacheRevision: Long) {
        val model = identity()
        val token = semanticHash(model.toString() + policy.toString() + metadataVersion +
            sources.toSortedMap().toString() + "hierarchical:" + SectionHierarchicalEngine.VERSION)
        if (latest.sourceSignature == token && latest.complete && latest.connectionPassComplete) {
            formationWorkspace = null
            needsMoreWork = false; return
        }
        val startEpoch = epoch.get()
        val context = currentCoroutineContext()
        val resume = (pendingFormation ?: checkpoint?.read())?.takeIf { it.sourceSignature == token && it.identity == model &&
            it.policyVersion == policy.version && it.engineVersion == SectionHierarchicalEngine.VERSION &&
            (it.formationProgress != null || it.complete) }
        var referenceTitles = if (latest.sourceSignature == token) latest.referenceTitles else emptyMap()
        var connectionInputs = if (latest.sourceSignature == token) latest.connectionWork.inputs else emptyMap()
        var connectionTitleKeys = if (latest.sourceSignature == token) latest.connectionWork.titleKeys else emptyMap()
        val grouped = if (latest.sourceSignature == token && latest.complete && latest.engineVersion == SectionHierarchicalEngine.VERSION) latest
        else if (resume?.complete == true) resume
        else {
            val prepared = formationWorkspace?.takeIf {
                it.token == token && it.cacheRevision == cacheRevision && it.epoch == startEpoch
            } ?: run {
                formationWorkspace = null
                val records = mutableListOf<SemanticRecord>()
                for (id in index.pageIds().sorted()) {
                    context.ensureActive(); waitForQueries()
                    if (startEpoch != epoch.get() || index.isPaused() || !mayWork()) return
                    readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision }?.let { records += it }
                }
                val next = FormationWorkspace(token, cacheRevision, startEpoch, records.toList(),
                    SectionHierarchicalEngine(model, distanceThreshold = policy.formationDistance,
                        membershipThreshold = policy.membership, workQuantum = formationWorkBudget))
                synchronized(workspaceLock) {
                    if (startEpoch != epoch.get() || cache.revision.value != cacheRevision || identity() != model) return
                    formationWorkspace = next
                }
                next
            }
            val records = prepared.records
            referenceTitles = records.associate { canonicalSemanticId(it.pageId) to it.metadata.title }
            connectionInputs = records.associate { canonicalSemanticId(it.pageId) to semanticHash(it.revision + it.metadata.toString()) }
            connectionTitleKeys = referenceTitles.mapValues { connectionTitleKey(it.value) }
            changed(view.copy(status = "Finding topics in all indexed cached Notes…", failure = null))
            try {
                bulk { prepared.engine.stepPrepared(records, resume ?: latest, policy,
                    shouldYield = { !mayWork() || shouldYield() }, checkActive = {
                    context.ensureActive()
                    if (startEpoch != epoch.get() || cache.revision.value != cacheRevision || identity() != model) throw WorkPaused()
                }) }.copy(sourceSignature = token, connectionWork = ConnectionWork(connectionInputs, connectionTitleKeys))
            } catch (_: WorkPaused) {
                // Keep the last durable quantum. A policy pause must not cancel the service collector.
                needsMoreWork = true
                return
            }
        }
        if (!grouped.complete || latest.sourceSignature != token || !latest.complete) {
            writes.withLock {
                // Orderly window/policy pauses retain completed work; changed source/model/reset state cannot.
                context.ensureActive()
                if (startEpoch != epoch.get() || cache.revision.value != cacheRevision || identity() != model) return
                val result = bulk {
                    // Query priority can delay this commit; recheck source/model validity after that wait.
                    context.ensureActive()
                    if (startEpoch != epoch.get() || cache.revision.value != cacheRevision || identity() != model) return@bulk null
                    checkpoint?.writeDetailed(grouped)
                }
                if (startEpoch != epoch.get() || cache.revision.value != cacheRevision || identity() != model) return
                if (result is TopicWriteResult.Rejected) {
                    needsMoreWork = false
                    failed(persistenceFailureMessage(result, checkpoint = true)); return
                }
                needsMoreWork = true
                pendingFormation = grouped
                if (!grouped.complete) changed(view.copy(status = grouped.message, failure = null))
            }
            if (!grouped.complete) { yield(); return }
        }
        formationWorkspace = null
        connectionInputs = grouped.connectionWork.inputs
        connectionTitleKeys = grouped.connectionWork.titleKeys
        val compatibleConnections = latest.identity == model && latest.policyVersion == policy.version &&
            latest.engineVersion == SectionHierarchicalEngine.VERSION && latest.connectionWork.inputs.isNotEmpty()
        val priorTitleCounts = latest.connectionWork.titleKeys.values.groupingBy { it }.eachCount()
        val currentTitleCounts = connectionTitleKeys.values.groupingBy { it }.eachCount()
        val changedTitleCounts = (priorTitleCounts.keys + currentTitleCounts.keys).filterTo(hashSetOf()) { title ->
            priorTitleCounts[title] != currentTitleCounts[title]
        }
        val sameConnectionSweep = latest.sourceSignature == token
        val affectedConnections = if (!compatibleConnections) sources.keys
            else if (sameConnectionSweep) latest.connectionWork.changedIds else
            sources.keys.filterTo(hashSetOf()) { id -> latest.connectionWork.inputs[id] != connectionInputs[id] ||
                connectionTitleKeys[id] in changedTitleCounts }
        // Completed directional seeds remain complete for unchanged targets. Only the
        // partial seed and later seeds need their old target work after a source delta.
        val unfinishedSeeds = if (sameConnectionSweep) latest.connectionWork.unfinishedSeedIds
            else if (compatibleConnections && !latest.connectionPassComplete)
                latest.connectionWork.inputs.keys.sorted().drop(latest.connectionCursor).filterTo(hashSetOf()) { it in sources }
            else emptySet()
        connectionCursor = if (latest.sourceSignature == token) latest.connectionCursor else 0
        connectionPassageCursor = if (latest.sourceSignature == token) latest.connectionPassageCursor else 0
        connectionTargetCursor = if (latest.sourceSignature == token) latest.connectionTargetCursor else 0
        val connectionPlan = ConnectionWork(connectionInputs, connectionTitleKeys, affectedConnections, unfinishedSeeds)
        val semantic = try { connections(sources, model, startEpoch, cacheRevision, referenceTitles, connectionPlan, compatibleConnections) }
            catch (_: WorkPaused) { needsMoreWork = true; return }
            catch (_: java.io.IOException) {
                if (epoch.get() != startEpoch || cache.revision.value != cacheRevision) { needsMoreWork = true; return }
                needsMoreWork = false
                failed("Stored connections are unavailable. Retry processing."); return
            }
        val connected = semantic
        val result = grouped.copy(connections = connected, catalogReference = null, referenceTitles = referenceTitles,
            connectionCursor = connectionCursor, connectionPassageCursor = connectionPassageCursor,
            connectionTargetCursor = connectionTargetCursor,
            connectionTargetCount = sources.size,
            connectionPassComplete = connectionCursor >= sources.size, generation = latest.generation + 1,
            sourceSignature = token, connectionWork = if (connectionCursor >= sources.size)
                connectionPlan.copy(changedIds = emptySet(), unfinishedSeedIds = emptySet()) else connectionPlan)
        writes.withLock {
            if (startEpoch != epoch.get() || cache.revision.value != cacheRevision || identity() != model || index.isPaused() || !mayWork()) return
            val saved = bulk { store.writeDetailed(result) }
            if (saved is TopicWriteResult.Rejected) {
                needsMoreWork = false; failed(persistenceFailureMessage(saved)); return
            }
            checkpoint?.clear()
            val initial = view.snapshot.generation == 0L && view.snapshot.topics.isEmpty()
            // Re-open committed storage rather than retaining a chain of every prior merge batch.
            val committed = bulk { store.readMetadata() }
            if (committed == null) {
                needsMoreWork = false
                failed("Stored discoveries could not be reloaded. Retry processing.")
                return
            }
            latest = committed
            pendingFormation = null
            needsMoreWork = !result.connectionPassComplete
            val visibleChange = view.snapshot.topics != latest.topics || view.snapshot.connections != latest.connections ||
                view.snapshot.catalogReference?.fingerprint != latest.catalogReference?.fingerprint ||
                view.snapshot.complete != latest.complete || view.snapshot.connectionPassComplete != latest.connectionPassComplete
            view = if (initial) TopicView(latest, status = result.message)
                else view.copy(updated = visibleChange, status = result.message, failure = null)
            store.pinInspection(view.snapshot)
            changed(view)
        }
    }
    private suspend fun connections(sources: Map<String, String>, model: EmbeddingIdentity,
        startEpoch: Long, cacheRevision: Long, titles: Map<String, String>, work: ConnectionWork, compatible: Boolean): List<NoteConnection> {
        val ids = sources.keys.sorted()
        if (ids.size < 2) {
            connectionCursor = ids.size; connectionPassageCursor = 0; connectionTargetCursor = 0
            return emptyList()
        }
        val context = currentCoroutineContext()
        fun checkActive() {
            context.ensureActive()
            if (!mayWork() || shouldYield() || epoch.get() != startEpoch || cache.revision.value != cacheRevision) throw WorkPaused()
        }
        val references = ExplicitNoteReferenceIndex(titles)
        val previous = bulk { store.connectionCatalog(latest) }
        val staleInputs = sources.keys.filterTo(hashSetOf()) { id -> !compatible || work.inputs[id] == null ||
            latest.connectionWork.inputs[id] != work.inputs[id] }
        val base = filterConnectionCatalog(previous, ::checkActive) { pair ->
            pair.left.pageId !in staleInputs && pair.right.pageId !in staleInputs &&
                sources[pair.left.pageId] == pair.left.revision && sources[pair.right.pageId] == pair.right.revision &&
                (if (pair.independentNamedReference) classifyConnectionEvidence(pair, titles).kind == ConnectionEvidenceKind.NamedReference
                    else pair.left.passage.isBodyEvidence() && pair.right.passage.isBodyEvidence())
        }
        val allTargets = ids.indices.toList()
        val affectedTargets = allTargets.filter { ids[it] in work.changedIds }
        if (affectedTargets.isEmpty() && work.unfinishedSeedIds.isEmpty()) {
            connectionCursor = ids.size; connectionPassageCursor = 0; connectionTargetCursor = 0
            return base
        }
        val pairs = linkedMapOf<String, NoteConnection>()
        var remaining = 256
        while (connectionCursor < ids.size && remaining > 0) {
            checkActive()
            val seedId = ids[connectionCursor]
            val targetIndices = if (seedId in work.changedIds || seedId in work.unfinishedSeedIds) allTargets else affectedTargets
            val seed = readRecord(seedId)?.takeIf { it.identity == model && sources[seedId] == it.revision }
            val passages = seed?.passages.orEmpty().filter {
                it.passage.sourceBlockId != it.passage.sectionId && it.passage.excerpt.isNotBlank()
            }.sortedBy { it.passage.id }
            if (seed == null || connectionPassageCursor >= passages.size) {
                connectionCursor++; connectionPassageCursor = 0; connectionTargetCursor = 0; continue
            }
            val passage = passages[connectionPassageCursor]
                waitForQueries(); currentCoroutineContext().ensureActive()
                var targetSlot = targetIndices.binarySearch(connectionTargetCursor).let { if (it >= 0) it else -it - 1 }
                while (targetSlot < targetIndices.size && remaining > 0) {
                    if (!mayWork() || shouldYield()) throw WorkPaused()
                    currentCoroutineContext().ensureActive()
                    waitForQueries()
                    val targetIndex = targetIndices[targetSlot++]
                    connectionTargetCursor = targetIndex + 1
                    val id = ids[targetIndex]
                    if (id == seedId) continue
                    remaining--
                    val other = readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision } ?: continue
                    val reference = bulk { references.candidate(seed, passage.passage, other, ::checkActive) }
                    if (reference != null) {
                        val prior = pairs[reference.id]
                        if (prior == null || compareValuesBy(reference, prior,
                                { it.left.passage.id }, { it.right.passage.id }) < 0) pairs[reference.id] = reference
                    }
                    if (!passage.passage.isBodyEvidence()) continue
                    val matches = bulk { rankSemanticRecords(passage.vector, model, sequenceOf(other),
                        limit = 1, excludePageId = seedId, threshold = policy.connection,
                        checkActive = ::checkActive, passageFilter = { it.isBodyEvidence() }) }
                    for (match in matches) {
                        val left = TopicMembership(seedId, seed.metadata, passage.passage, match.score, match.passage, seed.revision)
                        val right = TopicMembership(match.pageId, match.metadata, match.passage, match.score, passage.passage, other.revision)
                        val ordered = listOf(left, right).sortedBy { it.pageId }
                        val pairId = semanticHash(ordered.joinToString("|") { it.pageId })
                        val candidate = NoteConnection(pairId, ordered[0], ordered[1], match.score,
                            evidenceKind = ConnectionEvidenceKind.SimilarPassages)
                        val prior = pairs[pairId]
                        if (prior == null || candidate.score > prior.score || candidate.score == prior.score &&
                            compareValuesBy(candidate, prior, { it.left.passage.id }, { it.right.passage.id }) < 0) pairs[pairId] = candidate
                    }
                }
            if (targetSlot >= targetIndices.size) connectionTargetCursor = ids.size
            if (connectionTargetCursor < ids.size) break
            connectionTargetCursor = 0
            connectionPassageCursor++
            if (connectionPassageCursor >= passages.size) { connectionCursor++; connectionPassageCursor = 0 }
        }
        return mergeConnectionUpdates(base, pairs.values.toList(), ::checkActive)
    }

    suspend fun clear() = writes.withLock {
        epoch.incrementAndGet(); formationWorkspace = null; store.clear(); checkpoint?.clear(); pendingFormation = null; latest = TopicSnapshot()
        view = TopicView(); changed(view)
    }
    suspend fun bytes(): Long = store.bytes() + (checkpoint?.bytes() ?: 0)

    /** Drop the sole retained generation. In-flight locals observe the epoch change and unwind cooperatively. */
    fun releaseMemory() = synchronized(workspaceLock) {
        epoch.incrementAndGet()
        formationWorkspace = null
        if (checkpoint != null) pendingFormation = null
    }
}

/** Recovery descriptions contain structural categories only, never source evidence. */
internal fun persistenceFailureMessage(result: TopicWriteResult.Rejected, checkpoint: Boolean = false): String {
    val subject = if (checkpoint) "Topic checkpoint" else "Topic"
    return when (result.failure) {
        TopicWriteFailure.BudgetExceeded -> "$subject storage is full; discovery coverage is incomplete. Free derived storage and retry."
        TopicWriteFailure.RecordMemoryLimit -> "$subject exceeds the safe record memory limit; discovery coverage is incomplete. Previous discoveries remain available."
        TopicWriteFailure.InvalidSnapshot -> "$subject validation failed; previous discoveries remain available. Retry processing."
        TopicWriteFailure.IoFailure -> "$subject I/O failure; previous discoveries remain available. Retry saving."
        TopicWriteFailure.Unknown -> "$subject could not be saved for an unknown reason; previous discoveries remain available. Retry saving."
    }
}
