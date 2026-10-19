package com.example.notionreader.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

import com.example.notionreader.data.discovery.GemmaTokenizer
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.EnumSet
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Public fixtures only. Never installs a production model, changes settings or edits Notes. */
class GemmaBackendBenchmarkTest {
    @Test fun quantizationsAndBackendsReportMeasuredExecutionAndFallback() {
        runBenchmark(listOf("CPU", "XNNPACK", "NNAPI", "QNN_HTP", "QNN_GPU", "WEBGPU"), "gemma-backend-benchmark")
    }

    fun runBenchmark(modes: List<String>, outputDirectory: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null), outputDirectory).apply { mkdirs() }
        val reference = Json.parseToJsonElement(instrumentation.context.assets.open("gemma2-android-reference.json")
            .bufferedReader().use { it.readText() }).jsonObject
        val references = reference.getValue("embeddingReferences").jsonArray.map { it.jsonObject }

        val int8 = File(context.cacheDir, "gemma-test-model")
        val q4 = File(context.cacheDir, "gemma-benchmark-q4")
        fun verify(file: File, sha: String) {
            assertTrue("Stage pinned fixture ${file.name}", file.isFile)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            assertEquals(sha, digest.digest().joinToString("") { "%02x".format(it) })
        }
        for (artifact in GemmaInt8BenchmarkFixture.artifacts) verify(File(int8, artifact.path), artifact.sha256)
        verify(File(q4, "model_q4.onnx"), "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9")
        verify(File(q4, "model_q4.onnx_data"), "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49")
        verify(File(q4, "tokenizer.json"), reference.getValue("tokenizerSha256").jsonPrimitive.content)
        val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        val power = context.getSystemService(PowerManager::class.java)
        fun thermal() = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
        val results = mutableListOf<JsonObject>()
        fun save() {
            File(output, "gemma-benchmark.json").writeText(buildJsonObject {
                put("device", Build.MODEL); put("api", Build.VERSION.SDK_INT)
                put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE)
                put("runtime", "ONNX Runtime Android 1.31.0")
                put("availableProviders", OrtEnvironment.getAvailableProviders().joinToString())
                put("scope", "Two reversed-order rounds; two warm repeats after one warmup per input. Session-cold, not filesystem-cold. Memory sampled, not peak. No profiling in timed sessions. Other device/app work may interfere.")
                put("configurations", JsonArray(results))
            }.toString())
        }
        val configurations = modes.flatMap { mode -> listOf("int8" to mode, "q4" to mode) }
        GemmaTokenizer(File(int8, "tokenizer.json")).use { tokenizer ->
            val texts = listOf(
                "short" to references[1].getValue("input").jsonPrimitive.content,
                "medium" to references[0].getValue("input").jsonPrimitive.content,
                "long" to List(4) { references[2].getValue("input").jsonPrimitive.content }.joinToString("\n")
            )
            val inputs = texts.map { (label, text) -> label to tokenizer.inputIds(text).also { assertTrue(it.size in 1..8192) } }
            val int8Vectors = mutableMapOf<String, List<Float>>()
            for (round in 0..1) for ((precision, mode) in if (round == 0) configurations else configurations.reversed()) {
                android.util.Log.i("GemmaBenchmark", "Starting $precision $mode round $round")
                val graph = if (precision == "int8") File(int8, GemmaInt8BenchmarkFixture.graph) else File(q4, "model_q4.onnx")
                val requested = when (mode) {
                    "CPU" -> "CPUExecutionProvider"
                    "XNNPACK", "XNNPACK_CPU2" -> "XnnpackExecutionProvider"
                    "NNAPI" -> "NnapiExecutionProvider"
                    "WEBGPU" -> "WebGpuExecutionProvider"
                    else -> "QNNExecutionProvider"
                }
                fun options(profile: Boolean): OrtSession.SessionOptions {
                    val result = OrtSession.SessionOptions()
                    try { return result.apply {
                    setIntraOpNumThreads(2)
                    when (mode) {
                        "XNNPACK" -> { setIntraOpNumThreads(1); addXnnpack(mapOf("intra_op_num_threads" to "2")) }
                        "XNNPACK_CPU2" -> addXnnpack(mapOf("intra_op_num_threads" to "2"))
                        "NNAPI" -> addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                        "QNN_HTP" -> addQnn(mapOf("backend_path" to "libQnnHtp.so"))
                        "QNN_GPU" -> addQnn(mapOf("backend_path" to "libQnnGpu.so"))
                        "WEBGPU" -> addWebGPU(emptyMap())
                    }
                    if (profile) enableProfiling(File(output, "$precision-$mode").absolutePath)
                    } } catch (error: Throwable) { result.close(); throw error }
                }
                fun run(session: OrtSession, ids: LongArray): List<Float> {
                    val shape = longArrayOf(1, ids.size.toLong())
                    val tensors = session.inputNames.associateWith { name ->
                        when (name) {
                            "input_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                            "attention_mask" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
                            "position_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { it.toLong() }), shape)
                            "image_features", "video_features", "audio_features" -> OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, 512))
                            else -> error("Unexpected input $name")
                        }
                    }
                    try {
                        session.run(tensors, setOf("sentence_embedding")).use { result ->
                            @Suppress("UNCHECKED_CAST") val vector = (result[0].value as Array<FloatArray>).single()
                            assertEquals(768, vector.size); assertTrue(vector.all { it.isFinite() })
                            val norm = sqrt(vector.sumOf { it.toDouble() * it })
                            assertTrue(norm > 0); return vector.map { (it / norm).toFloat() }
                        }
                    } finally { tensors.values.forEach { it.close() } }
                }
                val samples = mutableListOf<JsonObject>()
                var coldMs: Long? = null
                var profileFile: String? = null
                var failure: String? = null
                val startingThermal = thermal()
                try {
                    val start = SystemClock.elapsedRealtime()
                    options(false).use { opts -> env.createSession(graph.absolutePath, opts).use { session ->
                        coldMs = SystemClock.elapsedRealtime() - start
                        for ((label, ids) in inputs) {
                            run(session, ids) // Explicit untimed warmup for this shape.
                            repeat(2) { repeat ->
                                val before = thermal()
                                val begin = SystemClock.elapsedRealtimeNanos()
                                val vector = run(session, ids)
                                val elapsed = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
                                if (precision == "int8" && mode == "CPU") int8Vectors[label] = vector
                                val baseline = int8Vectors[label]
                                samples += buildJsonObject {
                                    put("input", label); put("tokens", ids.size); put("repeat", repeat)
                                    put("milliseconds", elapsed); put("thermalBefore", before); put("thermalAfter", thermal())
                                    put("processPssKiB", Debug.getPss()); put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                                    put("javaAllocatedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                                    if (baseline != null) put("cosineWithInt8Cpu", vector.indices.sumOf { vector[it].toDouble() * baseline[it] })
                                    if (repeat == 0) put("vector", JsonArray(vector.map { JsonPrimitive(it) }))
                                }
                            }
                        }
                    } }
                    if (round == 0) options(true).use { opts -> env.createSession(graph.absolutePath, opts).use { session ->
                        run(session, inputs[1].second)
                        profileFile = session.endProfiling()
                    } }
                } catch (error: Exception) {
                    failure = "${error.javaClass.simpleName}: ${error.message}".take(3000)
                }
                results += buildJsonObject {
                    put("precision", precision); put("mode", mode); put("requestedProvider", requested); put("round", round)
                    put("ortIntraThreads", if (mode == "XNNPACK") 1 else 2)
                    put("xnnpackThreads", if (mode.startsWith("XNNPACK")) 2 else 0)
                    put("thermalAtStart", startingThermal); coldMs?.let { put("sessionCreationMilliseconds", it) }
                    put("samples", JsonArray(samples)); failure?.let { put("failure", it) }
                    profileFile?.let { put("profileFile", it) }
                }
                save()
                android.util.Log.i("GemmaBenchmark", "Finished $precision $mode round $round: ${failure ?: "measured"}")
                if (mode == "CPU") assertNull("CPU baseline must run: $failure", failure)
            }
        }
        assertEquals(modes.size * 4, results.size)
    }
}
