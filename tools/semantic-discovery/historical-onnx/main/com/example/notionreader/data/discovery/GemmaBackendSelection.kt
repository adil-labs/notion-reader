package com.example.notionreader.data.discovery

import java.util.concurrent.atomic.AtomicBoolean

enum class GemmaRuntimeBackend { CPU, VULKAN }

internal interface GemmaBackendSession : AutoCloseable {
    fun run(ids: LongArray): FloatArray
}

internal class GemmaBackendSelection(
    private val factory: (GemmaRuntimeBackend) -> GemmaBackendSession,
    private val gpuDisabled: AtomicBoolean
) : AutoCloseable {
    private var cpu: GemmaBackendSession? = null
    private var gpu: GemmaBackendSession? = null
    private var calibrated = false
    var backend = GemmaRuntimeBackend.CPU
        private set

    fun embed(ids: LongArray): List<Float> {
        val active = cpu ?: factory(GemmaRuntimeBackend.CPU).also { cpu = it }
        if (gpuDisabled.get()) {
            discardGpu()
            return normalizeGemmaOutput(active.run(ids))
        }
        if (!calibrated) {
            val reference = normalizeGemmaOutput(active.run(ids))
            return try {
                val candidate = factory(GemmaRuntimeBackend.VULKAN).also { gpu = it }
                val vector = normalizeGemmaOutput(candidate.run(ids))
                val cosine = vector.indices.sumOf { vector[it].toDouble() * reference[it] }
                check(cosine >= .9995) { "Vulkan embedding validation failed" }
                calibrated = true
                backend = GemmaRuntimeBackend.VULKAN
                vector
            } catch (_: Exception) {
                disableGpu(); reference
            } catch (_: LinkageError) {
                disableGpu(); reference
            }
        }
        return try {
            normalizeGemmaOutput(checkNotNull(gpu).run(ids))
        } catch (_: Exception) {
            disableGpu(); normalizeGemmaOutput(active.run(ids))
        } catch (_: LinkageError) {
            disableGpu(); normalizeGemmaOutput(active.run(ids))
        }
    }

    private fun discardGpu() {
        val previous = gpu
        gpu = null
        calibrated = false
        backend = GemmaRuntimeBackend.CPU
        // A failed GPU must not prevent the already validated CPU fallback.
        try { previous?.close() } catch (_: Exception) {} catch (_: LinkageError) {}
    }

    private fun disableGpu() { gpuDisabled.set(true); discardGpu() }

    override fun close() {
        discardGpu()
        val previous = cpu
        cpu = null
        previous?.close()
    }
}
