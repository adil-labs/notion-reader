package com.example.notionreader.data.discovery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.notionreader.domain.discovery.EmbeddingProvider
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

internal fun normalizeGemmaOutput(output: FloatArray): List<Float> {
    require(output.size == 768 && output.all { it.isFinite() }) { "Invalid Gemma sentence embedding" }
    val norm = sqrt(output.sumOf { it.toDouble() * it })
    require(norm.isFinite() && norm > 0) { "Invalid Gemma embedding norm" }
    return output.map { (it / norm).toFloat() }
}

/** Uses publisher projection/pooling, validating local Vulkan against CPU before reuse. */
class GemmaEmbeddingProvider(private val directory: File) : EmbeddingProvider {
    private companion object { val gpuDisabled = AtomicBoolean(false) }
    override val identity = GemmaModelPack.identity
    override val tokenizer = GemmaTokenizer(File(directory, "tokenizer.json"))
    private val lock = Any()
    private val selection = GemmaBackendSelection({ backend -> createSession(backend) }, gpuDisabled)
    val runtimeBackend: GemmaRuntimeBackend get() = synchronized(lock) { selection.backend }

    override suspend fun embed(text: String): List<Float> = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val ids = tokenizer.inputIds(text)
            require(ids.size in 1..8192) { "Embedding input exceeds Gemma's 8192 token context" }
            selection.embed(ids)
        }
    }

    private fun createSession(backend: GemmaRuntimeBackend): GemmaBackendSession {
        val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        val session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            if (backend == GemmaRuntimeBackend.VULKAN) {
                options.addWebGPU(mapOf("dawnBackendType" to "Vulkan", "validationMode" to "basic"))
            }
            env.createSession(File(directory, GemmaModelPack.graph).absolutePath, options)
        }
        return object : GemmaBackendSession {
            override fun run(ids: LongArray): FloatArray {
                val shape = longArrayOf(1, ids.size.toLong())
                val tensors = linkedMapOf<String, OnnxTensor>()
                try {
                    for (name in session.inputNames) {
                        tensors[name] = when (name) {
                            "input_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                            "attention_mask" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
                            "position_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { it.toLong() }), shape)
                            "image_features", "video_features", "audio_features" -> OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, 512))
                            else -> error("Unsupported Gemma input contract")
                        }
                    }
                    check(session.outputNames.contains("sentence_embedding"))
                    session.run(tensors, setOf("sentence_embedding")).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val output = result[0].value as Array<FloatArray>
                        require(output.size == 1)
                        return output[0].copyOf()
                    }
                } finally { tensors.values.forEach { it.close() } }
            }
            override fun close() { session.close() }
        }
    }

    override fun release() = synchronized(lock) {
        try { selection.close() } finally { tokenizer.close() }
    }
}
