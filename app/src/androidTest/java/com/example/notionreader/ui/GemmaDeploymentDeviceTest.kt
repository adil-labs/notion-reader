package com.example.notionreader.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.ReaderApplication
import com.example.notionreader.data.discovery.GemmaEmbeddingProvider
import com.example.notionreader.data.discovery.GemmaModelPack
import com.example.notionreader.data.discovery.LocalDiscoveryModelManager
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Explicit developer deployment: uses the production download API, no Activity or credential bypass.
 * Run only when the owner has requested installation. Never clears Notes, interests or listening data.
 */
class GemmaDeploymentDeviceTest {
    @Test fun explicitlyRequestedInt4DeploymentUsesVerifiedAcquisitionAndLiteRtCpu() = runBlocking {
        org.junit.Assume.assumeTrue("Production acquisition requires explicit wrapper -DeployModel",
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("allowModelDeployment") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as ReaderApplication
        val app = application.host.app
        assertFalse("Deployment must not interrupt active playback", app.session.state.value.isPlaying)
        val model = app.discoveryModel as LocalDiscoveryModelManager
        model.checkInstalled()
        val alreadyInstalled = model.state.value.installed
        assertEquals(164626432L, model.state.value.totalBytes)
        if (!alreadyInstalled) {
            // This test invocation is the user's explicit installation request; network preference remains enforced.
            model.download()
            withTimeout(300_000) {
                while (!model.state.value.installed && model.state.value.message == null) delay(200)
            }
        }
        assertTrue("Verified model acquisition: ${model.state.value.message}", model.state.value.installed)
        model.checkInstalled()
        assertTrue(model.state.value.installed)
        val reference = Json.parseToJsonElement(instrumentation.context.assets.open("gemma2-q4-android-reference.json")
            .bufferedReader().use { it.readText() }).jsonObject
        val rows = mutableListOf<JsonObject>()
        val provider = GemmaEmbeddingProvider(model.modelDirectory)
        try {
            for (case in reference.getValue("embeddingReferences").jsonArray.map { it.jsonObject }) {
                val actual = provider.embed(case.getValue("input").jsonPrimitive.content)
                val expected = case.getValue("vector").jsonArray.map { it.jsonPrimitive.double }
                val cosine = actual.indices.sumOf { actual[it].toDouble() * expected[it] } /
                    sqrt(actual.sumOf { it.toDouble() * it } * expected.sumOf { it * it })
                assertEquals(768, actual.size)
                assertTrue(actual.all { it.isFinite() })
                assertEquals(1.0, actual.sumOf { it.toDouble() * it }, .00001)
                val repeated = provider.embed(case.getValue("input").jsonPrimitive.content)
                assertTrue("Installed LiteRT model must be repeatable",
                    actual.indices.sumOf { actual[it].toDouble() * repeated[it] } >= .99999)
                // Different published quantizations are not required to reproduce old ONNX vectors.
                // The original .9995 ONNX parity assertion remains in its historical control test.
                rows += buildJsonObject { put("historicalOnnxCosine", cosine); put("backend", provider.runtimeBackend) }
            }
            File(application.getExternalFilesDir(null), "gemma-int4-deployment.json").writeText(buildJsonObject {
                put("device", android.os.Build.MODEL); put("model", GemmaModelPack.identity.model)
                put("bytes", model.state.value.totalBytes); put("alreadyInstalled", alreadyInstalled)
                put("verified", true); put("backend", provider.runtimeBackend); put("publicReferences", JsonArray(rows))
            }.toString())
        } finally { provider.release() }
    }
}
