package com.example.notionreader.application

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retrieval and derived-data lifecycle have no reference to the listening session. */
class DiscoveryController(
    private val cache: DocumentCache,
    private val index: SemanticIndexStore,
    private val interestStore: DiscoveryInterestStore,
    private val provider: EmbeddingProvider,
    private val modelAvailable: () -> Boolean = { true },
    private val recommendationThreshold: Float = .5f,
    private val clock: () -> Instant = { Instant.now() },
    private val queryTimings: (Map<String, Long>) -> Unit = {},
    private val topicStore: TopicStore? = null,
    private val topicCheckpointStore: TopicStore? = null,
    private val topicPolicy: TopicPolicy = TopicPolicy(),
    private val mayContinueIndexing: () -> Boolean = { true },
    private val invalidationAcknowledgementLimit: () -> Long = { Long.MAX_VALUE },
    private val formationWorkBudget: Int = 250_000,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val passageProgressStore: SemanticIndexStore? = null,
    topicLabelRuntime: TopicLabelRuntime? = null,
    topicLabelModelAvailable: () -> Boolean = { false },
    topicLabelStore: TopicLabelStore? = null,
    topicLabelIdentity: () -> TopicLabelIdentity? = { null },
    topicLabelUnavailableMessage: () -> String = { "Topic naming is unavailable. Install the topic naming model in Discovery settings." },
    topicRetrievalStore: TopicRetrievalStore? = null,
    private val topicRetrievalPolicy: TopicRetrievalPolicy = TopicRetrievalPolicy(.85f),
    private val proposalProgressStore: ProposalProgressStore? = null,
    private val topicSupportIdentity: () -> TopicLabelIdentity? = { null },
    private val topicProposalIdentity: () -> TopicLabelIdentity? = { null }
) {
    private val mutable = MutableStateFlow(DiscoveryState())
    private val runtime = DiscoveryRuntime(cache, provider, topicLabelRuntime, topicLabelModelAvailable)
    fun updatePowerState(power: DiscoveryPowerState) {
        mutable.update { it.copy(power = power) }
        if (!mayContinueWork()) runtime.interruptLabeling()
    }
    fun updateWorkAvailability(effort: com.example.notionreader.domain.settings.DiscoveryEffort, availability: DiscoveryExecutionAvailability) {
        mutable.update { it.copy(effort = effort, executionAvailability = availability) }
        if (!mayContinueWork()) runtime.interruptLabeling()
    }
    private fun mayContinueWork() = !mutable.value.paused && mutable.value.power?.allows(mutable.value.effort) != false && mayContinueIndexing()
    val state = mutable.asStateFlow()
    val embeddingRuntime: kotlinx.coroutines.flow.StateFlow<EmbeddingRuntimeState> =
        (provider as? ObservableEmbeddingRuntime)?.runtimeState ?: MutableStateFlow(EmbeddingRuntimeState())
    val hasPendingWork: Boolean get() = indexing.reconciliationPending || topics?.needsMoreWork == true ||
        state.value.indexedCount < state.value.cachedPageIds.size || state.value.cachedRevision != cache.revision.value
    fun requestAutomaticContinuation() { wake.value++ }
    suspend fun releaseTopicLabelModel() { runtime.closeLabels(); requestAutomaticContinuation() }
    private val wake = MutableStateFlow(0L)
    val revision = wake.asStateFlow()
    private val retrieval = DiscoveryRetrieval(cache, index, provider, topicStore, runtime.validity, workDispatcher, ::recordTiming)
    private val connectionProjection = ConnectionCatalogProjection { snapshot, offset, limit ->
        retrieval.connectionWindow(snapshot, offset, limit)
    }
    private val topics = topicStore?.let { store -> TopicCoordinator(cache, index, store, topicCheckpointStore,
        { provider.identity }, topicPolicy, bulkGate = runtime.bulkGate, mayWork = ::mayContinueWork, waitForQueries = {
            while (runtime.foregroundQueries.get() > 0) delay(10)
            currentCoroutineContext().ensureActive()
        }, changed = { view -> mutable.update {
            // Publication belongs to the background runtime.generation, never to detail navigation.
            val published = if (it.topicSnapshot.topics == view.snapshot.topics) it.publishedTopics
                else publishableDiscoveryTopics(view.snapshot.topics).mapNotNull { topic ->
                    discoveryTopicPublicationStatus(topic)?.let { status -> PublishedTopic(topic, status) }
                }
            it.copy(topicSnapshot = view.snapshot, publishedTopics = published,
                topicsUpdated = view.updated, topicStatus = view.status, topicFailure = view.failure,
                topicNaming = view.naming, workPending = hasPendingWork)
        } },
        invalidationAcknowledgementLimit = invalidationAcknowledgementLimit,
        formationWorkBudget = formationWorkBudget,
        shouldYield = { runtime.foregroundQueries.get() > 0 }, operationTimings = ::recordTiming,
        labels = topicLabelStore?.let { labelStore -> TopicLabelCoordinator(labelStore, topicLabelIdentity,
            topicLabelModelAvailable, { request -> runtime.labelTopic(request, ::mayContinueWork) }, ::mayContinueWork,
            waitForQueries = {
                while (runtime.foregroundQueries.get() > 0) delay(10)
                currentCoroutineContext().ensureActive()
            }, unavailableMessage = topicLabelUnavailableMessage) },
        retrievals = topicRetrievalStore?.let { matches -> TopicRetrievalCoordinator(index, matches,
            { provider.identity }, topicRetrievalPolicy, embed = { text ->
                runtime.bulkStep { runtime.inference.withLock { runtime.embedExactInput(text) } }
            }, mayWork = ::mayContinueWork, waitForQueries = {
                while (runtime.foregroundQueries.get() > 0) delay(10)
                currentCoroutineContext().ensureActive()
            }, bulkGate = runtime.bulkGate) }, proposalProgress = proposalProgressStore) }

    private val interests = DiscoveryInterests(cache, interestStore, provider, mutable, runtime, retrieval,
        workDispatcher, modelAvailable, recommendationThreshold, clock)
    private val indexing = DiscoveryIndexing(cache, index, passageProgressStore, interestStore, provider,
        mutable, runtime, topics, workDispatcher, modelAvailable, ::mayContinueWork, ::pause,
        { wake.value++ }, monotonicNanos, proposalProgressStore)
    private val previews = DiscoveryPreviews(mutable, retrieval)
    private val search = DiscoverySearch(runtime, retrieval, mutable, workDispatcher, modelAvailable,
        recommendationThreshold, ::recordTiming)

    internal suspend fun advanceExistingConceptWorkForNote(pageId: String, limit: Int, restart: Boolean = false): DurableConceptScanResult =
        withContext(workDispatcher) {
            val owner = topics ?: return@withContext DurableConceptScanResult.Incomplete
            if (!mayContinueWork()) return@withContext DurableConceptScanResult.Deferred
            val ticket = owner.captureCatalogTicket() ?: return@withContext DurableConceptScanResult.Incomplete
            val verifier = topicSupportIdentity() ?: return@withContext DurableConceptScanResult.Incomplete
            val sources = runtime.validity.eligibleRevisions()
            if (!owner.isCatalogTicketCurrent(ticket) || topicSupportIdentity() != verifier) return@withContext DurableConceptScanResult.Stale
            val record = runtime.bulkStep { index.read(canonicalSemanticId(pageId)) } ?: return@withContext DurableConceptScanResult.Incomplete
            if (!owner.isCatalogTicketCurrent(ticket) || topicSupportIdentity() != verifier) return@withContext DurableConceptScanResult.Stale
            owner.advanceExistingConceptWork(ticket, record, sources, verifier, limit, restart,
                verifierCurrent = { topicSupportIdentity() == verifier }) { request ->
                runtime.verifyTopicSupport(request) {
                    mayContinueWork() && owner.isCatalogTicketCurrent(ticket) && topicSupportIdentity() == verifier
                }
            }
        }

    internal suspend fun replayExistingConceptWorkForNote(pageId: String, saved: ExistingConceptScanRecord,
        replay: ConceptScanReplay, limit: Int): ConceptScanReplayResult = withContext(workDispatcher) {
        val owner = topics ?: return@withContext ConceptScanReplayResult.Incomplete
        if (!mayContinueWork()) return@withContext ConceptScanReplayResult.Deferred
        val ticket = owner.captureCatalogTicket() ?: return@withContext ConceptScanReplayResult.Incomplete
        val verifier = topicSupportIdentity() ?: return@withContext ConceptScanReplayResult.Incomplete
        val sources = runtime.validity.eligibleRevisions()
        if (!owner.isCatalogTicketCurrent(ticket) || topicSupportIdentity() != verifier) return@withContext ConceptScanReplayResult.Stale
        val record = runtime.bulkStep { index.read(canonicalSemanticId(pageId)) } ?: return@withContext ConceptScanReplayResult.Incomplete
        if (!owner.isCatalogTicketCurrent(ticket) || topicSupportIdentity() != verifier) return@withContext ConceptScanReplayResult.Stale
        owner.replayExistingConceptWork(ticket, record, sources, verifier, saved, replay, limit) { topicSupportIdentity() == verifier }
    }

    /** Rechecks selected citations against live state; this alone does not authorize publication. */
    internal suspend fun revalidatePendingCandidate(candidate: PendingConceptCandidate): PendingConceptCoverage = withContext(workDispatcher) {
        val owner = topics ?: return@withContext PendingConceptCoverage.Unavailable
        val ticket = candidate.catalogTicket ?: return@withContext PendingConceptCoverage.Unavailable
        val store = proposalProgressStore ?: return@withContext PendingConceptCoverage.Unavailable
        val token = runtime.validity.capture()
        val indexedRevision = mutable.value.indexedRevision
        fun current() = owner.isCatalogTicketCurrent(ticket) && runtime.validity.isCurrent(token) &&
            mutable.value.indexedRevision == indexedRevision && topicProposalIdentity() == candidate.generator &&
            topicSupportIdentity() == candidate.verifier && candidate.embedding == ticket.identity
        if (!current()) return@withContext PendingConceptCoverage.Stale
        val sources = runtime.validity.eligibleRevisions()
        if (sources != candidate.sources || !current()) return@withContext PendingConceptCoverage.Stale
        val concept = candidate.generation.response as? DiscoveryProposalResponse.Concept
            ?: return@withContext PendingConceptCoverage.Invalid
        val expectedQuery = TopicRetrievalQuery("pending:${candidate.generation.workKey}", candidate.generation.workKey,
            "${concept.name.trim()}\n${concept.definition.trim()}", candidate.embedding, topicRetrievalPolicy)
        if (candidate.query.query != expectedQuery) return@withContext PendingConceptCoverage.Invalid
        val guardedIndex = object : SemanticIndexStore by index {
            override suspend fun read(pageId: String): SemanticRecord? = runtime.bulkStep { index.read(pageId) }
        }
        val coverage = readPendingConceptCoverage(candidate.proposal, candidate.generation, guardedIndex,
            candidate.embedding, candidate.generator, candidate.verifier, ticket.epoch, sources, store, ::current)
        if (!current()) return@withContext PendingConceptCoverage.Stale
        if (coverage is PendingConceptCoverage.Complete && coverage.assessments != candidate.citations)
            return@withContext PendingConceptCoverage.Invalid
        coverage
    }

    /** Runs bounded existing steps inside the caller's process-owned work window. */
    internal suspend fun advancePendingCorpusWindow(initial: PendingCorpusWork, maxSteps: Int,
        maxRecords: Int = 8, shouldContinue: () -> Boolean = { true }): PendingCorpusWorkResult = withContext(workDispatcher) {
        require(maxSteps in 1..128 && maxRecords in 1..128)
        var work = initial
        repeat(maxSteps) {
            currentCoroutineContext().ensureActive()
            if (!shouldContinue()) return@withContext PendingCorpusWorkResult.Pending(work)
            if (!mayContinueWork() || runtime.foregroundQueries.get() > 0)
                return@withContext PendingCorpusWorkResult.Stopped(EvidenceFirstNoteStep.Deferred, work)
            when (val result = advancePendingCorpusWork(work, maxRecords, shouldContinue)) {
                is PendingCorpusWorkResult.Pending -> work = result.work
                else -> return@withContext result
            }
            kotlinx.coroutines.yield()
        }
        PendingCorpusWorkResult.Pending(work)
    }
    /** Advance at most one existing bounded operation; the caller owns scheduling and retries. */
    internal suspend fun advancePendingCorpusWork(work: PendingCorpusWork, maxRecords: Int = 8,
        windowContinue: () -> Boolean = { true }): PendingCorpusWorkResult {
        val result = when (work.phase) {
            PendingCorpusWorkPhase.CORPUS -> advanceEvidenceFirstNote(work.pageId, replay = work.existingReplay,
                generateIndex = work.proposalIndex, expectedGenerationKey = work.generationKey, maxRecords = maxRecords, windowContinue = windowContinue, knownCatalogScan = work.knownCatalogScan,
                explicitPageIds = work.explicitPageIds, structureComplete = work.structureComplete, anchorOffset = work.anchorOffset, retrievePageId = work.pageId, corpusReplay = work.corpus)
            PendingCorpusWorkPhase.ASSESS_CITATION -> advanceEvidenceFirstNote(work.pageId, replay = work.existingReplay,
                generateIndex = work.proposalIndex, expectedGenerationKey = work.generationKey, maxRecords = maxRecords, windowContinue = windowContinue, knownCatalogScan = work.knownCatalogScan,
                explicitPageIds = work.explicitPageIds, structureComplete = work.structureComplete, anchorOffset = work.anchorOffset,
                assessEvidenceIndex = checkNotNull(work.citationIndex))
            PendingCorpusWorkPhase.REPLAY_NOTE -> advanceEvidenceFirstNote(work.pageId, replay = work.existingReplay,
                generateIndex = work.proposalIndex, expectedGenerationKey = work.generationKey, maxRecords = maxRecords, windowContinue = windowContinue, knownCatalogScan = work.knownCatalogScan,
                explicitPageIds = work.explicitPageIds, structureComplete = work.structureComplete, anchorOffset = work.anchorOffset, retrievePageId = checkNotNull(work.target),
                retrieveScan = work.note, replayPendingScan = true)
            PendingCorpusWorkPhase.SCAN_NOTE -> advanceEvidenceFirstNote(work.pageId, replay = work.existingReplay,
                generateIndex = work.proposalIndex, expectedGenerationKey = work.generationKey, maxRecords = maxRecords, windowContinue = windowContinue, knownCatalogScan = work.knownCatalogScan,
                explicitPageIds = work.explicitPageIds, structureComplete = work.structureComplete, anchorOffset = work.anchorOffset, retrievePageId = checkNotNull(work.target),
                retrieveScan = work.note, checkpointPendingScan = true)
        }
        if (result is EvidenceFirstNoteStep.PendingKnownCatalog)
            return PendingCorpusWorkResult.Pending(work.copy(knownCatalogScan = result.scan))
        if (result is EvidenceFirstNoteStep.PendingReplay)
            return PendingCorpusWorkResult.Pending(work.copy(existingReplay = result.replay))
        if (result == EvidenceFirstNoteStep.PendingScan) return PendingCorpusWorkResult.Pending(work)
        if (work.phase == PendingCorpusWorkPhase.REPLAY_NOTE && result == EvidenceFirstNoteStep.Incomplete)
            return PendingCorpusWorkResult.Pending(work.copy(phase = PendingCorpusWorkPhase.SCAN_NOTE, note = PendingConceptNoteScan.start()))
        when (result) {
            is EvidenceFirstNoteStep.Generated -> {
                val abstention = result.generation.response as? DiscoveryProposalResponse.Abstained
                if (abstention != null) {
                    val proposal = result.window.window.proposals.getOrNull(work.proposalIndex)
                        ?: return PendingCorpusWorkResult.Stopped(result, work)
                    return PendingCorpusWorkResult.Abstained(abstention.reason, proposal.incomplete, result.window.scanPending)
                }
            }
            is EvidenceFirstNoteStep.CitationCoverage -> {
                val pending = result.coverage as? PendingConceptCoverage.Pending
                val concept = result.generation.response as? DiscoveryProposalResponse.Concept
                val citation = concept?.evidenceIds?.indexOfFirst { it in pending?.missingEvidenceIds.orEmpty() } ?: -1
                if (citation >= 0) return PendingCorpusWorkResult.Pending(work.copy(phase = PendingCorpusWorkPhase.ASSESS_CITATION,
                    citationIndex = citation, generationKey = result.generation.workKey))
            }
            is EvidenceFirstNoteStep.Assessed -> {
                if (result.assessment.decision == SupportDecision.SUPPORTED)
                    return PendingCorpusWorkResult.Pending(work.copy(phase = PendingCorpusWorkPhase.CORPUS, citationIndex = null))
            }
            is EvidenceFirstNoteStep.CorpusReplay -> when (val replay = result.replay) {
                is PendingCorpusReplayResult.Pending -> return PendingCorpusWorkResult.Pending(work.copy(corpus = replay.replay, generationKey = result.candidate.generation.workKey))
                is PendingCorpusReplayResult.Incomplete -> return PendingCorpusWorkResult.Pending(work.copy(
                    phase = PendingCorpusWorkPhase.REPLAY_NOTE, corpus = replay.replay, target = replay.pageId, generationKey = result.candidate.generation.workKey,
                    note = PendingConceptNoteScan.start()))
                is PendingCorpusReplayResult.Complete, is PendingCorpusReplayResult.Uncertain -> {
                    val proposal = result.window.window.proposals.getOrNull(work.proposalIndex)
                        ?: return PendingCorpusWorkResult.Stopped(result, work)
                    return if (pendingCorpusIsIncomplete(replay, proposal.incomplete, result.window.scanPending))
                        PendingCorpusWorkResult.Incomplete(replay, result.candidate, proposal.incomplete, result.window.scanPending)
                    else PendingCorpusWorkResult.Done(replay, result.candidate)
                }
                else -> Unit
            }
            is EvidenceFirstNoteStep.PendingNoteReplay -> when (val replay = result.replay) {
                is PendingNoteReplayResult.Pending -> return PendingCorpusWorkResult.Pending(work.copy(note = replay.scan))
                is PendingNoteReplayResult.Validated -> return PendingCorpusWorkResult.Pending(work.copy(phase = PendingCorpusWorkPhase.SCAN_NOTE, note = replay.scan))
                is PendingNoteReplayResult.Complete, is PendingNoteReplayResult.Uncertain -> return PendingCorpusWorkResult.Pending(
                    work.copy(phase = PendingCorpusWorkPhase.CORPUS, target = null, note = PendingConceptNoteScan.start()))
                else -> Unit
            }
            is EvidenceFirstNoteStep.NoteRetrievalScan -> when (val scan = result.scan) {
                is PendingConceptNoteScanResult.Pending -> return PendingCorpusWorkResult.Pending(work.copy(note = scan.scan))
                is PendingConceptNoteScanResult.Complete, is PendingConceptNoteScanResult.Uncertain -> return PendingCorpusWorkResult.Pending(
                    work.copy(phase = PendingCorpusWorkPhase.CORPUS, target = null, note = PendingConceptNoteScan.start()))
                else -> Unit
            }
            else -> Unit
        }
        return PendingCorpusWorkResult.Stopped(result, work)
    }
    /** Shared bounded retrieval for complete and provisional concepts; caller validates citations first. */
    private suspend fun advancePendingRetrieval(proposal: IncrementalProposal, generated: ProposalGenerationResult.Completed,
        window: IncrementalProposalStepResult.Ready, concept: DiscoveryProposalResponse.Concept, citations: List<TopicSupportAssessment>, identity: EmbeddingIdentity,
        verifier: TopicLabelIdentity, generator: TopicLabelIdentity, catalogTicket: TopicCatalogTicket?, epoch: Long, sources: Map<String, String>, guardedIndex: SemanticIndexStore,
        store: ProposalProgressStore, originPageId: String, record: SemanticRecord, retrievePageId: String,
        retrieveCursor: PendingRetrievalCursor?, retrieveScan: PendingConceptNoteScan?, checkpointPendingScan: Boolean,
        replayPendingScan: Boolean, corpusReplay: PendingCorpusReplay?, scopeTicket: String, scopeAnchor: Any,
        maxRecords: Int, current: () -> Boolean, mayWork: () -> Boolean): EvidenceFirstNoteStep {
        val targetId = canonicalSemanticId(retrievePageId)
        if (sources[targetId] == null) return EvidenceFirstNoteStep.Stale
        val target = guardedIndex.read(targetId) ?: return EvidenceFirstNoteStep.Incomplete
        if (!current() || target.identity != identity || target.revision != sources[targetId])
            return EvidenceFirstNoteStep.Stale
        val dependencyGroups = proposal.evidence.groupBy { canonicalSemanticId(it.pageId) }
        if (dependencyGroups.any { (id, refs) -> id.isBlank() || refs.map { it.revision }.toSet().size != 1 ||
                sources[id] != refs.first().revision }) return EvidenceFirstNoteStep.Stale
        val proposalDependencies = dependencyGroups.mapValues { it.value.first().revision }
        val noteDependencies = proposalDependencies + (targetId to target.revision)
        val pendingConcept = SupportConcept("pending:${generated.workKey}", generated.workKey, concept.name, concept.definition)
        val query = TopicRetrievalQuery(pendingConcept.topicId, generated.workKey,
            "${concept.name.trim()}\n${concept.definition.trim()}", identity, topicRetrievalPolicy)
        val vector = runtime.bulkStep { runtime.inference.withLock {
            if (!current() || !mayWork()) null else runtime.embedExactInput(query.text)
        } } ?: return if (current()) EvidenceFirstNoteStep.Deferred else EvidenceFirstNoteStep.Stale
        if (!current()) return EvidenceFirstNoteStep.Stale
        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
        val embeddedQuery = try { TopicQueryEmbedding(query, vector) } catch (_: IllegalArgumentException) {
            return EvidenceFirstNoteStep.Incomplete
        }
        if (corpusReplay != null) {
            val corpus = replayPendingCorpus(corpusReplay, scopeTicket, pendingConcept, embeddedQuery, verifier,
                epoch, sources, guardedIndex, store, current, mayWork, scopeAnchor, proposalDependencies)
            if (!current()) return EvidenceFirstNoteStep.Stale
            if (!mayWork()) return EvidenceFirstNoteStep.Deferred
            if (guardedIndex.read(canonicalSemanticId(originPageId)) != record || !current())
                return EvidenceFirstNoteStep.Stale
            return EvidenceFirstNoteStep.CorpusReplay(PendingConceptCandidate(proposal, generated, citations.toList(),
                sources.toMap(), identity, verifier, generator, embeddedQuery, catalogTicket), corpus, window)
        }
        if (replayPendingScan) {
            val saved = store.readPendingNoteScan(pendingConcept.workKey, targetId, sources)
                ?: return EvidenceFirstNoteStep.Incomplete
            if (saved.formatVersion != 2) return EvidenceFirstNoteStep.Incomplete
            val replayed = replayPendingNoteScan(saved, checkNotNull(retrieveScan), pendingConcept, embeddedQuery,
                target, guardedIndex, verifier, epoch, sources, store, current, mayWork, noteDependencies)
            if (!current()) return EvidenceFirstNoteStep.Stale
            if (!mayWork()) return EvidenceFirstNoteStep.Deferred
            if (guardedIndex.read(targetId) != target || guardedIndex.read(canonicalSemanticId(originPageId)) != record || !current())
                return EvidenceFirstNoteStep.Stale
            return EvidenceFirstNoteStep.PendingNoteReplay(generated, replayed, window)
        }
        if (retrieveScan != null) {
            val expected = if (checkpointPendingScan) {
                val stored = store.readPendingNoteScan(pendingConcept.workKey, targetId, sources)
                val old = if (retrieveScan.binding == null) stored?.takeIf { it.formatVersion == 1 } else PendingNoteScanRecord(pendingConcept.workKey,
                    targetId, target.revision, noteDependencies, retrieveScan.binding, checkNotNull(retrieveScan.next).passage,
                    retrieveScan.supported, retrieveScan.uncertain, formatVersion = 2)
                if (stored != old) return EvidenceFirstNoteStep.Stale
                stored
            } else null
            val scan = scanPendingConceptNote(pendingConcept, embeddedQuery, target, guardedIndex, verifier,
                epoch, sources, retrieveScan, maxRecords, store, current, mayWork, scopeSources = noteDependencies) { request ->
                runtime.verifyTopicSupport(request) { current() && mayWork() }
            }
            if (!current()) return EvidenceFirstNoteStep.Stale
            if (!mayWork()) return EvidenceFirstNoteStep.Deferred
            if (guardedIndex.read(targetId) != target || guardedIndex.read(canonicalSemanticId(originPageId)) != record || !current())
                return EvidenceFirstNoteStep.Stale
            if (checkpointPendingScan && (scan is PendingConceptNoteScanResult.Pending ||
                    scan is PendingConceptNoteScanResult.Complete || scan is PendingConceptNoteScanResult.Uncertain)) {
                val written = savePendingNoteResult(pendingConcept, target, noteDependencies, scan, expected, store) { current() && mayWork() }
                if (!current()) return EvidenceFirstNoteStep.Stale
                if (!mayWork()) return EvidenceFirstNoteStep.Deferred
                if (written != ProposalProgressWriteResult.Written)
                    return EvidenceFirstNoteStep.PendingNoteStorageFailure(written)
            }
            return EvidenceFirstNoteStep.NoteRetrievalScan(generated, scan, window)
        }
        val page = retrievePendingConceptPage(pendingConcept, embeddedQuery, target, guardedIndex, verifier,
            epoch, sources, retrieveCursor, maxRecords, store, current, mayWork) { request ->
            runtime.verifyTopicSupport(request) { current() && mayWork() }
        }
        if (!current()) return EvidenceFirstNoteStep.Stale
        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
        if (guardedIndex.read(targetId) != target || guardedIndex.read(canonicalSemanticId(originPageId)) != record || !current())
            return EvidenceFirstNoteStep.Stale
        return when (page) {
            is PendingConceptRetrievalPage.Ready -> EvidenceFirstNoteStep.Retrieved(generated, page, window)
            PendingConceptRetrievalPage.Stale -> EvidenceFirstNoteStep.Stale
            PendingConceptRetrievalPage.Deferred -> EvidenceFirstNoteStep.Deferred
            else -> EvidenceFirstNoteStep.RetrievalFailure(page)
        }
    }

    /** Candidate-only work while the existing catalog cannot yet be validated. */
    private suspend fun provisionalEvidenceFirstWindow(pageId: String, explicitPageIds: Set<String>,
        structureComplete: Boolean, anchorOffset: Int, maxRecords: Int, store: ProposalProgressStore,
        windowContinue: () -> Boolean, explained: Set<String> = emptySet(), uncertain: Boolean = false,
        generateIndex: Int? = null, expectedGenerationKey: String? = null, assessEvidenceIndex: Int? = null,
        wantCoverage: Boolean = false, retrievePageId: String? = null, retrieveCursor: PendingRetrievalCursor? = null,
        retrieveScan: PendingConceptNoteScan? = null, checkpointPendingScan: Boolean = false,
        replayPendingScan: Boolean = false, corpusReplay: PendingCorpusReplay? = null,
        scopeAnchor: Any, catalogScope: String, extraCurrent: () -> Boolean = { true }): EvidenceFirstNoteStep {
        val token = runtime.validity.capture()
        val identity = provider.identity
        val indexedRevision = mutable.value.indexedRevision
        fun current() = runtime.validity.isCurrent(token) && provider.identity == identity &&
            mutable.value.indexedRevision == indexedRevision && cache.invalidations.value.isEmpty() && extraCurrent()
        fun mayWork() = mayContinueWork() && windowContinue() && runtime.foregroundQueries.get() == 0
        if (!current()) return EvidenceFirstNoteStep.Stale
        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
        val sources = runtime.validity.eligibleRevisions()
        val id = canonicalSemanticId(pageId)
        val record = runtime.bulkStep { index.read(id) } ?: return EvidenceFirstNoteStep.Incomplete
        if (!current() || record.identity != identity || sources[id] != record.revision) return EvidenceFirstNoteStep.Stale
        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
        val result = advancePendingProposalWindow(record, explained, sources, explicitPageIds, structureComplete,
            anchorOffset, store, maxRecords, ::current, ::mayWork) { target -> runtime.bulkStep { index.read(target) } }
        if (!current()) return EvidenceFirstNoteStep.Stale
        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
        if (runtime.bulkStep { index.read(id) } != record || !current()) return EvidenceFirstNoteStep.Stale
        return when (result) {
            is PendingProposalStepResult.Ready -> {
                val provisional = result.step.window.proposals.map { proposal ->
                    val incomplete = proposal.incomplete + ProposalIncompleteReason.EXISTING_CONCEPT_CATALOG +
                        if (uncertain) setOf(ProposalIncompleteReason.EXISTING_CONCEPT_UNCERTAIN) else emptySet()
                    val fields = listOf("unverified-catalog-v2", proposal.proposalKey) + incomplete.map { it.name }.sorted()
                    proposal.copy(proposalKey = semanticHash(fields.joinToString("") { "${it.length}:$it" }), incomplete = incomplete)
                }
                val window = result.step.copy(window = result.step.window.copy(proposals = provisional))
                if (generateIndex == null) return EvidenceFirstNoteStep.Proposals(window)
                val proposal = provisional.getOrNull(generateIndex) ?: return EvidenceFirstNoteStep.Incomplete
                val generator = topicProposalIdentity() ?: return EvidenceFirstNoteStep.GenerationFailure(ProposalGenerationResult.Unavailable)
                fun generationCurrent() = current() && topicProposalIdentity() == generator
                val guardedIndex = object : SemanticIndexStore by index {
                    override suspend fun read(pageId: String): SemanticRecord? = runtime.bulkStep { index.read(pageId) }
                }
                val generated = generateProposal(proposal, guardedIndex, identity, generator, token.reset, sources,
                    { generationCurrent() && mayWork() }, store) { request ->
                    runtime.proposeTopic(request) { generationCurrent() && mayWork() }
                }
                if (!generationCurrent()) return EvidenceFirstNoteStep.Stale
                if (!mayWork()) return EvidenceFirstNoteStep.Deferred
                if (guardedIndex.read(id) != record || !generationCurrent()) return EvidenceFirstNoteStep.Stale
                when (generated) {
                    is ProposalGenerationResult.Completed -> {
                        if (expectedGenerationKey != null && generated.workKey != expectedGenerationKey) return EvidenceFirstNoteStep.Stale
                        val concept = generated.response as? DiscoveryProposalResponse.Concept
                        if (concept == null || (!wantCoverage && assessEvidenceIndex == null)) return EvidenceFirstNoteStep.Generated(generated, window)
                        val verifier = topicSupportIdentity() ?: return EvidenceFirstNoteStep.AssessmentFailure(SupportVerificationResult.Unavailable)
                        fun assessmentCurrent() = generationCurrent() && topicSupportIdentity() == verifier
                        if (wantCoverage) {
                            val coverage = readPendingConceptCoverage(proposal, generated, guardedIndex, identity, generator,
                                verifier, token.reset, sources, store) { assessmentCurrent() && mayWork() }
                            if (!assessmentCurrent()) return EvidenceFirstNoteStep.Stale
                            if (!mayWork()) return EvidenceFirstNoteStep.Deferred
                            if (coverage == PendingConceptCoverage.Stale) return EvidenceFirstNoteStep.Stale
                            if (coverage !is PendingConceptCoverage.Complete || coverage.assessments.any { it.decision != SupportDecision.SUPPORTED } || retrievePageId == null)
                                return EvidenceFirstNoteStep.CitationCoverage(generated, coverage, window)
                            val scopeFields = listOf("provisional-retrieval-v1", catalogScope, token.reset.toString(),
                                token.cacheRevision.toString(), indexedRevision.toString(), identity.toString())
                            val scopeTicket = semanticHash(scopeFields.joinToString("") { "${it.length}:$it" })
                            return advancePendingRetrieval(proposal, generated, window, concept, coverage.assessments, identity, verifier, generator, null, token.reset,
                                sources, guardedIndex, store, pageId, record, retrievePageId, retrieveCursor, retrieveScan,
                                checkpointPendingScan, replayPendingScan, corpusReplay, scopeTicket, scopeAnchor, maxRecords,
                                ::assessmentCurrent, ::mayWork)
                        }
                        val evidenceId = concept.evidenceIds.getOrNull(checkNotNull(assessEvidenceIndex)) ?: return EvidenceFirstNoteStep.Incomplete
                        val ref = proposal.evidence.singleOrNull { it.passageId == evidenceId } ?: return EvidenceFirstNoteStep.Stale
                        val pending = SupportConcept("pending:${generated.workKey}", generated.workKey, concept.name, concept.definition)
                        val candidate = TopicMatchReference(ref.pageId, ref.revision, ref.passageId, 0f)
                        val assessed = verifyRetrievedPassage(pending, candidate, guardedIndex, identity, verifier, token.reset,
                            sources, { assessmentCurrent() && mayWork() }, store) { request ->
                            runtime.verifyTopicSupport(request) { assessmentCurrent() && mayWork() }
                        }
                        if (!assessmentCurrent()) return EvidenceFirstNoteStep.Stale
                        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
                        if (guardedIndex.read(id) != record || !assessmentCurrent()) return EvidenceFirstNoteStep.Stale
                        when (assessed) {
                            is SupportVerificationResult.Completed -> EvidenceFirstNoteStep.Assessed(generated, assessed.assessment, assessEvidenceIndex, window)
                            SupportVerificationResult.Stale -> EvidenceFirstNoteStep.Stale
                            else -> EvidenceFirstNoteStep.AssessmentFailure(assessed)
                        }
                    }
                    ProposalGenerationResult.Stale -> EvidenceFirstNoteStep.Stale
                    else -> EvidenceFirstNoteStep.GenerationFailure(generated)
                }
            }
            PendingProposalStepResult.Missing -> EvidenceFirstNoteStep.Incomplete
            PendingProposalStepResult.Stale -> EvidenceFirstNoteStep.Stale
            PendingProposalStepResult.Deferred -> EvidenceFirstNoteStep.Deferred
            is PendingProposalStepResult.StorageFailure -> EvidenceFirstNoteStep.ProposalFailure(result)
        }
    }
    private suspend fun advanceKnownCatalogWindow(owner: TopicCoordinator, ticket: TopicCatalogTicket,
        verifier: TopicLabelIdentity, pageId: String, explicitPageIds: Set<String>, structureComplete: Boolean,
        anchorOffset: Int, maxRecords: Int, store: ProposalProgressStore, windowContinue: () -> Boolean,
        scan: KnownCatalogScan, generateIndex: Int?, expectedGenerationKey: String?, assessEvidenceIndex: Int?,
        wantCoverage: Boolean, retrievePageId: String?, retrieveCursor: PendingRetrievalCursor?,
        retrieveScan: PendingConceptNoteScan?, checkpointPendingScan: Boolean, replayPendingScan: Boolean,
        corpusReplay: PendingCorpusReplay?): EvidenceFirstNoteStep {
        val token = runtime.validity.capture()
        val indexedRevision = mutable.value.indexedRevision
        fun current() = owner.isCatalogTicketCurrent(ticket) && topicSupportIdentity() == verifier &&
            runtime.validity.isCurrent(token) && mutable.value.indexedRevision == indexedRevision
        fun mayWork() = mayContinueWork() && windowContinue() && runtime.foregroundQueries.get() == 0
        if (scan.ticket != null && (!owner.isCatalogTicketCurrent(scan.ticket) || scan.ticket.snapshot !== ticket.snapshot ||
                scan.verifier != verifier || scan.indexedRevision != indexedRevision)) return EvidenceFirstNoteStep.Stale
        if (!current()) return EvidenceFirstNoteStep.Stale
        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
        val sources = runtime.validity.eligibleRevisions()
        val id = canonicalSemanticId(pageId)
        val record = runtime.bulkStep { index.read(id) } ?: return EvidenceFirstNoteStep.Incomplete
        if (!current() || record.identity != ticket.identity || sources[id] != record.revision) return EvidenceFirstNoteStep.Stale
        val result = owner.scanKnownConceptWork(ticket, record, sources, verifier, scan.cursor, maxRecords,
            verifierCurrent = { topicSupportIdentity() == verifier }) { request ->
            runtime.verifyTopicSupport(request) { current() && mayWork() }
        }
        if (!current()) return EvidenceFirstNoteStep.Stale
        if (!mayWork()) return EvidenceFirstNoteStep.Deferred
        return when (result) {
            is ExistingConceptScanResult.Page -> {
                if (scan.scope != null && scan.scope != result.scopeKey) return EvidenceFirstNoteStep.Stale
                val supported = scan.supported + result.supported
                val uncertain = scan.uncertain + result.uncertain
                if (result.next != null) EvidenceFirstNoteStep.PendingKnownCatalog(scan.advance(ticket, verifier, indexedRevision,
                    result.scopeKey, result.next, supported, uncertain))
                else {
                    val provisional = provisionalEvidenceFirstWindow(pageId, explicitPageIds, structureComplete, anchorOffset,
                        maxRecords, store, windowContinue, supported, uncertain.isNotEmpty(), generateIndex, expectedGenerationKey,
                        assessEvidenceIndex, wantCoverage, retrievePageId, retrieveCursor, retrieveScan, checkpointPendingScan,
                        replayPendingScan, corpusReplay, ticket.snapshot, "${ticket.epoch}:${ticket.cacheRevision}:${ticket.queryTicket}", ::current)
                    if (!current()) EvidenceFirstNoteStep.Stale else if (!mayWork()) EvidenceFirstNoteStep.Deferred else provisional
                }
            }
            ExistingConceptScanResult.Incomplete -> {
                val provisional = provisionalEvidenceFirstWindow(pageId, explicitPageIds, structureComplete, anchorOffset,
                    maxRecords, store, windowContinue, scan.supported, scan.uncertain.isNotEmpty(), generateIndex, expectedGenerationKey,
                    assessEvidenceIndex, wantCoverage, retrievePageId, retrieveCursor, retrieveScan, checkpointPendingScan,
                    replayPendingScan, corpusReplay, ticket.snapshot, "${ticket.epoch}:${ticket.cacheRevision}:${ticket.queryTicket}", ::current)
                if (!current()) EvidenceFirstNoteStep.Stale else if (!mayWork()) EvidenceFirstNoteStep.Deferred else provisional
            }
            ExistingConceptScanResult.Stale -> EvidenceFirstNoteStep.Stale
            ExistingConceptScanResult.Deferred -> EvidenceFirstNoteStep.Deferred
            is ExistingConceptScanResult.Failure -> EvidenceFirstNoteStep.ScanFailure(DurableConceptScanResult.Failure(result.result))
        }
    }
    internal suspend fun advanceEvidenceFirstNote(pageId: String, replay: ConceptScanReplay? = null,
        explicitPageIds: Set<String> = emptySet(), structureComplete: Boolean = false, anchorOffset: Int = 0,
        maxRecords: Int = 8, restart: Boolean = false, generateIndex: Int? = null,
        assessEvidenceIndex: Int? = null, summarizeEvidence: Boolean = false, retrievePageId: String? = null,
        retrieveCursor: PendingRetrievalCursor? = null, retrieveScan: PendingConceptNoteScan? = null,
        checkpointPendingScan: Boolean = false, replayPendingScan: Boolean = false,
        corpusReplay: PendingCorpusReplay? = null, expectedGenerationKey: String? = null,
        windowContinue: () -> Boolean = { true }, knownCatalogScan: KnownCatalogScan? = null): EvidenceFirstNoteStep = withContext(workDispatcher) {
        require(maxRecords in 1..128 && anchorOffset >= 0 && (generateIndex == null || generateIndex >= 0) &&
            (assessEvidenceIndex == null || assessEvidenceIndex >= 0 && generateIndex != null) &&
            (!summarizeEvidence || generateIndex != null && assessEvidenceIndex == null && retrievePageId == null) &&
            (retrievePageId == null || retrievePageId.isNotBlank() && generateIndex != null && assessEvidenceIndex == null) &&
            (retrieveCursor == null || retrievePageId != null && retrieveScan == null) &&
            (retrieveScan == null || retrievePageId != null) &&
            (!checkpointPendingScan || retrieveScan != null && !replayPendingScan) &&
            (!replayPendingScan || retrieveScan != null) &&
            (corpusReplay == null || retrievePageId != null && retrieveScan == null && retrieveCursor == null && !checkpointPendingScan && !replayPendingScan))
        val owner = topics ?: return@withContext EvidenceFirstNoteStep.Incomplete
        val store = proposalProgressStore ?: return@withContext EvidenceFirstNoteStep.Incomplete
        if (!mayContinueWork() || !windowContinue() || runtime.foregroundQueries.get() > 0) return@withContext EvidenceFirstNoteStep.Deferred
        val ticket = owner.captureCatalogTicket()
        if (ticket == null) {
            val partial = owner.captureCatalogTicket(allowIncomplete = true)
            val knownVerifier = topicSupportIdentity()
            if (partial != null && !partial.snapshot.complete && partial.snapshot.topics.isNotEmpty() && knownVerifier != null)
                return@withContext advanceKnownCatalogWindow(owner, partial, knownVerifier, pageId, explicitPageIds,
                    structureComplete, anchorOffset, maxRecords, store, windowContinue, knownCatalogScan ?: KnownCatalogScan.start(),
                    generateIndex, expectedGenerationKey, assessEvidenceIndex, summarizeEvidence || retrievePageId != null,
                    retrievePageId, retrieveCursor, retrieveScan, checkpointPendingScan, replayPendingScan, corpusReplay)
            return@withContext provisionalEvidenceFirstWindow(pageId, explicitPageIds, structureComplete, anchorOffset,
                maxRecords, store, windowContinue, generateIndex = generateIndex, expectedGenerationKey = expectedGenerationKey,
                assessEvidenceIndex = assessEvidenceIndex, wantCoverage = summarizeEvidence || retrievePageId != null,
                retrievePageId = retrievePageId, retrieveCursor = retrieveCursor, retrieveScan = retrieveScan,
                checkpointPendingScan = checkpointPendingScan, replayPendingScan = replayPendingScan, corpusReplay = corpusReplay,
                scopeAnchor = owner, catalogScope = "unavailable")
        }
        val verifier = topicSupportIdentity() ?: return@withContext EvidenceFirstNoteStep.Incomplete
        val generator = if (generateIndex != null) topicProposalIdentity() ?: return@withContext EvidenceFirstNoteStep.Incomplete else null
        val token = runtime.validity.capture()
        val indexedRevision = mutable.value.indexedRevision
        fun current() = owner.isCatalogTicketCurrent(ticket) && topicSupportIdentity() == verifier &&
            (generator == null || topicProposalIdentity() == generator) && runtime.validity.isCurrent(token) && mutable.value.indexedRevision == indexedRevision
        fun mayWork() = mayContinueWork() && windowContinue() && runtime.foregroundQueries.get() == 0
        val sources = runtime.validity.eligibleRevisions()
        if (!current()) return@withContext EvidenceFirstNoteStep.Stale
        val record = runtime.bulkStep { index.read(canonicalSemanticId(pageId)) } ?: return@withContext EvidenceFirstNoteStep.Incomplete
        if (!current()) return@withContext EvidenceFirstNoteStep.Stale
        val scan = owner.advanceExistingConceptWork(ticket, record, sources, verifier, maxRecords, restart,
            verifierCurrent = { topicSupportIdentity() == verifier }) { request ->
            runtime.verifyTopicSupport(request) { current() && mayWork() }
        }
        if (!current()) return@withContext EvidenceFirstNoteStep.Stale
        if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
        val saved = when (scan) {
            DurableConceptScanResult.Pending -> return@withContext EvidenceFirstNoteStep.PendingScan
            is DurableConceptScanResult.AwaitingValidation -> scan.record
            DurableConceptScanResult.Incomplete -> return@withContext EvidenceFirstNoteStep.Incomplete
            DurableConceptScanResult.Stale -> return@withContext EvidenceFirstNoteStep.Stale
            DurableConceptScanResult.Deferred -> return@withContext EvidenceFirstNoteStep.Deferred
            else -> return@withContext EvidenceFirstNoteStep.ScanFailure(scan)
        }
        val checked = owner.replayExistingConceptWork(ticket, record, sources, verifier, saved,
            if (restart) ConceptScanReplay.start() else replay ?: ConceptScanReplay.start(), maxRecords) { topicSupportIdentity() == verifier }
        if (!current()) return@withContext EvidenceFirstNoteStep.Stale
        if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
        val supported = when (checked) {
            is ConceptScanReplayResult.Pending -> return@withContext EvidenceFirstNoteStep.PendingReplay(checked.replay)
            is ConceptScanReplayResult.Validated -> {
                if (checked.uncertain.isNotEmpty()) return@withContext EvidenceFirstNoteStep.Uncertain(checked.uncertain)
                checked.supported
            }
            ConceptScanReplayResult.Stale -> return@withContext EvidenceFirstNoteStep.Stale
            ConceptScanReplayResult.Deferred -> return@withContext EvidenceFirstNoteStep.Deferred
            else -> return@withContext EvidenceFirstNoteStep.Incomplete
        }
        val result = advancePendingProposalWindow(record, supported, sources, explicitPageIds, structureComplete,
            anchorOffset, store, maxRecords, ::current, ::mayWork) { id -> runtime.bulkStep { index.read(id) } }
        if (!current()) return@withContext EvidenceFirstNoteStep.Stale
        if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
        if (runtime.bulkStep { index.read(canonicalSemanticId(pageId)) } != record || !current())
            return@withContext EvidenceFirstNoteStep.Stale
        when (result) {
            is PendingProposalStepResult.Ready -> {
                if (generateIndex == null) return@withContext EvidenceFirstNoteStep.Proposals(result.step)
                val proposal = result.step.window.proposals.getOrNull(generateIndex) ?: return@withContext EvidenceFirstNoteStep.Incomplete
                val guardedIndex = object : SemanticIndexStore by index {
                    override suspend fun read(pageId: String): SemanticRecord? = runtime.bulkStep { index.read(pageId) }
                }
                val generated = generateProposal(proposal, guardedIndex, ticket.identity, checkNotNull(generator), ticket.epoch,
                    sources, ::current, store) { request -> runtime.proposeTopic(request) { current() && mayWork() } }
                if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                if (runtime.bulkStep { index.read(canonicalSemanticId(pageId)) } != record || !current())
                    return@withContext EvidenceFirstNoteStep.Stale
                when (generated) {
                    is ProposalGenerationResult.Completed -> {
                        if (expectedGenerationKey != null && generated.workKey != expectedGenerationKey)
                            return@withContext EvidenceFirstNoteStep.Stale
                        if (retrievePageId != null) {
                            val concept = generated.response as? DiscoveryProposalResponse.Concept
                                ?: return@withContext EvidenceFirstNoteStep.Generated(generated, result.step)
                            val coverage = readPendingConceptCoverage(proposal, generated, guardedIndex, ticket.identity,
                                checkNotNull(generator), verifier, ticket.epoch, sources, store, ::current)
                            if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                            if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                            if (coverage == PendingConceptCoverage.Stale) return@withContext EvidenceFirstNoteStep.Stale
                            if (coverage !is PendingConceptCoverage.Complete || coverage.assessments.any { it.decision != SupportDecision.SUPPORTED })
                                return@withContext EvidenceFirstNoteStep.CitationCoverage(generated, coverage, result.step)
                            val scopeFields = listOf(ticket.epoch.toString(), ticket.cacheRevision.toString(), ticket.queryTicket,
                                token.reset.toString(), token.cacheRevision.toString(), indexedRevision.toString(), ticket.identity.toString())
                            val scopeTicket = semanticHash(scopeFields.joinToString("") { "${it.length}:$it" })
                            return@withContext advancePendingRetrieval(proposal, generated, result.step, concept, coverage.assessments, ticket.identity,
                                verifier, checkNotNull(generator), ticket, ticket.epoch, sources, guardedIndex, store, pageId, record, retrievePageId, retrieveCursor,
                                retrieveScan, checkpointPendingScan, replayPendingScan, corpusReplay, scopeTicket, ticket.snapshot,
                                maxRecords, ::current, ::mayWork)
                        }
                        if (summarizeEvidence) {
                            val coverage = readPendingConceptCoverage(proposal, generated, guardedIndex, ticket.identity,
                                checkNotNull(generator), verifier, ticket.epoch, sources, store, ::current)
                            if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                            if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                            if (coverage == PendingConceptCoverage.Stale) return@withContext EvidenceFirstNoteStep.Stale
                            if (runtime.bulkStep { index.read(canonicalSemanticId(pageId)) } != record || !current())
                                return@withContext EvidenceFirstNoteStep.Stale
                            return@withContext EvidenceFirstNoteStep.CitationCoverage(generated, coverage, result.step)
                        }
                        if (assessEvidenceIndex == null) return@withContext EvidenceFirstNoteStep.Generated(generated, result.step)
                        val concept = generated.response as? DiscoveryProposalResponse.Concept
                                ?: return@withContext EvidenceFirstNoteStep.Incomplete
                        val evidenceId = concept.evidenceIds.getOrNull(assessEvidenceIndex) ?: return@withContext EvidenceFirstNoteStep.Incomplete
                        val ref = proposal.evidence.singleOrNull { it.passageId == evidenceId } ?: return@withContext EvidenceFirstNoteStep.Stale
                        // The pending ID scopes the assessment and is never a canonical catalog ID.
                        val pendingConcept = SupportConcept("pending:${generated.workKey}", generated.workKey, concept.name, concept.definition)
                        val candidate = TopicMatchReference(ref.pageId, ref.revision, ref.passageId, 0f)
                        val assessed = verifyRetrievedPassage(pendingConcept, candidate, guardedIndex, ticket.identity, verifier,
                            ticket.epoch, sources, ::current, store) { request ->
                            runtime.verifyTopicSupport(request) { current() && mayWork() }
                        }
                        if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                        if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                        if (runtime.bulkStep { index.read(canonicalSemanticId(pageId)) } != record || !current())
                            return@withContext EvidenceFirstNoteStep.Stale
                        when (assessed) {
                            is SupportVerificationResult.Completed -> EvidenceFirstNoteStep.Assessed(generated, assessed.assessment,
                                assessEvidenceIndex, result.step)
                            SupportVerificationResult.Stale -> EvidenceFirstNoteStep.Stale
                            else -> EvidenceFirstNoteStep.AssessmentFailure(assessed)
                        }
                    }
                    ProposalGenerationResult.Stale -> EvidenceFirstNoteStep.Stale
                    else -> EvidenceFirstNoteStep.GenerationFailure(generated)
                }
            }
            PendingProposalStepResult.Missing -> EvidenceFirstNoteStep.Incomplete
            PendingProposalStepResult.Stale -> EvidenceFirstNoteStep.Stale
            PendingProposalStepResult.Deferred -> EvidenceFirstNoteStep.Deferred
            is PendingProposalStepResult.StorageFailure -> EvidenceFirstNoteStep.ProposalFailure(result)
        }
    }

    suspend fun refreshTopics() = withContext(workDispatcher) { topics?.refresh() }
    suspend fun setTopicsInspected(active: Boolean) = withContext(workDispatcher) { topics?.setInspected(active) }
    fun sourcePageId(id: String): String = state.value.cachedSourceIds[canonicalSemanticId(id)] ?: id
    suspend fun loadInterestMatches(id: String, force: Boolean = false) = interests.loadInterestMatches(id, force)
    suspend fun followTopic(topicId: String, name: String, text: String) = interests.followTopic(topicId, name, text)
    suspend fun updateFollow(id: String, name: String, text: String) = interests.updateFollow(id, name, text)
    fun topicPreview(topicId: String): List<SemanticMatch> = previews.topicPreview(topicId)
    suspend fun connectionWindow(snapshot: TopicSnapshot, offset: Int = 0, limit: Int = 20,
        selectedId: String? = null, expectedCacheRevision: Long? = null): ConnectionCatalogPageResult =
        retrieval.connectionWindow(snapshot, offset, limit, selectedId, expectedCacheRevision)
    suspend fun groupedConnectionWindow(snapshot: TopicSnapshot, offset: Int = 0, limit: Int = 20,
        expectedCacheRevision: Long = state.value.cachedRevision): GroupedConnectionPage = withContext(workDispatcher) {
        val token = runtime.validity.capture()
        if (token.cacheRevision != expectedCacheRevision)
            GroupedConnectionPage(ConnectionCatalogPageResult(emptyList(), snapshot.catalogReference?.count ?: snapshot.connections.size, false), 0)
        else connectionProjection.read(snapshot, state.value.cachedTitles, token, offset, limit) {
            runtime.validity.isCurrent(token)
        }
    }
    suspend fun connectionPreview(connectionId: String, snapshot: TopicSnapshot = state.value.topicSnapshot,
        expectedCacheRevision: Long = state.value.cachedRevision): List<SemanticMatch> =
        previews.connectionPreview(connectionId, snapshot, expectedCacheRevision)

    suspend fun reconcile(entries: List<ReaderIndexEntry>, mayIndex: Boolean) {
        withContext(workDispatcher) {
            mutable.update { it.copy(workPending = hasPendingWork) }
            try { indexing.reconcileInBackground(entries, mayIndex) }
            finally { mutable.update { it.copy(workPending = hasPendingWork) } }
        }
    }
    suspend fun search(text: String, filters: BrowseQuery = state.value.filters) = search.search(text, filters)

    /** Optional numeric diagnostics must never change search behavior or expose source/query text. */
    private fun recordTiming(timing: Map<String, Long>) {
        try { queryTimings(timing) } catch (_: Exception) { /* Diagnostic observer only. */ }
    }

    suspend fun relatedNotes(pageId: String) = search.relatedNotes(pageId)

    suspend fun refreshRecommendations() = interests.refreshRecommendations()
    suspend fun saveInterest(id: String?, text: String, seedPageId: String? = null) = interests.saveInterest(id, text, seedPageId)
    suspend fun removeInterest(id: String) = interests.removeInterest(id)
    suspend fun clearInterests() = interests.clearInterests()
    suspend fun pause() {
        runtime.commits.withLock { index.setPaused(true); mutable.update { it.copy(paused = true) }; wake.value++ }
        runtime.interruptLabeling()
        runtime.closeLabels()
    }
    suspend fun resume() = runtime.commits.withLock { indexing.reconciliationPending = true; index.setPaused(false); mutable.update { it.copy(paused = false) }; wake.value++ }
    suspend fun clearIndex(preserveTopicContinuity: Boolean = false) = runtime.commits.withLock {
        connectionProjection.clear()
        runtime.embeddingMemo.clear()
        runtime.generation.incrementAndGet(); search.reset(); index.clear()
        passageProgressStore?.clear()
        proposalProgressStore?.clear()
        interests.resetRequests()
        if (preserveTopicContinuity) topics?.invalidate(emptyMap()) else topics?.clear()
        mutable.update { it.copy(relatedPageId = null, results = emptyList(), related = emptyList(), recommendations = emptyList(), older = emptyList(),
            interestMatch = InterestMatchState(), indexedRevision = it.indexedRevision + 1,
            preview = emptyList(), indexedCount = 0, indexCoverage = DiscoveryCoverageStatus.Ready, embeddingWork = null, paused = true, indexFailure = null, topicFailure = null, searching = false,
            searchFailure = null, searchCompleted = false, message = null, bytes = index.bytes()) }
        wake.value++
    }
    fun preparePreview(matches: List<SemanticMatch>, label: String = "Discovery recommendations") =
        previews.preparePreview(matches, label)
    fun removePreview(pageId: String) = previews.removePreview(pageId)
    fun movePreview(pageId: String, delta: Int) = previews.movePreview(pageId, delta)
    suspend fun releaseModel() { runtime.releaseEngines(); connectionProjection.clear(); topics?.releaseMemory(); runtime.embeddingMemo.clear(); index.releaseMemory(); passageProgressStore?.releaseMemory() }
}
