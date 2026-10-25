package com.example.notionreader.data.discovery

import com.example.notionreader.domain.discovery.EmbeddingRuntimeState
import com.example.notionreader.domain.settings.DiscoveryBackend
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal interface LiteRtEmbeddingSession : AutoCloseable {
    fun embed(text: String): List<Float>
}
internal class EmbeddingBackendValidationFailure(val cosine: Double) : IllegalArgumentException("Embedding validation failed")

/** Caller serializes selection, execution and release. Only one native session is live. */
internal class LiteRtBackendRuntime(
    private val open: (DiscoveryBackend) -> LiteRtEmbeddingSession,
    private val unavailable: (DiscoveryBackend) -> String? = {
        if (it == DiscoveryBackend.NPU) "NPU requires a matching published model and compatible vendor libraries." else null
    },
    private val clock: () -> Long = System::nanoTime
) {
    private val mutable = MutableStateFlow(EmbeddingRuntimeState())
    val state: StateFlow<EmbeddingRuntimeState> = mutable
    private var session: LiteRtEmbeddingSession? = null
    private var sessionBackend: DiscoveryBackend? = null
    private var selected: DiscoveryBackend? = null
    private var rejection: String? = null

    fun select(preference: DiscoveryBackend) {
        if (preference == mutable.value.requested) return
        release(); selected = null; rejection = null
        mutable.value = EmbeddingRuntimeState(requested = preference)
    }
    fun resetSelection() {
        release(); selected = null; rejection = null
        mutable.value = EmbeddingRuntimeState(requested = mutable.value.requested)
    }

    fun embed(text: String, cpuOnly: Boolean = false): List<Float> {
        try {
            if (selected == null) choose()
            val target = if (cpuOnly) DiscoveryBackend.CPU else checkNotNull(selected)
            if (sessionBackend != target) release()
            if (session == null) { sessionBackend = target; session = open(target) }
            val result = checked(checkNotNull(session).embed(text))
            mutable.value = mutable.value.copy(effective = target, reason = if (cpuOnly && selected != target)
                "This input exceeds the accelerator context; using CPU." else rejection)
            return result
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            return recover(text, failure)
        } catch (failure: LinkageError) {
            return recover(text, failure)
        }
    }

    private fun recover(text: String, failure: Throwable): List<Float> {
        val failed = sessionBackend ?: selected
        release()
        if (failed == null || failed == DiscoveryBackend.CPU) {
            mutable.value = mutable.value.copy(reason = "CPU inference failed (${failure.javaClass.simpleName}).")
            throw failure
        }
        rejection = "${failed.label} execution failed (${failure.javaClass.simpleName}); using CPU."
        selected = DiscoveryBackend.CPU
        return embed(text)
    }

    private fun choose() {
        val requested = mutable.value.requested
        if (requested == DiscoveryBackend.CPU) { selected = DiscoveryBackend.CPU; return }
        if (requested == DiscoveryBackend.NPU && unavailable(requested) != null) {
            rejection = unavailable(requested) + " Using CPU."
            selected = DiscoveryBackend.CPU; return
        }
        val candidates = if (requested == DiscoveryBackend.AUTOMATIC)
            listOf(DiscoveryBackend.NPU, DiscoveryBackend.GPU).filter { unavailable(it) == null }
            else listOf(requested)
        val measurements = mutableMapOf<DiscoveryBackend, Long>()
        selected = DiscoveryBackend.CPU
        val reference: List<List<Float>>
        open(DiscoveryBackend.CPU).use { cpu ->
            reference = probes.map { checked(cpu.embed(it)) }
            measurements[DiscoveryBackend.CPU] = measure(cpu)
        }
        var best = DiscoveryBackend.CPU
        for (backend in candidates) {
            try {
                open(backend).use { candidate ->
                    probes.forEachIndexed { index, probe ->
                        val output = checked(candidate.embed(probe))
                        val cosine = output.indices.sumOf { output[it].toDouble() * reference[index][it] }
                        if (cosine < .999) throw EmbeddingBackendValidationFailure(cosine)
                    }
                    measurements[backend] = measure(candidate)
                }
                if (requested != DiscoveryBackend.AUTOMATIC || measurements.getValue(backend) < measurements.getValue(best)) best = backend
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                rejection = if (failure is EmbeddingBackendValidationFailure)
                    "${backend.label} embedding validation failed (cosine ${failure.cosine}); using CPU."
                    else "${backend.label} validation unavailable (${failure.javaClass.simpleName}); using CPU."
            } catch (failure: LinkageError) {
                rejection = "${backend.label} native runtime unavailable; using CPU."
            }
        }
        selected = best
        if (best != DiscoveryBackend.CPU) rejection = null
        mutable.value = mutable.value.copy(benchmarkNanos = measurements.toMap(), reason = rejection)
    }

    private fun measure(active: LiteRtEmbeddingSession): Long = List(3) {
        val start = clock()
        probes.forEach { checked(active.embed(it)) }
        (clock() - start).coerceAtLeast(0L)
    }.sorted()[1]

    private fun checked(vector: List<Float>): List<Float> {
        require(vector.size == 768 && vector.all { it.isFinite() }) { "Invalid embedding" }
        require(kotlin.math.abs(vector.sumOf { it.toDouble() * it } - 1.0) < .001) { "Invalid embedding norm" }
        return vector
    }

    fun release() {
        val old = session; session = null
        sessionBackend = null
        mutable.value = mutable.value.copy(effective = null)
        old?.close()
    }

    companion object {
        private val probes = listOf(
            "PostgreSQL transactions use snapshots to isolate concurrent database updates.",
            "Riverbank trees shade streams and protect aquatic habitats. Their roots stabilize soil. ".repeat(12)
        )
    }
}
