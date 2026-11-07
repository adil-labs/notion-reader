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
        assertEquals(CanonicalMembershipScheduleResult.Deferred, step())
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals(2, scans); assertEquals(2, commits)
        val completed = snapshot.copy(topics = listOf(topic.copy(canonicalConcept = concept.copy(scannedRevisions = sources, matchingComplete = true))))
        assertFalse(canonicalMembershipNeedsWork(completed, sources, verifier))
        // A verified Note with an unchecked candidate tail has complete membership coverage,
        // so the scheduler must reach Idle instead of retrying that Note forever.
        val uncheckedCoverage = snapshot.copy(topics = listOf(topic.copy(canonicalConcept = concept.copy(
            scannedRevisions = sources, matchingComplete = true, uncheckedRevisions = mapOf("seed" to "r1")))))
        assertTrue(canonicalMembershipNeedsWork(uncheckedCoverage, sources, verifier))
        assertEquals(CanonicalMembershipScheduleResult.Deferred, scheduler.step(uncheckedCoverage, sources, verifier,
            scan = { id, _ -> assertEquals("seed", id); DurableConceptScanResult.Deferred },
            commit = { _, _, _, _ -> error("No checkpoint yet") }))
        val withdrawnCoverage = completed.copy(topics = listOf(topic.copy(canonicalConcept = concept.copy(scannedRevisions = sources, matchingComplete = false))))
        // A stale completeness summary is not work. Work is derived from unscanned Notes and
        // unchecked tails, so a fully scanned concept must not be re-scanned to repair a flag.
        assertFalse(canonicalMembershipNeedsWork(withdrawnCoverage, sources, verifier))
        val freshlyAccepted = sources + ("new" to "r9")
        assertTrue("A newly accepted Note is membership work",
            canonicalMembershipNeedsWork(completed, freshlyAccepted, verifier))
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
        // An automatic wake-up must not reopen work blocked for the same source revision.
        blockedScheduler.continueAutomatically()
        assertEquals(CanonicalMembershipScheduleResult.Blocked, blockedStep())
        assertEquals(2, attempts)
        // An explicit user retry may reopen it.
        blockedScheduler.retryBlocked()
        assertEquals(CanonicalMembershipScheduleResult.Progress, blockedStep())
        assertEquals(3, attempts)
    }

    private fun fixture(concept: CanonicalConceptEvidence, model: EmbeddingIdentity): Pair<TopicSnapshot, TopicLabelIdentity> {
        val text = "Seeds germinate with warmth and adequate moisture."
        val p = SemanticPassage("p", "seed", "s", "Seeds", "b", "c", 0, text.length, text, text, 0)
        val member = TopicMembership("seed", SemanticMetadata("Seeds"), p, 1f, p, "r1")
        val topic = DiscoveredTopic("topic", "Seeds", p, EmbeddedPassage(p, listOf(1f, 0f)), listOf(member), 1,
            canonicalConcept = concept, canonicalProjection = CanonicalTopicProjection(concept.workKey, "Seeds",
                "Seeds need moisture.", "Listen about seeds.", emptyList(), null))
        return TopicSnapshot(topics = listOf(topic), identity = model, complete = true) to concept.verifier
    }

    @Test fun anUncheckedTailRunsAsADistinctExhaustiveScan() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val key = semanticHash("concept")
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.", verifier, verifier,
            emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"), matchingComplete = true,
            uncheckedRevisions = mapOf("seed" to "r1"))
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("seed" to "r1")
        val exhausted = ExistingConceptScanRecord("seed", "r1", sources, semanticHash("scope"), null, emptyList(),
            completion = ConceptScanCompletion.EXHAUSTED)
        val scheduler = CanonicalMembershipScheduler()
        val restarts = mutableListOf<Boolean>(); var commits = 0
        suspend fun step() = scheduler.step(snapshot, sources, identity,
            scan = { id, restart -> assertEquals("seed", id); restarts += restart; DurableConceptScanResult.AwaitingValidation(exhausted) },
            commit = { _, _, _, _ -> commits++; CanonicalMembershipUpdateResult.Updated })
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        // The completed tail commits in the same bounded step; with the tail cleared, the
        // scheduler is idle instead of re-scanning the same Note.
        val cleared = snapshot.copy(topics = listOf(snapshot.topics.single().copy(
            canonicalConcept = snapshot.topics.single().canonicalConcept!!.copy(uncheckedRevisions = emptyMap()))))
        assertEquals(CanonicalMembershipScheduleResult.Idle, scheduler.step(cleared, sources, identity,
            scan = { _, _ -> error("The completed tail must not be scanned again") },
            commit = { _, _, _, _ -> error("The completed tail must not be committed again") }))
        assertEquals("Tail work must not force a scheduler restart; the scan owns durable tail semantics",
            listOf(false), restarts)
        assertEquals(1, commits)
    }

    @Test fun tailReconciliationCannotBeStarvedByContinuousMembershipWork() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val key = semanticHash("concept")
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.", verifier, verifier,
            emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"), matchingComplete = true,
            uncheckedRevisions = mapOf("seed" to "r1"))
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("seed" to "r1", "a" to "ra", "b" to "rb")
        val scheduler = CanonicalMembershipScheduler()
        val scanned = mutableListOf<String>(); var recorded = 0
        suspend fun step() = scheduler.step(snapshot, sources, identity,
            scan = { id, _ -> scanned += id
                DurableConceptScanResult.AwaitingValidation(ExistingConceptScanRecord(id, sources.getValue(id), sources,
                    semanticHash("scope-$id"), null, emptyList(), completion = ConceptScanCompletion.EXHAUSTED)) },
            commit = { _, _, _, _ -> recorded++; CanonicalMembershipUpdateResult.Updated })
        repeat(6) { step() }
        assertTrue("The tail must run within a bounded number of membership turns; scanned=$scanned",
            scanned.contains("seed"))
    }

    @Test fun aDeferredTailReturnsTheTurnToMembership() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val key = semanticHash("concept")
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.", verifier, verifier,
            emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"), matchingComplete = true,
            uncheckedRevisions = mapOf("seed" to "r1"))
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("seed" to "r1", "a" to "ra", "b" to "rb")
        val scheduler = CanonicalMembershipScheduler()
        val scanned = mutableListOf<String>()
        suspend fun step() = scheduler.step(snapshot, sources, identity,
            scan = { id, _ -> scanned += id
                if (id == "seed") DurableConceptScanResult.Deferred
                else DurableConceptScanResult.AwaitingValidation(ExistingConceptScanRecord(id, sources.getValue(id), sources,
                    semanticHash("scope-$id"), null, emptyList(), completion = ConceptScanCompletion.EXHAUSTED)) },
            commit = { _, _, _, _ -> CanonicalMembershipUpdateResult.Updated })
        repeat(6) { step() }
        assertTrue("A permanently deferred tail must not starve membership; scanned=$scanned",
            scanned.containsAll(listOf("a", "b")))
    }

    @Test fun aBlockedNoteSurvivesAnUnrelatedCatalogChangeButReopensForItsOwnDependency() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val key = semanticHash("concept")
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "Seeds\nSeeds need moisture.",
            model, TopicRetrievalPolicy(.8f)), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.", verifier, verifier,
            emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"), queryEmbedding = query)
        val (snapshot, identity) = fixture(concept, model)
        var sources = mapOf("seed" to "r1", "later" to "r2")
        val scheduler = CanonicalMembershipScheduler()
        var attempts = 0
        suspend fun step(s: TopicSnapshot = snapshot, src: Map<String, String> = sources,
            policy: String = "policy-1") = scheduler.step(s, src, identity, retrievalPolicyIdentity = policy,
            scan = { id, _ -> attempts++; assertEquals("later", id); DurableConceptScanResult.Incomplete },
            commit = { _, _, _, _ -> error("No checkpoint") })
        // The Note is blocked after its first incomplete scan.
        assertEquals(CanonicalMembershipScheduleResult.Blocked, step())
        assertEquals(1, attempts)
        // An unrelated catalog generation change must not reopen it.
        assertEquals(CanonicalMembershipScheduleResult.Blocked, step(snapshot.copy(generation = snapshot.generation + 1)))
        assertEquals(1, attempts)
        // The concept query changed: the dependency for this Note changed, so it is retried.
        val changedQuery = snapshot.copy(topics = listOf(snapshot.topics.single().copy(
            canonicalConcept = concept.copy(queryEmbedding = query.copy(vector = listOf(0f, 1f))))))
        assertEquals(CanonicalMembershipScheduleResult.Blocked, step(changedQuery))
        assertEquals(2, attempts)
        // A retrieval-policy change is also part of the dependency identity.
        assertEquals(CanonicalMembershipScheduleResult.Blocked, step(changedQuery, sources, "policy-2"))
        assertEquals(3, attempts)
        // A source revision change reopens the Note.
        sources = sources + ("later" to "r3")
        assertEquals(CanonicalMembershipScheduleResult.Blocked, step(changedQuery))
        assertEquals(4, attempts)
    }

    @Test fun membershipDependencyIncludesVerifierPolicyAndConceptQuery() {
        val model = EmbeddingIdentity("model", 2)
        val key = semanticHash("concept")
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val query = TopicQueryEmbedding(TopicRetrievalQuery("topic", key, "Seeds\nSeeds need moisture.",
            model, TopicRetrievalPolicy(.8f)), listOf(1f, 0f))
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.", verifier, verifier,
            emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"), queryEmbedding = query)
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("seed" to "r1", "later" to "r2")
        val work = MembershipWork("later", MembershipWorkKind.MEMBERSHIP)
        val base = membershipDependency(work, snapshot, sources, identity, "policy-1")
        assertEquals(base, membershipDependency(work, snapshot.copy(generation = snapshot.generation + 1), sources, identity, "policy-1"))
        assertNotEquals(base, membershipDependency(work, snapshot, sources, identity.copy(policy = "changed"), "policy-1"))
        assertNotEquals(base, membershipDependency(work, snapshot, sources, identity, "policy-2"))
        assertNotEquals(base, membershipDependency(work, snapshot.topics.single().let { topic ->
            snapshot.copy(topics = listOf(topic.copy(canonicalConcept = concept.copy(queryEmbedding = query.copy(vector = listOf(0f, 1f))))))
        }, sources, identity, "policy-1"))
        assertNotEquals(base, membershipDependency(work, snapshot, sources + ("later" to "r3"), identity, "policy-1"))
    }

    @Test fun tailRunsEvenWhenMembershipWorkStaysContinuouslyPending() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val key = semanticHash("concept")
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(key, "Seeds", "Seeds need moisture.", "Listen about seeds.", verifier, verifier,
            emptyList(), emptyList(), scannedRevisions = mapOf("seed" to "r1"), matchingComplete = true,
            uncheckedRevisions = mapOf("seed" to "r1"))
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("seed" to "r1", "a" to "ra", "b" to "rb")
        val exhausted = ExistingConceptScanRecord("seed", "r1", sources, semanticHash("scope"), null, emptyList(),
            completion = ConceptScanCompletion.EXHAUSTED)
        val scheduler = CanonicalMembershipScheduler()
        val scanned = mutableListOf<String>()
        suspend fun step() = scheduler.step(snapshot, sources, identity,
            scan = { id, _ -> scanned += id
                if (id == "seed") DurableConceptScanResult.AwaitingValidation(exhausted) else DurableConceptScanResult.Pending },
            commit = { _, _, _, _ -> CanonicalMembershipUpdateResult.Updated })
        repeat(8) { step() }
        assertTrue("A continuously pending membership scan must not starve the tail; scanned=$scanned",
            scanned.contains("seed"))
    }

    @Test fun automaticContinuationPreservesAnAwaitingValidationCommit() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(semanticHash("concept"), "Seeds", "Seeds need moisture.",
            "Listen about seeds.", verifier, verifier, emptyList(), emptyList(), scannedRevisions = emptyMap())
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("seed" to "r1")
        val record = ExistingConceptScanRecord("seed", "r1", sources, semanticHash("scope"), null, emptyList(),
            completion = ConceptScanCompletion.EXHAUSTED)
        var scans = 0; var commits = 0
        val scheduler = CanonicalMembershipScheduler()
        suspend fun step() = scheduler.step(snapshot, sources, identity,
            scan = { id, _ -> scans++; assertEquals("seed", id); DurableConceptScanResult.AwaitingValidation(record) },
            commit = { id, saved, _, _ -> commits++; assertEquals("seed", id); assertEquals(record, saved)
                CanonicalMembershipUpdateResult.Updated })
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals(1, scans); assertEquals(0, commits)
        scheduler.continueAutomatically()
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals("Automatic continuation must preserve active continuation state", 1, scans)
        assertEquals(1, commits)
    }

    @Test fun anUnrelatedCatalogGenerationChangePreservesActiveContinuation() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(semanticHash("concept"), "Seeds", "Seeds need moisture.",
            "Listen about seeds.", verifier, verifier, emptyList(), emptyList(), scannedRevisions = emptyMap())
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("seed" to "r1")
        val record = ExistingConceptScanRecord("seed", "r1", sources, semanticHash("scope"), null, emptyList(),
            completion = ConceptScanCompletion.EXHAUSTED)
        var scans = 0; var commits = 0
        val scheduler = CanonicalMembershipScheduler()
        suspend fun step(catalog: TopicSnapshot) = scheduler.step(catalog, sources, identity,
            scan = { _, _ -> scans++; DurableConceptScanResult.AwaitingValidation(record) },
            commit = { _, saved, _, _ -> commits++; assertEquals(record, saved); CanonicalMembershipUpdateResult.Updated })
        assertEquals(CanonicalMembershipScheduleResult.Progress, step(snapshot))
        assertEquals(1, scans); assertEquals(0, commits)
        // An unrelated catalog generation bump does not change this Note's work identity.
        assertEquals(CanonicalMembershipScheduleResult.Progress, step(snapshot.copy(generation = snapshot.generation + 1)))
        assertEquals("An unrelated generation change must preserve active continuation state", 1, scans)
        assertEquals(1, commits)
    }

    @Test fun aStaleCompletedTailIsRestartedInsteadOfLooping() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(semanticHash("concept"), "Seeds", "Seeds need moisture.",
            "Listen about seeds.", verifier, verifier, emptyList(), emptyList(),
            scannedRevisions = mapOf("note" to "r1"), matchingComplete = false,
            uncheckedRevisions = mapOf("note" to "r1"))
        val (snapshot, identity) = fixture(concept, model)
        // Only the tail page is eligible; no membership work may run first and mask the tail.
        val sources = mapOf("note" to "r1")
        val stale = ExistingConceptScanRecord("note", "r1", sources, semanticHash("stale-scope"), null,
            emptyList(), completion = ConceptScanCompletion.EXHAUSTED)
        val current = stale.copy(scopeKey = semanticHash("current-scope"))
        var scans = 0; var commits = 0; var lastRestart: Boolean? = null
        val scheduler = CanonicalMembershipScheduler()
        suspend fun step() = scheduler.step(snapshot, sources, identity,
            scan = { _, restart ->
                scans++; lastRestart = restart
                DurableConceptScanResult.AwaitingValidation(if (restart) current else stale)
            },
            commit = { _, saved, _, _ ->
                commits++
                if (saved == stale) CanonicalMembershipUpdateResult.Stale else CanonicalMembershipUpdateResult.Updated
            })
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals(1, scans); assertEquals(false, lastRestart)
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals("A stale completed tail must be rescanned with restart=true", true, lastRestart)
        assertEquals("The restarted tail must commit instead of looping", 2, commits)
    }

    @Test fun aTailCommitReplayResumesAcrossBoundedSteps() = runBlocking {
        val model = EmbeddingIdentity("model", 2)
        val verifier = TopicLabelIdentity("a", "t", "c", "support", "s", "v", "CPU")
        val concept = CanonicalConceptEvidence(semanticHash("concept"), "Seeds", "Seeds need moisture.",
            "Listen about seeds.", verifier, verifier, emptyList(), emptyList(),
            scannedRevisions = mapOf("note" to "r1"), matchingComplete = false,
            uncheckedRevisions = mapOf("note" to "r1"))
        val (snapshot, identity) = fixture(concept, model)
        val sources = mapOf("note" to "r1")
        val record = ExistingConceptScanRecord("note", "r1", sources, semanticHash("scope"), null, emptyList(),
            completion = ConceptScanCompletion.EXHAUSTED)
        val replay1 = ConceptScanReplay.start().advance("binding",
            ExistingConceptScanCursor("scope", 0, null), emptySet(), emptySet(), emptySet(), emptySet())
        var scans = 0; var commits = 0; var replaySeen: ConceptScanReplay? = null
        val scheduler = CanonicalMembershipScheduler()
        suspend fun step() = scheduler.step(snapshot, sources, identity,
            scan = { _, _ -> scans++; DurableConceptScanResult.AwaitingValidation(record) },
            commit = { _, saved, replay, _ ->
                commits++; replaySeen = replay
                if (commits == 1) CanonicalMembershipUpdateResult.Pending(replay1)
                else { assertEquals(record, saved); CanonicalMembershipUpdateResult.Updated }
            })
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals(1, scans); assertEquals(1, commits)
        assertEquals(CanonicalMembershipScheduleResult.Progress, step())
        assertEquals("A bounded tail commit must not re-scan the completed record", 1, scans)
        assertEquals("The tail commit must resume its replay cursor", replay1, replaySeen)
        assertEquals(2, commits)
    }
}