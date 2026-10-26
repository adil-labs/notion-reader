package com.example.generativediscoveryprobe

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ResponseFormat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Isolated developer probe. No Reader dependency, credentials, private Notes or network permission. */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { this.text = "Public-input model probe starting" }
        setContentView(text)
        val backendName = intent.getStringExtra("backend") ?: "CPU"
        val requestedCase = intent.getStringExtra("case") ?: "different-wording"
        val contextTokens = intent.getIntExtra("contextTokens", 2048)
        val outputTokens = intent.getIntExtra("outputTokens", 512)
        val cancelAfterMillis = intent.getIntExtra("cancelAfterMillis", 0)
        val cpuThreads = intent.getIntExtra("cpuThreads", 2)
        val promptVersion = intent.getIntExtra("promptVersion", 3)
        Thread {
            val report = JSONObject().put("status", "running").put("backend", backendName)
                .put("case", requestedCase).put("runtime", "LiteRT-LM 0.18.1")
                .put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
                .put("contextTokens", contextTokens).put("maxOutputTokens", outputTokens)
                .put("promptVersion", if (promptVersion == 3) "public-probe-3-specific-concepts"
                    else if (promptVersion == 4) "public-probe-4-specific-grounded-concepts"
                    else if (promptVersion == 5) "public-probe-5-label-only"
                    else "public-probe-6-coverage-grouping").put("thinking", false)
                .put("pid", android.os.Process.myPid())
                .put("cancelAfterMillis", cancelAfterMillis)
                .put("cpuThreads", cpuThreads)
            val output = File(getExternalFilesDir(null), "probe-$backendName-$requestedCase.json")
            fun save() { output.writeText(report.toString(2)) }
            if (requestedCase == "topic-name-queries") {
                try {
                    report.put("nameOnlyQueries", com.example.notionreader.data.discovery.TopicQueryProbe.run(this))
                        .put("status", "completed")
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("error", failure.javaClass.name).put("message", failure.message)
                    android.util.Log.e("PublicQueryProbe", "Query probe failed", failure)
                }
                save()
                runOnUiThread { text.text = "Topic query ${report.getString("status")}" }
                return@Thread
            }
            if (requestedCase == "production-semantic") {
                try {
                    report.put("promptVersion", "shipping-label-policy; see nested labelIdentity")
                        .put("semantic", com.example.notionreader.data.discovery.ProductionSemanticProbe.run(this, backendName))
                        .put("status", "completed")
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("error", failure.javaClass.name).put("message", failure.message)
                    android.util.Log.e("PublicSemanticProbe", "Semantic probe failed", failure)
                }
                save()
                runOnUiThread { text.text = "Production semantic ${report.getString("status")}" }
                return@Thread
            }
            if (requestedCase in setOf("production-worker", "production-worker-save", "production-worker-restore", "production-worker-withdraw", "production-worker-reset", "production-worker-storage", "production-worker-model-delete", "production-worker-model-delete-active", "production-worker-init-query")) {
                try {
                    report.put("promptVersion", "shipping-label-policy; see nested labelIdentity")
                        .put("productionWorker", if (requestedCase == "production-worker-restore")
                            com.example.notionreader.data.discovery.ProductionWorkerProbe.restoreAfterProcessDeath(this, backendName)
                            else com.example.notionreader.data.discovery.ProductionWorkerProbe.run(
                                this, backendName, cancelAfterMillis, saveForRestart = requestedCase == "production-worker-save",
                                queryDuringInitialization = requestedCase == "production-worker-init-query",
                                deleteManagedModel = requestedCase in setOf("production-worker-model-delete", "production-worker-model-delete-active"),
                                refuseLabelStorage = requestedCase == "production-worker-storage", lifecycleAction = when (requestedCase) {
                                    "production-worker-withdraw" -> "withdraw"
                                    "production-worker-reset" -> "reset"
                                    "production-worker-model-delete-active" -> "model-delete"
                                    else -> null
                                }))
                        .put("status", "completed")
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("error", failure.javaClass.name).put("message", failure.message)
                    android.util.Log.e("PublicWorkerProbe", "Worker probe failed", failure)
                }
                save()
                runOnUiThread { text.text = "Production worker ${report.getString("status")}" }
                return@Thread
            }
            if (requestedCase == "production-label") {
                try {
                    report.put("promptVersion", "shipping-concept-policy; see nested labelIdentity").put("productionLabelPort", com.example.notionreader.data.discovery.ProductionLabelProbe.run(
                        this, backendName, cancelAfterMillis)).put("status", "completed")
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("error", failure.javaClass.name).put("message", failure.message)
                }
                save()
                runOnUiThread { text.text = "Production label port ${report.getString("status")}" }
                return@Thread
            }
            if (requestedCase == "embedding") {
                try {
                    require(backendName == "CPU" && cpuThreads == 2 && cancelAfterMillis == 0)
                    report.put("embeddingReplay", EmbeddingReplay.run(this)).put("status", "completed")
                } catch (failure: Throwable) {
                    report.put("status", "failed").put("error", failure.javaClass.name)
                        .put("message", failure.message)
                }
                save()
                runOnUiThread { text.text = "Public embedding replay ${report.getString("status")}" }
                return@Thread
            }
            try {
                require(backendName in setOf("CPU", "GPU"))
                require(contextTokens in setOf(512, 1024, 2048) && outputTokens in 64..512 && outputTokens < contextTokens)
                require(cancelAfterMillis in 0..10000)
                require(cpuThreads in setOf(2, 4, 6))
                require(promptVersion in setOf(3, 4, 5, 6))
                require((requestedCase == "baseline-labels") == (promptVersion == 5))
                report.put("beforeLoad", ProbeMemory.snapshot(this))
                report.put("recentExits", ProbeMemory.recentExits(this))
                require(!report.getJSONObject("beforeLoad").getBoolean("deviceLowMemory")) {
                    "Generation deferred: device reports low memory"
                }
                val model = File(getExternalFilesDir(null), "gemma-4-E2B-it.litertlm")
                require(model.length() == 2588147712L) { "Missing or wrong-sized pinned model" }
                val digest = MessageDigest.getInstance("SHA-256")
                model.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(hash == "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
                report.put("sha256", hash)
                val corpus = if (requestedCase == "baseline-labels") {
                    val source = File(getExternalFilesDir(null), "baseline-label-inputs.json")
                    require(source.length() in 1..1024L * 1024)
                    JSONObject(source.readText())
                } else JSONObject(assets.open("cases.json").bufferedReader().use { it.readText() })
                val cases = corpus.getJSONArray("cases")
                val selected = (0 until cases.length()).map { cases.getJSONObject(it) }.filter {
                    if (requestedCase == "baseline-labels") true
                    else if (requestedCase in setOf("development", "heldout")) it.getString("split") == requestedCase
                    else it.getString("id") == requestedCase
                }
                require(selected.isNotEmpty()) { "Unknown case or empty partition" }
                val prompts = selected.map { case ->
                    val evidence = JSONArray()
                    val passages = case.getJSONArray("passages")
                    for (i in 0 until passages.length()) {
                        val p = passages.getJSONObject(i)
                        if (p.getBoolean("eligible")) evidence.put(JSONObject().put("id", p.getString("id"))
                            .put("text", p.getString("body")))
                    }
                    case.getString("id") to assets.open(if (promptVersion == 5) "prompt-v5-label-only.txt" else "prompt-v$promptVersion.txt").bufferedReader().use {
                        it.readText().replace("{{EVIDENCE}}", evidence.toString())
                    }
                }
                report.put("prompts", JSONArray().apply {
                    prompts.forEach { (id, prompt) -> put(JSONObject().put("case_id", id).put("prompt", prompt)) }
                })
                val runs = JSONArray()
                report.put("runs", runs)
                val responseFormat = ResponseFormat.json("""
                    {"type":"object","additionalProperties":false,"required":["topics"],
                    "properties":{"topics":{"type":"array","maxItems":8,"items":{
                    "type":"object","additionalProperties":false,"required":["label","passage_ids"],
                    "properties":{"label":{"type":"string","minLength":1,"maxLength":96},
                    "passage_ids":{"type":"array","minItems":1,"maxItems":16,
                    "items":{"type":"string"}}}}}}}
                """.trimIndent())
                save()
                val live = AtomicBoolean(true)
                val generationStage = AtomicBoolean(false)
                val peakPss = AtomicLong(0)
                val peakAllocated = AtomicLong(0)
                val peakGenerationAllocated = AtomicLong(0)
                fun allocatedBytes(): Long = Runtime.getRuntime().let {
                    it.totalMemory() - it.freeMemory() + Debug.getNativeHeapAllocatedSize()
                }
                val sampler = Thread {
                    while (live.get()) {
                        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                        peakPss.updateAndGet { maxOf(it, info.totalPss.toLong()) }
                        peakAllocated.updateAndGet { maxOf(it, allocatedBytes()) }
                        if (generationStage.get()) peakGenerationAllocated.updateAndGet {
                            maxOf(it, allocatedBytes())
                        }
                        Thread.sleep(50)
                    }
                }.apply { start() }
                try {
                    val start = SystemClock.elapsedRealtime()
                    Engine(EngineConfig(modelPath = model.absolutePath,
                        backend = if (backendName == "GPU") Backend.GPU() else Backend.CPU(threadCount = cpuThreads),
                        maxNumTokens = contextTokens, cacheDir = cacheDir.absolutePath)).use { engine ->
                        engine.initialize()
                        report.put("loadMillis", SystemClock.elapsedRealtime() - start)
                        val idle = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                        report.put("modelLoadedPssKiB", idle.totalPss)
                        report.put("modelLoadedAllocatedBytes", allocatedBytes())
                        report.put("modelLoadedMemory", ProbeMemory.snapshot(this))
                        peakGenerationAllocated.set(allocatedBytes())
                        generationStage.set(true)
                        save()
                        for ((caseId, prompt) in prompts) {
                          repeat(if (prompts.size == 1) 2 else 1) { iteration ->
                            engine.createConversation(ConversationConfig(
                                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                                automaticToolCalling = false, enableResponseFormat = true)).use { conversation ->
                                val started = SystemClock.elapsedRealtime()
                                var firstToken: Long? = null
                                val response = StringBuilder()
                                val cancellationRequested = AtomicLong(0)
                                var cancellationError: String? = null
                                var nativeCancelled = false
                                var nativeTerminalAt = 0L
                                val timer = Executors.newSingleThreadScheduledExecutor()
                                val pending = if (cancelAfterMillis > 0) timer.schedule({
                                    cancellationRequested.set(SystemClock.elapsedRealtime())
                                    try { conversation.cancelProcess() }
                                    catch (failure: Exception) { cancellationError = failure.javaClass.name }
                                }, cancelAfterMillis.toLong(), TimeUnit.MILLISECONDS) else null
                                try {
                                runBlocking {
                                  try {
                                    conversation.sendMessageAsync(prompt, maxOutputToken = outputTokens,
                                        thinkingConfig = ThinkingConfig(enableThinking = false),
                                        responseFormat = responseFormat).collect { chunk ->
                                        val value = chunk.toString()
                                        if (value.isNotEmpty() && firstToken == null)
                                            firstToken = SystemClock.elapsedRealtime() - started
                                        response.append(value)
                                        check(response.length <= 32768) { "Output byte/character safety bound" }
                                    }
                                  } catch (failure: CancellationException) {
                                    if (cancellationRequested.get() == 0L) throw failure
                                    nativeCancelled = true
                                  }
                                }
                                } finally {
                                    nativeTerminalAt = SystemClock.elapsedRealtime()
                                    pending?.cancel(false)
                                    timer.shutdown()
                                    check(timer.awaitTermination(5, TimeUnit.SECONDS)) { "Native cancellation call still running" }
                                }
                                val row = JSONObject().put("iteration", iteration).put("case_id", caseId)
                                    .put("firstTokenMillis", firstToken)
                                    .put("generationMillis", SystemClock.elapsedRealtime() - started)
                                    .put("rawOutput", response.toString())
                                    .put("memoryAfterGeneration", ProbeMemory.snapshot(this))
                                    .put("nativeCancellationException", nativeCancelled)
                                    .put("nativeCancellationError", cancellationError)
                                if (cancellationRequested.get() > 0L) row.put("cancelToTerminalMillis",
                                    nativeTerminalAt - cancellationRequested.get())
                                row.put("cancellationTimingExcludesDiagnostics", true)
                                runs.put(row)
                                if (prompts.size == 1) report.put("run$iteration", row)
                                save()
                                if (cancelAfterMillis == 0) JSONObject(response.toString()).getJSONArray("topics")
                                else check(cancellationRequested.get() > 0 && cancellationError == null && nativeCancelled) {
                                    "Native cancellation was not observed; completion alone is not proof"
                                }
                            }
                          }
                        }
                    }
                    report.put("status", "completed")
                    report.put("afterEngineClose", ProbeMemory.snapshot(this))
                } finally {
                    live.set(false)
                    sampler.join(1000)
                    report.put("sampledPeakPssKiB", peakPss.get())
                    report.put("sampledPeakAllocatedBytes", peakAllocated.get())
                    report.put("sampledPeakGenerationAllocatedBytes", peakGenerationAllocated.get())
                    report.put("managedHeapLimitBytes", Runtime.getRuntime().maxMemory())
                }
            } catch (failure: Throwable) {
                report.put("status", "failed").put("error", failure.javaClass.name)
                    .put("message", failure.message)
            }
            save()
            runOnUiThread { text.text = "Probe ${report.getString("status")}; public report saved." }
        }.start()
    }
}
