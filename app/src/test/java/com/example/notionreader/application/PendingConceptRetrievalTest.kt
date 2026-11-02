package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingConceptRetrievalTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun candidatePagesRequireSupportAndBindContinuationToTheExactQueryVector() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val store = FileProposalProgressStore(root.resolve("progress"))
        val embedding = EmbeddingIdentity("model", 2); val work = semanticHash("concept")
        val concept = SupportConcept("pending:$work", work, "Seed germination", "Conditions needed for seeds to germinate.")
        val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, work, "Seed germination\nConditions needed for seeds to germinate.",
            embedding, TopicRetrievalPolicy(.5f)), listOf(1f, 0f))
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        fun passage(id: String, order: Int): EmbeddedPassage {
            val text = "Moisture and suitable temperature allow seeds to germinate and begin sprouting."
            return EmbeddedPassage(SemanticPassage(id, "note", "section", "Overview", "block$id", "chunk$id", 0,
                text.length, text, text, order), listOf(1f, 0f))
        }
        val record = SemanticRecord("note", "r1", embedding, SemanticMetadata("note"), listOf(passage("a", 0), passage("b", 1)))
        assertTrue(index.write(record)); var calls = 0
        suspend fun step(q: TopicQueryEmbedding, cursor: PendingRetrievalCursor?) = retrievePendingConceptPage(concept, q, record, index,
            verifier, 4, mapOf("note" to "r1"), cursor, 1, store, { true }, { true }) {
            calls++; TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.UNSUPPORTED else SupportDecision.SUPPORTED)
        }
        val first = step(query, null) as PendingConceptRetrievalPage.Ready
        assertTrue(first.supported.isEmpty()); assertNotNull(first.next)
        assertEquals(PendingConceptRetrievalPage.Stale, step(query.copy(vector = listOf(1f, .00001f)), first.next))
        val second = step(query, first.next) as PendingConceptRetrievalPage.Ready
        assertEquals(setOf("b"), second.supported); assertNull(second.next); assertEquals(2, calls)
        assertEquals(first, step(query, null)); assertEquals(2, calls)
    }

    @Test fun noteScanAccumulatesPagesAndRejectsChangedConcept() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val store = FileProposalProgressStore(root.resolve("progress"))
        val embedding = EmbeddingIdentity("model", 2); val work = semanticHash("concept")
        val concept = SupportConcept("pending:$work", work, "Seeds", "Seeds germinate with water.")
        val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, work, "Seeds\nSeeds germinate with water.", embedding, TopicRetrievalPolicy(.5f)), listOf(1f, 0f))
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        fun passage(id: String, order: Int): EmbeddedPassage {
            val text = "Water helps seeds germinate in suitable conditions."
            return EmbeddedPassage(SemanticPassage(id, "note", "section", "Overview", "block$id", "chunk$id", 0, text.length, text, text, order), listOf(1f, 0f))
        }
        val record = SemanticRecord("note", "r1", embedding, SemanticMetadata("note"), listOf(passage("a", 0), passage("b", 1)))
        assertTrue(index.write(record))
        suspend fun step(c: SupportConcept, state: PendingConceptNoteScan) = scanPendingConceptNote(c, query, record, index, verifier, 4, mapOf("note" to "r1"), state, 1, store, { true }, { true }) {
            TopicSupportRun.Completed(if (it.evidence.passageId == "a") SupportDecision.SUPPORTED else SupportDecision.UNCERTAIN)
        }
        val first = step(concept, PendingConceptNoteScan.start()) as PendingConceptNoteScanResult.Pending
        assertEquals(PendingConceptNoteScanResult.Stale, step(concept.copy(definition = "Changed definition"), first.scan))
        val changedRecord = record.copy(passages = listOf(record.passages[0], record.passages[1].copy(vector = listOf(0f, 1f))))
        assertTrue(index.write(changedRecord))
        val changed = scanPendingConceptNote(concept, query, changedRecord, index, verifier, 4, mapOf("note" to "r1"), first.scan, 1, store, { true }, { true }) { TopicSupportRun.Unavailable }
        assertEquals(PendingConceptNoteScanResult.Stale, changed)
        assertTrue(index.write(record))
        val done = step(concept, first.scan) as PendingConceptNoteScanResult.Uncertain
        assertEquals(setOf("a"), done.supported); assertEquals(setOf("b"), done.uncertain)
        assertEquals(ProposalProgressWriteResult.Written, savePendingNoteResult(concept, record, mapOf("note" to "r1"), done, null, store) { true })
        val checkpoint = store.readPendingNoteScan(work, "note", mapOf("note" to "r1"))!!
        val firstReplay = replayPendingNoteScan(checkpoint, PendingConceptNoteScan.start(), concept, query, record, index, verifier, 4,
            mapOf("note" to "r1"), store, { true }, { true }) as PendingNoteReplayResult.Pending
        val replayed = replayPendingNoteScan(checkpoint, firstReplay.scan, concept, query, record, index, verifier, 4,
            mapOf("note" to "r1"), store, { true }, { true }) as PendingNoteReplayResult.Uncertain
        assertEquals(setOf("a"), replayed.supported); assertEquals(setOf("b"), replayed.uncertain)
    }
    @Test fun checkpointReplayRechecksStoredEvidenceBeforeResuming() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); val store = FileProposalProgressStore(root.resolve("progress"))
        val embedding = EmbeddingIdentity("model", 2); val work = semanticHash("concept")
        val concept = SupportConcept("pending:$work", work, "Seeds", "Seeds germinate with water.")
        val query = TopicQueryEmbedding(TopicRetrievalQuery(concept.topicId, work, "Seeds\nSeeds germinate with water.", embedding, TopicRetrievalPolicy(.5f)), listOf(1f, 0f))
        val verifier = TopicLabelIdentity("a", "t", "template", "support", "schema", "v1", "CPU")
        fun passage(id: String, order: Int): EmbeddedPassage {
            val text = "Water helps seeds germinate in suitable conditions."
            return EmbeddedPassage(SemanticPassage(id, "note", "section", "Overview", "block$id", "chunk$id", 0, text.length, text, text, order), listOf(1f, 0f))
        }
        val record = SemanticRecord("note", "r1", embedding, SemanticMetadata("note"), listOf(passage("a", 0), passage("b", 1), passage("c", 2)))
        val sources = mapOf("note" to "r1"); assertTrue(index.write(record))
        val first = scanPendingConceptNote(concept, query, record, index, verifier, 4, sources, PendingConceptNoteScan.start(), 2, store, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) } as PendingConceptNoteScanResult.Pending
        val saved = PendingNoteScanRecord(work, "note", "r1", sources, first.scan.binding!!, first.scan.next!!.passage, first.scan.supported, first.scan.uncertain)
        assertEquals(ProposalProgressWriteResult.Written, savePendingNoteScan(concept, record, sources, first.scan, null, store) { true })
        assertEquals(saved, store.readPendingNoteScan(work, "note", sources))
        suspend fun replay(checkpoint: PendingNoteScanRecord, progress: ProposalProgressStore = store, state: PendingConceptNoteScan = PendingConceptNoteScan.start()) = replayPendingNoteScan(checkpoint, state, concept, query, record, index, verifier, 4, sources, progress, { true }, { true })
        val partial = replay(saved) as PendingNoteReplayResult.Pending
        val restored = replay(saved, state = partial.scan) as PendingNoteReplayResult.Validated
        assertEquals(first.scan.next, restored.scan.next); assertEquals(setOf("a", "b"), restored.scan.supported)
        val forged = saved.copy(supported = emptySet())
        assertEquals(ProposalProgressWriteResult.Written, store.replacePendingNoteScan(saved, forged) { true })
        assertEquals(PendingNoteReplayResult.Invalid, replay(forged))
        assertEquals(ProposalProgressWriteResult.Written, store.replacePendingNoteScan(forged, saved) { true })
        val missing = object : ProposalProgressStore by store { override suspend fun readSupport(workKey: String, sources: Map<String, String>): TopicSupportAssessment? = null }
        assertEquals(PendingNoteReplayResult.Incomplete, replay(saved, missing))
        val done = scanPendingConceptNote(concept, query, record, index, verifier, 4, sources, restored.scan, 1, store, { true }, { true }) { TopicSupportRun.Completed(SupportDecision.SUPPORTED) }
        assertTrue(done is PendingConceptNoteScanResult.Complete)
        assertEquals(ProposalProgressWriteResult.Written, savePendingNoteResult(concept, record, sources, done, saved, store) { true })
        val completed = store.readPendingNoteScan(work, "note", sources)!!
        assertNull(completed.cursor)
        var state = PendingConceptNoteScan.start()
        var outcome: PendingNoteReplayResult
        do {
            outcome = replay(completed, state = state)
            if (outcome is PendingNoteReplayResult.Pending) state = outcome.scan
        } while (outcome is PendingNoteReplayResult.Pending)
        assertEquals(setOf("a", "b", "c"), (outcome as PendingNoteReplayResult.Complete).supported)
        val forgedCompleted = completed.copy(supported = completed.supported + "invented")
        assertEquals(ProposalProgressWriteResult.Written, store.replacePendingNoteScan(completed, forgedCompleted) { true })
        state = PendingConceptNoteScan.start()
        do {
            outcome = replay(forgedCompleted, state = state)
            if (outcome is PendingNoteReplayResult.Pending) state = outcome.scan
        } while (outcome is PendingNoteReplayResult.Pending)
        assertEquals(PendingNoteReplayResult.Invalid, outcome)
    }}
