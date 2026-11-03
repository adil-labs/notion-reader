package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class EvidenceFirstSchedulerTest {
    private fun proposal(id: String) = IncrementalProposal(id, emptyList(), emptySet(), emptySet())
    private fun ready(ids: List<String>, next: Int? = null, pending: Boolean = false) =
        EvidenceFirstNoteStep.Proposals(IncrementalProposalStepResult.Ready(ProposalWindow(ids.map(::proposal), next), pending))

    @Test fun boundedTraversalContinuesPastAbstentionsAndAnchorWindows() = runBlocking {
        val scheduler = EvidenceFirstScheduler()
        val seen = mutableListOf<Pair<Int, Int>>()
        suspend fun step() = scheduler.step("scope", listOf("note"),
            inspect = { _, offset, _, _ -> if (offset == 0) ready(listOf("a", "b"), 8) else ready(listOf("c")) },
            process = { work -> seen += work.anchorOffset to work.proposalIndex
                PendingCorpusWorkResult.Abstained(DiscoveryAbstentionReason.NO_USEFUL_TOPIC, emptySet(), false) },
            admit = { _, _ -> error("Abstentions cannot be admitted") })
        repeat(8) { step() }
        assertEquals(listOf(0 to 0, 0 to 1, 8 to 0), seen)
        assertEquals(EvidenceFirstScheduleResult.Idle, step())
    }

    @Test fun incompleteWorkRemainsPendingAndDoesNotStarveOtherNotes() = runBlocking {
        val scheduler = EvidenceFirstScheduler()
        val seen = mutableListOf<String>()
        suspend fun step() = scheduler.step("scope", listOf("a", "b"),
            inspect = { page, _, _, _ -> seen += page; ready(listOf(page)) },
            process = { work -> PendingCorpusWorkResult.Abstained(DiscoveryAbstentionReason.NO_USEFUL_TOPIC,
                if (work.pageId == "a") setOf(ProposalIncompleteReason.SOURCE_SCAN) else emptySet(), false) },
            admit = { _, _ -> error("Abstentions cannot be admitted") })
        repeat(6) { step() }
        assertTrue(seen.containsAll(listOf("a", "b")))
        assertEquals(EvidenceFirstScheduleResult.Blocked, step())
        val before = seen.size
        assertEquals(EvidenceFirstScheduleResult.Blocked, step())
        assertEquals(before, seen.size)
        scheduler.retryBlocked()
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertTrue(seen.size > before)
    }

    @Test fun unfinishedSourceScanCannotRetireAnEmptyWindow() = runBlocking {
        val scheduler = EvidenceFirstScheduler(); var checks = 0
        suspend fun step() = scheduler.step("scope", listOf("note"),
            inspect = { _, _, _, _ -> checks++; ready(emptyList(), pending = checks == 1) },
            process = { error("No proposal") }, admit = { _, _ -> error("No candidate") })
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertEquals(EvidenceFirstScheduleResult.Idle, step())
        assertEquals(2, checks)
    }
    @Test fun staleSavedScanIsRestartedOnceBeforeParkingTheNote() = runBlocking {
        val scheduler = EvidenceFirstScheduler(); val restarts = mutableListOf<Boolean>()
        suspend fun step() = scheduler.step("scope", listOf("note"),
            inspect = { _, _, _, restart -> restarts += restart
                if (restart) ready(emptyList()) else EvidenceFirstNoteStep.Stale },
            process = { error("No proposal") }, admit = { _, _ -> error("No candidate") })
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertEquals(EvidenceFirstScheduleResult.Idle, step())
        assertEquals(listOf(false, true), restarts)
    }

    @Test fun retryingABlockedNoteAllowsASecondStaleScanRecovery() = runBlocking {
        val scheduler = EvidenceFirstScheduler(); var recover = false
        suspend fun step() = scheduler.step("scope", listOf("note"),
            inspect = { _, _, _, restart -> if (recover && restart) ready(emptyList()) else EvidenceFirstNoteStep.Stale },
            process = { error("No proposal") }, admit = { _, _ -> error("No candidate") })
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertEquals(EvidenceFirstScheduleResult.Blocked, step())
        recover = true; scheduler.retryBlocked()
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertEquals(EvidenceFirstScheduleResult.Progress, step())
        assertEquals(EvidenceFirstScheduleResult.Idle, step())
    }

    @Test fun resetDuringAnInFlightStepDiscardsItsResult() = runBlocking {
        val scheduler = EvidenceFirstScheduler()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = async { scheduler.step("scope", listOf("note"),
            inspect = { _, _, _, _ -> entered.complete(Unit); release.await(); ready(listOf("old")) },
            process = { error("Reset work cannot run") }, admit = { _, _ -> error("No candidate") }) }
        entered.await(); scheduler.reset(); release.complete(Unit)
        assertEquals(EvidenceFirstScheduleResult.Deferred, old.await())
        assertEquals(EvidenceFirstScheduleResult.Progress, scheduler.step("scope", listOf("note"),
            inspect = { _, _, _, _ -> ready(emptyList()) },
            process = { error("Old proposal must not survive reset") }, admit = { _, _ -> error("No candidate") }))
        assertEquals(EvidenceFirstScheduleResult.Idle, scheduler.step("scope", listOf("note"),
            inspect = { _, _, _, _ -> error("Completed") },
            process = { error("Old proposal must not survive reset") }, admit = { _, _ -> error("No candidate") }))
    }

}
