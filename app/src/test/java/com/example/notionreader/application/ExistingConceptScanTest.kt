package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExistingConceptScanTest {
    @get:Rule val files = TemporaryFolder()
    private val identity = EmbeddingIdentity("model", 2)
    private val policy = TopicRetrievalPolicy(.8f)
    private val verifier = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "CPU")
    private fun record(id: String): SemanticRecord {
        val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
        val p = SemanticPassage("$id:p", id, "$id:s", "Summary", "$id:b", "$id:c", 0, text.length, text, text, 0)
        return SemanticRecord(id, "r1", identity, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
    }
    private fun topic(id: String, order: Long): DiscoveredTopic {
        val rec = record("seed"); val p = rec.passages.single()
        val topic = DiscoveredTopic(id, "seed", p.passage, p, listOf(TopicMembership("seed", rec.metadata, p.passage, 1f, p.passage, "r1")), order)
        val req = topicLabelRequest(topic, verifier, 0)!!
        val label = (validateTopicLabel(req, "Seed germination", req.evidence.map { it.passageId }, req.groupFingerprint, 0,
            "Conditions that allow seeds to germinate and sprout.") as TopicLabelValidation.Accepted).label
        return topic.copy(generatedLabel = label)
    }
    private fun query(topic: DiscoveredTopic): TopicQueryEmbedding {
        val label = topic.currentGeneratedLabel!!
        return TopicQueryEmbedding(TopicRetrievalQuery(topic.id, label.workKey, topicConceptQuery(label)!!, identity, policy), listOf(1f, 0f))
    }
    @Test fun savedCanonicalConceptParticipatesInIndependentExistingConceptChecks() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val note = record("note"); assertTrue(index.write(note))
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val original = topic("canonical", 1); val label = original.currentGeneratedLabel!!
        val query = TopicQueryEmbedding(TopicRetrievalQuery(original.id, label.workKey, topicConceptQuery(label)!!, identity, policy), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(label.workKey, label.text, label.definition, "Listen about seeds.",
            verifier, verifier, emptyList(), emptyList(), queryEmbedding = query)
        val projection = CanonicalTopicProjection(label.workKey, label.text, label.definition, "Listen about seeds.", emptyList(), emptyList())
        val canonical = original.copy(generatedLabel = null, canonicalConcept = concept, canonicalProjection = projection)
        val snapshot = TopicSnapshot(topics = listOf(canonical), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val changedQuery = canonical.copy(canonicalConcept = concept.copy(queryEmbedding = query.copy(vector = listOf(0f, 1f))))
        assertNotEquals(existingConceptScanScope(snapshot, note, identity, policy, verifier, 4, sources, "ticket"),
            existingConceptScanScope(snapshot.copy(topics = listOf(changedQuery)), note, identity, policy, verifier, 4, sources, "ticket"))
        var calls = 0
        val result = scanExistingConceptPage(snapshot, note, identity, policy, verifier, 4, sources, retrievals,
            index, progress, null, 1, { true }, { true }) { calls++; TopicSupportRun.Completed(SupportDecision.UNSUPPORTED) }
        assertTrue(result is ExistingConceptScanResult.Page)
        assertTrue((result as ExistingConceptScanResult.Page).supported.isEmpty())
        assertEquals(1, calls)
        assertEquals(ExistingConceptScanResult.Incomplete, scanExistingConceptPage(snapshot.copy(topics = listOf(canonical.copy(canonicalProjection = null))),
            note, identity, policy, verifier, 4, sources, retrievals, index, progress, null, 1, { true }, { true }) { error("No verification") })
    }
    @Test fun conceptPagesAdvanceWithoutTreatingSimilarityAsSupport() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(second, first), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        suspend fun step(cursor: ExistingConceptScanCursor?) = scanExistingConceptPage(snapshot, note, identity, policy, verifier,
            4, sources, retrievals, index, progress, cursor, 1, { true }, { true }) {
            TopicSupportRun.Completed(if (it.topicId == "first") SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED)
        } as ExistingConceptScanResult.Page
        val a = step(null); assertTrue(a.supported.isEmpty()); assertNotNull(a.next)
        val b = step(a.next); assertEquals(setOf("note:p"), b.supported); assertNull(b.next)
    }
    @Test fun replacingAPreviouslyScannedQueryInvalidatesTheContinuation() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        suspend fun step(cursor: ExistingConceptScanCursor?) = scanExistingConceptPage(snapshot, note, identity, policy, verifier,
            4, sources, retrievals, index, progress, cursor, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        val firstPage = step(null) as ExistingConceptScanResult.Page
        val changed = query(first).copy(vector = listOf(0f, 1f))
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(changed)) { true })
        assertEquals(ExistingConceptScanResult.Stale, step(firstPage.next))
    }

    @Test fun passagePaginationFinishesBeforeAdvancingToTheNextConcept() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        suspend fun step(c: ExistingConceptScanCursor?) = scanExistingConceptPage(snapshot, note, identity, policy, verifier, 4,
            sources, retrievals, index, progress, c, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
            as ExistingConceptScanResult.Page
        val a = step(null); assertEquals(setOf("note:p"), a.supported); assertEquals(0, a.next!!.topicOffset)
        val b = step(a.next); assertEquals(setOf("note:q"), b.supported); assertEquals(1, b.next!!.topicOffset)
        assertNull(b.next!!.passage)
    }
    @Test fun durablePagesSurviveRestartWithoutLosingSupportDecisions() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val dir = root.resolve("progress")
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1"); var calls = 0
        suspend fun step() = advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            FileProposalProgressStore(dir), 1, { true }, { true }) {
            calls++; TopicSupportRun.Completed(if (it.topicId == "first") SupportDecision.SUPPORTED else SupportDecision.UNCERTAIN)
        }
        assertEquals(DurableConceptScanResult.Pending, step())
        val done = step() as DurableConceptScanResult.AwaitingValidation
        assertEquals(setOf(SupportDecision.SUPPORTED, SupportDecision.UNCERTAIN), done.record.assessments.map { it.decision }.toSet())
        assertEquals(2, calls); assertEquals(done, step()); assertEquals(2, calls)
    }

    @Test fun restoredScanIsReplayedAgainstCachedSupportBeforeItCanExplainAnything() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1"); var calls = 0
        suspend fun scan() = advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            progress, 1, { true }, { true }) { calls++; TopicSupportRun.Completed(if (it.topicId == "first") SupportDecision.SUPPORTED else SupportDecision.UNCERTAIN) }
        assertEquals(DurableConceptScanResult.Pending, scan())
        val saved = (scan() as DurableConceptScanResult.AwaitingValidation).record
        suspend fun replay(state: ConceptScanReplay) = replayConceptScanPage(saved, state, snapshot, note, identity, policy, verifier,
            4, sources, retrievals, index, progress, 1, { true }, { true })
        val firstPage = replay(ConceptScanReplay.start()) as ConceptScanReplayResult.Pending
        val validated = replay(firstPage.replay) as ConceptScanReplayResult.Validated
        assertEquals(setOf("note:p"), validated.supported); assertEquals(setOf("note:p"), validated.uncertain)
        assertEquals(2, calls)
        val missingSupport = object : ProposalProgressStore by progress {
            override suspend fun readSupport(workKey: String, sources: Map<String, String>): TopicSupportAssessment? = null
        }
        assertEquals(ConceptScanReplayResult.Incomplete, replayConceptScanPage(saved, ConceptScanReplay.start(), snapshot,
            note, identity, policy, verifier, 4, sources, retrievals, index, missingSupport, 1, { true }, { true }))
        assertEquals(ConceptScanReplayResult.Stale, replayConceptScanPage(saved, ConceptScanReplay.start(), snapshot.copy(generation = 9),
            note, identity, policy, verifier, 4, sources, retrievals, index, progress, 1, { true }, { true }))
        assertEquals(ProposalProgressWriteResult.Written, progress.replaceConceptScan(saved, saved.copy(assessments = emptyList())) { true })
        assertEquals(ConceptScanReplayResult.Invalid, replayConceptScanPage(saved.copy(assessments = emptyList()), ConceptScanReplay.start(),
            snapshot, note, identity, policy, verifier, 4, sources, retrievals, index, progress, 1, { true }, { true }))
    }

    @Test fun refusedCheckpointDoesNotAdvanceAndReusesAlreadySavedSupport() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val real = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1"); var calls = 0
        val refused = object : ProposalProgressStore by real {
            override suspend fun replaceConceptScan(expected: ExistingConceptScanRecord?, record: ExistingConceptScanRecord,
                stillCurrent: () -> Boolean) = ProposalProgressWriteResult.StorageRefused
        }
        suspend fun step(store: ProposalProgressStore) = advanceDurableConceptScan(snapshot, note, identity, policy, verifier,
            4, sources, retrievals, index, store, 1, { true }, { true }) { calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        assertEquals(DurableConceptScanResult.StorageFailure(ProposalProgressWriteResult.StorageRefused), step(refused))
        assertNull(real.readConceptScan("note", sources)); assertEquals(1, calls)
        assertEquals(DurableConceptScanResult.Pending, step(real)); assertEquals(1, calls)
    }

    @Test fun restartingAStaleCompletedScanAtomicallyReplacesItsCheckpoint() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        suspend fun step(generation: Long, restart: Boolean, store: ProposalProgressStore = progress) =
            advanceDurableConceptScan(snapshot.copy(generation = generation), note, identity, policy, verifier, 4, sources,
                retrievals, index, store, 1, { true }, { true }, restart = restart) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        val old = (step(0, false) as DurableConceptScanResult.AwaitingValidation).record
        val refused = object : ProposalProgressStore by progress {
            override suspend fun replaceConceptScan(expected: ExistingConceptScanRecord?, record: ExistingConceptScanRecord,
                stillCurrent: () -> Boolean) = ProposalProgressWriteResult.StorageRefused
        }
        assertEquals(DurableConceptScanResult.StorageFailure(ProposalProgressWriteResult.StorageRefused), step(1, true, refused))
        assertEquals(old, progress.readConceptScan("note", sources))
        val replaced = (step(1, true) as DurableConceptScanResult.AwaitingValidation).record
        assertNotEquals(old.scopeKey, replaced.scopeKey)
        assertEquals(replaced, progress.readConceptScan("note", sources))
    }

    @Test fun queryReplacementDuringCheckpointWriteCannotSaveTheOldScan() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val real = FileProposalProgressStore(root.resolve("progress"))
        val topic = topic("first", 1); val initial = query(topic)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(initial)) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val racing = object : ProposalProgressStore by real {
            override suspend fun replaceConceptScan(expected: ExistingConceptScanRecord?, record: ExistingConceptScanRecord,
                stillCurrent: () -> Boolean): ProposalProgressWriteResult {
                retrievals.write(TopicRetrievalProgress(initial.copy(vector = listOf(0f, 1f)))) { true }
                return real.replaceConceptScan(expected, record, stillCurrent)
            }
        }
        val result = advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            racing, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        assertEquals(DurableConceptScanResult.Stale, result)
        assertNull(real.readConceptScan("note", sources))
    }

    @Test fun unavailableConceptAndChangedSnapshotCannotReportCompletedScan() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(first))) { true })
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        suspend fun step(s: TopicSnapshot, c: ExistingConceptScanCursor?) = scanExistingConceptPage(s, note, identity, policy, verifier,
            4, sources, retrievals, index, progress, c, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.UNCERTAIN) }
        val firstPage = step(snapshot, null) as ExistingConceptScanResult.Page
        assertEquals(setOf("note:p"), firstPage.uncertain)
        assertEquals(ExistingConceptScanResult.Incomplete, step(snapshot, firstPage.next))
        assertEquals(ExistingConceptScanResult.Stale, step(snapshot.copy(topics = listOf(first)), firstPage.next))
        assertEquals(ExistingConceptScanResult.Incomplete, step(snapshot.copy(complete = false), null))
    }
    @Test fun knownConceptsCanBeCheckedWithoutClaimingCatalogCompletion() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val note = record("note"); index.write(note)
        val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (item in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(item))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = false)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        assertEquals(ExistingConceptScanResult.Incomplete, scanExistingConceptPage(snapshot, note, identity, policy, verifier,
            4, sources, retrievals, index, progress, null, 1, { true }, { true }) { error("Must not infer") })
        val page = scanExistingConceptPage(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            progress, null, 1, { true }, { true }, allowIncompleteCatalog = true) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) } as ExistingConceptScanResult.Page
        assertEquals(setOf("note:p"), page.supported); assertNotNull(page.next)
        val changed = scanExistingConceptPage(snapshot.copy(complete = true), note, identity, policy, verifier, 4, sources, retrievals, index,
            progress, page.next, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        assertEquals(ExistingConceptScanResult.Stale, changed)
    }}
