package com.example.notionreader.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.data.discovery.GemmaTokenizer
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Diagnostic compatibility probe. A collected failure is never a speed/acceleration result. */
class GemmaVulkanProbeTest {
    @Test fun vulkanReadbackAndGemmaCompatibilityAreMeasuredIndependently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        val directory = File(context.getExternalFilesDir(null), "gemma-vulkan-probe").apply { mkdirs() }
        val rows = mutableListOf<JsonObject>()
        val cpuVectors = mutableMapOf<String, List<Float>>()
        val installed = File(context.cacheDir, "gemma-test-model")
        val q4 = File(context.cacheDir, "gemma-benchmark-q4")
        fun verify(file: File, sha: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            assertEquals(file.name, sha, digest.digest().joinToString("") { "%02x".format(it) })
        }
        for (artifact in GemmaInt8BenchmarkFixture.artifacts) verify(File(installed, artifact.path), artifact.sha256)
        verify(File(q4, "model_q4.onnx"), "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9")
        verify(File(q4, "model_q4.onnx_data"), "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49")
        for ((precision, hash) in listOf("int8" to "1ccb2065c43b4e1361cb92024e6a97103d2f6088d0d03d75572067b76a299dc9",
            "q4" to "0047bad3eef4ad5938f221c3c98d0f5f1e54a7799ea4d3396989b7787c8238c8")) {
            val fixed = File(context.cacheDir, "gemma-benchmark-$precision-fixed")
            verify(File(fixed, "model-fixed.onnx"), hash)
            verify(File(fixed, if (precision == "int8") "model_quantized.onnx_data" else "model_q4.onnx_data"),
                if (precision == "int8") "278a7ff1248c3618e4bd11a607fc54f7bdc7778854230f3956d3f86bd9db4f3b" else "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49")
        }
        fun save() = File(directory, "gemma-vulkan-probe.json").writeText(buildJsonObject {
            put("device", Build.MODEL); put("api", Build.VERSION.SDK_INT); put("runtime", env.version)
            put("route", "ONNX Runtime WebGPU / Dawn, explicitly selected Vulkan where requested")
            put("scope", "Compatibility probe; CPU controls mandatory. Failed rows are collected evidence, not acceleration. Three timed calls only after correct warmup; profiling separate.")
            put("configurations", JsonArray(rows))
        }.toString())
        val reference = Json.parseToJsonElement(instrumentation.context.assets.open("gemma2-android-reference.json")
            .bufferedReader().use { it.readText() }).jsonObject.getValue("embeddingReferences").jsonArray[0].jsonObject
        // ONNX opset17 MatMul: [1,2] @ [[1,2],[3,4]] must read back exactly [7,10].
        val smoke = Base64.decode("CAg6aAoRCgF4CgF3EgF5IgZNYXRNdWwSDHZ1bGthbi1zbW9rZSobCAIIAhABIhAAAIA/AAAAQAAAQEAAAIBAQgF3WhMKAXgSDgoMCAESCAoCCAEKAggCYhMKAXkSDgoMCAESCAoCCAEKAggCQgQKABAR", Base64.DEFAULT)
        GemmaTokenizer(File(installed, "tokenizer.json")).use { tokenizer ->
            val ids = tokenizer.inputIds(reference.getValue("input").jsonPrimitive.content)
            assertEquals(220, ids.size)
            val cases = listOf("smoke") + listOf("int8-original", "q4-original", "int8-fixed", "q4-fixed")
            for (case in cases) for (mode in if (case == "smoke") listOf("CPU", "VULKAN", "WEBGPU_DEFAULT") else listOf("CPU", "VULKAN")) {
                val cpu = mode == "CPU"
                val precision = case.substringBefore('-')
                val fixed = case.endsWith("fixed")
                val graph = when {
                    case == "smoke" -> null
                    fixed -> File(context.cacheDir, "gemma-benchmark-$precision-fixed/model-fixed.onnx")
                    precision == "int8" -> File(installed, GemmaInt8BenchmarkFixture.graph)
                    else -> File(q4, "model_q4.onnx")
                }
                fun options(profile: Boolean): OrtSession.SessionOptions {
                    val opts = OrtSession.SessionOptions()
                    try {
                        opts.setIntraOpNumThreads(2)
                        opts.setSessionLogLevel(if (profile) OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE else OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                        opts.setSessionLogVerbosityLevel(1)
                        if (!cpu) opts.addWebGPU(if (mode == "VULKAN") mapOf("dawnBackendType" to "Vulkan", "validationMode" to "basic") else emptyMap())
                        if (profile) opts.enableProfiling(File(directory, "$case-$mode").absolutePath)
                        return opts
                    } catch (error: Exception) { opts.close(); throw error }
                }
                fun create(opts: OrtSession.SessionOptions) = if (graph == null) env.createSession(smoke, opts) else env.createSession(graph.absolutePath, opts)
                fun inputs(session: OrtSession): Map<String, OnnxTensor> = session.inputNames.associateWith { name ->
                    when (name) {
                        "x" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f, 2f)), longArrayOf(1, 2))
                        "input_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
                        "attention_mask" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), longArrayOf(1, ids.size.toLong()))
                        "image_features", "audio_features", "video_features" -> OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, 512))
                        else -> error("Unexpected input $name")
                    }
                }
                fun values(result: OrtSession.Result): List<Float> {
                    @Suppress("UNCHECKED_CAST") val vector = (result[0].value as Array<FloatArray>).single().toList()
                    require(vector.size == if (case == "smoke") 2 else 768)
                    require(vector.all { it.isFinite() } && vector.any { it != 0f })
                    if (case == "smoke") require(vector == listOf(7f, 10f)) { "Incorrect matrix result $vector" }
                    return vector
                }
                fun agreement(vector: List<Float>): Double {
                    val baseline = cpuVectors.getValue(case)
                    val cosine = vector.indices.sumOf { vector[it].toDouble() * baseline[it] } /
                        sqrt(vector.sumOf { it.toDouble() * it } * baseline.sumOf { it.toDouble() * it })
                    require(cosine.isFinite() && cosine >= .9995) { "Same-precision CPU cosine $cosine" }
                    return cosine
                }
                var failure: String? = null
                var stage = "session creation"
                var creation: Long? = null
                var profile: String? = null
                val times = mutableListOf<Double>()
                val cosines = mutableListOf<Double>()
                android.util.Log.i("GemmaVulkanProbe", "Starting $case $mode")
                try {
                    options(false).use { opts ->
                        val start = SystemClock.elapsedRealtime()
                        create(opts).use { session ->
                            creation = SystemClock.elapsedRealtime() - start
                            val tensors = inputs(session)
                            val outputs = setOf(if (case == "smoke") "y" else "sentence_embedding")
                            try {
                                stage = "warmup readback"
                                session.run(tensors, outputs).use { result ->
                                    val vector = values(result)
                                    if (cpu) cpuVectors[case] = vector
                                    agreement(vector)
                                }
                                stage = "timed readback"
                                repeat(3) {
                                    val begin = SystemClock.elapsedRealtimeNanos()
                                    session.run(tensors, outputs).use { result ->
                                        val elapsed = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
                                        cosines += agreement(values(result)); times += elapsed
                                    }
                                }
                            } finally { tensors.values.forEach { it.close() } }
                        }
                    }
                    stage = "profile"
                    options(true).use { opts -> create(opts).use { session ->
                        val tensors = inputs(session)
                        try { session.run(tensors, setOf(if (case == "smoke") "y" else "sentence_embedding")).use { agreement(values(it)) } }
                        finally { tensors.values.forEach { it.close() } }
                        profile = session.endProfiling()
                    } }
                } catch (error: Exception) { failure = "${error.javaClass.simpleName}: ${error.message}".take(6000) }
                rows += buildJsonObject {
                    put("case", case); put("mode", mode); put("requestedProvider", if (cpu) "CPUExecutionProvider" else "WebGpuExecutionProvider")
                    put("tokens", if (case == "smoke") 0 else ids.size); put("stage", stage)
                    creation?.let { put("sessionCreationMilliseconds", it) }; profile?.let { put("profileFile", it) }
                    put("milliseconds", JsonArray(times.map { JsonPrimitive(it) })); put("cosinesWithSamePrecisionCpu", JsonArray(cosines.map { JsonPrimitive(it) }))
                    failure?.let { put("failure", it) }
                }
                save()
            }
        }
        assertEquals(11, rows.size)
        assertEquals("Every CPU control must work; diagnostic GPU failures are reported separately", 5,
            rows.count { it.getValue("mode").jsonPrimitive.content == "CPU" && "failure" !in it && it.getValue("milliseconds").jsonArray.size == 3 })
    }
}
