package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.EmbeddingProvider
import com.example.notionreader.domain.discovery.ObservableEmbeddingRuntime
import com.example.notionreader.domain.settings.DiscoveryBackend
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

internal fun normalizeGemmaOutput(output: FloatArray): List<Float> {
    require(output.size == 768 && output.all { it.isFinite() }) { "Invalid Gemma sentence embedding" }
    val norm = sqrt(output.sumOf { it.toDouble() * it })
    require(norm.isFinite() && norm > 0) { "Invalid Gemma embedding norm" }
    return output.map { (it / norm).toFloat() }
}

/** LiteRT-LM owns bundled tokenization, projection and pooling; CPU is the baseline. */
class GemmaEmbeddingProvider(
    private val directory: File,
    private val npuBundle: () -> File? = { null },
    private val nativeLibraryDirectory: String? = null
) : EmbeddingProvider, ObservableEmbeddingRuntime {
    override val identity = GemmaModelPack.identity
    override val tokenizer = GemmaTokenizer(File(directory, GemmaModelPack.bundleFile))
    private val lock = Any()
    private val runtime = LiteRtBackendRuntime(::openSession, unavailable = { backend ->
        if (backend != DiscoveryBackend.NPU) null
        else if (npuBundle() == null) "NPU requires the optional matching SM8750 model; other chipsets use CPU/GPU."
        else if (nativeLibraryDirectory == null || listOf("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so",
                "libQnnSystem.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so").any {
                !File(nativeLibraryDirectory, it).isFile }) "NPU vendor libraries unavailable."
        else null
    })
    override val runtimeState = runtime.state
    val runtimeBackend: String get() = runtimeState.value.effective?.label ?: "Not running"

    /** Called off-main by the process settings collector; shares the inference lock. */
    fun selectBackend(preference: DiscoveryBackend) = synchronized(lock) { runtime.select(preference) }
    fun installationChanged() = synchronized(lock) { runtime.resetSelection() }

    private fun openSession(backend: DiscoveryBackend): LiteRtEmbeddingSession {
        val active = EmbeddingEngine(EmbeddingEngineConfig(
            modelPath = (if (backend == DiscoveryBackend.NPU) checkNotNull(npuBundle()) else File(directory, GemmaModelPack.bundleFile)).absolutePath,
            backend = when (backend) {
                DiscoveryBackend.CPU -> Backend.CPU(threadCount = 2)
                DiscoveryBackend.GPU -> Backend.GPU()
                DiscoveryBackend.NPU -> Backend.NPU(nativeLibraryDir = checkNotNull(nativeLibraryDirectory))
                else -> error("Backend prerequisites unavailable")
            }, maxInputLength = if (backend == DiscoveryBackend.CPU) 8192 else 512
        ))
        try { active.initialize() } catch (failure: Throwable) {
            try { active.close() } catch (_: Exception) { }
            throw failure
        }
        return object : LiteRtEmbeddingSession {
            override fun embed(text: String) = normalizeGemmaOutput(active.computeEmbedding(
                listOf(InputData.Text(GemmaModelPack.instructedText(text))),
                EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = 768)
            ).embedding)
            override fun close() = active.close()
        }
    }

    override suspend fun embed(text: String): List<Float> = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val tokens = tokenizer.inputIds(text).size
            require(tokens in 1..8192) { "Embedding input exceeds Gemma's 8192 token context" }
            runtime.embed(text, cpuOnly = tokens > 512)
        }
    }

    override fun release() = synchronized(lock) {
        try { runtime.release() } finally { tokenizer.close() }
        Unit
    }
}
