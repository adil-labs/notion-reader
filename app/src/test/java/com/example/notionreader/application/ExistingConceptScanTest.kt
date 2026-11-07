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
        assertNotEquals(existingConceptScanScope(snapshot, note, identity, policy, verifier, sources, "ticket"),
            existingConceptScanScope(snapshot.copy(topics = listOf(changedQuery)), note, identity, policy, verifier, sources, "ticket"))
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

    @Test fun earlySupportAdvancesToTheNextConceptWithoutCheckingTheRemainingPassages() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val calls = mutableListOf<String>()
        suspend fun step(c: ExistingConceptScanCursor?) = scanExistingConceptPage(snapshot, note, identity, policy, verifier, 4,
            sources, retrievals, index, progress, c, 1, { true }, { true }) {
            calls += "${it.topicId}:${it.evidence.passageId}"
            TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as ExistingConceptScanResult.Page
        val a = step(null)
        assertEquals(setOf("note:p"), a.supported)
        assertEquals(ConceptScanTermination.SUPPORTED_EARLY, a.termination)
        assertEquals(1, a.next!!.topicOffset)
        assertNull(a.next!!.passage)
        // The weaker passage of the first concept and the whole second concept remain unchecked
        // when the second concept's strongest passage establishes membership.
        val b = step(a.next)
        assertEquals(setOf("note:p"), b.supported)
        assertEquals(ConceptScanTermination.SUPPORTED_EARLY, b.termination)
        assertNull(b.next)
        assertEquals(listOf("first:note:p", "second:note:p"), calls)
    }

    @Test fun unsupportedPassagesPageBeforeAdvancingToTheNextConcept() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval")); val progress = FileProposalProgressStore(root.resolve("progress"))
        val first = topic("first", 1); val second = topic("second", 2)
        for (topic in listOf(first, second)) assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(first, second), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        suspend fun step(c: ExistingConceptScanCursor?) = scanExistingConceptPage(snapshot, note, identity, policy, verifier, 4,
            sources, retrievals, index, progress, c, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.UNSUPPORTED) }
            as ExistingConceptScanResult.Page
        val a = step(null); assertTrue(a.supported.isEmpty()); assertEquals(0, a.next!!.topicOffset); assertNotNull(a.next!!.passage)
        val b = step(a.next); assertTrue(b.supported.isEmpty()); assertEquals(1, b.next!!.topicOffset); assertNull(b.next!!.passage)
        val c = step(b.next); assertTrue(c.supported.isEmpty()); assertEquals(1, c.next!!.topicOffset); assertNotNull(c.next!!.passage)
        val d = step(c.next); assertTrue(d.supported.isEmpty()); assertNull(d.next)
        assertEquals(ConceptScanTermination.EXHAUSTED, d.termination)
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
        val changedCatalog = snapshot.copy(topics = snapshot.topics + topic("third", 3))
        assertEquals("A relevant topic/catalog change invalidates the checkpoint",
            ConceptScanReplayResult.Stale, replayConceptScanPage(saved, ConceptScanReplay.start(), changedCatalog,
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
        suspend fun step(restart: Boolean, store: ProposalProgressStore = progress) =
            advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources,
                retrievals, index, store, 1, { true }, { true }, restart = restart) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        val old = (step(false) as DurableConceptScanResult.AwaitingValidation).record
        // A changed retrieval query is a relevant scan dependency; restarting under it produces
        // a genuinely new scope, unlike a bare catalog generation bump.
        assertEquals(TopicRetrievalWriteResult.Written,
            retrievals.write(TopicRetrievalProgress(query(topic).copy(vector = listOf(0f, 1f)))) { true })
        val refused = object : ProposalProgressStore by progress {
            override suspend fun replaceConceptScan(expected: ExistingConceptScanRecord?, record: ExistingConceptScanRecord,
                stillCurrent: () -> Boolean) = ProposalProgressWriteResult.StorageRefused
        }
        assertEquals(DurableConceptScanResult.StorageFailure(ProposalProgressWriteResult.StorageRefused), step(true, refused))
        assertEquals(old, progress.readConceptScan("note", sources))
        val replaced = (step(true) as DurableConceptScanResult.AwaitingValidation).record
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
    @Test fun earlySupportCheckpointReplaysWithoutInferenceAndWithoutTheUncheckedTail() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val progressDirectory = root.resolve("progress"); val progress = FileProposalProgressStore(progressDirectory)
        val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1"); var calls = 0
        val saved = advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            progress, 1, { true }, { true }) {
            calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED)
        } as DurableConceptScanResult.AwaitingValidation
        assertEquals(1, calls)
        assertEquals(ConceptScanCompletion.SUPPORTED_EARLY, saved.record.completion)
        assertEquals(setOf(topic.currentGeneratedLabel!!.workKey), saved.record.earlyConceptWorkKeys)
        assertEquals(1, saved.record.assessments.size)
        assertEquals("note:p", saved.record.assessments.single().evidence.passageId)
        val reopened = FileProposalProgressStore(progressDirectory)
        val replayed = replayConceptScanPage(saved.record, ConceptScanReplay.start(), snapshot, note, identity, policy,
            verifier, 4, sources, retrievals, index, reopened, 8, { true }, { true })
        assertEquals(setOf("note:p"), (replayed as ConceptScanReplayResult.Validated).supported)
        assertEquals(1, calls)
    }

    @Test fun forgedOrTruncatedEarlyCompletionCannotMasqueradeAsAnExhaustiveScan() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val saved = (advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            progress, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
            as DurableConceptScanResult.AwaitingValidation).record
        suspend fun replay(record: ExistingConceptScanRecord) = replayConceptScanPage(record, ConceptScanReplay.start(),
            snapshot, note, identity, policy, verifier, 4, sources, retrievals, index, progress, 8, { true }, { true })
        // A checkpoint that claims exhaustion while only the supported passage was checked is invalid.
        val forged = saved.copy(completion = ConceptScanCompletion.EXHAUSTED, earlyConceptWorkKeys = emptySet())
        assertEquals(ProposalProgressWriteResult.Written, progress.replaceConceptScan(saved, forged) { true })
        assertEquals(ConceptScanReplayResult.Invalid, replay(forged))
        // A supported early key without its supported assessment cannot be saved at all.
        val truncated = saved.copy(assessments = emptyList())
        assertEquals(ProposalProgressWriteResult.Invalid, progress.replaceConceptScan(forged, truncated) { true })
        // A checkpoint with a cursor is interrupted, never a completed early-support result.
        val interrupted = saved.copy(next = ExistingConceptScanCursor(saved.scopeKey, 0, null), completion = null)
        assertEquals(ProposalProgressWriteResult.Written, progress.replaceConceptScan(forged, interrupted) { true })
        assertEquals(ConceptScanReplayResult.Stale, replay(interrupted))
    }

    @Test fun anInterruptedNegativeScanIsNeverTreatedAsDefinitive() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val progress = FileProposalProgressStore(root.resolve("progress")); val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val result = advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            progress, 1, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.UNSUPPORTED) }
        assertEquals(DurableConceptScanResult.Pending, result)
        val saved = progress.readConceptScan("note", sources)!!
        assertNotNull(saved.next); assertNull(saved.completion)
        assertNull(saved.assessments.firstOrNull())
        assertEquals(ConceptScanReplayResult.Stale, replayConceptScanPage(saved, ConceptScanReplay.start(), snapshot, note,
            identity, policy, verifier, 4, sources, retrievals, index, progress, 1, { true }, { true }))
    }

    @Test fun anOlderCheckpointIsRebuiltFromValidSupportWithoutInference() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val directory = root.resolve("progress"); val progress = FileProposalProgressStore(directory)
        val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1"); var calls = 0
        val saved = (advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            progress, 1, { true }, { true }) { calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
            as DurableConceptScanResult.AwaitingValidation).record
        assertEquals(1, calls)
        // Simulate an on-disk checkpoint from the retired derived-state contract.
        val file = directory.listFiles()!!.single { it.name.startsWith("proposal-concept-scan-") }
        file.writeText(file.readText().replace(Regex("\"formatVersion\":\\d+"), "\"formatVersion\":1"))
        val reopened = FileProposalProgressStore(directory)
        assertNull("An older checkpoint is not reinterpreted as current coverage",
            reopened.readConceptScan("note", sources))
        val rebuilt = advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index,
            reopened, 1, { true }, { true }) {
            error("The persisted support decision must be reused")
        } as DurableConceptScanResult.AwaitingValidation
        assertEquals(1, calls)
        assertEquals(ConceptScanCompletion.SUPPORTED_EARLY, rebuilt.record.completion)
    }

    @Test fun aNoteWithNoEligiblePassagesRecordsAnEmptyRetrievalOutcome() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val text = "This note is about a different subject entirely."
        val passage = SemanticPassage("note:p", "note", "note:s", "Other", "note:b", "note:c", 0,
            text.length, text, text, 0)
        val note = SemanticRecord("note", "r1", identity, SemanticMetadata("note"),
            listOf(EmbeddedPassage(passage, listOf(0f, 1f))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val progress = FileProposalProgressStore(root.resolve("progress")); val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val saved = (advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals,
            index, progress, 8, { true }, { true }) { error("No eligible passage reaches the verifier") }
            as DurableConceptScanResult.AwaitingValidation).record
        assertEquals(ConceptScanCompletion.EXHAUSTED, saved.completion)
        assertEquals(0, saved.retrievedCandidates)
        assertEquals(0, saved.examinedCandidates)
        assertTrue(saved.assessments.isEmpty())
        assertTrue(saved.earlyConceptWorkKeys.isEmpty())
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
    }
    @Test fun anUnrelatedCatalogGenerationChangeDoesNotDiscardAPartialScan() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val progress = FileProposalProgressStore(root.resolve("progress")); val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true, generation = 0)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        suspend fun step(generation: Long) = advanceDurableConceptScan(snapshot.copy(generation = generation), note,
            identity, policy, verifier, 4, sources, retrievals, index, progress, 1, { true }, { true }) {
            TopicSupportRun.Completed(SupportDecision.UNSUPPORTED)
        }
        assertEquals(DurableConceptScanResult.Pending, step(0))
        // A membership commit for an unrelated Note advances the catalog generation, but this
        // Note's topic/query/verifier/source identity is unchanged. A partial scan must resume
        // instead of being discarded as stale.
        var result = step(1)
        assertTrue("A partial scan must survive an unrelated generation change: $result",
            result is DurableConceptScanResult.Pending || result is DurableConceptScanResult.AwaitingValidation)
        var generation = 2L
        while (result is DurableConceptScanResult.Pending) result = step(generation++)
        assertTrue("The resumed scan must finish with a terminal checkpoint", result is DurableConceptScanResult.AwaitingValidation)
    }

    @Test fun aCompletedEarlyCheckpointWithAPendingTailRestartsExhaustively() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val progress = FileProposalProgressStore(root.resolve("progress"))
        val key = semanticHash("concept")
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "Seed germination\nConditions for seeds.",
            identity, policy), listOf(1f, 0f))
        val p = note.passages.first().passage
        val member = TopicMembership("seed", record("seed").metadata, record("seed").passages.single().passage, 1f,
            record("seed").passages.single().passage, "r1")
        fun snapshot(unchecked: Boolean): TopicSnapshot {
            val concept = CanonicalConceptEvidence(key, "Seed germination", "Conditions for seeds.", "Listen about seeds.",
                verifier, verifier, emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"),
                proposalKey = semanticHash("proposal"), queryEmbedding = query,
                uncheckedRevisions = if (unchecked) mapOf("note" to "r1") else emptyMap())
            val topic = DiscoveredTopic("topic", "Seed germination", p, note.passages.first(), listOf(member), 1,
                canonicalConcept = concept,
                canonicalProjection = CanonicalTopicProjection(key, "Seed germination", "Conditions for seeds.",
                    "Listen about seeds.", emptyList(), null))
            return TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        }
        val sources = mapOf("seed" to "r1", "note" to "r1")
        val tailEvidence = TopicLabelEvidence("note:p", "note", "r1", p.excerpt)
        val witness = (validateTopicSupport(TopicSupportRequest("topic", key, "Seed germination", "Conditions for seeds.",
            tailEvidence, verifier, 4), SupportDecision.SUPPORTED, 4, key, sources)
            as TopicSupportValidation.Accepted).assessment
        assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(witness) { true })
        var calls = 0
        suspend fun scan(snapshot: TopicSnapshot, restart: Boolean = false, limit: Int = 1) =
            advanceDurableConceptScan(snapshot, note, identity, policy, verifier, 4, sources, retrievals, index, progress,
                limit, { true }, { true }, restart = restart) { calls++; TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        val early = scan(snapshot(unchecked = false)) as DurableConceptScanResult.AwaitingValidation
        assertEquals(ConceptScanCompletion.SUPPORTED_EARLY, early.record.completion)
        assertEquals(setOf(key), early.record.earlyConceptWorkKeys)
        // The concept now has an unchecked tail. A completed early checkpoint must not be treated
        // as a finished tail; the scan restarts exhaustively (p0 reused, p1 checked).
        val restarted = scan(snapshot(unchecked = true))
        assertTrue("A completed early checkpoint with a pending tail must restart: $restarted",
            restarted is DurableConceptScanResult.Pending)
        var result = restarted
        while (result is DurableConceptScanResult.Pending) result = scan(snapshot(unchecked = true))
        val done = result as DurableConceptScanResult.AwaitingValidation
        assertEquals(ConceptScanCompletion.EXHAUSTED, done.record.completion)
        assertTrue(done.record.earlyConceptWorkKeys.isEmpty())
        assertEquals("The witness is reused; only the new tail passage is fresh", 1, calls)
    }

    @Test fun anUnrelatedCatalogEpochChangeDoesNotDiscardAPartialScan() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        val base = record("note"); val original = base.passages.single()
        val note = base.copy(passages = listOf(original, original.copy(passage = original.passage.copy(id = "note:q", order = 1))))
        index.write(note); val retrievals = FileTopicRetrievalStore(root.resolve("retrieval"))
        val progress = FileProposalProgressStore(root.resolve("progress")); val topic = topic("first", 1)
        assertEquals(TopicRetrievalWriteResult.Written, retrievals.write(TopicRetrievalProgress(query(topic))) { true })
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = identity, complete = true)
        val sources = mapOf("seed" to "r1", "note" to "r1")
        suspend fun step(epoch: Long) = advanceDurableConceptScan(snapshot, note, identity, policy, verifier, epoch, sources,
            retrievals, index, progress, 1, { true }, { true }) {
            TopicSupportRun.Completed(SupportDecision.UNSUPPORTED)
        }
        assertEquals(DurableConceptScanResult.Pending, step(4))
        // An unrelated catalog mutation advances the in-memory catalog epoch. The Note's
        // topic/query/verifier/source identity is unchanged, so the partial scan must resume.
        var result = step(5)
        assertTrue("A partial scan must survive an unrelated catalog epoch change: $result",
            result is DurableConceptScanResult.Pending || result is DurableConceptScanResult.AwaitingValidation)
        var epoch = 6L
        while (result is DurableConceptScanResult.Pending) result = step(epoch++)
        assertTrue("The resumed scan must finish with a terminal checkpoint", result is DurableConceptScanResult.AwaitingValidation)
    }
}