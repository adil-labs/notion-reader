package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CanonicalMembershipSchedulerTest {
    @Test fun boundedStepsResumeSavedScansAndDoNotRetireBlockedWork() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val text = "Seeds germinate with warmth and adequate moisture."
        val p = SemanticPassage("p", "seed", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        val member = TopicMembership("seed", SemanticMetadata("Seeds"), p, 1f, p, "r1")
        val key = semanticHash("concept")
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.", verifier, verifier,
            emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"))
        val topic = DiscoveredTopic("topic", "Seeds", p, EmbeddedPassage(p, listOf(1f, 0f)), listOf(member), 1,
            canonicalConcept = concept, canonicalProjection = CanonicalTopicProjection(key, "Seeds", "Seeds need moisture.",
                "Listen about seeds.", emptyList(), null))
        val snapshot = TopicSnapshot(topics = listOf(topic), identity = model, complete = true)
        val sources = mapOf("seed" to "r1", "later" to "r2")
        val saved = ExistingConceptScanRecord("later", "r2", sources, semanticHash("scope"), null, emptyList())
        var scans = 0; var commits = 0
        val scheduler = CanonicalMembershipScheduler()
        suspend fun step() = scheduler.step(snapshot, sources, verifier,
            scan = { id, restart -> assertEquals("later", id); assertFalse(restart); scans++; if (scans == 1) DurableConceptScanResult.Pending else DurableConceptScanResult.AwaitingValidation(saved) },
            commit = { _, record, _, _ -> assertEquals(saved, record); commits++; if (commits == 1) CanonicalMembershipUpdateResult.Deferred else CanonicalMembershipUpdateResult.Updated })
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals(CanonicalMembershipScheduleResult.Blocked, step())
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals(2, scans); assertEquals(2, commits)
        val completed = snapshot.copy(topics = listOf(topic.copy(canonicalConcept = concept.copy(scannedRevisions = sources, matchingComplete = true))))
        assertFalse(canonicalMembershipNeedsWork(completed, sources, verifier))
        val withdrawnCoverage = completed.copy(topics = listOf(topic.copy(canonicalConcept = concept.copy(scannedRevisions = sources, matchingComplete = false))))
        assertTrue(canonicalMembershipNeedsWork(withdrawnCoverage, sources, verifier))
        assertTrue(canonicalMembershipNeedsWork(completed, sources, verifier.copy(policy = "changed")))
        assertEquals(CanonicalMembershipScheduleResult.Idle, scheduler.step(completed, sources, verifier,
            scan = { _, _ -> error("Already covered") }, commit = { _, _, _, _ -> error("Already covered") }))
        scheduler.reset()
        val moreSources = sources + ("z" to "r3")
        val later = ExistingConceptScanRecord("z", "r3", moreSources, semanticHash("other"), null, emptyList())
        val seen = mutableListOf<String>()
        suspend fun fairStep() = scheduler.step(snapshot, moreSources, verifier,
            scan = { id, _ -> seen += id; if (id == "later") DurableConceptScanResult.Incomplete else DurableConceptScanResult.AwaitingValidation(later) },
            commit = { _, _, _, _ -> CanonicalMembershipUpdateResult.Updated })
        assertEquals(CanonicalMembershipScheduleResult.Progress, fairStep())
        assertEquals(CanonicalMembershipScheduleResult.Progress, fairStep())
        assertEquals(CanonicalMembershipScheduleResult.Progress, fairStep())
        assertEquals(listOf("later", "z"), seen)
        val blockedScheduler = CanonicalMembershipScheduler(); var attempts = 0
        suspend fun blockedStep() = blockedScheduler.step(snapshot, moreSources, verifier,
            scan = { _, _ -> attempts++; DurableConceptScanResult.Incomplete },
            commit = { _, _, _, _ -> error("No commit") })
        assertEquals(CanonicalMembershipScheduleResult.Progress, blockedStep())
        assertEquals(CanonicalMembershipScheduleResult.Blocked, blockedStep())
        assertEquals(CanonicalMembershipScheduleResult.Blocked, blockedStep())
        assertEquals(2, attempts)
        blockedScheduler.retryBlocked()
        assertEquals(CanonicalMembershipScheduleResult.Progress, blockedStep())
        assertEquals(3, attempts)
    }
}
