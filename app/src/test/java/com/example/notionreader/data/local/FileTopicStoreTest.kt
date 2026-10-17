package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTopicStoreTest {
    @Test fun experimentalCandidatesBeyondLegacyAnchorLimitRemainPersistable() = runBlocking {
        val identity = EmbeddingIdentity("many-subjects", 70)
        val records = (0 until 70).map { n ->
            val heading = "Subject number $n"
            val p = SemanticPassage("p$n", "n$n", "s$n", heading, "b$n", "c$n", 0, 80,
                "$heading has independent explanatory body evidence.", "$heading body", 0)
            SemanticRecord("n$n", "r1", identity, SemanticMetadata("n$n"),
                listOf(EmbeddedPassage(p, List(70) { if (it == n) 1f else 0f })))
        }
        val snapshot = SectionHierarchicalEngine(identity).discover(records, null, TopicPolicy())
        assertEquals(70, snapshot.topics.size)
        val store = FileTopicStore(temporary.newFolder())
        assertEquals(TopicWriteResult.Written, store.writeDetailed(snapshot))
        assertEquals(snapshot, store.read())
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.CandidateBound),
            store.writeDetailed(snapshot.copy(engineVersion = 0)))
    }

    @Test fun malformedFormationCheckpointCannotReplaceThePreviousGeneration() = runBlocking {
        val store = FileTopicStore(temporary.newFolder())
        val prior = TopicSnapshot(generation = 4)
        assertTrue(store.write(prior))
        val corrupt = prior.copy(engineVersion = SectionHierarchicalEngine.VERSION,
            formationProgress = TopicFormationProgress("signature", 2, listOf(listOf(0), listOf(0))))
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.InvalidSnapshotProgress),
            store.writeDetailed(corrupt))
        assertEquals(prior, store.read())
    }
    @Test fun revisedCheckpointBranchesRemainPersistableWithoutReusingAnIdentity() = runBlocking {
        val model = EmbeddingIdentity("checkpoint-branches", 2)
        fun record(page: String, axis: Int, revision: String = "r1"): SemanticRecord {
            val text = if (axis == 0) "Forest ecosystems contain diverse trees" else "Planetary astronomy studies distant stars"
            val passage = SemanticPassage("$page:body", page, "$page:section", null, "$page:block", "$page:chunk",
                0, text.length, text, text, 0)
            return SemanticRecord(page, revision, model, SemanticMetadata(page),
                listOf(EmbeddedPassage(passage, if (axis == 0) listOf(1f, 0f) else listOf(0f, 1f))))
        }
        val initial = TopicBuilder(null, model).apply { accept(record("a", 0)); accept(record("b", 1)) }.checkpoint()
        val builder = TopicBuilder(initial, model)
        val store = FileTopicStore(temporary.newFolder())
        assertEquals(TopicWriteResult.Written, store.writeDetailed(builder.checkpoint()))
        builder.accept(record("a", 1, "r2")); builder.accept(record("c", 0))
        val revised = builder.checkpoint()
        assertEquals(TopicWriteResult.Written, store.writeDetailed(revised))
        assertEquals(revised, store.read())
    }
    @Test fun invalidSnapshotsAreNotReportedAsBudgetExhaustion() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        val prior = TopicSnapshot(generation = 4)
        assertTrue(store.write(prior))
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.InvalidSnapshot, TopicValidationReason.CandidateBound),
            store.writeDetailed(prior.copy(candidatePassLimit = 1)))
        assertEquals(prior, store.read())
    }
    @Test fun peakBudgetFailureHasAnExplicitOutcome() = runBlocking {
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded),
            FileTopicStore(temporary.newFolder(), 1).writeDetailed(TopicSnapshot()))
    }
    @Test fun ioFailurePreservesThePreviousGeneration() = runBlocking {
        val directory = temporary.newFolder()
        val prior = TopicSnapshot(generation = 4)
        assertTrue(FileTopicStore(directory).write(prior))
        val store = FileTopicStore(directory, otherBytes = { throw java.io.IOException("private details") })
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.IoFailure), store.writeDetailed(prior.copy(generation = 5)))
        assertEquals(prior, store.read())
    }
    @Test fun cancellationIsNotConvertedToAWriteFailure() = runBlocking {
        val store = FileTopicStore(temporary.newFolder(), otherBytes = { throw kotlinx.coroutines.CancellationException("cancelled") })
        try { store.writeDetailed(TopicSnapshot()); fail("Cancellation must propagate") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun atomicWriteIoFailureIsReportedWithoutLeavingStagingFiles() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileTopicStore(directory)
        File(directory, "topics.json").mkdir()
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.IoFailure), store.writeDetailed(TopicSnapshot()))
        assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
    }
    @Test fun legacyBooleanAdaptersReportUnknownWithoutInventingACause() = runBlocking {
        val legacy = object : TopicStore {
            override suspend fun read(): TopicSnapshot? = null
            override suspend fun write(snapshot: TopicSnapshot) = false
            override suspend fun clear() { }
            override suspend fun bytes() = 0L
        }
        assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.Unknown), legacy.writeDetailed(TopicSnapshot()))
    }
    @get:Rule val temporary = TemporaryFolder()
    @Test fun aCompletedSnapshotSurvivesRestart() = runBlocking {
        val directory = temporary.newFolder()
        val snapshot = TopicSnapshot(continuity = listOf(TopicContinuity("opaque", 1, setOf("page"))), generation = 3, complete = true)
        assertTrue(FileTopicStore(directory).write(snapshot))
        assertEquals(snapshot, FileTopicStore(directory).read())
    }
    @Test fun insufficientPeakBudgetPreservesPreviousGeneration() = runBlocking {
        val directory = temporary.newFolder()
        val snapshot = TopicSnapshot(generation = 1, complete = true)
        val store = FileTopicStore(directory)
        assertTrue(store.write(snapshot))
        val priorBytes = store.bytes()
        assertFalse(FileTopicStore(directory, priorBytes + 1).write(snapshot.copy(generation = 2)))
        assertEquals(1L, store.read()!!.generation)
    }
    @Test fun corruptSnapshotIsDiscardedWithoutPublishingEvidence() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "topics.json").writeText("{broken")
        assertNull(FileTopicStore(directory).read())
        assertEquals(0L, FileTopicStore(directory).bytes())
    }
    @Test fun otherDerivedDataParticipatesInBudget() = runBlocking {
        assertFalse(FileTopicStore(temporary.newFolder(), 100, { 100 }).write(TopicSnapshot()))
    }
}
