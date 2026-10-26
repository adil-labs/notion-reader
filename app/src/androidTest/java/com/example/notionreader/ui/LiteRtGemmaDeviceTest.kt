package com.example.notionreader.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.data.discovery.GemmaEmbeddingProvider
import com.example.notionreader.data.discovery.GemmaModelPack
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Public inputs only; exercises the shipping provider and the published, checksum-pinned bundle. */
class LiteRtGemmaDeviceTest {
    @Test fun backendPreferencesExecuteOrExplainFallbackAndAutomaticMeasuresThisDevice() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = File(context.noBackupFilesDir, "discovery-model/current")
        val directory = if (File(installed, GemmaModelPack.bundleFile).isFile) installed
            else File(context.cacheDir, "litert-gemma-test")
        val npu = File(context.cacheDir, "litert-gemma-test/${GemmaModelPack.npuBundleFile}")
        val npuAvailable = android.os.Build.VERSION.SDK_INT >= 31 && android.os.Build.SOC_MODEL == "SM8750" && npu.isFile
        if (npuAvailable) {
            assertEquals(GemmaModelPack.npuArtifacts.single().bytes, npu.length())
            val hash = MessageDigest.getInstance("SHA-256")
            npu.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            assertEquals(GemmaModelPack.npuArtifacts.single().sha256, hash.digest().joinToString("") { "%02x".format(it) })
        }
        val provider = GemmaEmbeddingProvider(directory, npuBundle = { npu.takeIf { npuAvailable } },
            nativeLibraryDirectory = context.applicationInfo.nativeLibraryDir)
        val rows = mutableListOf<JsonElement>()
        var reference: List<Float>? = null
        try {
            for (preference in listOf(
                com.example.notionreader.domain.settings.DiscoveryBackend.CPU,
                com.example.notionreader.domain.settings.DiscoveryBackend.GPU,
                com.example.notionreader.domain.settings.DiscoveryBackend.NPU,
                com.example.notionreader.domain.settings.DiscoveryBackend.AUTOMATIC
            )) {
                provider.selectBackend(preference)
                val started = android.os.SystemClock.elapsedRealtime()
                val vector = provider.embed("PostgreSQL transactions isolate concurrent updates using snapshots.")
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val status = provider.runtimeState.value
                assertEquals(preference, status.requested)
                assertNotNull(status.effective)
                if (preference != com.example.notionreader.domain.settings.DiscoveryBackend.AUTOMATIC && status.effective != preference)
                    assertNotNull("Fallback must be explained", status.reason)
                reference?.let { cpu -> assertTrue(cpu.indices.sumOf { cpu[it].toDouble() * vector[it] } >= .999) }
                if (reference == null) reference = vector
                rows += buildJsonObject {
                    put("requested", preference.name); put("effective", status.effective!!.name)
                    put("reason", status.reason)
                    put("selectionAndFirstInputMillis", elapsed)
                    val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                    put("sampledProcessPssKiB", memory.totalPss)
                    put("medianPublicProbePairNanos", buildJsonObject {
                        status.benchmarkNanos.forEach { (backend, nanos) -> put(backend.name, nanos) }
                    })
                }
            }
            val longText = " token".repeat(600)
            val beforeLongInput = provider.runtimeState.value.effective
            assertTrue(provider.tokenizer.inputIds(longText).size > 512)
            provider.embed(longText)
            assertEquals(com.example.notionreader.domain.settings.DiscoveryBackend.CPU, provider.runtimeState.value.effective)
            if (beforeLongInput != com.example.notionreader.domain.settings.DiscoveryBackend.CPU)
                assertTrue(provider.runtimeState.value.reason!!.contains("context"))
            provider.release()
            val diagnostics = mutableListOf<JsonElement>()
            if (npuAvailable) {
                for (backend in listOf("CPU", "NPU")) for (length in listOf(128, 512)) {
                    val engine = com.google.ai.edge.litertlm.EmbeddingEngine(com.google.ai.edge.litertlm.EmbeddingEngineConfig(
                        modelPath = if (backend == "CPU") File(directory, GemmaModelPack.bundleFile).absolutePath else npu.absolutePath,
                        backend = if (backend == "CPU") com.google.ai.edge.litertlm.Backend.CPU(threadCount = 2)
                            else com.google.ai.edge.litertlm.Backend.NPU(context.applicationInfo.nativeLibraryDir),
                        maxInputLength = length))
                    try {
                        engine.initialize()
                        fun raw() = engine.computeEmbedding(listOf(com.google.ai.edge.litertlm.InputData.Text(
                            GemmaModelPack.instructedText("PostgreSQL transactions isolate concurrent updates using snapshots."))),
                            com.google.ai.edge.litertlm.EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = 768)).embedding
                        val first = raw(); val second = raw()
                        val cpu = checkNotNull(reference)
                        val agreement = first.indices.sumOf { first[it].toDouble() * cpu[it] }
                        val repeatability = first.indices.sumOf { first[it].toDouble() * second[it] }
                        if (backend == "CPU") assertTrue(agreement >= .999)
                        diagnostics += buildJsonObject {
                            put("backend", backend); put("maximumTokens", length)
                            put("cpuCosine", agreement); put("repeatCosine", repeatability)
                        }
                    } finally { engine.close() }
                }
            }
            val output = buildJsonObject {
                put("device", android.os.Build.MODEL); put("soc", android.os.Build.SOC_MODEL)
                put("runtime", "LiteRT-LM 0.18.1"); put("results", JsonArray(rows))
                put("longInputUsesCpu", true); put("npuDiagnostics", JsonArray(diagnostics))
            }
            File(context.getExternalFilesDir(null), "litert-backends.json").writeText(output.toString())
            android.util.Log.i("LiteRtBackends", output.toString())
        } finally { provider.release() }
    }

    @Test fun publishedLiteRtModelProducesNormalizedDiscriminatingRepeatableEmbeddings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val installed = File(context.noBackupFilesDir, "discovery-model/current")
        val directory = if (File(installed, GemmaModelPack.bundleFile).isFile) installed
            else File(context.cacheDir, "litert-gemma-test")
        for (artifact in GemmaModelPack.artifacts) {
            val file = File(directory, artifact.path)
            assertEquals("Stage the pinned published model first", artifact.bytes, file.length())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            assertEquals(artifact.sha256, digest.digest().joinToString("") { "%02x".format(it) })
        }
        val provider = GemmaEmbeddingProvider(directory)
        val timings = mutableListOf<Long>()
        suspend fun embed(text: String): List<Float> {
            val start = android.os.SystemClock.elapsedRealtime()
            val result = provider.embed(text)
            timings += android.os.SystemClock.elapsedRealtime() - start
            assertEquals(768, result.size)
            assertTrue(result.all { it.isFinite() })
            assertEquals(1.0, result.sumOf { it.toDouble() * it }, .00001)
            return result
        }
        fun cosine(a: List<Float>, b: List<Float>) = a.indices.sumOf { a[it].toDouble() * b[it] }
        try {
            val reference = Json.parseToJsonElement(instrumentation.context.assets.open("gemma2-q4-android-reference.json")
                .bufferedReader().use { it.readText() }).jsonObject
            val tokenizerReference = Json.parseToJsonElement(instrumentation.context.assets.open("litert-tokenizer-reference.json")
                .bufferedReader().use { it.readText() }).jsonObject
            assertEquals(GemmaModelPack.tokenizerSha256, tokenizerReference.getValue("sentencePieceSha256").jsonPrimitive.content)
            // Independent Python SentencePiece evidence from this exact published bundle.
            for (element in tokenizerReference.getValue("cases").jsonArray) {
                val case = element.jsonObject
                val tokens = provider.tokenizer.tokens(case.getValue("text").jsonPrimitive.content)
                assertEquals(case.getValue("ids").jsonArray.map { it.jsonPrimitive.int }, tokens.map { it.id })
                assertEquals(case.getValue("utf16Offsets").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } },
                    tokens.map { listOf(it.start, it.end) })
                assertEquals(case.getValue("instructedIds").jsonArray.map { it.jsonPrimitive.long },
                    provider.tokenizer.inputIds(case.getValue("text").jsonPrimitive.content).toList())
            }
            for (element in tokenizerReference.getValue("boundaries").jsonArray) {
                val boundary = element.jsonObject
                assertEquals(boundary.getValue("tokens").jsonPrimitive.int,
                    provider.tokenizer.inputIds(" token".repeat(boundary.getValue("repeats").jsonPrimitive.int)).size)
            }
            val anchor = "PostgreSQL transaction isolation uses snapshots to control concurrent database updates."
            val related = "Database transactions in PostgreSQL use snapshot isolation to manage concurrent writes."
            val unrelated = "Riverbank trees shade aquatic habitats and their roots prevent soil erosion."
            val a = embed(anchor)
            val b = embed(related)
            val c = embed(unrelated)
            val repeat = embed(anchor)
            embed("Literal tokens <bos><eos><unk><mask>[multimodal] in documentation 🐱 中文")
            assertTrue("Identical input must be repeatable", cosine(a, repeat) >= .99999)
            val relatedCosine = cosine(a, b)
            val unrelatedCosine = cosine(a, c)
            assertTrue("Related public inputs must rank above an unrelated subject", relatedCosine > unrelatedCosine + .05)
            val oldComparisons = reference.getValue("embeddingReferences").jsonArray.map { element ->
                val case = element.jsonObject
                val actual = embed(case.getValue("input").jsonPrimitive.content)
                val expected = case.getValue("vector").jsonArray.map { it.jsonPrimitive.float }
                cosine(actual, expected)
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { provider.embed(" token".repeat(8185)) } }
            provider.release()
            assertTrue("Release must permit safe lazy reopening", cosine(a, embed(anchor)) >= .99999)
            val report = buildJsonObject {
                put("model", provider.identity.model); put("runtime", "LiteRT-LM 0.18.1 CPU")
                put("device", android.os.Build.MODEL); put("api", android.os.Build.VERSION.SDK_INT)
                put("relatedCosine", relatedCosine); put("unrelatedCosine", unrelatedCosine)
                put("oldOnnxReferenceCosines", JsonArray(oldComparisons.map { JsonPrimitive(it) }))
                put("milliseconds", JsonArray(timings.map { JsonPrimitive(it) }))
                put("processPssKiB", android.os.Debug.getPss())
                put("limits", "Public smoke inputs and post-inference memory, not representative quality or peak memory")
            }
            File(context.getExternalFilesDir(null), "litert-gemma-device.json").writeText(report.toString())
        } finally { provider.release() }
    }
}
