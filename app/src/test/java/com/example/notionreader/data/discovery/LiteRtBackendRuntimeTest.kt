package com.example.notionreader.data.discovery

import com.example.notionreader.domain.settings.DiscoveryBackend
import org.junit.Assert.*
import org.junit.Test

class LiteRtBackendRuntimeTest {
    private val vector = List(768) { if (it == 0) 1f else 0f }
    private class Factory(val vector: List<Float>) {
        val opened = mutableListOf<DiscoveryBackend>()
        var live = 0
        var failGpu = false
        var wrongGpu = false
        var clock = 0L
        fun open(backend: DiscoveryBackend): LiteRtEmbeddingSession {
            check(live == 0) { "Old session was not closed" }
            opened += backend; live++
            return object : LiteRtEmbeddingSession {
                override fun embed(text: String): List<Float> {
                    clock += if (backend == DiscoveryBackend.GPU) 1 else 10
                    if (backend == DiscoveryBackend.GPU && failGpu) error("driver failed")
                    return if (backend == DiscoveryBackend.GPU && wrongGpu) vector.reversed() else vector
                }
                override fun close() { live-- }
            }
        }
    }
    @Test fun automaticSelectsTheFasterValidatedBackendAndClosesBeforeSwitching() {
        val f = Factory(vector)
        val runtime = LiteRtBackendRuntime(f::open, clock = { f.clock })
        assertEquals(vector, runtime.embed("public input"))
        assertEquals(DiscoveryBackend.GPU, runtime.state.value.effective)
        assertEquals(1, f.live)
        runtime.release(); assertEquals(0, f.live)
    }
    @Test fun incompatibleAcceleratorCannotPolluteTheExistingEmbeddingSpace() {
        val f = Factory(vector); f.wrongGpu = true
        val runtime = LiteRtBackendRuntime(f::open, clock = { f.clock })
        runtime.select(DiscoveryBackend.GPU)
        assertEquals(vector, runtime.embed("public input"))
        assertEquals(DiscoveryBackend.CPU, runtime.state.value.effective)
        assertTrue(runtime.state.value.reason!!.contains("validation"))
    }
    @Test fun executionFailureFallsBackWithoutRetryingTheRejectedBackend() {
        val f = Factory(vector)
        val runtime = LiteRtBackendRuntime(f::open, clock = { f.clock })
        runtime.select(DiscoveryBackend.GPU); runtime.embed("first")
        val openedBeforeFailure = f.opened.count { it == DiscoveryBackend.GPU }
        f.failGpu = true
        assertEquals(vector, runtime.embed("second"))
        runtime.release(); runtime.embed("third")
        assertEquals(openedBeforeFailure, f.opened.count { it == DiscoveryBackend.GPU })
        assertEquals(DiscoveryBackend.CPU, runtime.state.value.effective)
    }
    @Test fun unavailableNpuIsExplainedAndCpuFailureRemainsAFailure() {
        val f = Factory(vector)
        val runtime = LiteRtBackendRuntime(f::open, clock = { f.clock })
        runtime.select(DiscoveryBackend.NPU); runtime.embed("public")
        assertEquals(listOf(DiscoveryBackend.CPU), f.opened)
        assertTrue(runtime.state.value.reason!!.contains("NPU"))
        val broken = LiteRtBackendRuntime({ error("CPU unavailable") })
        broken.select(DiscoveryBackend.CPU)
        assertThrows(IllegalStateException::class.java) { broken.embed("public") }
        assertNull(broken.state.value.effective)
    }
    @Test fun changingPreferenceReleasesTheOldEngineAndPreservesRequestedChoice() {
        val f = Factory(vector)
        val runtime = LiteRtBackendRuntime(f::open, clock = { f.clock })
        runtime.embed("first"); runtime.select(DiscoveryBackend.CPU)
        assertEquals(0, f.live); assertNull(runtime.state.value.effective)
        runtime.embed("second")
        assertEquals(DiscoveryBackend.CPU, runtime.state.value.requested)
        assertEquals(DiscoveryBackend.CPU, runtime.state.value.effective)
    }
    @Test fun missingOrUnknownStoredPreferenceUsesAutomatic() {
        assertEquals(DiscoveryBackend.AUTOMATIC, DiscoveryBackend.fromStored(null))
        assertEquals(DiscoveryBackend.AUTOMATIC, DiscoveryBackend.fromStored("future"))
        DiscoveryBackend.entries.forEach { assertEquals(it, DiscoveryBackend.fromStored(it.name)) }
    }
    @Test fun aLongInputUsesCpuWithoutForgettingTheValidatedAccelerator() {
        val f = Factory(vector)
        val runtime = LiteRtBackendRuntime(f::open, clock = { f.clock })
        runtime.embed("short")
        runtime.embed("long", cpuOnly = true)
        assertEquals(DiscoveryBackend.CPU, runtime.state.value.effective)
        assertTrue(runtime.state.value.reason!!.contains("context"))
        runtime.embed("short again")
        assertEquals(DiscoveryBackend.GPU, runtime.state.value.effective)
        assertEquals(1, f.live)
    }
    @Test fun cancellationIsNeverTurnedIntoCpuSuccess() {
        val runtime = LiteRtBackendRuntime({ throw java.util.concurrent.CancellationException() })
        runtime.select(DiscoveryBackend.GPU)
        assertThrows(java.util.concurrent.CancellationException::class.java) { runtime.embed("public") }
        assertNull(runtime.state.value.effective)
    }
    @Test fun aLongInputCpuInitializationFailureIsNotRetriedOrBlamedOnGpu() {
        val f = Factory(vector)
        var failCpu = false
        var attempts = 0
        val runtime = LiteRtBackendRuntime({ backend ->
            if (backend == DiscoveryBackend.CPU && failCpu) { attempts++; error("CPU unavailable") }
            f.open(backend)
        }, clock = { f.clock })
        runtime.embed("short"); failCpu = true
        assertThrows(IllegalStateException::class.java) { runtime.embed("long", cpuOnly = true) }
        assertEquals(1, attempts)
        assertTrue(runtime.state.value.reason!!.startsWith("CPU"))
    }
}
