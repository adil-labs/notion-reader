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
    val status: String? = null, val failure: String? = null, val naming: TopicNamingStatus? = null)

internal data class TopicCatalogTicket(val snapshot: TopicSnapshot, val epoch: Long, val cacheRevision: Long,
    val identity: EmbeddingIdentity, val queryTicket: String)

internal enum class IncrementalConnectionsResult { Pending, Updated, Complete, Deferred, Failed }

/** Shared with explicit host replay so resume diagnostics describe the shipping input identity. */
internal fun topicSourceSignature(model: EmbeddingIdentity, policy: TopicPolicy, metadataVersion: Int,
    sources: Map<String, String>): String = semanticHash(model.toString() + policy.toString() + metadataVersion +
        sources.toSortedMap().toString() + "hierarchical:" + SectionHierarchicalEngine.VERSION)

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
    private val operationTimings: (Map<String, Long>) -> Unit = {},
    private val labels: TopicLabelCoordinator? = null,
    private val retrievals: TopicRetrievalCoordinator? = null,
    private val proposalProgress: ProposalProgressStore? = null,
    private val requireKnownCatalogOnRestore: Boolean = false,
    private val bootstrapInterests: DiscoveryInterestStore? = null
) {
    private class WorkPaused : RuntimeException()
    private class ConnectionSourceUnavailable : RuntimeException()
    private val writes = Mutex()
    private var loaded = false
    @Volatile private var latest = TopicSnapshot()
    @Volatile private var view = TopicView()
    private var inspected = false
    private var namingStatus: String? = null
    private var naming: TopicNamingStatus? = null
    private val epoch = AtomicLong()
    private var connectionCursor = 0
    private var connectionPassageCursor = 0
    private var connectionTargetCursor = 0
    @Volatile private var pendingFormation: TopicSnapshot? = null
    private class FormationWorkspace(val token: String, val cacheRevision: Long, val epoch: Long,
        val records: List<SemanticRecord>, val engine: SectionHierarchicalEngine)
    private val workspaceLock = Any()
    @Volatile private var formationWorkspace: FormationWorkspace? = null
    @Volatile var needsMoreWork: Boolean = false
        private set
    @Volatile var sourceAcceptancePending: Boolean = false
        private set
    private data class SourceAcceptance(val scope: String, val lastId: String?)
    private var sourceAcceptance: SourceAcceptance? = null
    private data class ConnectionInputs(val cacheRevision: Long, val sources: Map<String, String>,
        val metadata: Map<String, SemanticMetadata>)
    @Volatile private var connectionInputs: ConnectionInputs? = null
    private fun connectionInputHash(revision: String, metadata: SemanticMetadata): String {
        fun optional(value: String?) = if (value == null) "0" else "1$value"
        val fields = listOf(revision, metadata.title, optional(metadata.lastEditedAt), optional(metadata.kind),
            optional(metadata.area), optional(metadata.project), optional(metadata.status)) + metadata.topics.sorted()
        return semanticHash(fields.joinToString("") { "${it.length}:$it" })
    }

    private fun retainPendingWork(startEpoch: Long) = synchronized(workspaceLock) {
        if (startEpoch == epoch.get()) needsMoreWork = true
    }
    /** Captured from the sole coordinator; object identity changes on catalog or label publication. */
    suspend fun captureCatalogTicket(allowIncomplete: Boolean = false): TopicCatalogTicket? = writes.withLock {
        if (!loaded || cache.invalidations.value.isNotEmpty()) return@withLock null
        if (!allowIncomplete && if (requireKnownCatalogOnRestore) !latest.canonicalCatalogReady else !latest.complete) return@withLock null
        val snapshot = latest
        val model = identity()
        if (snapshot.identity != model) return@withLock null
        val ticket = TopicCatalogTicket(snapshot, epoch.get(), cache.revision.value, model,
            retrievals?.queryTicket() ?: return@withLock null)
        ticket.takeIf(::isCatalogTicketCurrent)
    }
    fun isCatalogTicketCurrent(ticket: TopicCatalogTicket): Boolean =
        latest === ticket.snapshot && epoch.get() == ticket.epoch && cache.revision.value == ticket.cacheRevision &&
            cache.invalidations.value.isEmpty() &&
            identity() == ticket.identity && retrievals?.queryTicket() == ticket.queryTicket

    /** Undo a stale replacement, withdrawing changed sources before restoring unaffected catalog state. */
    private suspend fun rollbackAfterStaleWrite(previous: TopicSnapshot, ticket: TopicCatalogTicket) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try {
            fun originalCurrent() = cache.revision.value == ticket.cacheRevision && cache.invalidations.value.isEmpty() &&
                identity() == ticket.identity
            if (originalCurrent()) {
                val rollback = store.writeGuarded(previous, ::originalCurrent)
                if (rollback == TopicGuardedWriteResult.Written && originalCurrent()) return@withContext
            }
            val revision = cache.revision.value
            val events = cache.invalidations.value.toList()
            val model = identity()
            val snapshots = cache.snapshot()
            val revisions = snapshots.associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
            fun sourceCurrent() = cache.revision.value == revision && cache.invalidations.value == events && identity() == model
            if (sourceCurrent() && model == ticket.identity && revisions.size == snapshots.size) {
                val eventIds = events.mapNotNull { it.pageId?.let(::canonicalSemanticId) }.toSet()
                val allowed = if (events.any { it.pageId == null }) emptyMap() else revisions.filterKeys { it !in eventIds }
                fun clean(snapshot: TopicSnapshot): TopicSnapshot {
                    if (events.any { it.reason == CacheRemovalReason.RESET }) return TopicSnapshot()
                    val known = snapshot.acceptedRevisions.keys + snapshot.referenceTitles.keys +
                        (snapshot.topics + snapshot.provisionalTopics).flatMap { topic ->
                            topic.members.map { it.pageId } + topic.canonicalConcept?.offered.orEmpty().map { it.pageId } +
                                topic.canonicalConcept?.scannedRevisions.orEmpty().keys
                        } + snapshot.connections.flatMap { listOf(it.left.pageId, it.right.pageId) } +
                        snapshot.subjectRelationships.map { it.evidence.pageId }
                    val stale = known.map(::canonicalSemanticId).filter { id ->
                        id !in allowed || snapshot.acceptedRevisions[id]?.let { it != allowed[id] } == true
                    }.toSet()
                    var result = invalidateTopics(snapshot, stale, permanent = false)
                    for (event in events) {
                        val ids = event.pageId?.let { setOf(canonicalSemanticId(it)) } ?: known.map(::canonicalSemanticId).toSet()
                        result = invalidateTopics(result, ids,
                            permanent = event.reason == CacheRemovalReason.DELETED || event.reason == CacheRemovalReason.PERMISSION_DENIED)
                    }
                    return result.copy(formationProgress = null, formationReuse = null, sourceSignature = null,
                        complete = false, connectionPassComplete = false,
                        connectionWork = result.connectionWork.copy(incrementalKey = null))
                }
                var recovered = clean(previous)
                var shown = clean(view.snapshot)
                latest = recovered
                epoch.incrementAndGet()
                formationWorkspace = null
                pendingFormation = null
                needsMoreWork = true
                view = view.copy(snapshot = shown, updated = inspected, status = "Discovery recovery is incomplete.")
                changed(view)
                checkpoint?.clear()
                proposalProgress?.reconcile(allowed)
                if (events.any { it.reason == CacheRemovalReason.RESET }) {
                    labels?.clear(); retrievals?.clear()
                } else {
                    labels?.withdraw(recovered)
                    val named = labels?.restore(recovered, ::sourceCurrent) ?: recovered
                    retrievals?.withdraw(named)
                }
                if (sourceCurrent() && store.reconcileCatalogs(allowed, model, emptyMap()) && sourceCurrent()) {
                    recovered = store.refreshCatalogReference(recovered)
                    shown = store.refreshCatalogReference(shown)
                    val saved = store.writeGuarded(recovered, ::sourceCurrent)
                    if (saved == TopicGuardedWriteResult.Written && sourceCurrent()) {
                        latest = recovered
                        view = view.copy(snapshot = shown)
                        store.pinInspection(shown)
                        changed(view)
                        return@withContext
                    }
                }
            }
            } catch (_: Exception) {
                // Fall through to mandatory withdrawal if storage or cache inspection failed.
            }
            // If safe restoration cannot be proven, withdraw visible evidence and the replacement.
            latest = TopicSnapshot()
            epoch.incrementAndGet()
            formationWorkspace = null
            pendingFormation = null
            needsMoreWork = true
            view = TopicView(latest, status = "Discovery storage needs recovery.")
            changed(view)
            // Attempt every independent withdrawal even if another store fails.
            try { checkpoint?.clear() } catch (_: Exception) { }
            try { proposalProgress?.reconcile(emptyMap()) } catch (_: Exception) { }
            try { labels?.clear() } catch (_: Exception) { }
            try { retrievals?.clear() } catch (_: Exception) { }
            store.clear()
        }

    /** Atomic catalog admission; replay and independent citation checks are performed by the caller. */
    suspend fun admitCandidate(ticket: TopicCatalogTicket, candidate: PendingConceptCandidate,
        coverage: PendingCorpusReplayResult.Complete, current: () -> Boolean): CanonicalAdmissionResult = writes.withLock {
        if (!isCatalogTicketCurrent(ticket) || !current()) return@withLock CanonicalAdmissionResult.Stale
        if (!mayWork() || shouldYield()) return@withLock CanonicalAdmissionResult.Deferred
        if (candidate.catalogTicket != ticket || candidate.sources != latest.acceptedRevisions ||
            candidate.proposal.incomplete != candidate.generation.incomplete ||
            candidate.proposal.incomplete.any { it.blocksConceptAdmission() })
            return@withLock CanonicalAdmissionResult.Incomplete
        val concept = candidate.generation.response as? DiscoveryProposalResponse.Concept
            ?: return@withLock CanonicalAdmissionResult.Incomplete
        val prior = latest.topics.singleOrNull { it.canonicalConcept?.workKey == candidate.generation.workKey }
        if (prior != null) return@withLock CanonicalAdmissionResult.Admitted(prior.id)
        val citation = candidate.citations.firstOrNull { it.decision == SupportDecision.SUPPORTED }?.evidence
            ?: return@withLock CanonicalAdmissionResult.Incomplete
        val record = readRecord(citation.pageId)
        if (!isCatalogTicketCurrent(ticket) || !current()) return@withLock CanonicalAdmissionResult.Stale
        if (!mayWork() || shouldYield()) return@withLock CanonicalAdmissionResult.Deferred
        if (record == null || record.identity != ticket.identity || record.revision != citation.revision)
            return@withLock CanonicalAdmissionResult.Incomplete
        val anchor = record.passages.singleOrNull { it.passage.id == citation.passageId && it.passage.isBodyEvidence() }
            ?: return@withLock CanonicalAdmissionResult.Incomplete
        val order = (latest.continuity.maxOfOrNull { it.createdOrder } ?: 0L)
        if (order == Long.MAX_VALUE) return@withLock CanonicalAdmissionResult.Incomplete
        val id = java.util.UUID.randomUUID().toString()
        val member = TopicMembership(citation.pageId, record.metadata, anchor.passage, 1f, anchor.passage, citation.revision)
        val supported = coverage.supported.toSortedMap().flatMap { (page, passages) ->
            val revision = candidate.sources[page] ?: return@withLock CanonicalAdmissionResult.Stale
            passages.sorted().map { ProposalEvidenceRef(page, revision, it) }
        }
        val evidence = CanonicalConceptEvidence(candidate.generation.workKey, concept.name, concept.definition,
            concept.listeningDescription, candidate.generator, candidate.verifier, candidate.proposal.evidence, candidate.citations,
            supported, candidate.sources, matchingComplete = true, proposalKey = candidate.proposal.proposalKey,
            incomplete = candidate.proposal.incomplete,
            queryEmbedding = candidate.query.copy(query = TopicRetrievalQuery(id, candidate.generation.workKey,
                candidate.query.query.text, ticket.identity, candidate.query.query.policy)))
        val topic = DiscoveredTopic(id, concept.name, anchor.passage, anchor, listOf(member), order + 1,
            canonicalConcept = evidence)
        val continuity = TopicContinuity(id, order + 1, setOf(citation.pageId), setOf(topicEvidenceIdentity(anchor.passage)),
            anchorSourceId = topicEvidenceIdentity(anchor.passage))
        val next = latest.copy(topics = latest.topics + topic, continuity = latest.continuity + continuity,
            generation = latest.generation + 1)
        if (!isCatalogTicketCurrent(ticket) || !current()) return@withLock CanonicalAdmissionResult.Stale
        val saved = bulk { store.writeGuarded(next) { isCatalogTicketCurrent(ticket) && current() } }
        if (saved == TopicGuardedWriteResult.Stale) return@withLock CanonicalAdmissionResult.Stale
        if (saved is TopicGuardedWriteResult.Rejected) return@withLock if (current())
            CanonicalAdmissionResult.StorageFailure(saved.result) else CanonicalAdmissionResult.Stale
        if (!isCatalogTicketCurrent(ticket) || !current()) {
            rollbackAfterStaleWrite(latest, ticket)
            return@withLock CanonicalAdmissionResult.Stale
        }
        latest = next
        epoch.incrementAndGet()
        formationWorkspace = null
        pendingFormation = null
        view = if (inspected) view.copy(updated = true) else view.copy(snapshot = next)
        store.pinInspection(view.snapshot)
        changed(view)
        CanonicalAdmissionResult.Admitted(id)
    }

    suspend fun advanceExistingConceptWork(ticket: TopicCatalogTicket, record: SemanticRecord,
        sources: Map<String, String>, verifier: TopicLabelIdentity, limit: Int, restart: Boolean = false,
        verifierCurrent: () -> Boolean = { true }, verify: suspend (TopicSupportRequest) -> TopicSupportRun): DurableConceptScanResult {
        fun current() = isCatalogTicketCurrent(ticket) && verifierCurrent()
        if (!current()) return DurableConceptScanResult.Stale
        if (!mayWork() || shouldYield()) return DurableConceptScanResult.Deferred
        val matching = retrievals ?: return DurableConceptScanResult.Incomplete
        val pending = proposalProgress ?: return DurableConceptScanResult.Incomplete
        val snapshots = bulk { cache.snapshot() }
        if (!current()) return DurableConceptScanResult.Stale
        if (!mayWork() || shouldYield()) return DurableConceptScanResult.Deferred
        val revisions = snapshots.associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        if (revisions.size != snapshots.size || revisions != sources) return DurableConceptScanResult.Stale
        val currentRecord = readRecord(canonicalSemanticId(record.pageId))
        if (!current()) return DurableConceptScanResult.Stale
        if (!mayWork() || shouldYield()) return DurableConceptScanResult.Deferred
        if (currentRecord != record || sources[canonicalSemanticId(record.pageId)] != record.revision)
            return DurableConceptScanResult.Stale
        return advanceDurableConceptScan(ticket.snapshot, record, ticket.identity, matching.existingConceptPolicy(), verifier,
            ticket.epoch, sources, matching.existingConceptStore(), index, pending, limit, ::current,
            { mayWork() && !shouldYield() }, restart, verify)
    }

    /** Known topics may be checked provisionally; the partial snapshot is never complete coverage. */
    suspend fun scanKnownConceptWork(ticket: TopicCatalogTicket, record: SemanticRecord, sources: Map<String, String>,
        verifier: TopicLabelIdentity, cursor: ExistingConceptScanCursor?, limit: Int,
        verifierCurrent: () -> Boolean = { true }, verify: suspend (TopicSupportRequest) -> TopicSupportRun): ExistingConceptScanResult {
        fun current() = isCatalogTicketCurrent(ticket) && verifierCurrent()
        if (!current()) return ExistingConceptScanResult.Stale
        if (!mayWork() || shouldYield()) return ExistingConceptScanResult.Deferred
        val matching = retrievals ?: return ExistingConceptScanResult.Incomplete
        val pending = proposalProgress ?: return ExistingConceptScanResult.Incomplete
        val snapshots = bulk { cache.snapshot() }
        if (!current()) return ExistingConceptScanResult.Stale
        if (!mayWork() || shouldYield()) return ExistingConceptScanResult.Deferred
        val revisions = snapshots.associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        if (revisions.size != snapshots.size || revisions != sources) return ExistingConceptScanResult.Stale
        val currentRecord = readRecord(canonicalSemanticId(record.pageId))
        if (!current()) return ExistingConceptScanResult.Stale
        if (!mayWork() || shouldYield()) return ExistingConceptScanResult.Deferred
        if (currentRecord != record || sources[canonicalSemanticId(record.pageId)] != record.revision) return ExistingConceptScanResult.Stale
        return scanExistingConceptPage(ticket.snapshot, record, ticket.identity, matching.existingConceptPolicy(), verifier,
            ticket.epoch, sources, matching.existingConceptStore(), index, pending, cursor, limit, ::current,
            { mayWork() && !shouldYield() }, allowIncompleteCatalog = true, verify = verify)
    }
    suspend fun replayExistingConceptWork(ticket: TopicCatalogTicket, record: SemanticRecord,
        sources: Map<String, String>, verifier: TopicLabelIdentity, saved: ExistingConceptScanRecord,
        replay: ConceptScanReplay, limit: Int, verifierCurrent: () -> Boolean = { true }): ConceptScanReplayResult {
        fun current() = isCatalogTicketCurrent(ticket) && verifierCurrent()
        if (!current()) return ConceptScanReplayResult.Stale
        if (!mayWork() || shouldYield()) return ConceptScanReplayResult.Deferred
        val matching = retrievals ?: return ConceptScanReplayResult.Incomplete
        val pending = proposalProgress ?: return ConceptScanReplayResult.Incomplete
        val snapshots = bulk { cache.snapshot() }
        if (!current()) return ConceptScanReplayResult.Stale
        if (!mayWork() || shouldYield()) return ConceptScanReplayResult.Deferred
        val revisions = snapshots.associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        if (revisions.size != snapshots.size || revisions != sources) return ConceptScanReplayResult.Stale
        val currentRecord = readRecord(canonicalSemanticId(record.pageId))
        if (!current()) return ConceptScanReplayResult.Stale
        if (!mayWork() || shouldYield()) return ConceptScanReplayResult.Deferred
        if (currentRecord != record || sources[canonicalSemanticId(record.pageId)] != record.revision)
            return ConceptScanReplayResult.Stale
        return replayConceptScanPage(saved, replay, ticket.snapshot, record, ticket.identity, matching.existingConceptPolicy(),
            verifier, ticket.epoch, sources, matching.existingConceptStore(), index, pending, limit, ::current,
            { mayWork() && !shouldYield() })
    }

    /** Replays one bounded scan page and atomically updates only the checked Note's canonical memberships. */
    suspend fun advanceCanonicalMembershipUpdate(ticket: TopicCatalogTicket, record: SemanticRecord,
        sources: Map<String, String>, verifier: TopicLabelIdentity, saved: ExistingConceptScanRecord,
        replay: ConceptScanReplay, limit: Int, verifierCurrent: () -> Boolean = { true }): CanonicalMembershipUpdateResult {
        fun current() = isCatalogTicketCurrent(ticket) && verifierCurrent()
        val checked = replayExistingConceptWork(ticket, record, sources, verifier, saved, replay, limit, verifierCurrent)
        when (checked) {
            is ConceptScanReplayResult.Pending -> return CanonicalMembershipUpdateResult.Pending(checked.replay)
            ConceptScanReplayResult.Stale -> return CanonicalMembershipUpdateResult.Stale
            ConceptScanReplayResult.Deferred -> return CanonicalMembershipUpdateResult.Deferred
            is ConceptScanReplayResult.Validated -> if (checked.uncertain.isNotEmpty()) return CanonicalMembershipUpdateResult.Incomplete
            else -> return CanonicalMembershipUpdateResult.Incomplete
        }
        return writes.withLock {
            if (!current()) return@withLock CanonicalMembershipUpdateResult.Stale
            if (!mayWork() || shouldYield()) return@withLock CanonicalMembershipUpdateResult.Deferred
            if (latest.acceptedRevisions != sources) return@withLock CanonicalMembershipUpdateResult.Incomplete
            val progress = proposalProgress ?: return@withLock CanonicalMembershipUpdateResult.Incomplete
            if (progress.readConceptScan(canonicalSemanticId(record.pageId), sources) != saved || !current())
                return@withLock CanonicalMembershipUpdateResult.Stale
            val id = canonicalSemanticId(record.pageId)
            val changed = mutableListOf<DiscoveredTopic>()
            for (topic in latest.topics) {
                val concept = topic.canonicalConcept
                if (concept == null) { changed += topic; continue }
                if (concept.verifier != verifier) return@withLock CanonicalMembershipUpdateResult.Incomplete
                val assessments = saved.assessments.filter { it.topicId == topic.id }
                if (assessments.any { it.conceptWorkKey != concept.workKey || it.verifier != verifier ||
                        it.evidence.pageId != id || it.evidence.revision != record.revision ||
                        it.decision != SupportDecision.SUPPORTED }) return@withLock CanonicalMembershipUpdateResult.Incomplete
                val scanned = concept.scannedRevisions.filter { (page, revision) -> sources[page] == revision } + (id to record.revision)
                val retained = concept.supported.filter { it.pageId != id && scanned[it.pageId] == it.revision }
                val added = assessments.map { it.evidence }
                val nextConcept = concept.copy(supported = (retained + added).distinct().sortedWith(
                    compareBy<ProposalEvidenceRef> { it.pageId }.thenBy { it.passageId }), scannedRevisions = scanned,
                    matchingComplete = scanned == sources)
                changed += if (nextConcept == concept) topic else topic.copy(canonicalConcept = nextConcept, canonicalProjection = null)
            }
            if (changed == latest.topics) return@withLock CanonicalMembershipUpdateResult.Unchanged
            val next = latest.copy(topics = changed, generation = latest.generation + 1)
            if (!current()) return@withLock CanonicalMembershipUpdateResult.Stale
            val result = bulk { store.writeGuarded(next, ::current) }
            if (result == TopicGuardedWriteResult.Stale) return@withLock CanonicalMembershipUpdateResult.Stale
            if (result is TopicGuardedWriteResult.Rejected) return@withLock if (current())
                CanonicalMembershipUpdateResult.StorageFailure(result.result) else CanonicalMembershipUpdateResult.Stale
            if (!current()) {
                rollbackAfterStaleWrite(latest, ticket)
                return@withLock CanonicalMembershipUpdateResult.Stale
            }
            latest = next
            epoch.incrementAndGet()
            formationWorkspace = null
            pendingFormation = null
            view = if (inspected) view.copy(updated = true) else view.copy(snapshot = next)
            store.pinInspection(view.snapshot)
            changed(view)
            CanonicalMembershipUpdateResult.Updated
        }
    }

    suspend fun setInspected(active: Boolean) = writes.withLock {
        if (inspected == active) return@withLock
        inspected = active
        if (!active) {
            view = TopicView(latest, status = namingStatus ?: latest.message, failure = view.failure, naming = naming)
            store.pinInspection(latest)
            changed(view)
        }
    }
    suspend fun refresh() = writes.withLock {
        inspected = true
        val pinned = bulk { store.pinInspection(latest) }
        view = TopicView(latest, status = if (pinned) namingStatus ?: latest.message else "Connection catalog is unavailable. Retry processing.",
            failure = if (pinned) view.failure else "Connection catalog is unavailable. Retry processing.", naming = naming)
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
        val restoring = !loaded
        val cacheRevision = cache.revision.value
        if (!loaded) {
            latest = if (requireKnownCatalogOnRestore) {
                when (val result = restoreStep("restoreMetadata") { store.inspectMetadata() }) {
                    TopicMetadataState.Fresh -> {
                        val fresh = bootstrapInterests?.withCatalogBootstrapGuard { interests ->
                            val progress = proposalProgress
                            if (interests == null || interests.any { it.originTopicId != null } || checkpoint == null ||
                                labels == null || retrievals == null || progress == null || checkpoint.bytes() != 0L ||
                                labels.bytes() != 0L || retrievals.bytes() != 0L || !progress.hasOnlySourceDeltas() ||
                                cache.revision.value != cacheRevision) return@withCatalogBootstrapGuard null
                            // No catalog or topic-derived state exists. Retire any pending withdrawals
                            // only after source deltas have been reconciled and other consumers permit it.
                            val events = cache.invalidations.value
                            if (events.isNotEmpty()) {
                                val withdrawn = events.mapNotNull { it.pageId?.let(::canonicalSemanticId) }.toSet()
                                val eligible = if (events.any { it.pageId == null }) emptyMap() else sources.filterKeys { it !in withdrawn }
                                progress.reconcile(eligible)
                                if (cache.revision.value != cacheRevision) return@withCatalogBootstrapGuard null
                                events.maxOfOrNull { it.generation }?.let {
                                    cache.acknowledgeInvalidations(minOf(it, invalidationAcknowledgementLimit()))
                                }
                            }
                            val model = identity()
                            fun current() = cache.revision.value == cacheRevision && cache.invalidations.value.isEmpty() && identity() == model
                            if (!current()) return@withCatalogBootstrapGuard null
                            val candidate = TopicSnapshot(identity = model, canonicalCatalogReady = true,
                                policyVersion = policy.version, connectionPassComplete = false)
                            if (store.initializeFresh(candidate, ::current) != TopicGuardedWriteResult.Written) return@withCatalogBootstrapGuard null
                            if (!current()) { store.clear(); return@withCatalogBootstrapGuard null }
                            candidate
                        }
                        if (fresh == null) {
                            needsMoreWork = true
                            failed("Topic catalog initialization needs recovery before discovery can continue.")
                            return@withLock
                        }
                        fresh
                    }
                    is TopicMetadataState.Available -> result.snapshot
                    else -> {
                        // Unknown state is not proof of an empty canonical catalog. On withdrawal,
                        // remove uninspectable derived evidence and leave the invalidation pending.
                        if (cache.invalidations.value.isNotEmpty()) {
                            proposalProgress?.reconcile(emptyMap())
                            checkpoint?.clear(); labels?.clear(); retrievals?.clear(); store.clear()
                        }
                        needsMoreWork = true
                        failed("Topic catalog needs recovery before discovery can continue.")
                        return@withLock
                    }
                }
            } else restoreStep("restoreMetadata") { store.readMetadata() } ?: TopicSnapshot()
            view = TopicView(latest, status = latest.message)
            loaded = true
        }
        store.pinInspection(view.snapshot)
        val events = cache.invalidations.value
        proposalProgress?.let { progress ->
            val withdrawn = events.mapNotNull { it.pageId?.let(::canonicalSemanticId) }.toSet()
            val eligible = if (events.any { it.pageId == null }) emptyMap() else sources.filterKeys { it !in withdrawn }
            progress.reconcile(eligible)
            // The source snapshot is no longer authoritative; retry before acknowledging any event.
            if (cache.revision.value != cacheRevision) return@withLock
        }
        val evidence = (latest.topics + latest.provisionalTopics + view.snapshot.topics + view.snapshot.provisionalTopics)
            .asSequence().flatMap { it.members.asSequence() } +
            (latest.connections.asSequence() + view.snapshot.connections.asSequence()).flatMap { sequenceOf(it.left, it.right) }
        val relationships = latest.subjectRelationships + view.snapshot.subjectRelationships
        val conceptSources = (latest.topics + latest.provisionalTopics + view.snapshot.topics + view.snapshot.provisionalTopics)
            .flatMap { it.canonicalConcept?.offered.orEmpty() }
        val tracked = listOf(latest, view.snapshot)
        val known = evidence.map { it.pageId }.toSet() + relationships.map { it.evidence.pageId } +
            tracked.flatMap { it.acceptedRevisions.keys + it.referenceTitles.keys } + conceptSources.map { it.pageId }
        val stale = evidence.filter { latest.identity != identity() || sources[it.pageId] != it.revision }.map { it.pageId }.toSet() +
            (known - sources.keys) + conceptSources.filter { latest.identity != identity() || sources[it.pageId] != it.revision }.map { it.pageId } + relationships.filter { latest.identity != identity() ||
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
        if (requireKnownCatalogOnRestore && (stale.isNotEmpty() || events.isNotEmpty())) {
            next = next.copy(connectionPassComplete = false, connectionWork = next.connectionWork.copy(incrementalKey = null))
            shown = shown.copy(connectionPassComplete = false, connectionWork = shown.connectionWork.copy(incrementalKey = null))
            needsMoreWork = true
        }
        suspend fun withdrawNames(snapshot: TopicSnapshot) {
            if (events.any { it.reason == CacheRemovalReason.RESET }) {
                labels?.clear()
                retrievals?.clear()
            } else {
                labels?.withdraw(snapshot)
                // Graph persistence omits generated overlays; restore valid concepts before pruning queries.
                val named = labels?.restore(snapshot, { cache.revision.value == cacheRevision }) ?: snapshot
                retrievals?.withdraw(named)
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
            // A failed graph write must not skip removal of private derived names.
            withdrawNames(next)
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
        } else withdrawNames(latest)
        // Migrate the known legacy catalog only after stale sources and pending withdrawals
        // have been removed. Newly indexed sources are accepted separately in bounded work.
        if (requireKnownCatalogOnRestore && !latest.canonicalCatalogReady && latest.complete &&
            latest.formationProgress == null && latest.provisionalTopics.isEmpty() && latest.identity == identity() &&
            latest.acceptedRevisions.all { (id, revision) -> sources[id] == revision } && cache.invalidations.value.isEmpty()) {
            val previous = latest
            val queryTicket = retrievals?.queryTicket()
            if (queryTicket == null) {
                needsMoreWork = true; failed("Topic catalog migration needs available queries. Retry processing."); return@withLock
            }
            val ticket = TopicCatalogTicket(previous, epoch.get(), cacheRevision, identity(), queryTicket)
            fun current() = isCatalogTicketCurrent(ticket)
            val migrated = previous.copy(canonicalCatalogReady = true)
            val saved = store.writeGuarded(migrated, ::current)
            if (saved != TopicGuardedWriteResult.Written) {
                needsMoreWork = true; failed("Topic catalog migration is incomplete. Retry processing."); return@withLock
            }
            if (!current()) { rollbackAfterStaleWrite(previous, ticket); return@withLock }
            latest = migrated
            epoch.incrementAndGet()
            if (!inspected) { shown = migrated; view = view.copy(snapshot = migrated) }
            changed(view)
        }
        val restoreEpoch = epoch.get()
        val beforeRestore = latest
        fun current() = epoch.get() == restoreEpoch && cache.revision.value == cacheRevision
        latest = labels?.restore(latest, ::current) ?: latest
        shown = labels?.restore(shown, ::current, readSaved = restoring || !inspected) ?: shown
        latest = retrievals?.restore(latest, sources, current = ::current) ?: latest
        shown = retrievals?.restore(shown, sources, readSaved = restoring || !inspected, current = ::current) ?: shown
        suspend fun hydrate(snapshot: TopicSnapshot): TopicSnapshot {
            val progress = proposalProgress ?: return snapshot
            val guarded = object : SemanticIndexStore by index {
                override suspend fun read(pageId: String): SemanticRecord? = readRecord(pageId)
            }
            val topics = snapshot.topics.map { topic ->
                if (topic.canonicalConcept == null) topic else
                    hydrateCanonicalTopic(topic, identity(), sources, guarded, progress, ::current)
                        ?: topic.copy(canonicalProjection = null)
            }
            return snapshot.copy(topics = topics)
        }
        latest = hydrate(latest).let { if (it == beforeRestore) beforeRestore else it }
        shown = hydrate(shown)
        naming = labels?.status(latest)
        namingStatus = namingMessage(naming, labels?.unavailableMessage?.invoke())
        if (latest.complete) needsMoreWork = !latest.connectionPassComplete || naming == TopicNamingStatus.Pending ||
            retrievals != null && latest.topics.any { it.currentGeneratedLabel != null && it.currentTopicMatches == null }
        view = view.copy(snapshot = shown, naming = naming, status = view.failure ?: namingStatus ?: latest.message)
        store.pinInspection(view.snapshot)
        restoreStep("restorePublication") { changed(view) }
    }

    suspend fun build(sources: Map<String, String>, metadataVersion: Int, cacheRevision: Long, indexComplete: Boolean = true,
        metadata: Map<String, SemanticMetadata>? = null) {
        if (cache.revision.value != cacheRevision || !mayWork()) return
        if (requireKnownCatalogOnRestore) {
            if (!indexComplete) return
            acceptIndexedSources(sources, cacheRevision)
            val input = metadata?.takeIf { it.keys == sources.keys }?.let { ConnectionInputs(cacheRevision, sources.toMap(), it.toMap()) }
            connectionInputs = input
            if (input != null && latest.canonicalCatalogReady && latest.acceptedRevisions == sources) {
                val hashes = sources.mapValues { (id, revision) -> connectionInputHash(revision, input.metadata.getValue(id)) }
                if (latest.connectionWork.inputs != hashes) needsMoreWork = true
            }
            return
        }
        buildGeneration(sources, metadataVersion, cacheRevision)
    }

    /** Check newly indexed revisions in bounded windows before accepting them into the catalog. */
    private suspend fun acceptIndexedSources(sources: Map<String, String>, cacheRevision: Long) = writes.withLock {
        if (!loaded || !latest.canonicalCatalogReady) { sourceAcceptance = null; sourceAcceptancePending = false; return@withLock }
        if (cache.invalidations.value.isNotEmpty()) return@withLock
        val model = identity()
        if (latest.identity != model) {
            sourceAcceptance = null; sourceAcceptancePending = false
            failed("The topic catalog needs a compatible embedding model. Retry processing."); return@withLock
        }
        if (cache.revision.value != cacheRevision) { sourceAcceptance = null; sourceAcceptancePending = false; return@withLock }
        val snapshot = latest
        val pending = sources.toSortedMap().filter { (id, revision) -> snapshot.acceptedRevisions[id] != revision }.keys.toList()
        if (snapshot.acceptedRevisions == sources) { sourceAcceptance = null; sourceAcceptancePending = false; return@withLock }
        // Withdrawal must be reconciled first; accepting an inventory must never resurrect it.
        if (snapshot.acceptedRevisions.any { (id, revision) -> sources[id] != revision }) {
            sourceAcceptancePending = false; failed("Topic sources need recovery before discovery can continue."); return@withLock
        }
        val queryTicket = retrievals?.queryTicket() ?: run {
            sourceAcceptancePending = false; failed("Topic queries are unavailable. Retry processing."); return@withLock
        }
        val ticket = TopicCatalogTicket(snapshot, epoch.get(), cacheRevision, model, queryTicket)
        fun current() = isCatalogTicketCurrent(ticket) && mayWork() && !shouldYield()
        if (!current()) return@withLock
        val fields = listOf("canonical-source-acceptance-v1", cacheRevision.toString(), ticket.epoch.toString(), model.toString()) +
            sources.toSortedMap().flatMap { listOf(it.key, it.value) }
        val scope = semanticHash(fields.joinToString("") { "${it.length}:$it" })
        val cursor = sourceAcceptance?.takeIf { it.scope == scope }?.lastId
        val work = pending.filter { cursor == null || it > cursor }.take(8)
        sourceAcceptancePending = true
        needsMoreWork = true
        for (id in work) {
            val record = readRecord(id)
            if (!current()) return@withLock
            if (record == null || canonicalSemanticId(record.pageId) != id || record.revision != sources[id] || record.identity != model) {
                sourceAcceptance = null; sourceAcceptancePending = false
                failed("Some indexed Notes need recovery before discovery can continue.")
                return@withLock
            }
            sourceAcceptance = SourceAcceptance(scope, id)
        }
        if (work.size < pending.count { cursor == null || it > cursor }) return@withLock
        val topics = snapshot.topics.map { topic ->
            val concept = topic.canonicalConcept
            if (concept == null || !concept.matchingComplete || concept.scannedRevisions == sources) topic
            else topic.copy(canonicalConcept = concept.copy(matchingComplete = false),
                canonicalProjection = topic.canonicalProjection?.copy(members = null))
        }
        val next = snapshot.copy(topics = topics, acceptedRevisions = sources, complete = false,
            connectionPassComplete = false, sourceSignature = null, connectionWork = snapshot.connectionWork.copy(incrementalKey = null),
            generation = snapshot.generation + 1)
        val result = bulk { store.writeGuarded(next, ::current) }
        if (result == TopicGuardedWriteResult.Stale) return@withLock
        if (result is TopicGuardedWriteResult.Rejected) {
            sourceAcceptancePending = false
            failed(persistenceFailureMessage(result.result)); return@withLock
        }
        if (!current()) { rollbackAfterStaleWrite(snapshot, ticket); return@withLock }
        latest = next; epoch.incrementAndGet(); sourceAcceptance = null; sourceAcceptancePending = false
        view = if (inspected) view.copy(updated = true) else view.copy(snapshot = next)
        store.pinInspection(view.snapshot)
        changed(view)
    }
    /** Resume connection evidence independently of topic formation, after foreground topic work. */
    suspend fun advanceIncrementalConnections(ticket: TopicCatalogTicket, sources: Map<String, String>): IncrementalConnectionsResult {
        if (!requireKnownCatalogOnRestore || !isCatalogTicketCurrent(ticket) || ticket.snapshot.acceptedRevisions != sources)
            return IncrementalConnectionsResult.Deferred
        if (!mayWork() || shouldYield()) return IncrementalConnectionsResult.Deferred
        val snapshot = ticket.snapshot
        val observedInputs = connectionInputs
        val capturedInputs = observedInputs?.takeIf { it.cacheRevision == ticket.cacheRevision && it.sources == sources }
        val documents = cache.snapshot()
        if (!isCatalogTicketCurrent(ticket) || connectionInputs != observedInputs) return IncrementalConnectionsResult.Deferred
        val actual = documents.associate { canonicalSemanticId(it.document.pageId) to semanticRevision(it.document) }
        if (actual.size != documents.size || actual != sources) return IncrementalConnectionsResult.Deferred
        val metadata = capturedInputs?.metadata ?: documents.associate {
            canonicalSemanticId(it.document.pageId) to SemanticMetadata(it.document.title)
        }
        val titles = metadata.mapValues { it.value.title }
        val inputs = sources.mapValues { (id, revision) -> connectionInputHash(revision, metadata.getValue(id)) }
        val titleKeys = titles.mapValues { connectionTitleKey(it.value) }
        if (snapshot.connectionPassComplete && snapshot.connectionWork.inputs == inputs &&
            snapshot.connectionWork.titleKeys == titleKeys && snapshot.policyVersion == policy.version) {
            needsMoreWork = false
            return IncrementalConnectionsResult.Complete
        }
        val fields = listOf("incremental-connections-v2", ticket.identity.toString(), policy.toString()) +
            sources.toSortedMap().flatMap { listOf(it.key, it.value, inputs.getValue(it.key), titles.getValue(it.key)) }
        val key = semanticHash(fields.joinToString("") { "${it.length}:$it" })
        val resume = snapshot.connectionWork.incrementalKey == key
        val compatible = snapshot.identity == ticket.identity && snapshot.policyVersion == policy.version && snapshot.connectionWork.inputs.isNotEmpty()
        val previousCounts = snapshot.connectionWork.titleKeys.values.groupingBy { it }.eachCount()
        val currentCounts = titleKeys.values.groupingBy { it }.eachCount()
        val changedTitles = (previousCounts.keys + currentCounts.keys).filterTo(hashSetOf()) { previousCounts[it] != currentCounts[it] }
        val changedIds = if (resume) snapshot.connectionWork.changedIds else if (!compatible) sources.keys else
            sources.keys.filterTo(hashSetOf()) { snapshot.connectionWork.inputs[it] != inputs[it] || titleKeys[it] in changedTitles }
        val unfinished = if (resume) snapshot.connectionWork.unfinishedSeedIds else if (compatible && !snapshot.connectionPassComplete)
            snapshot.connectionWork.inputs.keys.sorted().drop(snapshot.connectionCursor).filterTo(hashSetOf()) { it in sources } else emptySet()
        val plan = ConnectionWork(inputs, titleKeys, changedIds, unfinished, key)
        connectionCursor = if (resume) snapshot.connectionCursor else 0
        connectionPassageCursor = if (resume) snapshot.connectionPassageCursor else 0
        connectionTargetCursor = if (resume) snapshot.connectionTargetCursor else 0
        val connected = try { connections(snapshot, sources, ticket.identity, ticket.epoch, ticket.cacheRevision, titles, plan, compatible, strictSources = true, expectedMetadata = capturedInputs?.metadata) }
            catch (_: WorkPaused) { return IncrementalConnectionsResult.Deferred }
            catch (_: ConnectionSourceUnavailable) { failed("Some indexed Notes are unavailable for connections. Retry processing."); return IncrementalConnectionsResult.Failed }
            catch (_: java.io.IOException) { failed("Stored connections are unavailable. Retry processing."); return IncrementalConnectionsResult.Failed }
        return writes.withLock {
            fun current() = isCatalogTicketCurrent(ticket) && mayWork() && !shouldYield() &&
                connectionInputs == observedInputs
            if (!current()) return@withLock IncrementalConnectionsResult.Deferred
            val complete = connectionCursor >= sources.size
            val next = snapshot.copy(connections = connected, catalogReference = null, referenceTitles = titles,
                connectionCursor = connectionCursor, connectionPassageCursor = connectionPassageCursor,
                connectionTargetCursor = connectionTargetCursor, connectionTargetCount = sources.size,
                connectionPassComplete = complete, policyVersion = policy.version, generation = snapshot.generation + 1,
                connectionWork = if (complete) plan.copy(changedIds = emptySet(), unfinishedSeedIds = emptySet()) else plan)
            val result = bulk { store.writeGuarded(next, ::current) }
            if (result == TopicGuardedWriteResult.Stale) return@withLock IncrementalConnectionsResult.Deferred
            if (result is TopicGuardedWriteResult.Rejected) {
                failed(persistenceFailureMessage(result.result)); return@withLock IncrementalConnectionsResult.Failed
            }
            if (!current()) { rollbackAfterStaleWrite(snapshot, ticket); return@withLock IncrementalConnectionsResult.Deferred }
            val persisted = store.readMetadata() ?: run { failed("Stored connections could not be reloaded. Retry processing."); return@withLock IncrementalConnectionsResult.Failed }
            if (!current()) { rollbackAfterStaleWrite(snapshot, ticket); return@withLock IncrementalConnectionsResult.Deferred }
            latest = next.copy(connections = persisted.connections, catalogReference = persisted.catalogReference)
            needsMoreWork = !complete
            view = if (inspected) view.copy(updated = true) else view.copy(snapshot = latest, failure = null)
            store.pinInspection(view.snapshot)
            changed(view)
            if (complete) IncrementalConnectionsResult.Updated else IncrementalConnectionsResult.Pending
        }
    }

    /** Formation is bounded by comparisons, never by admitted source sections. */
    private suspend fun buildGeneration(sources: Map<String, String>, metadataVersion: Int,
        cacheRevision: Long) {
        val model = identity()
        val token = topicSourceSignature(model, policy, metadataVersion, sources)
        if (latest.sourceSignature == token && latest.complete && latest.connectionPassComplete) {
            formationWorkspace = null
            needsMoreWork = false
            labelGeneration(cacheRevision)
            return
        }
        val startEpoch = epoch.get()
        val context = currentCoroutineContext()
        val resume = (pendingFormation ?: checkpoint?.read())?.takeIf { it.sourceSignature == token && it.identity == model &&
            it.policyVersion == policy.version && it.engineVersion == SectionHierarchicalEngine.VERSION &&
            (it.formationProgress != null || it.complete) }?.let { withContinuityAnchors(it, latest) }
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
                retainPendingWork(startEpoch)
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
        val semantic = try { connections(latest, sources, model, startEpoch, cacheRevision, referenceTitles, connectionPlan, compatibleConnections) }
            catch (_: WorkPaused) { retainPendingWork(startEpoch); return }
            catch (_: java.io.IOException) {
                if (epoch.get() != startEpoch || cache.revision.value != cacheRevision) { retainPendingWork(startEpoch); return }
                needsMoreWork = false
                failed("Stored connections are unavailable. Retry processing."); return
            }
        val connected = semantic
        val result = retainCanonicalConcepts(latest, grouped).copy(connections = connected, catalogReference = null, referenceTitles = referenceTitles,
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
            view = if (initial || !inspected) TopicView(latest, status = result.message)
                else view.copy(updated = visibleChange, status = result.message, failure = null)
            store.pinInspection(view.snapshot)
            changed(view)
        }
        labelGeneration(cacheRevision)
    }

    /** Native inference never holds writes: source withdrawal must remain immediate. */
    private suspend fun labelGeneration(cacheRevision: Long) {
        val labeler = labels ?: return
        val startEpoch = epoch.get()
        val model = identity()
        val base = latest
        if (!base.complete) return
        fun current() = startEpoch == epoch.get() && cache.revision.value == cacheRevision && identity() == model &&
            latest.sourceSignature == base.sourceSignature && latest.generation == base.generation
        suspend fun publish(snapshot: TopicSnapshot) = writes.withLock {
            if (!current()) return@withLock
            latest = snapshot
            val changedNames = view.snapshot.topics != snapshot.topics
            view = if (inspected) view.copy(updated = view.updated || changedNames, status = "Naming topics…", failure = null, naming = TopicNamingStatus.Running)
                else TopicView(snapshot, status = "Naming topics…", naming = TopicNamingStatus.Running)
            changed(view)
        }
        if (labeler.status(base) == TopicNamingStatus.Pending) {
            needsMoreWork = true
            publish(base)
        }
        val result = labeler.process(base, startEpoch, ::current, ::publish)
        if (!current() || result.stale) return
        val matches = retrievals?.process(result.snapshot, base.acceptedRevisions, ::current)
        writes.withLock {
            if (!current() || result.stale || matches?.stale == true) return@withLock
            latest = matches?.snapshot ?: result.snapshot
            needsMoreWork = !latest.connectionPassComplete || result.pending || matches?.pending == true
            naming = when {
                result.unavailable -> TopicNamingStatus.Unavailable
                result.pending -> TopicNamingStatus.Pending
                else -> TopicNamingStatus.Ready
            }
            val failure = result.failure ?: matches?.failure
            namingStatus = failure ?: if (matches?.pending == true) "Matching Notes to topics…" else namingMessage(naming, labeler.unavailableMessage())
            view = if (inspected) view.copy(updated = view.updated || view.snapshot.topics != latest.topics,
                status = namingStatus ?: latest.message, failure = failure, naming = naming)
                else TopicView(latest, status = namingStatus ?: latest.message, failure = failure, naming = naming)
            changed(view)
        }
    }
    private suspend fun connections(baseSnapshot: TopicSnapshot, sources: Map<String, String>, model: EmbeddingIdentity,
        startEpoch: Long, cacheRevision: Long, titles: Map<String, String>, work: ConnectionWork, compatible: Boolean,
        strictSources: Boolean = false, expectedMetadata: Map<String, SemanticMetadata>? = null): List<NoteConnection> {
        val ids = sources.keys.sorted()
        suspend fun validRecord(id: String): SemanticRecord? {
            val record = readRecord(id)?.takeIf { it.identity == model && sources[id] == it.revision && canonicalSemanticId(it.pageId) == id &&
                (expectedMetadata == null || expectedMetadata[id] == it.metadata) }
            if (record == null && strictSources) throw ConnectionSourceUnavailable()
            return record
        }
        if (ids.size < 2) {
            if (strictSources) ids.forEach { validRecord(it) }
            connectionCursor = ids.size; connectionPassageCursor = 0; connectionTargetCursor = 0
            return emptyList()
        }
        val context = currentCoroutineContext()
        fun checkActive() {
            context.ensureActive()
            if (!mayWork() || shouldYield() || epoch.get() != startEpoch || cache.revision.value != cacheRevision) throw WorkPaused()
        }
        val references = ExplicitNoteReferenceIndex(titles)
        val previous = bulk { store.connectionCatalog(baseSnapshot) }
        val staleInputs = sources.keys.filterTo(hashSetOf()) { id -> !compatible || work.inputs[id] == null ||
            baseSnapshot.connectionWork.inputs[id] != work.inputs[id] }
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
            val seed = validRecord(seedId)
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
                    val other = validRecord(id) ?: continue
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
        synchronized(workspaceLock) {
            epoch.incrementAndGet()
            needsMoreWork = false
            formationWorkspace = null
        }
        sourceAcceptance = null; sourceAcceptancePending = false; connectionInputs = null
        store.clear(); checkpoint?.clear(); labels?.clear(); retrievals?.clear(); namingStatus = null; naming = null; pendingFormation = null
        // An explicit index clear authorizes an empty catalog even when follows remain.
        // Persist it so a recreated coordinator can resume after the user requests rebuilding.
        val empty = if (requireKnownCatalogOnRestore) TopicSnapshot(identity = identity(), canonicalCatalogReady = true,
            policyVersion = policy.version, connectionPassComplete = false) else TopicSnapshot()
        val result = if (requireKnownCatalogOnRestore) store.writeDetailed(empty) else TopicWriteResult.Written
        val failure = (result as? TopicWriteResult.Rejected)?.let(::persistenceFailureMessage)
        latest = if (failure == null) empty else TopicSnapshot()
        loaded = failure == null
        needsMoreWork = failure != null
        view = TopicView(latest, status = failure, failure = failure); changed(view)
        failure
    }
    suspend fun bytes(): Long = store.bytes() + (checkpoint?.bytes() ?: 0) + (labels?.bytes() ?: 0) + (retrievals?.bytes() ?: 0)

    /** Drop the sole retained generation. In-flight locals observe the epoch change and unwind cooperatively. */
    fun releaseMemory() = synchronized(workspaceLock) {
        epoch.incrementAndGet()
        formationWorkspace = null
        if (checkpoint != null) pendingFormation = null
    }
}

private fun namingMessage(status: TopicNamingStatus?, unavailableMessage: String? = null): String? = when (status) {
    TopicNamingStatus.Unavailable -> unavailableMessage ?: "Topic naming is unavailable. Install the topic naming model in Discovery settings."
    TopicNamingStatus.Pending -> "Topic naming is pending."
    TopicNamingStatus.Running -> "Naming topics…"
    else -> null
}

/** Recovery descriptions contain structural categories only, never source evidence. */
internal fun persistenceFailureMessage(result: TopicWriteResult.Rejected, checkpoint: Boolean = false): String {
    val subject = if (checkpoint) "Topic checkpoint" else "Topic"
    if (result.validationReason == TopicValidationReason.ContinuityBound)
        return "$subject reached the continuity memory limit; discovery coverage is incomplete. Previous discoveries remain available."
    return when (result.failure) {
        TopicWriteFailure.BudgetExceeded -> "$subject storage is full; discovery coverage is incomplete. Free derived storage and retry."
        TopicWriteFailure.RecordMemoryLimit -> "$subject exceeds the safe record memory limit; discovery coverage is incomplete. Previous discoveries remain available."
        TopicWriteFailure.InvalidSnapshot -> "$subject validation failed; previous discoveries remain available. Retry processing."
        TopicWriteFailure.IoFailure -> "$subject I/O failure; previous discoveries remain available. Retry saving."
        TopicWriteFailure.Unknown -> "$subject could not be saved for an unknown reason; previous discoveries remain available. Retry saving."
    }
}
