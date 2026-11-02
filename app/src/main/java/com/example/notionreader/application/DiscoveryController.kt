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

    internal suspend fun advanceEvidenceFirstNote(pageId: String, replay: ConceptScanReplay? = null,
        explicitPageIds: Set<String> = emptySet(), structureComplete: Boolean = false, anchorOffset: Int = 0,
        maxRecords: Int = 8, restart: Boolean = false, generateIndex: Int? = null,
        assessEvidenceIndex: Int? = null, summarizeEvidence: Boolean = false, retrievePageId: String? = null,
        retrieveCursor: PendingRetrievalCursor? = null, retrieveScan: PendingConceptNoteScan? = null,
        checkpointPendingScan: Boolean = false, replayPendingScan: Boolean = false,
        corpusReplay: PendingCorpusReplay? = null): EvidenceFirstNoteStep = withContext(workDispatcher) {
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
        if (!mayContinueWork() || runtime.foregroundQueries.get() > 0) return@withContext EvidenceFirstNoteStep.Deferred
        val ticket = owner.captureCatalogTicket() ?: return@withContext EvidenceFirstNoteStep.Incomplete
        val verifier = topicSupportIdentity() ?: return@withContext EvidenceFirstNoteStep.Incomplete
        val generator = if (generateIndex != null) topicProposalIdentity() ?: return@withContext EvidenceFirstNoteStep.Incomplete else null
        val token = runtime.validity.capture()
        val indexedRevision = mutable.value.indexedRevision
        fun current() = owner.isCatalogTicketCurrent(ticket) && topicSupportIdentity() == verifier &&
            (generator == null || topicProposalIdentity() == generator) && runtime.validity.isCurrent(token) && mutable.value.indexedRevision == indexedRevision
        fun mayWork() = mayContinueWork() && runtime.foregroundQueries.get() == 0
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
                        if (retrievePageId != null) {
                            val concept = generated.response as? DiscoveryProposalResponse.Concept ?: return@withContext EvidenceFirstNoteStep.Incomplete
                            val coverage = readPendingConceptCoverage(proposal, generated, guardedIndex, ticket.identity,
                                checkNotNull(generator), verifier, ticket.epoch, sources, store, ::current)
                            if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                            if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                            if (coverage == PendingConceptCoverage.Stale) return@withContext EvidenceFirstNoteStep.Stale
                            if (coverage !is PendingConceptCoverage.Complete || coverage.assessments.any { it.decision != SupportDecision.SUPPORTED })
                                return@withContext EvidenceFirstNoteStep.CitationCoverage(generated, coverage, result.step)
                            val targetId = canonicalSemanticId(retrievePageId)
                            if (sources[targetId] == null) return@withContext EvidenceFirstNoteStep.Stale
                            val target = guardedIndex.read(targetId) ?: return@withContext EvidenceFirstNoteStep.Incomplete
                            if (!current() || target.identity != ticket.identity || target.revision != sources[targetId])
                                return@withContext EvidenceFirstNoteStep.Stale
                            val pendingConcept = SupportConcept("pending:${generated.workKey}", generated.workKey, concept.name, concept.definition)
                            val query = TopicRetrievalQuery(pendingConcept.topicId, generated.workKey,
                                "${concept.name.trim()}\n${concept.definition.trim()}", ticket.identity, topicRetrievalPolicy)
                            val vector = runtime.bulkStep { runtime.inference.withLock {
                                if (!current() || !mayWork()) null else runtime.embedExactInput(query.text)
                            } } ?: return@withContext if (current()) EvidenceFirstNoteStep.Deferred else EvidenceFirstNoteStep.Stale
                            if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                            if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                            val embeddedQuery = try { TopicQueryEmbedding(query, vector) } catch (_: IllegalArgumentException) {
                                return@withContext EvidenceFirstNoteStep.Incomplete
                            }
                            if (corpusReplay != null) {
                                val scopeFields = listOf(ticket.epoch.toString(), ticket.cacheRevision.toString(), ticket.queryTicket,
                                    token.reset.toString(), token.cacheRevision.toString(), indexedRevision.toString(), ticket.identity.toString())
                                val scopeTicket = semanticHash(scopeFields.joinToString("") { "${it.length}:$it" })
                                val corpus = replayPendingCorpus(corpusReplay, scopeTicket, pendingConcept, embeddedQuery, verifier,
                                    ticket.epoch, sources, guardedIndex, store, ::current, ::mayWork, ticket.snapshot)
                                if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                                if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                                if (guardedIndex.read(canonicalSemanticId(pageId)) != record || !current())
                                    return@withContext EvidenceFirstNoteStep.Stale
                                return@withContext EvidenceFirstNoteStep.CorpusReplay(generated, corpus, result.step)
                            }
                            if (replayPendingScan) {
                                val saved = store.readPendingNoteScan(pendingConcept.workKey, targetId, sources)
                                    ?: return@withContext EvidenceFirstNoteStep.Incomplete
                                val replayed = replayPendingNoteScan(saved, checkNotNull(retrieveScan), pendingConcept, embeddedQuery,
                                    target, guardedIndex, verifier, ticket.epoch, sources, store, ::current, ::mayWork)
                                if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                                if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                                if (guardedIndex.read(targetId) != target || guardedIndex.read(canonicalSemanticId(pageId)) != record || !current())
                                    return@withContext EvidenceFirstNoteStep.Stale
                                return@withContext EvidenceFirstNoteStep.PendingNoteReplay(generated, replayed, result.step)
                            }
                            if (retrieveScan != null) {
                                val expected = if (checkpointPendingScan) {
                                    val stored = store.readPendingNoteScan(pendingConcept.workKey, targetId, sources)
                                    val old = if (retrieveScan.binding == null) null else PendingNoteScanRecord(pendingConcept.workKey,
                                        targetId, target.revision, sources, retrieveScan.binding, checkNotNull(retrieveScan.next).passage,
                                        retrieveScan.supported, retrieveScan.uncertain)
                                    if (stored != old) return@withContext EvidenceFirstNoteStep.Stale
                                    stored
                                } else null
                                val scan = scanPendingConceptNote(pendingConcept, embeddedQuery, target, guardedIndex, verifier,
                                    ticket.epoch, sources, retrieveScan, maxRecords, store, ::current, ::mayWork) { request ->
                                    runtime.verifyTopicSupport(request) { current() && mayWork() }
                                }
                                if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                                if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                                if (guardedIndex.read(targetId) != target || guardedIndex.read(canonicalSemanticId(pageId)) != record || !current())
                                    return@withContext EvidenceFirstNoteStep.Stale
                                if (checkpointPendingScan && (scan is PendingConceptNoteScanResult.Pending ||
                                        scan is PendingConceptNoteScanResult.Complete || scan is PendingConceptNoteScanResult.Uncertain)) {
                                    val written = savePendingNoteResult(pendingConcept, target, sources, scan, expected, store) { current() && mayWork() }
                                    if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                                    if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                                    if (written != ProposalProgressWriteResult.Written)
                                        return@withContext EvidenceFirstNoteStep.PendingNoteStorageFailure(written)
                                }
                                return@withContext EvidenceFirstNoteStep.NoteRetrievalScan(generated, scan, result.step)
                            }
                            val page = retrievePendingConceptPage(pendingConcept, embeddedQuery, target, guardedIndex, verifier,
                                ticket.epoch, sources, retrieveCursor, maxRecords, store, ::current, ::mayWork) { request ->
                                runtime.verifyTopicSupport(request) { current() && mayWork() }
                            }
                            if (!current()) return@withContext EvidenceFirstNoteStep.Stale
                            if (!mayWork()) return@withContext EvidenceFirstNoteStep.Deferred
                            if (guardedIndex.read(targetId) != target || guardedIndex.read(canonicalSemanticId(pageId)) != record || !current())
                                return@withContext EvidenceFirstNoteStep.Stale
                            return@withContext when (page) {
                                is PendingConceptRetrievalPage.Ready -> EvidenceFirstNoteStep.Retrieved(generated, page, result.step)
                                PendingConceptRetrievalPage.Stale -> EvidenceFirstNoteStep.Stale
                                PendingConceptRetrievalPage.Deferred -> EvidenceFirstNoteStep.Deferred
                                else -> EvidenceFirstNoteStep.RetrievalFailure(page)
                            }
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
                        val concept = generated.response as? DiscoveryProposalResponse.Concept ?: return@withContext EvidenceFirstNoteStep.Incomplete
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
