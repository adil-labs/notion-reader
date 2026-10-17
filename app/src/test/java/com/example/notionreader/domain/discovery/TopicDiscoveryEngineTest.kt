package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class TopicDiscoveryEngineTest {
    private val identity = EmbeddingIdentity("test-dim2", 2)

    private fun sampleRecord(pageId: String, angle: Double, sectionId: String = "sec1", sectionTitle: String? = "Shared Concepts"): SemanticRecord {
        val rad = Math.toRadians(angle)
        val vec = listOf(cos(rad).toFloat(), sin(rad).toFloat())
        val passage = SemanticPassage(
            id = "p-$pageId-$sectionId",
            pageId = pageId,
            sectionId = sectionId,
            sectionTitle = sectionTitle,
            sourceBlockId = "b1",
            readerChunkId = "c1",
            start = 0,
            end = 50,
            excerpt = "Excerpt for $pageId discussing $sectionTitle and queue management concepts",
            input = "Input for $pageId",
            order = 0
        )
        val embedded = EmbeddedPassage(passage, vec)
        return SemanticRecord(
            pageId = pageId,
            revision = "r1",
            identity = identity,
            metadata = SemanticMetadata("Note $pageId"),
            passages = listOf(embedded)
        )
    }

    @Test fun identicalNormalizedDirectionsHaveZeroDistanceAtTheStrictestCut() {
        val records = (0..2).map { sampleRecord("n$it", 17.1234567, sectionTitle = "Forest Ecosystems") }
        val snapshot = SectionHierarchicalEngine(identity, distanceThreshold = 0f).discover(records, null, TopicPolicy())
        assertTrue(snapshot.complete)
        assertEquals(1, snapshot.topics.size)
        assertEquals(3, snapshot.topics.single().members.size)
    }
    @Test fun identicalSectionCentroidsCoalesceWithoutDroppingTheirSourceSections() {
        val records = (0..11).map { sampleRecord("n${it.toString().padStart(2, '0')}", if (it < 8) 0.0 else 180.0) }
        val partial = SectionHierarchicalEngine(identity).step(records, null, TopicPolicy(), workBudget = 1)
        val groups = partial.formationProgress!!.clusters.filter { it.isNotEmpty() }
        assertEquals(2, groups.size)
        assertEquals(listOf(4, 8), groups.map { it.size }.sorted())
        assertEquals((0..11).toSet(), groups.flatten().toSet())
        val completed = SectionHierarchicalEngine(identity).discover(records, partial, TopicPolicy())
        assertTrue(completed.complete)
        assertEquals(listOf(4, 8), completed.topics.map { it.members.size }.sorted())
    }
    @Test fun aFocusedSubjectKeepsItsIdentityWhenAnOverlappingBroadFamilyAppears() {
        val initial = listOf(sampleRecord("n1", 0.0, sectionTitle = "Queue Architecture"), sampleRecord("n2", 0.0, sectionTitle = "Queue Architecture"))
        val engine = SectionHierarchicalEngine(identity)
        val old = engine.discover(initial, null, TopicPolicy())
        val expanded = engine.discover(initial + listOf(sampleRecord("n3", 0.0, sectionTitle = "Voice Audio"),
            sampleRecord("n4", 0.0, sectionTitle = "Voice Audio")), old, TopicPolicy())
        assertEquals(old.topics.single().id, expanded.topics.single { it.label == "Queue Architecture" }.id)
        assertNotEquals(old.topics.single().id, expanded.topics.single { it.members.size == 4 }.id)
    }
    @Test fun focusedAuthoredSubjectsRemainCandidatesInsideTheBroadLinkageFamily() {
        val records = listOf(sampleRecord("n1", 0.0, sectionTitle = "Queue Architecture"),
            sampleRecord("n2", 0.0, sectionTitle = "Queue Architecture"),
            sampleRecord("n3", 0.0, sectionTitle = "Voice Audio"),
            sampleRecord("n4", 0.0, sectionTitle = "Voice Audio"))
        val engine = SectionHierarchicalEngine(identity)
        var result = engine.step(records, null, TopicPolicy(), workBudget = 1)
        var rounds = 0
        while (result.formationProgress != null && rounds++ < 1000)
            result = SectionHierarchicalEngine(identity).step(records.reversed(), result, TopicPolicy(), workBudget = 1)
        assertTrue(result.complete)
        assertTrue(result.topics.any { it.label == "Queue Architecture" && it.members.map { m -> m.pageId }.toSet() == setOf("n1", "n2") })
        assertTrue(result.topics.any { it.label == "Voice Audio" && it.members.map { m -> m.pageId }.toSet() == setOf("n3", "n4") })
        assertTrue("A broader supported subject remains independently available", result.topics.any { it.members.size == 4 })
    }
    @Test fun resumedNearestNeighborChainsAgreeWithBruteForceAverageLinkage() {
        val random = java.util.Random(387)
        repeat(20) {
            val records = (0..7).map { sampleRecord("n$it", random.nextDouble() * 360.0) }
            val expected = records.indices.map { mutableListOf(it) }.toMutableList()
            fun distance(a: List<Int>, b: List<Int>): Float = a.sumOf { x -> b.sumOf { y ->
                (1.0 - records[x].passages.single().vector.zip(records[y].passages.single().vector).sumOf { (u, v) -> u.toDouble() * v })
            } }.toFloat() / (a.size * b.size)
            while (true) {
                val closest = expected.indices.flatMap { a -> (a + 1 until expected.size).map { b -> Triple(a, b, distance(expected[a], expected[b])) } }
                    .minByOrNull { it.third } ?: break
                if (closest.third > .2f) break
                expected[closest.first].addAll(expected[closest.second]); expected.removeAt(closest.second)
            }
            val engine = SectionHierarchicalEngine(identity, distanceThreshold = .2f)
            var actual = engine.step(records, null, TopicPolicy(), workBudget = 1)
            var rounds = 0
            while (actual.formationProgress?.groupingComplete == false && rounds++ < 1000)
                actual = engine.step(records, actual, TopicPolicy(), workBudget = 1)
            assertNotNull(actual.formationProgress)
            val groups = actual.formationProgress!!.clusters.filter { it.isNotEmpty() }.map { it.toSet() }.toSet()
            assertEquals(expected.map { it.toSet() }.toSet(), groups)
        }
    }
    @Test fun aNewEarlierSubjectCannotTakeTheIdentityOfAnUnchangedLaterSubject() {
        val initial = (1..3).map { sampleRecord("n$it", 180.0, "z", "Planetary Astronomy") }
        val engine = SectionHierarchicalEngine(identity)
        val old = engine.discover(initial, null, TopicPolicy())
        val changed = initial.map { r -> r.copy(passages = r.passages + sampleRecord(r.pageId, 0.0, "a", "Forest Ecosystems").passages) }
        val rebuilt = engine.discover(changed, old, TopicPolicy())
        assertEquals(old.topics.single().id, rebuilt.topics.single { it.label == "Planetary Astronomy" }.id)
        assertNotEquals(old.topics.single().id, rebuilt.topics.single { it.label == "Forest Ecosystems" }.id)
    }

    @Test fun sourceRemovalPreservesUnrelatedEmergingAndDemotesTheRemainingEstablishedSubject() {
        val records = listOf(sampleRecord("a", 0.0, sectionTitle = "Forest Ecosystems"),
            sampleRecord("b", 0.0, sectionTitle = "Forest Ecosystems"),
            sampleRecord("c", 180.0, sectionTitle = "Planetary Astronomy"))
        val original = SectionHierarchicalEngine(identity).discover(records, null, TopicPolicy())
        val forest = original.topics.single { it.label == "Forest Ecosystems" }
        val remaining = records[1].passages.single()
        val removed = invalidateTopics(original, setOf("a"), false, mapOf(forest.id to remaining))
        assertEquals(2, removed.topics.size)
        assertTrue(removed.topics.all { it.members.size == 1 })
        assertTrue(removed.topics.all { discoveryTopicPublicationStatus(it) == TopicPublicationStatus.Emerging })
    }

    @Test fun aPoisonedCheckpointCannotSkipUnprocessedSections() {
        val records = (1..5).map { sampleRecord("n$it", 0.0) }
        val engine = SectionHierarchicalEngine(identity)
        val partial = engine.step(records, null, TopicPolicy(), workBudget = 1)
        val poisoned = partial.copy(formationProgress = partial.formationProgress!!.copy(groupingComplete = true,
            projectionCluster = partial.formationProgress!!.totalSections))
        val result = engine.discover(records, poisoned, TopicPolicy())
        assertTrue(result.complete)
        assertEquals(5, result.topics.single().members.size)
    }
    @Test fun boundedWorkSurvivesSerializationAndFreshEngineInstances() {
        val records = (0..12).map { sampleRecord("n$it", if (it < 6) 0.0 else 180.0,
            sectionTitle = if (it < 6) "Forest Ecosystems" else "Planetary Astronomy") }
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        var snapshot = SectionHierarchicalEngine(identity).step(records, null, TopicPolicy(), workBudget = 3)
        assertFalse(snapshot.complete)
        assertEquals(3L, snapshot.formationProgress!!.comparisonsCompleted)
        var rounds = 0
        while (snapshot.formationProgress != null && rounds++ < 1000) {
            val resumed = json.decodeFromString<TopicSnapshot>(json.encodeToString(TopicSnapshot.serializer(), snapshot))
            snapshot = SectionHierarchicalEngine(identity).step(records.reversed(), resumed, TopicPolicy(), workBudget = 3)
        }
        assertTrue("All work eventually completes", snapshot.complete)
        val direct = SectionHierarchicalEngine(identity).discover(records, null, TopicPolicy())
        fun groups(value: TopicSnapshot) = value.topics.map { it.label to it.members.map { m -> m.passage.id }.toSet() }.toSet()
        assertEquals(groups(direct), groups(snapshot))
    }

    @Test fun cancellationInterruptsFormationWork() {
        val records = (0..100).map { sampleRecord("n$it", 0.0) }
        var callbacks = 0
        try {
            SectionHierarchicalEngine(identity).step(records, null, TopicPolicy(), workBudget = 10000) {
                if (++callbacks > records.size + 2) throw kotlinx.coroutines.CancellationException("cancelled")
            }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun invalidVectorsAndForeignPagePassagesNeverBecomeProjectedEvidence() {
        val base = sampleRecord("one", 0.0, sectionTitle = "Forest Ecosystems")
        val foreign = sampleRecord("foreign", 0.0).passages.single()
        val invalid = base.passages.single().copy(passage = base.passages.single().passage.copy(id = "bad"), vector = listOf(Float.NaN, 0f))
        val snapshot = SectionHierarchicalEngine(identity).discover(listOf(base.copy(passages = base.passages + foreign + invalid)), null, TopicPolicy())
        assertEquals(listOf(base.passages.single().passage), snapshot.topics.single().members.map { it.passage })
    }
    @Test fun sectionsBeyondTheFormerBudgetRetainTheirOwnEvidence() {
        val records = (0..4).map { sampleRecord("n$it", 0.0, sectionTitle = "Shared Concepts") } +
            (0..2).map { sampleRecord("z$it", 180.0, sectionTitle = "Distant Astronomy") }
        val snapshot = SectionHierarchicalEngine(identity, maxSectionsBudget = 4).discover(records, null, TopicPolicy())
        assertTrue(snapshot.complete)
        assertTrue(snapshot.topics.any { topic -> topic.members.map { it.pageId }.toSet() == setOf("z0", "z1", "z2") })
    }

    @Test fun separateSubjectsSharingNotesNeverReuseOneContinuityIdentity() {
        val a = (1..3).map { sampleRecord("n$it", 0.0, "a", "Forest Ecosystems") }
        val first = SectionHierarchicalEngine(identity).discover(a, null, TopicPolicy())
        val records = a.map { record -> record.copy(passages = record.passages + sampleRecord(record.pageId, 180.0, "b", "Planetary Astronomy").passages) }
        val rebuilt = SectionHierarchicalEngine(identity).discover(records, first, TopicPolicy())
        assertEquals(2, rebuilt.topics.size)
        assertEquals(2, rebuilt.topics.map { it.id }.distinct().size)
    }
    @Test
    fun streamingEngineProducesIdenticalOutputToLegacyTopicBuilder() {
        val records = listOf(
            sampleRecord("n1", 0.0),
            sampleRecord("n2", 5.0),
            sampleRecord("n3", 10.0)
        )
        val policy = TopicPolicy(admission = 0.65f, minNotes = 3)
        val engine = StreamingAnchorEngine(identity)
        val snapshot = engine.discover(records, null, policy)

        val directBuilder = TopicBuilder(null, identity, policy).apply { records.forEach(::accept) }.finish()

        assertEquals(directBuilder.topics.size, snapshot.topics.size)
        assertEquals(directBuilder.topics.firstOrNull()?.label, snapshot.topics.firstOrNull()?.label)
        assertEquals(directBuilder.complete, snapshot.complete)
    }

    @Test
    fun sectionHierarchicalEngineProducesDeterministicOrderInvariantClusters() {
        val recordsOrder1 = listOf(
            sampleRecord("n1", 0.0),
            sampleRecord("n2", 5.0),
            sampleRecord("n3", 10.0),
            sampleRecord("n4", 90.0),
            sampleRecord("n5", 95.0),
            sampleRecord("n6", 100.0)
        )
        val recordsOrder2 = recordsOrder1.shuffled()

        val policy = TopicPolicy(admission = 0.65f, minNotes = 3)
        val engine = SectionHierarchicalEngine(identity, distanceThreshold = 0.35f)

        val snap1 = engine.discover(recordsOrder1, null, policy)
        val snap2 = engine.discover(recordsOrder2, null, policy)

        assertEquals(snap1.topics.size, snap2.topics.size)
        val labels1 = snap1.topics.map { it.label }.sorted()
        val labels2 = snap2.topics.map { it.label }.sorted()
        assertEquals(labels1, labels2)

        val members1 = snap1.topics.map { it.members.map { m -> m.pageId }.sorted() }.sortedBy { it.joinToString() }
        val members2 = snap2.topics.map { it.members.map { m -> m.pageId }.sorted() }.sortedBy { it.joinToString() }
        assertEquals(members1, members2)
    }

    @Test
    fun sectionHierarchicalEngineAssignsMultiTopicMembership() {
        // Multi-topic note "multi" has two passages: one in Cluster A (0 degrees), one in Cluster B (90 degrees)
        val multiPassageA = EmbeddedPassage(
            SemanticPassage("p-m-a", "multi", "secA", "Queue Architecture", "b1", "c1", 0, 50, "Queue concepts", "in", 0),
            listOf(1f, 0f)
        )
        val multiPassageB = EmbeddedPassage(
            SemanticPassage("p-m-b", "multi", "secB", "Voice Audio", "b2", "c2", 50, 100, "Voice speech", "in", 1),
            listOf(0f, 1f)
        )
        val multiRecord = SemanticRecord("multi", "r1", identity, SemanticMetadata("Multi Note"), listOf(multiPassageA, multiPassageB))

        // Group A: around (1, 0)
        val recA1 = sampleRecord("a1", 0.0, "secA", "Queue Architecture")
        val recA2 = sampleRecord("a2", 5.0, "secA", "Queue Architecture")

        // Group B: around (0, 1)
        val recB1 = sampleRecord("b1", 90.0, "secB", "Voice Audio")
        val recB2 = sampleRecord("b2", 95.0, "secB", "Voice Audio")

        val allRecords = listOf(recA1, recA2, recB1, recB2, multiRecord)
        val policy = TopicPolicy(admission = 0.65f, minNotes = 3)
        val engine = SectionHierarchicalEngine(identity, distanceThreshold = 0.35f, membershipThreshold = 0.60f)

        val snapshot = engine.discover(allRecords, null, policy)

        // There should be 2 visible topics
        assertEquals(2, snapshot.topics.size)

        // The multi-topic note must be a member of BOTH topics
        val topicsContainingMulti = snapshot.topics.filter { t -> t.members.any { m -> m.pageId == "multi" } }
        assertEquals(2, topicsContainingMulti.size)
    }

    @Test
    fun sectionHierarchicalEnginePreservesOutliersWithoutSpuriousClusters() {
        val clusterRecords = listOf(
            sampleRecord("c1", 0.0),
            sampleRecord("c2", 5.0),
            sampleRecord("c3", 10.0)
        )
        // Outlier is completely orthogonal at 180 degrees
        val outlier = sampleRecord("outlier", 180.0, "secOut", "Unrelated Notes")

        val allRecords = clusterRecords + outlier
        val policy = TopicPolicy(admission = 0.65f, minNotes = 3)
        val engine = SectionHierarchicalEngine(identity, distanceThreshold = 0.35f, membershipThreshold = 0.60f)

        val snapshot = engine.discover(allRecords, null, policy)

        // Only 1 visible topic formed
        assertEquals(1, snapshot.topics.count { it.members.size >= 2 })
        // Outlier must NOT be admitted to the cluster
        assertFalse(snapshot.topics.first { it.members.size >= 2 }.members.any { it.pageId == "outlier" })
        assertTrue(snapshot.topics.any { it.members.singleOrNull()?.pageId == "outlier" })
    }

    @Test
    fun sectionHierarchicalEnginePreservesTopicIdentityAcrossRebuilds() {
        val initialRecords = listOf(
            sampleRecord("n1", 0.0),
            sampleRecord("n2", 5.0),
            sampleRecord("n3", 10.0)
        )
        val policy = TopicPolicy(admission = 0.65f, minNotes = 3)
        val engine = SectionHierarchicalEngine(identity, distanceThreshold = 0.35f)

        val initialSnapshot = engine.discover(initialRecords, null, policy)
        assertEquals(1, initialSnapshot.topics.size)
        val initialId = initialSnapshot.topics.single().id
        val initialOrder = initialSnapshot.topics.single().createdOrder

        // Rebuild with an added note n4
        val updatedRecords = initialRecords + sampleRecord("n4", 7.0)
        val rebuiltSnapshot = engine.discover(updatedRecords, initialSnapshot, policy)

        assertEquals(1, rebuiltSnapshot.topics.size)
        val rebuiltTopic = rebuiltSnapshot.topics.single()

        // Topic ID and createdOrder must be preserved through TopicContinuity
        assertEquals(initialId, rebuiltTopic.id)
        assertEquals(initialOrder, rebuiltTopic.createdOrder)
        assertEquals(4, rebuiltTopic.members.size)
    }

    @Test
    fun substantiveDiscussionRemainsEligibleUnderNonGoalHeadings() {
        val records = listOf(
            sampleRecord("n1", 0.0, sectionTitle = "Non-goals"),
            sampleRecord("n2", 5.0, sectionTitle = "Non-goals"),
            sampleRecord("n3", 10.0, sectionTitle = "Explicitly deferred work")
        )
        val policy = TopicPolicy(admission = 0.65f, minNotes = 3)
        val engine = SectionHierarchicalEngine(identity, distanceThreshold = 0.35f)
        val snapshot = engine.discover(records, null, policy)
        assertTrue("Section names do not discard substantive subject evidence", (snapshot.topics + snapshot.provisionalTopics).flatMap { it.members }.map { it.pageId }.toSet().containsAll(listOf("n1", "n2", "n3")))
    }
}
