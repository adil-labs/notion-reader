package com.example.notionreader.application

import com.example.notionreader.data.local.FileProposalProgressStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncrementalProposalStepTest {
    @get:Rule val files = TemporaryFolder()
    private val identity = EmbeddingIdentity("model", 2)
    private fun record(id: String): SemanticRecord {
        val body = "Moisture allows dormant seeds to absorb water and begin the germination process."
        val passage = SemanticPassage("$id:p", id, "$id:s", "Overview", "$id:b", "$id:c", 0, body.length, body, body, 0)
        return SemanticRecord(id, "r1", identity, SemanticMetadata(id), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
    }
    private val changed = record("new")
    private val sources = mapOf("new" to "r1", "old1" to "r1", "old2" to "r1")

    @Test fun boundedWindowsRetainEarlierEvidenceAcrossStoreRecreation() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        index.write(changed); index.write(record("old1")); index.write(record("old2"))
        var reads = 0
        suspend fun step() = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
            emptySet(), true, 0, FileProposalProgressStore(root.resolve("progress")), 1, { true }, { true }) {
            reads++; index.read(it)
        } as IncrementalProposalStepResult.Ready
        val first = step()
        assertTrue(first.scanPending); assertEquals(1, reads)
        assertTrue(ProposalIncompleteReason.SOURCE_SCAN in first.window.proposals.single().incomplete)
        val second = step()
        assertFalse(second.scanPending); assertEquals(2, reads)
        assertEquals(setOf("new", "old1", "old2"), second.window.proposals.single().evidence.map { it.pageId }.toSet())
        assertFalse(ProposalIncompleteReason.SOURCE_SCAN in second.window.proposals.single().incomplete)
    }

    @Test fun addingASourceDoesNotDiscardCompletedScansForUnchangedSources() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index"))
        index.write(record("old1")); index.write(record("old2")); index.write(record("old3"))
        val store = FileProposalProgressStore(root.resolve("progress")); val reads = mutableListOf<String>()
        suspend fun step(currentSources: Map<String, String>) = advanceIncrementalProposalWindow(changed,
            setOf("new:p"), emptySet(), currentSources, emptySet(), true, 0, store, 1, { true }, { true }) {
            reads += it; index.read(it)
        } as IncrementalProposalStepResult.Ready
        assertTrue(step(sources).scanPending)
        val afterAddition = step(sources + ("old3" to "r1"))
        assertEquals(listOf("old1", "old2"), reads)
        assertTrue(afterAddition.scanPending)
        assertEquals(setOf("new", "old1", "old2"), afterAddition.window.proposals.single().evidence.map { it.pageId }.toSet())
    }

    @Test fun unreadableSourcesDoNotStarveLaterSourcesAndCoverageRemainsIncomplete() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); index.write(record("old2"))
        val store = FileProposalProgressStore(root.resolve("progress")); val attempted = mutableListOf<String>()
        suspend fun step() = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
            emptySet(), true, 0, store, 1, { true }, { true }) { attempted += it; index.read(it) } as IncrementalProposalStepResult.Ready
        assertTrue(step().scanPending)
        val next = step()
        assertTrue(next.scanPending); assertEquals(listOf("old1", "old2"), attempted)
        assertTrue(next.window.proposals.single().evidence.any { it.pageId == "old2" })
        assertTrue(ProposalIncompleteReason.SOURCE_SCAN in next.window.proposals.single().incomplete)
    }

    @Test fun aSourceChangeDuringReadCannotCommitAStaleCheckpoint() = runBlocking {
        val root = files.newFolder(); val store = FileProposalProgressStore(root.resolve("progress")); var current = true
        val result = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
            emptySet(), true, 0, store, 1, { current }, { true }) { current = false; record(it) }
        assertEquals(IncrementalProposalStepResult.Stale, result)
        assertNull(store.read("new", 0, sources)); assertEquals(0L, store.bytes())
    }

    @Test fun aStorageFailureCannotBeReportedAsSavedProgress() = runBlocking {
        val root = files.newFolder(); val real = FileProposalProgressStore(root.resolve("progress"))
        val refused = object : ProposalProgressStore by real {
            override suspend fun write(record: ProposalProgressRecord, stillCurrent: () -> Boolean) = ProposalProgressWriteResult.StorageRefused
        }
        val result = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
            emptySet(), true, 0, refused, 1, { true }, { true }) { record(it) }
        assertEquals(IncrementalProposalStepResult.StorageFailure(ProposalProgressWriteResult.StorageRefused), result)
        assertEquals(0L, real.bytes())
    }

    @Test fun aPowerOrForegroundDeferralDuringReadDoesNotStartFurtherProcessing() = runBlocking {
        val store = FileProposalProgressStore(files.newFolder()); var allowed = true
        val result = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
            emptySet(), true, 0, store, 1, { true }, { allowed }) { allowed = false; record(it) }
        assertEquals(IncrementalProposalStepResult.Deferred, result)
        assertNull(store.read("new", 0, sources))
    }

    @Test fun unchangedEvidenceDoesNotReadOlderNotes() = runBlocking {
        val store = FileProposalProgressStore(files.newFolder())
        val result = advanceIncrementalProposalWindow(changed, emptySet(), emptySet(), sources,
            emptySet(), false, 0, store, 1, { true }, { true }) { error("No corpus read is needed") }
            as IncrementalProposalStepResult.Ready
        assertTrue(result.window.proposals.isEmpty()); assertFalse(result.scanPending)
    }

    @Test fun cancellationKeepsTheLastCommittedWindowForResume() = runBlocking {
        val root = files.newFolder(); val store = FileProposalProgressStore(root.resolve("progress"))
        val first = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
            emptySet(), true, 0, store, 1, { true }, { true }) { record(it) } as IncrementalProposalStepResult.Ready
        assertTrue(first.scanPending)
        val committed = store.read("new", 0, sources)!!
        try {
            advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
                emptySet(), true, 0, store, 1, { true }, { true }) { throw CancellationException("stop") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(committed, FileProposalProgressStore(root.resolve("progress")).read("new", 0, sources))
    }

    @Test fun staleTicketDuringAtomicWriteCannotCommitAWindow() = runBlocking {
        val root = files.newFolder(); var current = true
        val gate = object : com.example.notionreader.domain.document.StorageWriteGate {
            override suspend fun <T> write(category: com.example.notionreader.domain.document.StorageCategory,
                additionalPeakBytes: Long, block: suspend () -> T): T { current = false; return block() }
        }
        val store = FileProposalProgressStore(root.resolve("progress"), gate)
        val result = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), sources,
            emptySet(), true, 0, store, 1, { current }, { true }) { record(it) }
        assertEquals(IncrementalProposalStepResult.Stale, result)
        assertNull(store.read("new", 0, sources)); assertEquals(0L, store.bytes())
    }

    @Test fun oneIndexedNoteCanProduceAConceptWithoutGlobalFormation() = runBlocking {
        val root = files.newFolder(); val index = FileSemanticIndexStore(root.resolve("index")); index.write(changed)
        val store = FileProposalProgressStore(root.resolve("progress")); val only = mapOf("new" to "r1")
        val step = advanceIncrementalProposalWindow(changed, setOf("new:p"), emptySet(), only,
            emptySet(), false, 0, store, 1, { true }, { true }) { error("No other source exists") }
            as IncrementalProposalStepResult.Ready
        val model = TopicLabelIdentity("a", "t", "template", "prompt", "schema", "policy", "CPU")
        val result = generateProposal(step.window.proposals.single(), index, identity, model, 5, only, { true }, store) { request ->
            assertEquals(listOf("new:p"), request.evidence.map { it.passageId })
            DiscoveryProposalRun.Completed(DiscoveryProposalResponse.Concept("Seed germination conditions",
                "How moisture supports seed germination.", "Listen to the conditions for germination.", listOf("new:p")))
        } as ProposalGenerationResult.Completed
        assertTrue(result.response is DiscoveryProposalResponse.Concept)
        assertTrue(ProposalIncompleteReason.STRUCTURE in result.incomplete)
        assertFalse(step.scanPending)
    }
}
