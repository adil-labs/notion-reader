package com.example.notionreader.domain.discovery

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual pinned-model outputs on a separate synthetic corpus; not device inference evidence. */
class TopicQualityTest {
    private var corpus = Json.parseToJsonElement(javaClass.getResource("/discovery/topic-vectors.json")!!.readText()).jsonObject
    private val identity = EmbeddingIdentity("topic-quality-host", 384)
    private fun records(split: String): List<SemanticRecord> = corpus.getValue("documents").jsonArray
        .map { it.jsonObject }.filter { it.getValue("split").jsonPrimitive.content == split }.map { doc ->
            val id = doc.getValue("id").jsonPrimitive.content
            SemanticRecord(id, id, identity, SemanticMetadata(doc.getValue("title").jsonPrimitive.content),
                doc.getValue("passages").jsonArray.mapIndexed { i, entry ->
                    val p = entry.jsonObject
                    val text = p.getValue("text").jsonPrimitive.content
                    EmbeddedPassage(SemanticPassage("$id:$i", id, "$id:section:$i", p.getValue("heading").jsonPrimitive.content,
                        "$id:block:$i", "$id:chunk:$i", 0, text.length, text, p.getValue("input").jsonPrimitive.content, i),
                        normalizeEmbedding(p.getValue("vector").jsonArray.map { it.jsonPrimitive.float }, 384))
                })
        }
    private val truth get() = corpus.getValue("documents").jsonArray.map { it.jsonObject }.flatMap { doc ->
        val id = doc.getValue("id").jsonPrimitive.content
        doc.getValue("passages").jsonArray.mapIndexed { i, p -> "$id:$i" to p.jsonObject["topic"]?.jsonPrimitive?.contentOrNull }
    }.toMap()
    private fun snapshot(records: List<SemanticRecord>, policy: TopicPolicy) = TopicBuilder(null, identity, policy).apply { records.forEach(::accept) }.finish()
    private fun measures(snapshot: TopicSnapshot, records: List<SemanticRecord>): Pair<Double, Double> {
        val evidence = snapshot.topics.flatMap { topic -> topic.members.map { truth[it.passage.id] != null && truth[it.passage.id] == truth[topic.labelSource.id] } }
        assertTrue("Membership evidence must support the anchor theme", evidence.isNotEmpty() && evidence.count { it }.toDouble() / evidence.size >= .8)
        val predicted = snapshot.topics.flatMap { topic -> topic.members.map { it.pageId to truth[topic.labelSource.id] } }.toSet()
        val expected = records.flatMap { record -> record.passages.mapNotNull { truth[it.passage.id]?.let { label -> record.pageId to label } } }.toSet()
        val correct = predicted.count { it in expected }
        return (if (predicted.isEmpty()) 0.0 else correct.toDouble() / predicted.size) to
            (if (expected.isEmpty()) 0.0 else correct.toDouble() / expected.size)
    }
    @Test fun calibratedOverlappingTopicsRetainHeldOutPrecisionAndReportRecall() {
        val calibration = records("calibration")
        val candidates = listOf(.55f, .6f, .65f, .7f, .75f, .8f, .82f).map { threshold ->
            TopicPolicy(admission = threshold, retention = threshold - .06f, separation = minOf(1f, threshold + .1f))
        }
        val measured = candidates.map { it to measures(snapshot(calibration, it), calibration) }
        val selected = measured.filter { it.second.first >= .8 }.maxWithOrNull(
            compareBy<Pair<TopicPolicy, Pair<Double, Double>>> { it.second.second }.thenBy { it.first.admission })
        println("TOPIC_CALIBRATION=$measured SELECTED=$selected")
        assertNotNull("No calibration policy meets precision", selected)
        val held = records("heldOut")
        val result = snapshot(held, selected!!.first)
        val quality = measures(result, held)
        println("TOPIC_HELD_OUT precision=${quality.first} recall=${quality.second} topics=${result.topics.size} policy=${selected.first}")
        assertTrue("Held-out membership precision $quality", quality.first >= .8)
        assertTrue("Held-out corpus must yield useful coverage", quality.second >= .8)
        val mixed = result.topics.filter { t -> t.members.any { it.pageId == "mixedheld" } }
        assertEquals(setOf("music", "astronomy"), mixed.mapNotNull { truth[it.labelSource.id] }.toSet())
        assertEquals(2, mixed.flatMap { t -> t.members.filter { it.pageId == "mixedheld" }.map { it.passage.id } }.distinct().size)
        assertTrue(result.topics.all { it.members.size >= 3 && truth[it.labelSource.id] != null })
        println("TOPIC_NEIGHBORHOODS=" + result.topics.map { t -> Triple(t.label, t.anchor.passage.id, t.members.map { it.passage.id }) })
        assertTrue("Redundant neighborhoods should not crowd out distinct themes", result.topics.groupBy { truth[it.labelSource.id] }.values.all { it.size == 1 })
    }
    @Test fun fixedPolicyRetainsHeldOutThemesWithoutReferenceOnlyGroups() {
        corpus = Json.parseToJsonElement(javaClass.getResource("/discovery/policy-vectors.json")!!.readText()).jsonObject
        for (split in listOf("calibration", "heldOut")) {
            val inputs = records(split)
            val result = snapshot(inputs, TopicPolicy())
            val quality = measures(result, inputs)
            println("POLICY_QUALITY split=$split precision=${quality.first} recall=${quality.second} topics=${result.topics.size}")
            assertTrue("Fixed-policy precision on $split: $quality", quality.first >= .8)
            assertTrue("Fixed-policy recall on $split: $quality", quality.second >= .8)
            assertTrue("Reference and routing Notes must not become members", result.topics.flatMap { it.members }
                .none { "-reference-" in it.pageId || "-route-" in it.pageId })
        }
    }

}
