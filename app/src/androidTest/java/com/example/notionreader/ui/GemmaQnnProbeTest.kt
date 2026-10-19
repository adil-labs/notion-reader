package com.example.notionreader.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtHardwareDevice.OrtHardwareDeviceType
import ai.onnxruntime.OrtSession
import android.os.Build
import android.os.SystemClock
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.data.discovery.GemmaTokenizer
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Explicit opt-in Qualcomm research pack; all models/libraries live in isolated cache. */
class GemmaQnnProbeTest {
    @Test fun qnnRegistrationAndGemmaExecutionExposeActualNpuAndGpuCoverage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val libraries = File(context.cacheDir, "gemma-qnn-libs")
        val output = File(context.getExternalFilesDir(null), "gemma-qnn-probe").apply { mkdirs() }
        val results = mutableListOf<JsonObject>()
        val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        var devices = ""
        fun save() = File(output, "gemma-qnn-probe.json").writeText(buildJsonObject {
            put("device", Build.MODEL); put("api", Build.VERSION.SDK_INT)
            put("plugin", "com.qualcomm.qti:onnxruntime-android-qnn:2.7.0")
            put("qnnRuntime", "2.51.0"); put("runtime", env.version)
            put("devices", devices); put("attempts", JsonArray(results))
        }.toString())
        try {
            Os.setenv("ADSP_LIBRARY_PATH", "${libraries.absolutePath};/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp", true)
            // Android linker resolves dependencies by SONAME once these verified fixtures are loaded.
            for (name in listOf("libQnnSystem.so", "libQnnHtpV79Stub.so", "libQnnHtpPrepare.so", "libQnnHtp.so", "libQnnGpu.so")) {
                System.load(File(libraries, name).absolutePath)
            }
            env.registerExecutionProviderLibrary("QNNExecutionProvider", File(libraries, "libonnxruntime_providers_qnn.so").absolutePath)
            devices = env.epDevices.joinToString("\n")
            save()
        } catch (error: Throwable) {
            results += buildJsonObject { put("stage", "registration"); put("failure", "${error.javaClass.simpleName}: ${error.message}") }
            save(); throw AssertionError("QNN registration failed; see aggregate report", error)
        }
        val references = Json.parseToJsonElement(instrumentation.context.assets.open("gemma2-android-reference.json")
            .bufferedReader().use { it.readText() }).jsonObject.getValue("embeddingReferences").jsonArray
        val input = references[0].jsonObject
        val tokenizerFile = File(context.noBackupFilesDir, "discovery-model/current/tokenizer.json")
        val cpuVectors = mutableMapOf<String, List<Float>>()
        GemmaTokenizer(tokenizerFile).use { tokenizer ->
            val ids = tokenizer.inputIds(input.getValue("input").jsonPrimitive.content)
            assertEquals(220, ids.size)
            for (precision in listOf("int8", "q4")) for (backend in listOf("cpu", "htp", "htp_fp32", "gpu")) {
                android.util.Log.i("GemmaQnnProbe", "Starting $precision $backend fixed-shape probe")
                val npu = backend.startsWith("htp")
                val cpu = backend == "cpu"
                val type = if (npu) OrtHardwareDeviceType.NPU else OrtHardwareDeviceType.GPU
                val enumerated = env.epDevices.filter { it.epName == "QNNExecutionProvider" && it.device.type == type }
                // QNN synthesizes an Android NPU device but may not enumerate the GPU. Its factory
                // explicitly honors backend_path ahead of the default derived from device type.
                val selected = enumerated.ifEmpty { if (backend == "gpu") env.epDevices.filter {
                    it.epName == "QNNExecutionProvider" && it.device.type == OrtHardwareDeviceType.NPU
                } else emptyList() }
                val graph = File(context.cacheDir, "gemma-benchmark-$precision-fixed/model-fixed.onnx")
                var profile: String? = null
                var failure: String? = null
                var milliseconds: Long? = null
                var cosine: Double? = null
                var rawNorm: Double? = null
                var samePrecisionCosine: Double? = null
                var hiddenMaximum: Double? = null
                var hiddenNonFinite: Int? = null
                var timedCreation: Long? = null
                val warm = mutableListOf<Double>()
                val warmCosines = mutableListOf<Double>()
                val thermal = mutableListOf<Int>()
                fun configuredOptions(profiled: Boolean): OrtSession.SessionOptions {
                    val options = OrtSession.SessionOptions()
                    try {
                        options.setIntraOpNumThreads(2)
                        if (!cpu) {
                            val backendOptions = mutableMapOf("backend_path" to File(libraries,
                                if (npu) "libQnnHtp.so" else "libQnnGpu.so").absolutePath)
                            if (backend == "htp_fp32") backendOptions["enable_htp_fp16_precision"] = "0"
                            options.addExecutionProvider(selected, backendOptions)
                        }
                        if (profiled) options.enableProfiling(File(output, "$precision-$backend").absolutePath)
                        return options
                    } catch (error: Throwable) { options.close(); throw error }
                }
                fun tensors(session: OrtSession) = session.inputNames.associateWith { name -> when (name) {
                    "input_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
                    "attention_mask" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), longArrayOf(1, ids.size.toLong()))
                    else -> error("Unexpected text-only fixed input $name")
                } }
                try {
                    check(cpu || selected.isNotEmpty()) { "No QNN $type device exposed" }
                    check(graph.isFile) { "Stage fixed-shape public model first" }
                    configuredOptions(true).use { options ->
                        env.createSession(graph.absolutePath, options).use { session ->
                            val shape = longArrayOf(1, ids.size.toLong())
                            val tensors = session.inputNames.associateWith { name -> when (name) {
                                "input_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                                "attention_mask" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
                                "image_features", "video_features", "audio_features" -> OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, 512))
                                else -> error("Unexpected fixed input $name")
                            } }
                            try {
                                val start = SystemClock.elapsedRealtime()
                                session.run(tensors, setOf("sentence_embedding", "last_hidden_state")).use { result ->
                                    @Suppress("UNCHECKED_CAST") val hidden = result.get("last_hidden_state").get().value as Array<Array<FloatArray>>
                                    hiddenMaximum = hidden.asSequence().flatMap { it.asSequence() }.flatMap { it.asSequence() }
                                        .filter { it.isFinite() }.maxOfOrNull { kotlin.math.abs(it.toDouble()) }
                                    hiddenNonFinite = hidden.sumOf { batch -> batch.sumOf { row -> row.count { !it.isFinite() } } }
                                    @Suppress("UNCHECKED_CAST") val vector = (result.get("sentence_embedding").get().value as Array<FloatArray>).single()
                                    assertEquals(768, vector.size); assertTrue(vector.all { it.isFinite() })
                                    rawNorm = sqrt(vector.sumOf { it.toDouble() * it })
                                    require(rawNorm!!.isFinite() && rawNorm!! > 0) { "Backend returned a zero or invalid embedding norm: $rawNorm" }
                                    val expected = input.getValue("vector").jsonArray.map { it.jsonPrimitive.double }
                                    cosine = vector.indices.sumOf { vector[it] * expected[it] } /
                                        (rawNorm!! * sqrt(expected.sumOf { it * it }))
                                    require(cosine!!.isFinite() && cosine!! >= .95) { "Backend vector disagrees with host: $cosine" }
                                    if (cpu) cpuVectors[precision] = vector.toList()
                                    val baseline = cpuVectors.getValue(precision)
                                    samePrecisionCosine = vector.indices.sumOf { vector[it].toDouble() * baseline[it] } /
                                        (rawNorm!! * sqrt(baseline.sumOf { it.toDouble() * it }))
                                    require(samePrecisionCosine!!.isFinite() && samePrecisionCosine!! >= .9995) {
                                        "Backend disagrees with same-precision CPU: $samePrecisionCosine"
                                    }
                                }
                                milliseconds = SystemClock.elapsedRealtime() - start
                                profile = session.endProfiling()
                            } finally { tensors.values.forEach { it.close() } }
                        }
                    }
                    val createStart = SystemClock.elapsedRealtime()
                    configuredOptions(false).use { options -> env.createSession(graph.absolutePath, options).use { session ->
                        timedCreation = SystemClock.elapsedRealtime() - createStart
                        val inputs = tensors(session)
                        try {
                            session.run(inputs, setOf("sentence_embedding")).use { } // Untimed warmup.
                            repeat(3) {
                                thermal += if (Build.VERSION.SDK_INT >= 29)
                                    context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus else -1
                                val start = SystemClock.elapsedRealtimeNanos()
                                session.run(inputs, setOf("sentence_embedding")).use { result ->
                                    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                                    @Suppress("UNCHECKED_CAST") val values = (result[0].value as Array<FloatArray>).single()
                                    require(values.size == 768 && values.all { it.isFinite() } && values.any { it != 0f }) {
                                        "Warm backend returned an invalid embedding"
                                    }
                                    val baseline = cpuVectors.getValue(precision)
                                    val agreement = values.indices.sumOf { values[it].toDouble() * baseline[it] } /
                                        sqrt(values.sumOf { it.toDouble() * it } * baseline.sumOf { it.toDouble() * it })
                                    require(agreement.isFinite() && agreement >= .9995) { "Timed backend disagrees with same-precision CPU: $agreement" }
                                    warm += elapsed; warmCosines += agreement
                                }
                            }
                        } finally { inputs.values.forEach { it.close() } }
                    } }
                } catch (error: Exception) { failure = "${error.javaClass.simpleName}: ${error.message}".take(6000) }
                results += buildJsonObject {
                    put("precision", precision); put("backend", backend); put("exposedDevices", selected.size)
                    put("matchingHardwareDevices", enumerated.size)
                    put("backendLibrary", if (cpu) "built-in CPU" else if (npu) "libQnnHtp.so" else "libQnnGpu.so")
                    put("cpuFallbackAllowed", true)
                    put("fixedTokens", ids.size); failure?.let { put("failure", it) }
                    profile?.let { put("profileFile", it) }; milliseconds?.let { put("firstRunMilliseconds", it) }
                    cosine?.let { put("cosineWithHostInt8", it) }
                    rawNorm?.let { put("rawL2Norm", it) }
                    samePrecisionCosine?.let { put("cosineWithSamePrecisionCpu", it) }
                    hiddenMaximum?.let { put("hiddenMaximumAbsoluteValue", it) }
                    hiddenNonFinite?.let { put("hiddenNonFiniteValues", it) }
                    timedCreation?.let { put("timedSessionCreationMilliseconds", it) }
                    put("warmMilliseconds", JsonArray(warm.map { JsonPrimitive(it) }))
                    put("warmCosinesWithSamePrecisionCpu", JsonArray(warmCosines.map { JsonPrimitive(it) }))
                    put("warmThermalStatuses", JsonArray(thermal.map { JsonPrimitive(it) }))
                    put("warmTimingScope", "Unprofiled session.run only, one warmup then three repeats; tokenization/tensor creation excluded")
                }
                save()
            }
        }
        assertTrue("QNN must expose the actual S25 NPU", env.epDevices.any {
            it.epName == "QNNExecutionProvider" && it.device.type == OrtHardwareDeviceType.NPU
        })
        for (precision in listOf("int8", "q4")) for (backend in listOf("cpu", "htp"))
            assertTrue("No complete $precision $backend execution; inspect the per-attempt failures", results.any {
                it["precision"]?.jsonPrimitive?.content == precision && it["backend"]?.jsonPrimitive?.content == backend &&
                    "failure" !in it && it["warmMilliseconds"]?.jsonArray?.size == 3 && "profileFile" in it
            })
    }
}
