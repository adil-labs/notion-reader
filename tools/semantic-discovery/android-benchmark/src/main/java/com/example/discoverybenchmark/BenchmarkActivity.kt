package com.example.discoverybenchmark

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.TextView
import com.example.notionreader.application.DiscoveryController
import com.example.notionreader.data.discovery.OnnxEmbeddingProvider
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Separate UID, no permissions/network, public synthetic records, shipping sources copied by Gradle. */
class BenchmarkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Running public-data shipping discovery benchmark…" })
        scope.launch {
            val runId = intent.getStringExtra("runId") ?: java.util.UUID.randomUUID().toString()
            val queryDelaySeconds = intent.getIntExtra("queryDelaySeconds", 0)
            val report = try {
                require(queryDelaySeconds in 0..120)
                benchmark(runId, queryDelaySeconds)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                JSONObject().put("status", "error").put("errorType", failure.javaClass.simpleName)
            }
            report.put("runId", runId)
            File(noBackupFilesDir, "report.json").writeText(report.toString(2))
            android.util.Log.i("DiscoveryBenchmark", report.toString())
            withContext(Dispatchers.Main) { setContentView(TextView(this@BenchmarkActivity).apply { text = report.toString(2) }) }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private suspend fun benchmark(runId: String, queryDelaySeconds: Int): JSONObject {
        val root = File(noBackupFilesDir, "run-${System.currentTimeMillis()}").apply { mkdirs() }
        val model = File(root, "model").apply { mkdirs() }
        val hashes = mapOf("model_qint8_arm64.onnx" to "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474",
            "vocab.txt" to "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3")
        for ((name, expected) in hashes) {
            val file = File(model, name)
            assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected)
        }
        val provider = OnnxEmbeddingProvider(model)
        try {
            val text = "Database transactions protect atomic writes and recover safely after interruption."
            val template = ReaderDocument.fromChunkTexts("template", "Database reliability", List(100) { text })
            val chunked = SemanticChunker(provider.tokenizer).chunk(template)
            check(chunked.size == 100)
            val coldStarted = System.nanoTime()
            provider.embed("Cold embedding initialization probe")
            val coldEmbedMs = (System.nanoTime() - coldStarted) / 1e6
            val inferenceStarted = System.nanoTime()
            val embedded = chunked.map { EmbeddedPassage(it, provider.embed(it.input)) }
            val inferenceMs = (System.nanoTime() - inferenceStarted) / 1e6
            val cache = FileDocumentCache(File(root, "cache"))
            val index = FileSemanticIndexStore(File(root, "index"))
            val persistStarted = System.nanoTime()
            repeat(100) { n ->
                val id = "benchmark$n"
                val document = template.copy(pageId = id)
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                check(index.write(SemanticRecord(id, semanticRevision(document), provider.identity, SemanticMetadata(document.title),
                    embedded.map { it.copy(passage = it.passage.copy(pageId = id, id = "$id:${it.passage.order}")) })))
            }
            val persistMs = (System.nanoTime() - persistStarted) / 1e6
            check(index.pageIds().sumOf { index.read(it)!!.passages.size } == 10000)
            val phases = JSONArray()
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(File(root, "interests")), provider,
                queryTimings = { if (it.containsKey("searchTotalNs")) phases.put(JSONObject(it)) })
            controller.search("How do databases keep changes safe?")
            val readyEpochMs = System.currentTimeMillis()
            val readyElapsedRealtimeMs = SystemClock.elapsedRealtime()
            File(noBackupFilesDir, "ready.json").writeText(JSONObject()
                .put("runId", runId).put("requestedQueryDelaySeconds", queryDelaySeconds)
                .put("readyEpochMs", readyEpochMs).put("readyElapsedRealtimeMs", readyElapsedRealtimeMs)
                .toString())
            delay(queryDelaySeconds * 1000L)
            val queryWindowStartEpochMs = System.currentTimeMillis()
            val queryWindowStartElapsedRealtimeMs = SystemClock.elapsedRealtime()
            val timings = (0 until 20).map {
                val started = System.nanoTime()
                controller.search("How do databases keep changes safe?")
                check(controller.state.value.results.size == 20 && controller.state.value.message == null)
                (System.nanoTime() - started) / 1e6
            }.sorted()
            val queryWindowEndElapsedRealtimeMs = SystemClock.elapsedRealtime()
            val queryWindowEndEpochMs = System.currentTimeMillis()
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            return JSONObject().put("status", if (timings[18] < 2000) "passed" else "failed")
                .put("reportFormatVersion", 2).put("requestedQueryDelaySeconds", queryDelaySeconds)
                .put("readyEpochMs", readyEpochMs).put("readyElapsedRealtimeMs", readyElapsedRealtimeMs)
                .put("queryWindowStartEpochMs", queryWindowStartEpochMs)
                .put("queryWindowEndEpochMs", queryWindowEndEpochMs)
                .put("queryWindowStartElapsedRealtimeMs", queryWindowStartElapsedRealtimeMs)
                .put("queryWindowEndElapsedRealtimeMs", queryWindowEndElapsedRealtimeMs)
                .put("scope", "Isolated public synthetic corpus; shipping source code; no reader library access")
                .put("abi", android.os.Build.SUPPORTED_ABIS.first()).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("notes", 100).put("passages", 10000).put("syntheticRepeatedEmbeddings", true)
                .put("samples", 20).put("coldEmbedIncludingSessionMs", coldEmbedMs)
                .put("hundredPassageInferenceMs", inferenceMs).put("persistMs", persistMs)
                .put("queryP50Ms", timings[9]).put("queryP95Ms", timings[18]).put("targetP95Ms", 2000)
                .put("querySamplesMs", JSONArray(timings)).put("searchPhasesNs", phases)
                .put("indexBytes", index.bytes()).put("totalPssKiB", memory.totalPss)
                .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        } finally { provider.release(); root.deleteRecursively() }
    }
}
