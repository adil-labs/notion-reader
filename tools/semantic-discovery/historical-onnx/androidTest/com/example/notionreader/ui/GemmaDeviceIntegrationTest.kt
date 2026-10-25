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
import kotlin.math.sqrt

/** Real device inference against public frozen references; never reads or replaces private Notes. */
class GemmaDeviceIntegrationTest {
    @Test fun int4GemmaMatchesReferenceTokensAndSentenceEmbeddingsOnDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val installed = File(context.noBackupFilesDir, "discovery-model/current")
        val directory = if (File(installed, GemmaModelPack.graph).isFile) installed
            else File(context.cacheDir, "gemma-benchmark-q4")
        for (artifact in GemmaModelPack.artifacts) {
            val file = File(directory, artifact.path)
            assertTrue("Stage the pinned Gemma test pack first: ${artifact.path}", file.isFile)
            assertEquals(artifact.bytes, file.length())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            assertEquals(artifact.sha256, digest.digest().joinToString("") { "%02x".format(it) })
        }
        val reference = Json.parseToJsonElement(instrumentation.context.assets.open("gemma2-q4-android-reference.json").bufferedReader().use { it.readText() }).jsonObject
        assertEquals(GemmaModelPack.artifacts[0].sha256, reference.getValue("graphSha256").jsonPrimitive.content)
        assertEquals(GemmaModelPack.artifacts[1].sha256, reference.getValue("weightsSha256").jsonPrimitive.content)
        val provider = GemmaEmbeddingProvider(directory)
        try {
            for (item in reference.getValue("cases").jsonArray) {
                val case = item.jsonObject
                val text = case.getValue("text").jsonPrimitive.content
                val tokens = provider.tokenizer.tokens(text)
                assertEquals(case.getValue("tokenIds").jsonArray.map { it.jsonPrimitive.int }, tokens.map { it.id })
                assertEquals(case.getValue("utf16Offsets").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } }, tokens.map { listOf(it.start, it.end) })
                assertEquals(case.getValue("instructedTokenIds").jsonArray.map { it.jsonPrimitive.long }, provider.tokenizer.inputIds(text).toList())
            }
            val measurements = mutableListOf<JsonObject>()
            for (item in reference.getValue("embeddingReferences").jsonArray) {
                val case = item.jsonObject
                val input = case.getValue("input").jsonPrimitive.content
                assertEquals(case.getValue("tokenIds").jsonArray.map { it.jsonPrimitive.long }, provider.tokenizer.inputIds(input).toList())
                val start = android.os.SystemClock.elapsedRealtime()
                val vector = provider.embed(input)
                val ms = android.os.SystemClock.elapsedRealtime() - start
                val expected = case.getValue("vector").jsonArray.map { it.jsonPrimitive.double }
                val cosine = vector.indices.sumOf { vector[it].toDouble() * expected[it] } /
                    sqrt(vector.sumOf { it.toDouble() * it } * expected.sumOf { it * it })
                assertEquals(768, vector.size)
                assertTrue("Android/host vector disagreement: $cosine", cosine >= .9995)
                measurements += buildJsonObject {
                    put("cosine", cosine); put("milliseconds", ms)
                    put("runtimeBackend", provider.runtimeBackend.name)
                    put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                    put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    put("processPssKiB", android.os.Debug.getPss())
                }
            }
            val overflow = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { provider.embed(" token".repeat(9000)) }
            }
            assertTrue(overflow.message!!.contains("8192"))
            val report = buildJsonObject {
                put("model", provider.identity.model); put("device", android.os.Build.MODEL)
                put("runtimeBackend", provider.runtimeBackend.name)
                put("api", android.os.Build.VERSION.SDK_INT); put("cases", 12)
                put("memoryScope", "Post-inference samples; not peak or full-scale acceptance")
                put("measurements", JsonArray(measurements))
            }.toString()
            android.util.Log.i("GemmaDeviceParity", report)
            File(context.getExternalFilesDir(null), "gemma-device-parity.json").writeText(report)
        } finally { provider.release() }
    }
}
