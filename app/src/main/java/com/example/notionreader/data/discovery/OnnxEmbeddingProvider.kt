package com.example.notionreader.data.discovery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.notionreader.domain.discovery.EmbeddingIdentity
import com.example.notionreader.domain.discovery.EmbeddingProvider
import java.io.File
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Caller installs checksum-verified artifacts before constructing this provider. */
class OnnxEmbeddingProvider(private val modelDirectory: File) : EmbeddingProvider {
    companion object {
        /** Calibrated only against the versioned synthetic feasibility corpus; not a confidence score. */
        const val RECOMMENDATION_THRESHOLD = 0.3867064f
    }
    override val identity = EmbeddingIdentity("all-MiniLM-L6-v2:1110a243fdf4706b3f48f1d95db1a4f5529b4d41:4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474",384)
    override val tokenizer: BertWordPieceTokenizer by lazy { BertWordPieceTokenizer(File(modelDirectory,"vocab.txt").readLines()) }
    private var session: OrtSession? = null
    private val lock = Any()

    override suspend fun embed(text: String): List<Float> = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val tokens = tokenizer.tokens(text)
            require(tokens.size <= 254) { "Embedding input exceeds the model's 256 token context" }
            val ids = longArrayOf(tokenizer.classificationId.toLong()) + tokens.map { it.id.toLong() }.toLongArray() + longArrayOf(tokenizer.separatorId.toLong())
            val environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
            val activeSession = session ?: OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                environment.createSession(File(modelDirectory,"model_qint8_arm64.onnx").absolutePath,options).also { session = it }
            }
            val shape = longArrayOf(1,ids.size.toLong())
            val tensors = mapOf(
                "input_ids" to OnnxTensor.createTensor(environment,LongBuffer.wrap(ids),shape),
                "attention_mask" to OnnxTensor.createTensor(environment,LongBuffer.wrap(LongArray(ids.size) { 1L }),shape),
                "token_type_ids" to OnnxTensor.createTensor(environment,LongBuffer.wrap(LongArray(ids.size)),shape)
            )
            try {
                activeSession.run(tensors).use { result ->
                    @Suppress("UNCHECKED_CAST") val hidden = result[0].value as Array<Array<FloatArray>>
                    require(hidden.size == 1 && hidden[0].size == ids.size)
                    val pooled = DoubleArray(identity.dimensions)
                    hidden[0].forEach { token -> require(token.size == identity.dimensions); token.forEachIndexed { index,value -> require(value.isFinite()); pooled[index] += value.toDouble()/ids.size } }
                    val norm = sqrt(pooled.sumOf { it*it })
                    require(norm.isFinite() && norm > 0)
                    pooled.map { (it/norm).toFloat() }
                }
            } finally { tensors.values.forEach { it.close() } }
        }
    }

    override fun release() { synchronized(lock) { session?.close(); session = null } }
}
