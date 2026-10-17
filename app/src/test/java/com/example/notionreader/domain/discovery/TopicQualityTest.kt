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
    private fun snapshot(records: List<SemanticRecord>, policy: TopicPolicy) = SectionHierarchicalEngine(identity).discover(records, null, policy)
    private fun measures(snapshot: TopicSnapshot, records: List<SemanticRecord>): Pair<Double, Double> {
        val evidence = snapshot.topics.flatMap { topic -> topic.members.map { truth[it.passage.id] != null && truth[it.passage.id] == truth[topic.labelSource.id] } }
        assertTrue("Membership evidence must support the anchor theme", evidence.isNotEmpty() && evidence.count { it }.toDouble() / evidence.size >= .8)
        val predicted = snapshot.topics.flatMap { topic -> topic.members.map { it.pageId to truth[topic.labelSource.id] } }.toSet()
        val expected = records.flatMap { record -> record.passages.mapNotNull { truth[it.passage.id]?.let { label -> record.pageId to label } } }.toSet()
        val correct = predicted.count { it in expected }
        println("QUALITY_DENOMINATORS correct=$correct predicted=${predicted.size} expected=${expected.size}")
        return (if (predicted.isEmpty()) 0.0 else correct.toDouble() / predicted.size) to
            (if (expected.isEmpty()) 0.0 else correct.toDouble() / expected.size)
    }

    @Test fun calibrationReportsGroupingTradeoffsWithoutUsingHeldOutData() {
        corpus = Json.parseToJsonElement(javaClass.getResource("/discovery/policy-vectors.json")!!.readText()).jsonObject
        val inputs = records("calibration")
        for (distance in listOf(.20f, .25f, .30f, .35f)) {
            val result = SectionHierarchicalEngine(identity, distanceThreshold = distance).discover(inputs, null, TopicPolicy())
            val measured = measures(result, inputs)
            println("CALIBRATION distance=$distance precision=${measured.first} recall=${measured.second} topics=${result.topics.size}")
            if (distance == .35f) for (topic in result.topics) {
                println("CALIBRATION label=${topic.label} evidence=${topic.members.map { it.passage.id to truth[it.passage.id] }} source=${truth[topic.labelSource.id]}")
            }
        }
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
            val published = result.copy(topics = publishableDiscoveryTopics(result.topics))
            val publishedQuality = measures(published, inputs)
            assertTrue("Published membership precision on $split: $publishedQuality", publishedQuality.first >= .8)
            assertTrue("Published theme recall on $split: $publishedQuality", publishedQuality.second >= .8)
            assertTrue("Reference and routing Notes must not become members", result.topics.flatMap { it.members }
                .none { "-reference-" in it.pageId || "-route-" in it.pageId })
        }
    }

}
