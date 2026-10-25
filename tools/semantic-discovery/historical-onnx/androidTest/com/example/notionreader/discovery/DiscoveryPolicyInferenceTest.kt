package com.example.notionreader.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Historical MiniLM synthetic baseline; not active Gemma quality acceptance. Never reads the user's library. */
class DiscoveryPolicyInferenceTest {
    @Test fun nativeEvidencePoliciesRejectHardNegativesAndRetainOverlappingThemes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val corpus = JSONObject(instrumentation.context.assets.open("discovery/policy-evaluation.json").bufferedReader().use { it.readText() })
        val provider = ShippingDiscoveryInferenceTest().provider()
        val truth = mutableMapOf<String, String?>()
        val records = mutableMapOf<String, MutableList<SemanticRecord>>()
        try {
            val documents = corpus.getJSONArray("documents")
            for (i in 0 until documents.length()) {
                val doc = documents.getJSONObject(i); val id = doc.getString("id"); val title = doc.getString("title")
                val passages = doc.getJSONArray("passages")
                val embedded = (0 until passages.length()).map { n ->
                    val p = passages.getJSONObject(n); val text = p.getString("text"); val heading = p.getString("heading")
                    val key = "$id:$n"; truth[key] = if (p.isNull("topic")) null else p.getString("topic")
                    val input = "$title\n$heading\n$text"
                    EmbeddedPassage(SemanticPassage(key, id, "$id:s$n", heading, "$id:b$n", "$id:c$n", 0, text.length,
                        text, input, n), provider.embed(input))
                }
                records.getOrPut(doc.getString("split")) { mutableListOf() }.add(SemanticRecord(id, id, provider.identity, SemanticMetadata(title), embedded))
            }
            val report = JSONObject().put("policyVersion", TopicPolicy().version).put("source", "public synthetic hard-negative corpus; real native inference")
            for (split in listOf("calibration", "heldOut")) {
                val selected = records.getValue(split)
                val result = SectionHierarchicalEngine(provider.identity).discover(selected, null, TopicPolicy())
                val expected = selected.flatMap { r -> r.passages.mapNotNull { truth[it.passage.id]?.let { t -> r.pageId to t } } }.toSet()
                val predicted = result.topics.flatMap { t -> t.members.map { it.pageId to truth[t.labelSource.id] } }.toSet()
                val correct = predicted.count { it in expected }
                val precision = if (predicted.isEmpty()) 0.0 else correct.toDouble() / predicted.size
                val recall = correct.toDouble() / expected.size
                report.put(split, JSONObject().put("precision", precision).put("recall", recall).put("topics", result.topics.size))
                assertTrue("$split precision=$precision", precision >= .8)
                assertTrue("$split recall=$recall", recall >= .8)
                assertTrue(result.topics.flatMap { it.members }.none { "-reference-" in it.pageId || "-route-" in it.pageId })
            }
            android.util.Log.i("DiscoveryPolicy", "quality=$report")
            File(instrumentation.targetContext.noBackupFilesDir, "discovery-policy-quality.json").writeText(report.toString())
        } finally { provider.release() }
    }
}
