package com.example.notionreader.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.application.DiscoveryController
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.*
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Native evidence uses the shipping provider and cache/index/controller, never fabricated vectors. */
class ShippingTopicInferenceTest {
    @Test fun tenThousandPassageTopicWorkYieldsToForegroundSearch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "topic-benchmark-${System.nanoTime()}").apply { mkdirs() }
        val provider = ShippingDiscoveryInferenceTest().provider()
        try {
            val index = FileSemanticIndexStore(File(root, "index"))
            val cache = FileDocumentCache(File(root, "cache"))
            val store = FileTopicStore(File(root, "topics"))
            val text = "Database transactions commit all writes atomically and recover safely after failure."
            val vector = provider.embed("Database reliability\n$text")
            repeat(100) { i ->
                val document = ReaderDocument.fromChunkTexts("note$i", "Database reliability", List(100) { text })
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val passages = SemanticChunker(provider.tokenizer).chunk(document)
                assertTrue(index.write(SemanticRecord(document.pageId, semanticRevision(document), provider.identity,
                    SemanticMetadata(document.title), passages.map { EmbeddedPassage(it, vector) })))
            }
            index.setPaused(false)
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(File(root, "interests")), provider,
                topicStore = store, topicCheckpointStore = FileTopicStore(File(root, "checkpoint")),
                queryTimings = { times -> android.util.Log.i("DiscoveryTopics", "queryPhasesMs=${times.mapValues { it.value / 1e6 }}") })
            val coldStarted = System.nanoTime()
            controller.search("reliable atomic database writes")
            val coldQueryMs = (System.nanoTime() - coldStarted) / 1e6
            assertEquals(20, controller.state.value.results.size)
            val buildStarted = System.nanoTime()
            val build = async(Dispatchers.Default) { controller.reconcile(emptyList(), true) }
            yield()
            val samples = mutableListOf<Double>()
            repeat(5) {
                val start = System.nanoTime()
                controller.search("reliable atomic database writes")
                samples += (System.nanoTime() - start) / 1e6
            }
            build.await()
            controller.refreshTopics()
            val elapsed = (System.nanoTime() - buildStarted) / 1e6
            val p95 = samples.sorted().last()
            val info = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            val report = JSONObject().put("passages", 10000).put("notes", 100).put("syntheticRepeatedEmbedding", true)
                .put("topicAndConcurrentQueriesMs", elapsed).put("querySamplesMs", org.json.JSONArray(samples))
                .put("queryP95Ms", p95).put("topics", controller.state.value.topicSnapshot.topics.size)
                .put("coldQueryMs", coldQueryMs)
                .put("derivedBytes", index.bytes() + store.bytes()).put("totalPssKiB", info.totalPss)
            android.util.Log.i("DiscoveryTopics", "benchmark=$report")
            assertEquals(1, controller.state.value.topicSnapshot.topics.size)
            assertEquals(100, controller.state.value.topicSnapshot.topics.single().members.size)
            assertTrue("Foreground query target during topic work: $report", p95 < 2000)
        } finally { provider.release(); root.deleteRecursively() }
    }

    @Test fun nativeTopicsAndIndividualConnectionsMeetHeldOutQualityAndKeepFollows() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val corpus = JSONObject(instrumentation.context.assets.open("discovery/topic-evaluation.json").bufferedReader().use { it.readText() })
        val root = File(context.noBackupFilesDir, "topic-quality-${System.nanoTime()}").apply { mkdirs() }
        val provider = ShippingDiscoveryInferenceTest().provider()
        try {
            val cache = FileDocumentCache(File(root, "cache"))
            val index = FileSemanticIndexStore(File(root, "index"))
            val interests = FileDiscoveryInterestStore(File(root, "interests"))
            val topics = FileTopicStore(File(root, "topics"))
            val splits = mutableMapOf<String, String>()
            val truth = mutableMapOf<String, String?>()
            val documents = corpus.getJSONArray("documents")
            for (i in 0 until documents.length()) {
                val source = documents.getJSONObject(i); val id = source.getString("id")
                splits[id] = source.getString("split")
                val passages = source.getJSONArray("passages")
                val sections = (0 until passages.length()).map { p ->
                    val passage = passages.getJSONObject(p)
                    ReaderSection("$id:section:$p", "Summary", 1,
                        listOf(ReaderChunk("$id:heading:$p", "$id:section:$p", ReaderChunkKind.HEADING, "Summary"),
                            ReaderChunk("$id:chunk:$p", "$id:block:$p", ReaderChunkKind.PARAGRAPH, passage.getString("text"))))
                }
                val document = ReaderDocument(id, source.getString("title"), sections)
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                SemanticChunker(provider.tokenizer).chunk(document).forEach { chunk ->
                    val p = chunk.sectionId.substringAfterLast(':').toInt()
                    truth[chunk.id] = if (chunk.isBodyEvidence()) passages.getJSONObject(p).takeUnless { it.isNull("topic") }?.getString("topic") else null
                }
            }
            index.setPaused(false)
            val started = System.nanoTime()
            val controller = DiscoveryController(cache, index, interests, provider, topicStore = topics,
                topicCheckpointStore = FileTopicStore(File(root, "checkpoint")))
            repeat((documents.length() + 3) / 4 + 1) { controller.reconcile(emptyList(), true) }
            controller.refreshTopics()
            val totalMs = (System.nanoTime() - started) / 1e6
            val records = index.pageIds().sorted().mapNotNull { index.read(it) }
            fun measure(split: String, policy: TopicPolicy = TopicPolicy(), published: Boolean = false): Triple<Double, Double, TopicSnapshot> {
                val selected = records.filter { splits[it.pageId] == split }
                val candidates = TopicBuilder(null, provider.identity, policy).apply { selected.forEach(::accept) }.finish()
                val result = if (published) candidates.copy(topics = publishableDiscoveryTopics(candidates.topics)) else candidates
                val expected = selected.flatMap { r -> r.passages.mapNotNull { truth[it.passage.id]?.let { t -> r.pageId to t } } }.toSet()
                val predicted = result.topics.flatMap { t -> t.members.map { it.pageId to truth[t.labelSource.id] } }.toSet()
                val evidence = result.topics.flatMap { t -> t.members.map { truth[it.passage.id] != null && truth[it.passage.id] == truth[t.labelSource.id] } }
                val evidencePrecision = if (evidence.isEmpty()) 0.0 else evidence.count { it }.toDouble() / evidence.size
                val correct = predicted.count { it in expected }
                return Triple(minOf(evidencePrecision, if (predicted.isEmpty()) 0.0 else correct.toDouble() / predicted.size),
                    if (expected.isEmpty()) 0.0 else correct.toDouble() / expected.size, result)
            }
            val candidates = listOf(.55f, .6f, .65f, .7f, .75f, .8f, .82f).map { threshold ->
                val policy = TopicPolicy(admission = threshold, retention = threshold - .06f, separation = minOf(1f, threshold + .1f))
                val measured = measure("calibration", policy)
                Triple(policy, measured.first, measured.second)
            }
            val selectedPolicy = candidates.filter { it.second >= .8 }.maxWithOrNull(
                compareBy<Triple<TopicPolicy, Double, Double>> { it.third }.thenBy { it.first.admission })
            android.util.Log.i("DiscoveryTopics", "genericHeadingCalibration=$candidates selected=$selectedPolicy")
            assertNotNull("Calibration must produce an eligible policy", selectedPolicy)
            assertEquals("Shipping admission must match calibration", selectedPolicy!!.first.admission, TopicPolicy().admission)
            val calibration = measure("calibration"); val held = measure("heldOut")
            val publishedHeld = measure("heldOut", published = true)
            val snapshot = controller.state.value.topicSnapshot
            val truthPairs = corpus.getJSONArray("heldOutConnectionPairs").let { pairs ->
                (0 until pairs.length()).map { i -> pairs.getJSONArray(i).let { setOf(it.getString(0), it.getString(1)) } }.toSet()
            }
            // Pair labels use the same passage theme on both sides, independently of Note-level memberships.
            val pairs = snapshot.connections.filter { splits[it.left.pageId] == "heldOut" && splits[it.right.pageId] == "heldOut" }
            val pairCorrect = pairs.count { truth[it.left.passage.id] != null && truth[it.left.passage.id] == truth[it.right.passage.id] }
            val pairPrecision = if (pairs.isEmpty()) 0.0 else pairCorrect.toDouble() / pairs.size
            val requiredFound = truthPairs.count { expected -> pairs.any { setOf(it.left.pageId, it.right.pageId) == expected } }
            val mixed = held.third.topics.filter { t -> t.members.any { it.pageId == "mixedheld" } }
            val report = JSONObject().put("documents", documents.length()).put("passages", records.sumOf { it.passages.size })
                .put("calibrationPrecision", calibration.first).put("calibrationRecall", calibration.second)
                .put("heldOutPrecision", held.first).put("heldOutRecall", held.second)
                .put("publishedHeldOutPrecision", publishedHeld.first).put("publishedHeldOutRecall", publishedHeld.second)
                .put("pairPrecision", pairPrecision).put("heldOutPairs", pairs.size).put("requiredPairsFound", requiredFound)
                .put("topics", snapshot.topics.size).put("initialIndexAndTopicsMs", totalMs)
                .put("derivedBytes", index.bytes() + topics.bytes()).put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
            android.util.Log.i("DiscoveryTopics", "quality=$report")
            assertTrue("Calibration precision: $report", calibration.first >= .8)
            assertTrue("Held-out precision: $report", held.first >= .8)
            assertTrue("Held-out recall: $report", held.second >= .8)
            assertTrue("Published held-out precision: $report", publishedHeld.first >= .8)
            assertTrue("Published held-out recall: $report", publishedHeld.second >= .8)
            assertEquals(setOf("music", "astronomy"), mixed.mapNotNull { truth[it.labelSource.id] }.toSet())
            assertEquals(2, mixed.flatMap { t -> t.members.filter { it.pageId == "mixedheld" }.map { it.passage.id } }.distinct().size)
            assertTrue("Held-out pair precision: $report", pairPrecision >= .8)
            assertEquals("Required cross-topic pair: $report", truthPairs.size, requiredFound)
            val topic = snapshot.topics.first()
            controller.followTopic(topic.id, "My lasting interest", "frequency analysis")
            val saved = interests.read().single()
            controller.clearIndex()
            assertEquals(saved, interests.read().single())
            assertTrue(topics.read() == null)
        } finally { provider.release(); root.deleteRecursively() }
    }
}
