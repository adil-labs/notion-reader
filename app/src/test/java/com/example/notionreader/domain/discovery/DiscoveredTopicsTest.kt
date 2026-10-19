package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveredTopicsTest {
    @Test fun sourceReplacementKeepsTheActiveModelsStricterRetentionFloor() {
        val original = build(null, record("a", 0), record("b", 0), record("c", 0))
        val topic = original.topics.single().let { t -> t.copy(members = t.members.map { it.copy(score = .8f) }) }
        val replacement = record("b", 0).passages.single()
        val removed = invalidateTopics(original.copy(topics = listOf(topic)), setOf("a"), false,
            mapOf(topic.id to replacement), retentionThreshold = .9f)
        assertEquals(listOf("b"), removed.topics.single().members.map { it.pageId })
    }


    @Test fun separateChunksOfOneBlockKeepIndependentThemes() {
        val sources = listOf("a", "b", "c").map { id ->
            val r = record(id, 0, 1)
            r.copy(passages = r.passages.map { it.copy(passage = it.passage.copy(
                sourceBlockId = "$id:one-block", sectionTitle = "Independent themes")) })
        }
        assertEquals(2, build(null, *sources.toTypedArray()).topics.size)
    }
    @Test fun overlappingWindowsCannotDuplicateTheSameSupportedNeighborhood() {
        val records = listOf("a", "b", "c").map { id ->
            val r = record(id, 0)
            val first = r.passages.single().copy(passage = r.passages.single().passage.copy(start = 0, end = 100))
            val second = first.copy(passage = first.passage.copy(id = "$id-overlap", start = 50, end = 150),
                vector = listOf(kotlin.math.cos(.6).toFloat(), kotlin.math.sin(.6).toFloat()))
            r.copy(passages = listOf(first, second))
        }
        assertEquals(1, build(null, *records.toTypedArray()).topics.size)
    }
    @Test fun sharedStandaloneHeadingsCannotGroupUnrelatedBodies() {
        val sources = listOf(record("a", 0), record("b", 1), record("c", 0).let { r ->
            r.copy(passages = r.passages.map { it.copy(vector = listOf(-1f, 0f)) })
        }).map { r ->
            val body = r.passages.single()
            val heading = body.copy(passage = body.passage.copy(id = "${r.pageId}-heading",
                sourceBlockId = body.passage.sectionId, excerpt = "Summary"), vector = listOf(1f, 0f))
            r.copy(passages = listOf(heading, body))
        }
        assertTrue(build(null, *sources.toTypedArray()).topics.all { it.members.size == 1 })
    }

    @Test fun aLongFirstNoteCannotPreventOtherSubjectsFromReceivingOpportunity() {
        val model = EmbeddingIdentity("fair", 70)
        fun source(id: String, axes: List<Int>) = SemanticRecord(id, "r", model, SemanticMetadata(id), axes.map { axis ->
            val text = "Substantive body about theme $axis supports an independent subject."
            EmbeddedPassage(SemanticPassage("$id:$axis", id, "$id:section:$axis", "Subject number $axis", "$id:block:$axis",
                "$id:chunk:$axis", 0, text.length, text, text, axis), List(70) { if (it == axis) 1f else 0f })
        })
        val records = listOf(source("long", (0..65).toList())) + listOf("x", "y", "z").map { source(it, listOf(69)) }
        val result = SectionHierarchicalEngine(model).discover(records, null, TopicPolicy())
        assertTrue(result.complete)
        assertEquals(67, result.topics.size)
        assertEquals(setOf("x", "y", "z"), result.topics.single { it.label == "Subject number 69" }.members.map { it.pageId }.toSet())
    }
    private val identity = EmbeddingIdentity("test", 2)
    private fun record(id: String, vararg axes: Int) = SemanticRecord(id, "r1", identity,
        SemanticMetadata("Note $id"), axes.mapIndexed { n, axis ->
            val text = if (axis == 0) "Gardening" else "Astronomy"
            EmbeddedPassage(SemanticPassage("$id-$n", id, "$id-s$n", text, "$id-b$n", "$id-c$n", 0, text.length, text, text, n),
                if (axis == 0) listOf(1f, 0f) else listOf(0f, 1f))
        })
    private fun build(previous: TopicSnapshot? = null, vararg records: SemanticRecord): TopicSnapshot {
        return SectionHierarchicalEngine(identity).discover(records.toList(), previous, TopicPolicy())
    }
    @Test fun aTwoThemeNoteHasIndependentPassageEvidence() {
        val snapshot = build(null, record("both", 0, 1), record("garden1", 0), record("garden2", 0), record("sky1", 1), record("sky2", 1))
        assertEquals(2, snapshot.topics.size)
        assertEquals(setOf("both-0", "both-1"), snapshot.topics.flatMap { it.members }.filter { it.pageId == "both" }.map { it.passage.id }.toSet())
        assertTrue(snapshot.topics.all { it.members.map { m -> m.pageId }.distinct().size == 3 })
    }
    @Test fun repeatedPassagesCannotMakeAnEstablishedTopic() {
        assertTrue(build(null, record("one", 0, 0, 0)).topics.all { discoveryTopicPublicationStatus(it) == TopicPublicationStatus.Emerging })
    }
    @Test fun unrelatedAdditionsAndReorderPreserveIdentityAndLabel() {
        val first = build(null, record("a", 0), record("b", 0), record("c", 0))
        val next = build(first, record("noise", 1), record("c", 0), record("b", 0), record("a", 0))
        assertEquals(first.topics.single().id, next.topics.single { it.members.any { member -> member.pageId == "b" } }.id)
        assertEquals(first.topics.single().label, next.topics.single { it.members.any { member -> member.pageId == "b" } }.label)
    }
    @Test fun anchorLossPreservesIdentityFromSurvivingMembers() {
        val first = build(null, record("a", 0), record("b", 0), record("c", 0), record("d", 0))
        val next = build(first, record("b", 0), record("c", 0), record("d", 0))
        assertEquals(first.topics.single().id, next.topics.single { it.members.any { member -> member.pageId == "b" } }.id)
        assertFalse(next.topics.single().anchor.passage.pageId == "a")
    }
    @Test fun evictionRetainsOnlyOpaqueContinuityAndRecacheRestoresIdentity() {
        val first = build(null, record("a", 0), record("b", 0), record("c", 0))
        val removed = invalidateTopics(first, setOf("a", "b", "c"), false)
        assertTrue(removed.topics.isEmpty())
        assertEquals(first.topics.single().id, removed.continuity.single().id)
        assertEquals(first.topics.single().id, build(removed, record("a", 0), record("b", 0), record("c", 0)).topics.single().id)
        assertTrue(invalidateTopics(removed, setOf("a", "b", "c"), true).continuity.isEmpty())
    }
    @Test fun invalidVectorsAndDifferentModelsNeverCreateMembership() {
        val bad = record("bad", 0).let { it.copy(passages = it.passages.map { p -> p.copy(vector = listOf(Float.NaN, 0f)) }) }
        assertTrue(build(null, bad, record("b", 0), record("c", 0)).topics.flatMap { it.members }.none { it.pageId == "bad" })
        assertTrue(build(null, record("a", 0).copy(identity = identity.copy(model="different")), record("b", 0), record("c", 0)).topics.flatMap { it.members }.none { it.pageId == "a" })
    }


        @Test fun replacingALabelSourceUsesTheActualMembershipScoreDirection() {
        val original = build(null, record("a", 0), record("b", 0), record("c", 0))
        val base = original.topics.single()
        val replacement = record("b", 0).passages.single()
        val members = base.members.map { it.copy(score = .70710677f, anchor = replacement.passage,
            passage = if (it.pageId == "c") record("c", 1).passages.single().passage else it.passage) }
        val topic = base.copy(anchor = replacement, members = members, subjectVector = listOf(.70710677f, .70710677f))
        val removed = invalidateTopics(original.copy(topics = listOf(topic)), setOf("a"), false, mapOf(topic.id to replacement))
        assertEquals("An old score relative to a centroid cannot be reused as a score to its representative passage",
            listOf("b"), removed.topics.single().members.map { it.pageId })
    }

    @Test fun deletingOneMemberPreservesContinuityOfUnchangedSurvivors() {
        val original = build(null, record("a", 0), record("b", 0), record("c", 0))
        val removed = invalidateTopics(original, setOf("c"), permanent = true)
        val rebuilt = build(removed, record("a", 0), record("b", 0))
        assertEquals(original.topics.single().id, rebuilt.topics.single().id)
        assertEquals(setOf("a", "b"), rebuilt.topics.single().members.map { it.pageId }.toSet())
    }

    @Test fun irrelevantInvalidationDoesNotCreateANewGeneration() {
        val snapshot = build(null, record("a", 0), record("b", 0), record("c", 0))
        assertEquals(snapshot, invalidateTopics(snapshot, emptySet(), false))
        assertEquals(snapshot, invalidateTopics(snapshot, setOf("unrelated"), true))
    }    @Test fun invalidationCanReanchorFromAValidatedSurvivingRecord() {
        val snapshot = build(null, record("a", 0), record("b", 0), record("c", 0), record("d", 0))
        val topic = snapshot.topics.single()
        val result = invalidateTopics(snapshot, setOf("a"), false, mapOf(topic.id to record("b", 0).passages.single()))
        assertEquals(topic.id, result.topics.single().id)
        assertEquals("b", result.topics.single().anchor.passage.pageId)
        assertEquals(3, result.topics.single().members.size)
        assertTrue(result.topics.single().members.all { it.anchor.pageId == "b" })
    }    @Test fun theStrongestSurvivingBranchKeepsTheSplitIdentity() {
        val before = build(null, record("a", 0), record("b", 0), record("c", 0), record("d", 0), record("e", 0), record("f", 0))
        fun edited(id: String, axis: Int) = record(id, axis).copy(revision = "r2", passages = record(id, axis).passages.map { it.copy(passage = it.passage.copy(id = "$id-new")) })
        val after = build(before, record("b", 0), record("c", 0), record("g", 0), edited("d", 1), edited("e", 1), edited("f", 1))
        assertEquals(before.topics.single().id, after.topics.single { it.members.any { member -> member.pageId == "b" } }.id)
        assertEquals(2, after.topics.map { it.id }.distinct().size)
    }
    @Test fun mergingGroupsKeepsTheOldestIdentityAndRecordsItsReplacement() {
        // Controlled re-embedding fixture: both old groups retain their actual source text.
        fun secondGroup(id: String) = record(id, 0).let { r -> r.copy(passages = r.passages.map { it.copy(vector = listOf(0f, 1f)) }) }
        val before = build(null, record("a", 0), record("b", 0), record("c", 0), secondGroup("d"), secondGroup("e"), secondGroup("f"))
        val after = build(before, record("a", 0), record("b", 0), record("c", 0), record("d", 0).copy(revision = "r2"), record("e", 0).copy(revision = "r2"), record("f", 0).copy(revision = "r2"))
        val oldest = before.topics.minBy { it.createdOrder }.id
        assertEquals(oldest, after.topics.single().id)
        assertEquals(oldest, after.continuity.single { it.id != oldest }.replacementId)
    }    @Test fun redundantNeighborhoodsWithTheSamePassageEvidenceBecomeOneTopic() {
        fun tilted(id: String, angle: Double): SemanticRecord {
            val record = record(id, 0)
            return record.copy(passages = record.passages.map { it.copy(vector = listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())) })
        }
        val result = build(null, tilted("a", 0.0), tilted("b", .3), tilted("c", .4), tilted("d", .45), tilted("e", .5))
        assertEquals(1, result.topics.size)
        assertEquals(5, result.topics.single().members.size)
    }
    @Test fun identicalNoteMembershipDoesNotMergeIndependentPassageThemes() {
        val result = build(null, record("a", 0, 1), record("b", 0, 1), record("c", 0, 1))
        assertEquals(2, result.topics.size)
        assertEquals(2, result.topics.map { it.members.map { member -> member.passage.id }.toSet() }.distinct().size)
    }    @Test fun aNewModelCannotReuseTheOldCheckpointCompletionMap() {
        val inputs = listOf(record("a", 0), record("b", 0), record("c", 0))
        val original = SectionHierarchicalEngine(identity).step(inputs, null, TopicPolicy(), 1)
        val newIdentity = identity.copy(model = "new-model")
        val result = SectionHierarchicalEngine(newIdentity).discover(inputs.map { it.copy(identity = newIdentity) }, original, TopicPolicy())
        assertTrue(result.complete)
        assertEquals(newIdentity, result.identity)
        assertEquals(inputs.map { it.pageId }.toSet(), result.acceptedRevisions.keys)
    }
    @Test fun sourceEvictionRemovesHiddenCandidateVectorsAndTextImmediately() {
        val checkpoint = build(null, record("a", 0))
        val first = checkpoint.copy(topics = emptyList(), provisionalTopics = checkpoint.topics)
        val removed = invalidateTopics(first, setOf("a"), false)
        assertTrue(removed.provisionalTopics.isEmpty())
        assertEquals(first.continuity, removed.continuity)
        assertTrue(removed.acceptedRevisions.isEmpty())
    }    @Test fun replacingAnAnchorCannotInventDirectEvidenceBetweenOppositeEdges() {
        fun tilted(id: String, y: Float) = record(id, 0).let { r -> r.copy(passages = r.passages.map { it.copy(vector = listOf(.76f, y)) }) }
        val before = build(null, record("a", 0), tilted("b", .65f), tilted("c", -.65f), tilted("d", -.65f), tilted("e", -.65f))
        val after = build(before, tilted("b", .65f), tilted("c", -.65f), tilted("d", -.65f), tilted("e", -.65f))
        assertTrue(after.topics.none { topic -> topic.anchor.passage.pageId == "b" && topic.members.any { it.pageId == "c" } })
        val invalidated = invalidateTopics(before, setOf("a"), false, mapOf(before.topics.first().id to tilted("b", .65f).passages.single()))
        assertTrue(invalidated.topics.none { topic -> topic.anchor.passage.pageId == "b" && topic.members.any { it.pageId == "c" } })
    }
    }
