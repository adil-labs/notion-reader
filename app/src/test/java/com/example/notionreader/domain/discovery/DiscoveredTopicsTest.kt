package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveredTopicsTest {
    @Test fun continuityThatMovesToAnotherCandidateCannotAlsoIdentifyItsFormerCandidate() {
        var builder = TopicBuilder(null, identity)
        builder.accept(record("a", 0)); builder.accept(record("b", 1))
        val first = builder.checkpoint()
        val originalId = first.topics.single { it.members.any { member -> member.pageId == "a" } }.id
        builder = TopicBuilder(first, identity)
        builder.checkpoint()
        builder.accept(record("a", 1).copy(revision = "r2"))
        builder.accept(record("c", 0))
        val next = builder.checkpoint()
        assertEquals(next.topics.size, next.topics.map { it.id }.distinct().size)
        assertEquals(originalId, next.topics.single { it.members.any { member -> member.pageId == "a" } }.id)
        val freshId = next.topics.single { it.members.any { member -> member.pageId == "c" } }.id
        assertNotEquals(originalId, freshId)
        assertEquals(freshId, builder.checkpoint().topics.single { it.members.any { member -> member.pageId == "c" } }.id)
        val restored = TopicBuilder(next, identity).checkpoint()
        assertEquals(next.topics.map { it.id }.toSet(), restored.topics.map { it.id }.toSet())
        assertEquals(freshId, restored.topics.single { it.members.any { member -> member.pageId == "c" } }.id)
    }
    @Test fun resumingAMidNoteCheckpointPreservesItsCandidateBudget() {
        val model = EmbeddingIdentity("resume", 5)
        val passages = (0..4).map { axis ->
            EmbeddedPassage(SemanticPassage("p:$axis", "p", "s:$axis", null, "b:$axis", "c:$axis", 0, 4,
                "Body", "Body", axis), List(5) { if (it == axis) 1f else 0f })
        }
        val record = SemanticRecord("p", "r", model, SemanticMetadata("Note"), passages)
        val first = TopicBuilder(null, model).apply { acceptPartial(record.copy(passages = passages.take(2)), false) }.checkpoint()
        val resumed = TopicBuilder(first, model).apply { accept(record.copy(passages = passages.drop(2))) }.checkpoint()
        assertEquals(2, resumed.topics.size)
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
        val builder = TopicBuilder(null, identity, TopicPolicy(admission = .75f, retention = .69f, separation = .85f))
        listOf("a", "b", "c").forEach { id ->
            val r = record(id, 0)
            val first = r.passages.single().copy(passage = r.passages.single().passage.copy(start = 0, end = 100))
            val second = first.copy(passage = first.passage.copy(id = "$id-overlap", start = 50, end = 150),
                vector = listOf(kotlin.math.cos(.6).toFloat(), kotlin.math.sin(.6).toFloat()))
            builder.accept(r.copy(passages = listOf(first, second)))
        }
        assertEquals(1, builder.finish().topics.size)
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
        assertTrue(build(null, *sources.toTypedArray()).topics.isEmpty())
    }

    @Test fun aLongFirstNoteCannotUseEveryCandidateSlot() {
        val model = EmbeddingIdentity("fair", 70)
        fun source(id: String, axes: List<Int>) = SemanticRecord(id, "r", model, SemanticMetadata(id), axes.map { axis ->
            val text = "Substantive body about theme $axis"
            EmbeddedPassage(SemanticPassage("$id:$axis", id, "$id:section:$axis", null, "$id:block:$axis",
                "$id:chunk:$axis", 0, text.length, text, text, axis), List(70) { if (it == axis) 1f else 0f })
        })
        val builder = TopicBuilder(null, model)
        builder.accept(source("long", (0..65).toList()))
        listOf("x", "y", "z").forEach { builder.accept(source(it, listOf(69))) }
        assertEquals(setOf("x", "y", "z"), builder.finish().topics.single().members.map { it.pageId }.toSet())
    }
    private val identity = EmbeddingIdentity("test", 2)
    private fun record(id: String, vararg axes: Int) = SemanticRecord(id, "r1", identity,
        SemanticMetadata("Note $id"), axes.mapIndexed { n, axis ->
            val text = if (axis == 0) "Gardening" else "Astronomy"
            EmbeddedPassage(SemanticPassage("$id-$n", id, "$id-s$n", text, "$id-b$n", "$id-c$n", 0, text.length, text, text, n),
                if (axis == 0) listOf(1f, 0f) else listOf(0f, 1f))
        })
    private fun build(previous: TopicSnapshot? = null, vararg records: SemanticRecord): TopicSnapshot {
        val builder = TopicBuilder(previous, identity)
        records.forEach(builder::accept)
        return builder.finish()
    }
    @Test fun aTwoThemeNoteHasIndependentPassageEvidence() {
        val snapshot = build(null, record("both", 0, 1), record("garden1", 0), record("garden2", 0), record("sky1", 1), record("sky2", 1))
        assertEquals(2, snapshot.topics.size)
        assertEquals(setOf("both-0", "both-1"), snapshot.topics.flatMap { it.members }.filter { it.pageId == "both" }.map { it.passage.id }.toSet())
        assertTrue(snapshot.topics.all { it.members.map { m -> m.pageId }.distinct().size == 3 })
    }
    @Test fun repeatedPassagesCannotMakeAThreeNoteTopic() {
        assertTrue(build(null, record("one", 0, 0, 0)).topics.isEmpty())
    }
    @Test fun unrelatedAdditionsAndReorderPreserveIdentityAndLabel() {
        val first = build(null, record("a", 0), record("b", 0), record("c", 0))
        val next = build(first, record("noise", 1), record("c", 0), record("b", 0), record("a", 0))
        assertEquals(first.topics.single().id, next.topics.single().id)
        assertEquals(first.topics.single().label, next.topics.single().label)
    }
    @Test fun anchorLossPreservesIdentityFromSurvivingMembers() {
        val first = build(null, record("a", 0), record("b", 0), record("c", 0), record("d", 0))
        val next = build(first, record("b", 0), record("c", 0), record("d", 0))
        assertEquals(first.topics.single().id, next.topics.single().id)
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
        assertTrue(build(null, bad, record("b", 0), record("c", 0)).topics.isEmpty())
        assertTrue(build(null, record("a", 0).copy(identity = identity.copy(model="different")), record("b", 0), record("c", 0)).topics.isEmpty())
    }
    @Test fun repeatedCheckpointsKeepProvisionalIdentity() {
        val builder = TopicBuilder(null, identity)
        builder.accept(record("a", 0))
        val first = builder.checkpoint()
        assertEquals(first.topics.single().id, builder.checkpoint().topics.single().id)
    }
    @Test fun aPartialRecordResumesWithoutDroppingEarlierEvidence() {
        val builder = TopicBuilder(null, identity)
        builder.accept(record("a", 0))
        builder.acceptPartial(record("both", 0), false)
        val resumed = TopicBuilder(builder.checkpoint(), identity)
        resumed.acceptPartial(record("both", 1).let { r -> r.copy(passages = r.passages.map { it.copy(passage = it.passage.copy(id = "both-1")) }) }, true)
        resumed.accept(record("b", 0))
        resumed.accept(record("sky1", 1))
        resumed.accept(record("sky2", 1))
        val topics = resumed.finish().topics
        assertEquals(2, topics.size)
        assertEquals(setOf("both-0"), topics.first { it.label == "Gardening" }.members.filter { it.pageId == "both" }.map { it.passage.id }.toSet())
    }
    @Test fun reachingTheAnchorBoundReportsIncompleteCoverage() {
        val builder = TopicBuilder(null, identity, TopicPolicy(maxAnchors = 1))
        builder.accept(record("a", 0, 1))
        assertFalse(builder.finish().complete)
        assertNotNull(builder.finish().message)
        assertEquals(1, builder.checkpoint().topics.size)
    }    @Test fun irrelevantInvalidationDoesNotCreateANewGeneration() {
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
        assertEquals(before.topics.single().id, after.topics.single { it.members.any { member -> member.pageId == "d" } }.id)
        assertEquals(2, after.topics.map { it.id }.distinct().size)
    }
    @Test fun mergingGroupsKeepsTheOldestIdentityAndRecordsItsReplacement() {
        val before = build(null, record("a", 0), record("b", 0), record("c", 0), record("d", 1), record("e", 1), record("f", 1))
        val after = build(before, record("a", 0), record("b", 0), record("c", 0), record("d", 0).copy(revision = "r2"), record("e", 0).copy(revision = "r2"), record("f", 0).copy(revision = "r2"))
        val oldest = before.topics.minBy { it.createdOrder }.id
        assertEquals(oldest, after.topics.single().id)
        assertEquals(oldest, after.continuity.single { it.id != oldest }.replacementId)
    }    @Test fun redundantNeighborhoodsWithTheSamePassageEvidenceBecomeOneTopic() {
        fun tilted(id: String, angle: Double): SemanticRecord {
            val record = record(id, 0)
            return record.copy(passages = record.passages.map { it.copy(vector = listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())) })
        }
        val builder = TopicBuilder(null, identity, TopicPolicy(admission = .7f, retention = .65f, separation = .98f))
        listOf(tilted("a", 0.0), tilted("b", .3), tilted("c", .4), tilted("d", .45), tilted("e", .5)).forEach(builder::accept)
        val result = builder.finish()
        assertEquals(1, result.topics.size)
        assertEquals(5, result.topics.single().members.size)
    }
    @Test fun identicalNoteMembershipDoesNotMergeIndependentPassageThemes() {
        val result = build(null, record("a", 0, 1), record("b", 0, 1), record("c", 0, 1))
        assertEquals(2, result.topics.size)
        assertEquals(2, result.topics.map { it.members.map { member -> member.passage.id }.toSet() }.distinct().size)
    }    @Test fun aNewModelCannotReuseTheOldCheckpointCompletionMap() {
        val original = TopicBuilder(null, identity).apply { listOf(record("a", 0), record("b", 0), record("c", 0)).forEach(::accept) }.checkpoint()
        val newIdentity = identity.copy(model = "new-model")
        val builder = TopicBuilder(original, newIdentity)
        listOf(record("a", 0), record("b", 0), record("c", 0)).map { it.copy(identity = newIdentity) }.forEach(builder::accept)
        assertEquals(1, builder.finish().topics.size)
    }    @Test fun unsupportedCandidatesSurviveOneCompletedPassAndRetireAfterTwo() {
        val first = build(null, record("a", 0))
        assertTrue(first.topics.isEmpty())
        assertEquals(1, first.provisionalTopics.size)
        assertEquals(1, first.provisionalPasses[first.provisionalTopics.single().id])
        val second = build(first, record("a", 0))
        assertTrue(second.provisionalTopics.isEmpty())
        assertTrue(second.topics.isEmpty())
        assertEquals(first.continuity.single().id, second.continuity.single().id)
    }
    @Test fun sourceEvictionRemovesHiddenCandidateVectorsAndTextImmediately() {
        val checkpoint = TopicBuilder(null, identity).apply { accept(record("a", 0)) }.checkpoint()
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
    @Test fun aHeadingNeedsDistinctMemberSupportBeforeBecomingATopicLabel() {
        val anchor = record("a", 0).let { r -> r.copy(passages = r.passages.map { it.copy(passage = it.passage.copy(sectionTitle = "Private unrelated heading")) }) }
        val snapshot = build(null, anchor, record("b", 0), record("c", 0))
        assertEquals("Notes related to Note a", snapshot.topics.single().label)
    }}
