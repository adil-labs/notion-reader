package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.EmbeddingProvider
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
class GemmaEmbeddingProvider(private val directory: File) : EmbeddingProvider {
    override val identity = GemmaModelPack.identity
    override val tokenizer = GemmaTokenizer(File(directory, GemmaModelPack.bundleFile))
    private val lock = Any()
    private var engine: EmbeddingEngine? = null
    val runtimeBackend: String get() = "CPU"

    override suspend fun embed(text: String): List<Float> = withContext(Dispatchers.Default) {
        synchronized(lock) {
            require(tokenizer.inputIds(text).size in 1..8192) { "Embedding input exceeds Gemma's 8192 token context" }
            val active = engine ?: EmbeddingEngine(EmbeddingEngineConfig(
                modelPath = File(directory, GemmaModelPack.bundleFile).absolutePath,
                backend = Backend.CPU(threadCount = 2), maxInputLength = 8192
            )).also { candidate ->
                try { candidate.initialize() } catch (failure: Throwable) {
                    try { candidate.close() } catch (_: Exception) { }
                    throw failure
                }
                engine = candidate
            }
            normalizeGemmaOutput(active.computeEmbedding(
                listOf(InputData.Text(GemmaModelPack.instructedText(text))),
                EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = 768)
            ).embedding)
        }
    }

    override fun release() = synchronized(lock) {
        val previous = engine
        engine = null
        try { previous?.close() } finally { tokenizer.close() }
        Unit
    }
}
