package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoverySupportWiringTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun coordinatorUsesTheSharedRuntimeAndVersionedSupportIdentity() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2); val labelIdentity = TopicLabelIdentity("a", "t", "c", "label", "s", "v", "CPU")
        val supportIdentity = labelIdentity.copy(prompt = "support", policy = "support-v1")
        val proposalIdentity = labelIdentity.copy(prompt = "proposal", policy = "proposal-v1")
        var activeProposalIdentity = proposalIdentity; var switchProposalDuringInference = false; var proposeConcept = false
        var pendingSupportCalls = 0; var pendingDecision = SupportDecision.SUPPORTED
        var blockSupport = false; var supportCancelled = false
        val supportEntered = CompletableDeferred<Unit>(); val supportRelease = CompletableDeferred<Unit>()
        var lastProposal: DiscoveryProposalRequest? = null
        var activeSupportIdentity = supportIdentity; var switchDuringInference = false
        var decision = SupportDecision.SUPPORTED
        var releases = 0; var supportCalls = 0; var proposalCalls = 0; var queryEmbeddings = 0
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String): List<Float> { queryEmbeddings++; return listOf(1f, 0f) }
            override fun release() { releases++ }
        }
        val labeler = object : TopicLabelRuntime, TopicSupportRuntime, DiscoveryProposalRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No naming work")
            override suspend fun verifySupport(request: TopicSupportRequest): TopicSupportRun {
                assertEquals(activeSupportIdentity, request.verifier); assertTrue("Embedding engine must release before support inference", releases > 0)
                supportCalls++
                if (request.topicId.startsWith("pending:")) {
                    if (blockSupport) {
                        supportEntered.complete(Unit)
                        try { supportRelease.await() } catch (cancelled: CancellationException) { supportCancelled = true; throw cancelled }
                    }
                    pendingSupportCalls++
                    return TopicSupportRun.Completed(pendingDecision)
                }
                if (switchDuringInference) activeSupportIdentity = activeSupportIdentity.copy(policy = "changed-during-inference")
                return TopicSupportRun.Completed(decision)
            }
            override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                assertEquals(activeProposalIdentity, request.identity); proposalCalls++; lastProposal = request
                if (switchProposalDuringInference) activeProposalIdentity = activeProposalIdentity.copy(policy = "changed-during-proposal")
                val response = if (proposeConcept) DiscoveryProposalResponse.Concept("Seed germination conditions",
                    "Conditions that enable seeds to germinate.", "Listen to the conditions for seed germination.",
                    listOf(request.evidence.first().passageId)) else DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE)
                return DiscoveryProposalRun.Completed(response)
            }
            override suspend fun close() {}
        }
        val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val doc = ReaderDocument.fromChunkTexts("note", "Seed germination", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc); val p = SemanticPassage("p", "note", "section", "Summary", "block", "chunk", 0, text.length, text, text, 0)
        val embedded = EmbeddedPassage(p, listOf(1f, 0f)); val metadata = SemanticMetadata("Seed germination")
        assertTrue(index.write(SemanticRecord("note", revision, model, metadata, listOf(embedded)))); index.setPaused(false)
        val raw = DiscoveredTopic("topic", "seed", p, embedded, listOf(TopicMembership("note", metadata, p, 1f, p, revision)), 1)
        val request = topicLabelRequest(raw, labelIdentity, 0)!!
        val label = (validateTopicLabel(request, "Seed germination", request.evidence.map { it.passageId }, request.groupFingerprint, 0,
            "Conditions that allow seeds to germinate and sprout.") as TopicLabelValidation.Accepted).label
        val graph = object : TopicStore {
            var snapshot = TopicSnapshot(topics = listOf(raw.copy(generatedLabel = label)), identity = model, complete = true, connectionPassComplete = true)
            override suspend fun read() = snapshot
            override suspend fun write(snapshot: TopicSnapshot): Boolean { this.snapshot = snapshot; return true }
            override suspend fun clear() { snapshot = TopicSnapshot() }
            override suspend fun bytes() = 0L
        }
        val queries = FileTopicRetrievalStore(root.resolve("retrieval")); val policy = TopicRetrievalPolicy(.85f)
        val query = TopicRetrievalQuery("topic", label.workKey, topicConceptQuery(label)!!, model, policy)
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)))) { true })
        val backing = FileProposalProgressStore(root.resolve("progress")); var changeQueryDuringReplay = false
        val progress = object : ProposalProgressStore by backing {
            override suspend fun readConceptScan(pageId: String, sources: Map<String, String>): ExistingConceptScanRecord? {
                val saved = backing.readConceptScan(pageId, sources)
                if (changeQueryDuringReplay && saved != null) {
                    changeQueryDuringReplay = false
                    queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(0f, 1f)))) { true }
                }
                return saved
            }
        }
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, topicLabelRuntime = labeler, topicLabelModelAvailable = { true }, topicRetrievalStore = queries,
            proposalProgressStore = progress, topicSupportIdentity = { activeSupportIdentity }, topicProposalIdentity = { activeProposalIdentity })
        controller.reconcile(emptyList(), false)
        val proposed = controller.advanceEvidenceFirstNote("note") as EvidenceFirstNoteStep.Proposals
        assertTrue("Verified existing support must prevent a duplicate proposal", proposed.window.window.proposals.isEmpty())
        assertEquals(1, supportCalls)
        val result = controller.advanceExistingConceptWorkForNote("note", 1) as DurableConceptScanResult.AwaitingValidation
        assertEquals(1, supportCalls); assertEquals(listOf(SupportDecision.SUPPORTED), result.record.assessments.map { it.decision })
        val replayed = controller.replayExistingConceptWorkForNote("note", result.record, ConceptScanReplay.start(), 1)
            as ConceptScanReplayResult.Validated
        assertEquals(setOf("p"), replayed.supported); assertTrue(replayed.uncertain.isEmpty()); assertEquals(1, supportCalls)
        changeQueryDuringReplay = true
        assertEquals(ConceptScanReplayResult.Stale, controller.replayExistingConceptWorkForNote("note", result.record, ConceptScanReplay.start(), 1))
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)))) { true })
        activeSupportIdentity = supportIdentity.copy(policy = "unsupported-v2")
        assertEquals(ConceptScanReplayResult.Stale, controller.replayExistingConceptWorkForNote("note", result.record, ConceptScanReplay.start(), 1))
        decision = SupportDecision.UNSUPPORTED
        val generated = controller.advanceEvidenceFirstNote("note", restart = true, generateIndex = 0) as EvidenceFirstNoteStep.Generated
        assertEquals(DiscoveryProposalResponse.Abstained(DiscoveryAbstentionReason.MIXED_EVIDENCE), generated.generation.response)
        assertEquals(1, proposalCalls)
        val abstained = controller.advancePendingCorpusWork(PendingCorpusWork.start("note", 0)) as PendingCorpusWorkResult.Abstained
        assertEquals(DiscoveryAbstentionReason.MIXED_EVIDENCE, abstained.reason)
        assertTrue(ProposalIncompleteReason.STRUCTURE in abstained.reasons)
        assertTrue(controller.advanceEvidenceFirstNote("note", generateIndex = 0) is EvidenceFirstNoteStep.Generated)
        assertEquals("An unchanged abstention must survive reuse", 1, proposalCalls)
        activeProposalIdentity = proposalIdentity.copy(policy = "proposal-v2"); switchProposalDuringInference = true
        assertEquals(EvidenceFirstNoteStep.Stale, controller.advanceEvidenceFirstNote("note", generateIndex = 0))
        assertEquals(2, proposalCalls)
        assertNull(progress.readAbstention(discoveryProposalWorkKey(lastProposal!!), mapOf("note" to revision)))
        switchProposalDuringInference = false
        activeProposalIdentity = proposalIdentity.copy(policy = "proposal-concept-v3"); proposeConcept = true
        var citationWork = (controller.advancePendingCorpusWork(PendingCorpusWork.start("note", 0)) as PendingCorpusWorkResult.Pending).work
        assertEquals(PendingCorpusWorkPhase.ASSESS_CITATION, citationWork.phase)
        blockSupport = true
        val windowOpen = java.util.concurrent.atomic.AtomicBoolean(true)
        val expiring = async { controller.advancePendingCorpusWindow(citationWork, maxSteps = 1, shouldContinue = { windowOpen.get() }) }
        withTimeout(2_000) { supportEntered.await() }
        windowOpen.set(false)
        val expiredResult = withTimeoutOrNull(1_500) { expiring.await() }
        if (expiredResult == null) expiring.cancelAndJoin()
        assertNotNull("An expired window must cancel in-flight support", expiredResult)
        assertEquals(EvidenceFirstNoteStep.Deferred, (expiredResult as PendingCorpusWorkResult.Stopped).reason)
        assertTrue(supportCancelled); blockSupport = false
        citationWork = (controller.advancePendingCorpusWork(citationWork) as PendingCorpusWorkResult.Pending).work
        assertEquals(PendingCorpusWorkPhase.CORPUS, citationWork.phase)
        val assessed = controller.advanceEvidenceFirstNote("note", generateIndex = 0, assessEvidenceIndex = 0) as EvidenceFirstNoteStep.Assessed
        assertEquals(SupportDecision.SUPPORTED, assessed.assessment.decision)
        assertTrue(assessed.assessment.topicId.startsWith("pending:")); assertEquals("p", assessed.assessment.evidence.passageId)
        assertEquals(1, pendingSupportCalls)
        assertTrue(controller.advanceEvidenceFirstNote("note", generateIndex = 0, assessEvidenceIndex = 0) is EvidenceFirstNoteStep.Assessed)
        assertEquals("Unchanged support must be reused", 1, pendingSupportCalls)
        val covered = controller.advanceEvidenceFirstNote("note", generateIndex = 0, summarizeEvidence = true) as EvidenceFirstNoteStep.CitationCoverage
        assertEquals(listOf(SupportDecision.SUPPORTED), (covered.coverage as PendingConceptCoverage.Complete).assessments.map { it.decision })
        val retrieved = controller.advanceEvidenceFirstNote("note", generateIndex = 0, retrievePageId = "note") as EvidenceFirstNoteStep.Retrieved
        assertEquals(setOf("p"), retrieved.page.supported); assertTrue(retrieved.page.uncertain.isEmpty()); assertNull(retrieved.page.next)
        val scanned = controller.advanceEvidenceFirstNote("note", generateIndex = 0, retrievePageId = "note",
            retrieveScan = PendingConceptNoteScan.start()) as EvidenceFirstNoteStep.NoteRetrievalScan
        assertEquals(setOf("p"), (scanned.scan as PendingConceptNoteScanResult.Complete).supported)
        assertEquals(EvidenceFirstNoteStep.Incomplete, controller.advanceEvidenceFirstNote("note", generateIndex = 0,
            retrievePageId = "note", retrieveScan = PendingConceptNoteScan.start(), replayPendingScan = true))
        val legacy = PendingNoteScanRecord(assessed.generation.workKey, "note", revision, mapOf("note" to revision),
            semanticHash("legacy"), null, emptySet(), emptySet())
        assertEquals(ProposalProgressWriteResult.Written, backing.replacePendingNoteScan(null, legacy) { true })
        var work = PendingCorpusWork.start("note", 0)
        val stoppedWindow = controller.advancePendingCorpusWindow(work, maxSteps = 8, shouldContinue = { false }) as PendingCorpusWorkResult.Pending
        assertSame(work, stoppedWindow.work)
        controller.updatePowerState(DiscoveryPowerState(percent = 10, connected = false))
        val lowPower = controller.advancePendingCorpusWindow(work, maxSteps = 8) as PendingCorpusWorkResult.Stopped
        assertEquals(EvidenceFirstNoteStep.Deferred, lowPower.reason); assertSame(work, lowPower.work)
        controller.updatePowerState(DiscoveryPowerState(percent = 100, connected = false))
        work = (controller.advancePendingCorpusWindow(work, maxSteps = 1) as PendingCorpusWorkResult.Pending).work
        assertNotNull(work.generationKey)
        val originalProposalIdentity = activeProposalIdentity
        activeProposalIdentity = activeProposalIdentity.copy(policy = "changed-during-corpus")
        val changedGeneration = controller.advancePendingCorpusWork(work) as PendingCorpusWorkResult.Stopped
        assertEquals(EvidenceFirstNoteStep.Stale, changedGeneration.reason)
        activeProposalIdentity = originalProposalIdentity
        var terminal: PendingCorpusWorkResult? = null
        repeat(12) {
            if (terminal == null) when (val step = controller.advancePendingCorpusWork(work)) {
                is PendingCorpusWorkResult.Pending -> work = step.work
                is PendingCorpusWorkResult.Done -> terminal = step
                is PendingCorpusWorkResult.Incomplete -> terminal = step
                is PendingCorpusWorkResult.Stopped -> fail("Unexpected stop: ${step.reason}")
                is PendingCorpusWorkResult.Abstained -> fail("Unexpected abstention: ${step.reason}")
            }
        }
        val incomplete = terminal as PendingCorpusWorkResult.Incomplete
        assertTrue(ProposalIncompleteReason.STRUCTURE in incomplete.reasons)
        assertEquals(mapOf("note" to setOf("p")), (incomplete.coverage as PendingCorpusReplayResult.Complete).supported)
        assertEquals(2, backing.readPendingNoteScan(assessed.generation.workKey, "note", mapOf("note" to revision))!!.formatVersion)
        val restoredScan = controller.advanceEvidenceFirstNote("note", generateIndex = 0, retrievePageId = "note",
            retrieveScan = PendingConceptNoteScan.start(), replayPendingScan = true) as EvidenceFirstNoteStep.PendingNoteReplay
        assertEquals(setOf("p"), (restoredScan.replay as PendingNoteReplayResult.Complete).supported)
        val corpus = controller.advanceEvidenceFirstNote("note", generateIndex = 0, retrievePageId = "note",
            corpusReplay = PendingCorpusReplay.start()) as EvidenceFirstNoteStep.CorpusReplay
        assertEquals(mapOf("note" to setOf("p")), (corpus.replay as PendingCorpusReplayResult.Complete).supported)
        assertEquals(1, queryEmbeddings); assertEquals(1, pendingSupportCalls)
        activeSupportIdentity = supportIdentity.copy(policy = "unsupported-pending-v3"); pendingDecision = SupportDecision.UNSUPPORTED
        val pending = controller.advanceEvidenceFirstNote("note", restart = true, generateIndex = 0, summarizeEvidence = true) as EvidenceFirstNoteStep.CitationCoverage
        assertEquals(PendingConceptCoverage.Pending(setOf("p")), pending.coverage)
        val unsupportedWork = (controller.advancePendingCorpusWork(PendingCorpusWork.start("note", 0)) as PendingCorpusWorkResult.Pending).work
        val unsupportedStop = controller.advancePendingCorpusWork(unsupportedWork) as PendingCorpusWorkResult.Stopped
        assertEquals(SupportDecision.UNSUPPORTED, (unsupportedStop.reason as EvidenceFirstNoteStep.Assessed).assessment.decision)
        val rejected = controller.advanceEvidenceFirstNote("note", generateIndex = 0, assessEvidenceIndex = 0) as EvidenceFirstNoteStep.Assessed
        assertEquals(SupportDecision.UNSUPPORTED, rejected.assessment.decision)
        assertEquals(2, pendingSupportCalls)
        val rejectedCoverage = controller.advanceEvidenceFirstNote("note", generateIndex = 0, summarizeEvidence = true) as EvidenceFirstNoteStep.CitationCoverage
        assertEquals(listOf(SupportDecision.UNSUPPORTED), (rejectedCoverage.coverage as PendingConceptCoverage.Complete).assessments.map { it.decision })
        assertEquals(listOf("topic"), controller.state.value.topicSnapshot.topics.map { it.id })
        activeSupportIdentity = supportIdentity.copy(policy = "uncertain-v3")
        decision = SupportDecision.UNCERTAIN
        assertEquals(EvidenceFirstNoteStep.Uncertain(setOf("p")), controller.advanceEvidenceFirstNote("note", restart = true))
        val uncertainCheckpoint = progress.readConceptScan("note", mapOf("note" to revision))!!
        activeSupportIdentity = supportIdentity.copy(policy = "support-v4"); switchDuringInference = true
        assertEquals(DurableConceptScanResult.Stale, controller.advanceExistingConceptWorkForNote("note", 1, restart = true))
        assertEquals("The cancelled attempt also reached the support runtime", 8, supportCalls)
        assertEquals(uncertainCheckpoint, progress.readConceptScan("note", mapOf("note" to revision)))
    }
    @Test fun candidatesCanRemainProvisionalWithoutACompletedCatalog() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2)
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val doc = ReaderDocument.fromChunkTexts("note", "Seed germination", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc)
        val p = SemanticPassage("p", "note", "section", "Summary", "block", "chunk", 0, text.length, text, text, 0)
        val record = SemanticRecord("note", revision, model, SemanticMetadata("Seeds"), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
        assertTrue(index.write(record)); index.setPaused(false)
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val graph = object : TopicStore {
            override suspend fun read() = TopicSnapshot(identity = model, complete = false)
            override suspend fun write(snapshot: TopicSnapshot) = true
            override suspend fun clear() {}
            override suspend fun bytes() = 0L
        }
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, proposalProgressStore = progress)
        controller.reconcile(emptyList(), false)
        val preview = controller.advanceEvidenceFirstNote("note", generateIndex = 0) as EvidenceFirstNoteStep.Proposals
        assertEquals(1, preview.window.window.proposals.size)
        assertTrue(ProposalIncompleteReason.EXISTING_CONCEPT_CATALOG in preview.window.window.proposals.single().incomplete)
        assertTrue(controller.state.value.publishedTopics.isEmpty())
        cache.remove("note")
        cache.put(CachedDocument(doc, null, Instant.EPOCH))
        assertEquals(EvidenceFirstNoteStep.Stale, controller.advanceEvidenceFirstNote("note", generateIndex = 0))
    }    @Test fun provisionalCandidatesFirstCheckKnownConceptsInAnIncompleteCatalog() = runBlocking {
        val root = files.newFolder(); val cache = FileDocumentCache(root.resolve("cache")); val index = FileSemanticIndexStore(root.resolve("index"))
        val model = EmbeddingIdentity("model", 2); val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
            override fun release() {}
        }
        var qDecision = SupportDecision.UNSUPPORTED
        var activeVerifier = verifier
        val runtime = object : TopicLabelRuntime, TopicSupportRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun = error("No labels")
            override suspend fun verifySupport(request: TopicSupportRequest) = TopicSupportRun.Completed(
                if (request.evidence.passageId == "p") SupportDecision.SUPPORTED else qDecision)
            override suspend fun close() {}
        }
        val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val doc = ReaderDocument.fromChunkTexts("note", "Seed germination", listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
        val revision = semanticRevision(doc)
        val p = SemanticPassage("p", "note", "section", "Summary", "block", "chunk", 0, text.length, text, text, 0)
        val q = p.copy(id = "q", sourceBlockId = "block2", readerChunkId = "chunk2", order = 1)
        val embedded = EmbeddedPassage(p, listOf(1f, 0f)); val metadata = SemanticMetadata("Seeds")
        assertTrue(index.write(SemanticRecord("note", revision, model, metadata, listOf(embedded, EmbeddedPassage(q, listOf(1f, 0f)))))); index.setPaused(false)
        val raw = DiscoveredTopic("topic", "seed", p, embedded, listOf(TopicMembership("note", metadata, p, 1f, p, revision)), 1)
        val request = topicLabelRequest(raw, verifier, 0)!!
        val label = (validateTopicLabel(request, "Seed germination", request.evidence.map { it.passageId }, request.groupFingerprint, 0,
            "Conditions that allow seeds to germinate and sprout.") as TopicLabelValidation.Accepted).label
        val graph = object : TopicStore {
            override suspend fun read() = TopicSnapshot(topics = listOf(raw.copy(generatedLabel = label)), identity = model, complete = false)
            override suspend fun write(snapshot: TopicSnapshot) = true
            override suspend fun clear() {}
            override suspend fun bytes() = 0L
        }
        val queries = FileTopicRetrievalStore(root.resolve("retrieval")); val policy = TopicRetrievalPolicy(.85f)
        val query = TopicRetrievalQuery("topic", label.workKey, topicConceptQuery(label)!!, model, policy)
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(1f, 0f)))) { true })
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(root.resolve("interests")), provider,
            topicStore = graph, topicRetrievalStore = queries, proposalProgressStore = progress,
            topicLabelRuntime = runtime, topicLabelModelAvailable = { true }, topicSupportIdentity = { activeVerifier })
        controller.reconcile(emptyList(), false)
        val publishedBefore = controller.state.value.publishedTopics
        val first = controller.advanceEvidenceFirstNote("note", maxRecords = 1) as EvidenceFirstNoteStep.PendingKnownCatalog
        val result = controller.advanceEvidenceFirstNote("note", maxRecords = 1, knownCatalogScan = first.scan) as EvidenceFirstNoteStep.Proposals
        val proposals = result.window.window.proposals
        assertEquals(setOf("q"), proposals.map { it.evidence.first().passageId }.toSet())
        assertTrue(proposals.all { ProposalIncompleteReason.EXISTING_CONCEPT_CATALOG in it.incomplete })
        assertEquals(publishedBefore, controller.state.value.publishedTopics)
        val driven = (controller.advancePendingCorpusWork(PendingCorpusWork.start("note", 0), maxRecords = 1) as PendingCorpusWorkResult.Pending).work
        val provisionalStop = controller.advancePendingCorpusWork(driven, maxRecords = 1) as PendingCorpusWorkResult.Stopped
        assertTrue(provisionalStop.reason is EvidenceFirstNoteStep.Proposals)
        qDecision = SupportDecision.UNCERTAIN; activeVerifier = verifier.copy(policy = "uncertain-v2")
        val uncertainFirst = controller.advanceEvidenceFirstNote("note", maxRecords = 1) as EvidenceFirstNoteStep.PendingKnownCatalog
        val uncertain = controller.advanceEvidenceFirstNote("note", maxRecords = 1, knownCatalogScan = uncertainFirst.scan) as EvidenceFirstNoteStep.Proposals
        assertEquals(setOf("q"), uncertain.window.window.proposals.map { it.evidence.first().passageId }.toSet())
        assertTrue(uncertain.window.window.proposals.all { ProposalIncompleteReason.EXISTING_CONCEPT_UNCERTAIN in it.incomplete })
        val pending = controller.advanceEvidenceFirstNote("note", maxRecords = 1) as EvidenceFirstNoteStep.PendingKnownCatalog
        assertEquals(TopicRetrievalWriteResult.Written, queries.write(TopicRetrievalProgress(TopicQueryEmbedding(query, listOf(0f, 1f)))) { true })
        assertEquals(EvidenceFirstNoteStep.Stale, controller.advanceEvidenceFirstNote("note", maxRecords = 1, knownCatalogScan = pending.scan))
    }}
