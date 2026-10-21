package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class TopicDiscoveryEngineTest {
    private val identity = EmbeddingIdentity("test-dim2", 2)

    @Test fun addingAnUnrelatedNoteKeepsCertifiedUnchangedTopicGroups() {
        fun record(id: String, degrees: Double): SemanticRecord {
            val text = "Forest ecology sustains wildlife habitats and protects woodland ecosystems."
            val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
            val angle = Math.toRadians(degrees)
            return SemanticRecord(id, "r1", identity, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(cos(angle).toFloat(), sin(angle).toFloat()))))
        }
        val policy = TopicPolicy(formationDistance = .1f, membership = .9f)
        val original = (0..5).map { record("p$it", it.toDouble()) }
        val prior = SectionHierarchicalEngine(identity, .1f, .9f).discover(original, null, policy)
        val updated = original + record("new", 150.0)
        val first = SectionHierarchicalEngine(identity, .1f, .9f).step(updated, prior, policy, workBudget = 1)
        assertTrue("An unrelated addition must not dissolve a certified unchanged group", first.formationProgress!!.clusters.any { it.size == 6 })
        val incremental = SectionHierarchicalEngine(identity, .1f, .9f).discover(updated, prior, policy)
        val cold = SectionHierarchicalEngine(identity, .1f, .9f).discover(updated, null, policy)
        fun groups(value: TopicSnapshot) = (value.topics + value.provisionalTopics).map { it.members.map { m -> m.pageId }.toSet() }.toSet()
        assertEquals("Incremental reuse preserves cold exact grouping", groups(cold), groups(incremental))
    }

    @Test fun incrementalGroupsAgreeWithColdGroupingAcrossEditsDeletionsAndNearbyAdditions() {
        fun record(id: String, degrees: Double, revision: String = "r1"): SemanticRecord {
            val text = "Forest ecology sustains wildlife habitats and protects woodland ecosystems."
            val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
            val angle = Math.toRadians(degrees)
            return SemanticRecord(id, revision, identity, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(cos(angle).toFloat(), sin(angle).toFloat()))))
        }
        val policy = TopicPolicy(formationDistance = .1f, membership = .9f)
        fun engine() = SectionHierarchicalEngine(identity, .1f, .9f)
        var records = (0..5).map { record("a$it", it.toDouble()) } + (0..5).map { record("b$it", 100.0 + it) }
        var prior = engine().discover(records, null, policy)
        val edits = listOf(records + record("near", 12.0), records + record("bridge", 50.0),
            records.drop(1), records.map { if (it.pageId == "a0") record("a0", 95.0, "r2") else it },
            records.reversed(), records.map { it.copy(revision = "metadata-r2", metadata = SemanticMetadata("Renamed ${it.pageId}")) })
        fun groups(value: TopicSnapshot) = (value.topics + value.provisionalTopics).map { topic ->
            Triple(topic.label, topic.members.map { it.pageId }.toSet(), topic.members.map { it.revision }.toSet())
        }.toSet()
        for (updated in edits) {
            val cold = engine().discover(updated, prior.copy(formationReuse = null), policy)
            val incremental = engine().discover(updated, prior, policy)
            assertEquals(groups(cold), groups(incremental))
            prior = incremental; records = updated
        }
        val saved = prior.formationReuse!!
        val corrupted = prior.copy(formationReuse = saved.copy(groups = listOf(saved.inputs.indices.toList())))
        assertEquals(groups(engine().discover(records, prior.copy(formationReuse = null), policy)),
            groups(engine().discover(records, corrupted, policy)))
    }

    @Test fun membershipKeepsTheStrongestBodyPassageAcrossSectionsAndIgnoresHeadingOnlyEvidence() {
        fun body(id: String, page: String, vector: List<Float>, order: Int): EmbeddedPassage {
            val text = "Atomic transactions commit related writes together and recover safely after interruption."
            return EmbeddedPassage(SemanticPassage(id, page, "$id:section", "Atomic transactions", "$id:block",
                "$id:chunk", 0, text.length, text, text, order), vector)
        }
        val seed = SemanticRecord("seed", "r", identity, SemanticMetadata("Seed"),
            listOf(body("seed:body", "seed", listOf(1f, 0f), 0)))
        val weak = body("target:early", "target", listOf(.8f, .6f), 0)
        val strong = body("target:late", "target", listOf(1f, 0f), 500)
        val heading = strong.copy(passage = strong.passage.copy(id = "target:heading", sourceBlockId = strong.passage.sectionId))
        val target = SemanticRecord("target", "revision-current", identity, SemanticMetadata("Target"), listOf(weak, heading, strong))
        val snapshot = SectionHierarchicalEngine(identity).discover(listOf(seed, target), null, TopicPolicy())
        assertTrue(snapshot.topics.isNotEmpty())
        snapshot.topics.forEach { topic ->
            val member = topic.members.single { it.pageId == "target" }
            assertEquals("target:late", member.passage.id)
            assertEquals("revision-current", member.revision)
            assertEquals(strong.passage.sectionId, member.passage.sectionId)
        }
    }

    @Test fun semanticMembershipDoesNotRequireACommonLiteralLabel() {
        fun source(id: String, heading: String, text: String, vector: List<Float>): SemanticRecord {
            val p = SemanticPassage("$id:p", id, "$id:s", heading, "$id:b", "$id:c", 0, text.length, text, text, 0)
            return SemanticRecord(id, "r1", identity, SemanticMetadata(id), listOf(EmbeddedPassage(p, vector)))
        }
        val records = listOf(
            source("a", "Atomic transactions", "Atomic transactions commit all changes together and recover safely after interruption.", listOf(1f, 0f)),
            source("b", "Durable updates", "Durable updates persist completed operations across crashes while discarding incomplete writes.", listOf(.98f, .1989975f))
        )
        val result = SectionHierarchicalEngine(identity).discover(records, null, TopicPolicy())
        val established = publishableDiscoveryTopics(result.topics).filter { it.members.size == 2 }
        assertTrue("A missing common literal phrase cannot erase supported semantic membership", established.isNotEmpty())
        assertTrue(established.any { it.label in setOf("Atomic transactions", "Durable updates") })
        assertTrue(established.all { it.members.map { member -> member.pageId }.toSet() == setOf("a", "b") })
    }

    @Test fun independentSubjectsInsideOneLongSectionBothReceiveCandidates() {
        val records = listOf("a", "b", "c").map { page ->
            val first = sampleRecord(page, 0.0, sectionTitle = "Research subjects")
            val second = sampleRecord(page, 90.0, sectionTitle = "Research subjects").passages.single().let { e ->
                e.copy(passage = e.passage.copy(id = "$page:late", sourceBlockId = "$page:late-block",
                    excerpt = "Musical harmony combines resonant frequencies into chords and melodic structures.",
                    input = "Musical harmony combines resonant frequencies into chords and melodic structures."))
            }
            first.copy(passages = first.passages + second)
        }
        val result = SectionHierarchicalEngine(identity).discover(records, null, TopicPolicy())
        assertTrue("Late independent evidence must not disappear into the section mean",
            result.topics.any { topic -> topic.members.size == 3 && topic.members.all { it.passage.id.endsWith(":late") } })
        assertTrue(result.topics.any { topic -> topic.members.size == 3 && topic.members.none { it.passage.id.endsWith(":late") } })
    }

    @Test fun aRewrittenSubjectCannotInheritIdentityThroughUnchangedPassageIds() {
        val original = listOf(sampleRecord("a", 0.0), sampleRecord("b", 0.0))
        val engine = SectionHierarchicalEngine(identity)
        val old = engine.discover(original, null, TopicPolicy())
        val changed = original.map { record -> record.copy(revision = "rewritten", passages = record.passages.map { e ->
            e.copy(vector = listOf(0f, 1f), passage = e.passage.copy(sectionTitle = "Musical harmony",
                excerpt = "Musical harmony combines resonant frequencies into chords and melodic structures.",
                input = "Musical harmony combines resonant frequencies into chords and melodic structures."))
        }) }
        val current = engine.discover(changed, old, TopicPolicy())
        assertNotEquals(old.topics.single().id, current.topics.single().id)
    }

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
        val initial = listOf(sampleRecord("n1", -30.0, sectionTitle = "Queue Architecture"), sampleRecord("n2", -30.0, sectionTitle = "Queue Architecture"))
        val engine = SectionHierarchicalEngine(identity)
        val old = engine.discover(initial, null, TopicPolicy())
        val expanded = engine.discover(initial + listOf(sampleRecord("n3", 30.0, sectionTitle = "Voice Audio"),
            sampleRecord("n4", 30.0, sectionTitle = "Voice Audio"), sampleRecord("bridge", 0.0, sectionTitle = "Systems design")), old, TopicPolicy())
        assertEquals(old.topics.single().id, expanded.topics.single { it.label == "Queue Architecture" && it.members.map { member -> member.pageId }.toSet() == setOf("n1", "n2", "bridge") }.id)
        assertTrue(expanded.topics.filter { it.members.size == 5 }.all { it.id != old.topics.single().id })
    }
    @Test fun focusedAuthoredSubjectsRemainCandidatesInsideTheBroadLinkageFamily() {
        val records = listOf(sampleRecord("n1", -30.0, sectionTitle = "Queue Architecture"),
            sampleRecord("n2", -30.0, sectionTitle = "Queue Architecture"),
            sampleRecord("n3", 30.0, sectionTitle = "Voice Audio"),
            sampleRecord("n4", 30.0, sectionTitle = "Voice Audio"), sampleRecord("bridge", 0.0, sectionTitle = "Systems design"))
        val engine = SectionHierarchicalEngine(identity)
        var result = engine.step(records, null, TopicPolicy(), workBudget = 1)
        var rounds = 0
        while (result.formationProgress != null && rounds++ < 1000)
            result = SectionHierarchicalEngine(identity).step(records.reversed(), result, TopicPolicy(), workBudget = 1)
        assertTrue(result.complete)
        assertTrue(result.topics.any { it.label == "Queue Architecture" && it.members.map { m -> m.pageId }.toSet() == setOf("n1", "n2", "bridge") })
        assertTrue(result.topics.any { it.label == "Voice Audio" && it.members.map { m -> m.pageId }.toSet() == setOf("n3", "n4", "bridge") })
        assertTrue("A broader supported subject remains independently available", result.topics.any { it.members.size == 5 })
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

    @Test fun shortProcessingWindowsRetainCompletedComparisonsAndFinishTheSameSubjects() {
        val records = (0..24).map { sampleRecord("n$it", it * 2.0, sectionTitle = "Forest Ecosystems") }
        val policy = TopicPolicy()
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        var snapshot: TopicSnapshot? = null
        var previousComparisons = 0L
        var rounds = 0
        do {
            snapshot = SectionHierarchicalEngine(identity).step(records, snapshot, policy,
                workBudget = 250_000, shouldYield = { true })
            snapshot.formationProgress?.let { progress ->
                assertTrue("Every short window must retain new work", progress.comparisonsCompleted > previousComparisons)
                assertTrue("A yield must bound interruption loss", progress.comparisonsCompleted - previousComparisons <= 64)
                previousComparisons = progress.comparisonsCompleted
            }
            snapshot = json.decodeFromString<TopicSnapshot>(json.encodeToString(TopicSnapshot.serializer(), snapshot))
            rounds++
        } while (snapshot!!.formationProgress != null && rounds < 1000)
        assertTrue("The fixture must require several short windows", rounds > 1)
        assertTrue(snapshot!!.complete)
        val direct = SectionHierarchicalEngine(identity).discover(records, null, policy)
        fun evidence(value: TopicSnapshot) = (value.topics + value.provisionalTopics).map { topic ->
            Triple(topic.label, topic.subjectVector, topic.members.map { Triple(it.passage.id, it.score, it.anchor) })
        }
        assertEquals(evidence(direct), evidence(snapshot!!))
    }

    @Test fun aPreparedGenerationNormalizesEachPassageOnlyOnceAcrossItsSlices() {
        var vectorReads = 0
        val vector = object : AbstractList<Float>() {
            override val size = 2
            override fun get(index: Int): Float { vectorReads++; return if (index == 0) 1f else 0f }
        }
        val records = (0..4).map { index ->
            val record = sampleRecord("n$index", 0.0)
            record.copy(passages = record.passages.map { it.copy(vector = vector) })
        }
        val engine = SectionHierarchicalEngine(identity)
        val first = engine.stepPrepared(records, null, TopicPolicy(), workBudget = 1)
        assertTrue(vectorReads > 0)
        vectorReads = 0
        engine.stepPrepared(records, first, TopicPolicy(), workBudget = 1)
        assertEquals("Same-generation slices must reuse normalized evidence", 0, vectorReads)
    }

    @Test fun aPreparedWorkspaceStillValidatesAnImportedCheckpoint() {
        val records = (0..4).map { sampleRecord("n$it", 0.0) }
        val engine = SectionHierarchicalEngine(identity)
        val first = engine.stepPrepared(records, null, TopicPolicy(), workBudget = 1)
        val poisoned = first.copy(formationProgress = first.formationProgress!!.copy(
            projectionPassage = records.size, projectionMembers = emptyList()))
        val resumed = engine.stepPrepared(records, poisoned, TopicPolicy(), workBudget = 1)
        assertEquals("Foreign state must be validated rather than trusted by signature alone",
            first.formationProgress!!.projectionPassage, resumed.formationProgress!!.projectionPassage)
        assertEquals(first.formationProgress!!.comparisonsCompleted, resumed.formationProgress!!.comparisonsCompleted)
    }

    @Test fun anInterruptedPreparedSliceCannotReuseMutatedClusterSums() {
        val records = (0..24).map { sampleRecord("n$it", it * 2.0) }
        val engine = SectionHierarchicalEngine(identity)
        val first = engine.stepPrepared(records, null, TopicPolicy(), workBudget = 1)
        try {
            engine.stepPrepared(records, first, TopicPolicy(), shouldYield = {
                throw kotlinx.coroutines.CancellationException("window destroyed")
            })
            fail("Hard cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        val resumed = engine.stepPrepared(records, first, TopicPolicy(), workBudget = 10)
        val expected = SectionHierarchicalEngine(identity).step(records, first, TopicPolicy(), workBudget = 10)
        assertEquals(expected.formationProgress, resumed.formationProgress)
    }

    @Test fun metadataBoundsPreserveExhaustiveSubjectsAndEvidenceAcrossDifferentAreasAndTopics() {
        val records = (0..11).map { index ->
            val subject = index / 3
            val record = sampleRecord("n$index", subject * 90.0, sectionTitle = listOf(
                "Forest Ecosystems", "Planetary Astronomy", "Water Conservation", "Mineral Geology")[subject])
            record.copy(metadata = record.metadata.copy(area = if (index == 0) null else "Area $index",
                topics = if (index % 2 == 0) setOf("Shared authored topic") else emptySet()))
        }
        val policy = TopicPolicy()
        val exhaustive = SectionHierarchicalEngine(identity, metadataBoundsEnabled = false).discover(records, null, policy)
        val engine = SectionHierarchicalEngine(identity)
        val bounded = engine.discover(records, null, policy)
        fun evidence(value: TopicSnapshot) = (value.topics + value.provisionalTopics).map { topic ->
            Triple(topic.label, topic.subjectVector, topic.members.map { Triple(it.passage.id, it.score, it.anchor) })
        }
        assertEquals(evidence(exhaustive), evidence(bounded))
        assertTrue("The fixture must prove avoided exact dot products", engine.prunedProjectionComparisons > 0)
        println("Public synthetic metadata parity: 12 Notes; prunedProjectionComparisons=${engine.prunedProjectionComparisons}")
        assertTrue("Matching subjects must cross unrelated and absent authored labels", bounded.topics.any {
            it.members.map { member -> member.pageId }.toSet() == setOf("n0", "n1", "n2")
        })
    }

    @Test fun preparedSlicesPreserveTheExactArithmeticAndEvidenceOfIndependentlyResumedSlices() {
        val records = (0..24).map { index -> sampleRecord("n$index",
            if (index < 12) index * 1.3 else 120 + index * .7,
            sectionTitle = if (index < 12) "Forest Ecosystems" else "Planetary Astronomy") }
        val policy = TopicPolicy()
        val prepared = SectionHierarchicalEngine(identity)
        var fast: TopicSnapshot? = null
        var exhaustive: TopicSnapshot? = null
        var rounds = 0
        fun evidence(value: TopicSnapshot) = (value.topics + value.provisionalTopics).map { topic ->
            Triple(topic.label, topic.subjectVector, topic.members)
        }
        do {
            fast = prepared.stepPrepared(records, fast, policy, workBudget = 3)
            exhaustive = SectionHierarchicalEngine(identity).step(records, exhaustive, policy, workBudget = 3)
            assertEquals(exhaustive.formationProgress, fast.formationProgress)
            assertEquals(evidence(exhaustive), evidence(fast))
            assertEquals(exhaustive.complete, fast.complete)
        } while (!fast.complete && ++rounds < 1000)
        assertTrue(fast!!.complete)
    }

    @Test fun boundedFormationPreservesExactSubjectsWhileAvoidingUnrelatedFeatureComponents() {
        val wide = EmbeddingIdentity("formation-bounds-768", 768)
        val records = (0..11).map { index ->
            val record = sampleRecord("n$index", 0.0, sectionTitle = listOf(
                "Forest Ecosystems", "Planetary Astronomy", "Water Conservation", "Mineral Geology")[index / 3])
            val vector = List(768) { component -> if (component == index / 3 * 128) 1f else 0f }
            record.copy(identity = wide, passages = record.passages.map { it.copy(vector = vector) })
        }
        val policy = TopicPolicy()
        val boundedEngine = SectionHierarchicalEngine(wide, distanceThreshold = .1f, membershipThreshold = .9f)
        val exhaustive = SectionHierarchicalEngine(wide, distanceThreshold = .1f, membershipThreshold = .9f,
            formationBoundsEnabled = false).discover(records, null, policy)
        val bounded = boundedEngine.discover(records, null, policy)
        fun evidence(value: TopicSnapshot) = (value.topics + value.provisionalTopics).map { topic ->
            Triple(topic.label, topic.subjectVector, topic.members)
        }
        assertEquals(evidence(exhaustive), evidence(bounded))
        assertTrue("Formation must avoid actual feature work on unrelated pairs", boundedEngine.avoidedFormationComponents > 0)
    }

    @Test fun boundedFormationKeepsRandomizedSubjectsBoundaryTiesAndFreshEngineResumesExact() {
        val wide = EmbeddingIdentity("formation-boundaries-768", 768)
        val random = kotlin.random.Random(9127)
        val records = (0..23).map { index ->
            val group = index / 6
            val vector = FloatArray(768) { random.nextDouble(-.001, .001).toFloat() }
            vector[group * 128] = 1f
            if (index % 6 < 2) vector.indices.forEach { component -> vector[component] = if (component == group * 128) 1f else 0f }
            if (index % 6 == 2) {
                vector.fill(0f)
                vector[group * 128] = .9f
                vector[group * 128 + 1] = kotlin.math.sqrt(.19).toFloat()
            }
            normalizeProjectionVector(vector)
            val record = sampleRecord("n$index", 0.0, sectionTitle = listOf(
                "Forest Ecosystems", "Planetary Astronomy", "Water Conservation", "Mineral Geology")[group])
            record.copy(identity = wide, passages = record.passages.map { it.copy(vector = vector.toList()) })
        }
        fun engine(bounded: Boolean) = SectionHierarchicalEngine(wide, distanceThreshold = .1f,
            membershipThreshold = .9f, metadataBoundsEnabled = false, formationBoundsEnabled = bounded)
        fun evidence(value: TopicSnapshot) = (value.topics + value.provisionalTopics).map { topic ->
            Triple(topic.label, topic.subjectVector, topic.members)
        }
        for (budget in listOf(1, 7, 64, 250_000)) {
            var bounded: TopicSnapshot? = null
            var exhaustive: TopicSnapshot? = null
            var boundedEngine = engine(true)
            var exhaustiveEngine = engine(false)
            var rounds = 0
            do {
                if (rounds % 3 == 0) { boundedEngine = engine(true); exhaustiveEngine = engine(false) }
                bounded = boundedEngine.stepPrepared(records, bounded, TopicPolicy(), workBudget = budget)
                exhaustive = exhaustiveEngine.stepPrepared(records, exhaustive, TopicPolicy(), workBudget = budget)
                assertEquals("Bounded work must retain each quantum's completion boundary", exhaustive.complete, bounded.complete)
                assertEquals("Bounded work must preserve exact scores and evidence at every quantum", evidence(exhaustive), evidence(bounded))
                rounds++
            } while (!bounded.complete && rounds < 3000)
            assertTrue("Every resume budget must complete", bounded!!.complete)
        }
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
        val snapshot = SectionHierarchicalEngine(identity, workQuantum = 4).discover(records, null, TopicPolicy())
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

        val policy = TopicPolicy()
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
        val policy = TopicPolicy()
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
        val policy = TopicPolicy()
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
        val policy = TopicPolicy()
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
        val policy = TopicPolicy()
        val engine = SectionHierarchicalEngine(identity, distanceThreshold = 0.35f)
        val snapshot = engine.discover(records, null, policy)
        assertTrue("Section names do not discard substantive subject evidence", (snapshot.topics + snapshot.provisionalTopics).flatMap { it.members }.map { it.pageId }.toSet().containsAll(listOf("n1", "n2", "n3")))
    }
}
