package com.example.notionreader.data.discovery

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class GemmaBackendSelectionTest {
    private fun vector(index: Int = 0) = FloatArray(768).also { it[index] = 1f }
    private class Session(private val answer: (LongArray) -> FloatArray) : GemmaBackendSession {
        var calls = 0
        var closed = false
        override fun run(ids: LongArray): FloatArray { check(!closed); calls++; return answer(ids) }
        override fun close() { closed = true }
    }

    @Test fun aValidatedVulkanSessionAvoidsRepeatingCpuInference() {
        val cpu = Session { vector() }; val gpu = Session { vector() }
        val selector = GemmaBackendSelection({ if (it == GemmaRuntimeBackend.CPU) cpu else gpu }, AtomicBoolean(false))
        assertEquals(vector().toList(), selector.embed(longArrayOf(1)))
        assertEquals(GemmaRuntimeBackend.VULKAN, selector.backend)
        selector.embed(longArrayOf(2, 3))
        assertEquals(1, cpu.calls)
        assertEquals(2, gpu.calls)
        selector.close()
        assertTrue(cpu.closed); assertTrue(gpu.closed)
    }

    @Test fun aMismatchedVulkanOutputDisablesGpuForOtherSessionsInTheProcess() {
        val disabled = AtomicBoolean(false)
        val cpu = Session { vector() }; val gpu = Session { vector(1) }
        val selector = GemmaBackendSelection({ if (it == GemmaRuntimeBackend.CPU) cpu else gpu }, disabled)
        assertEquals(vector().toList(), selector.embed(longArrayOf(1)))
        assertEquals(GemmaRuntimeBackend.CPU, selector.backend)
        assertTrue(disabled.get()); assertTrue(gpu.closed)
        val otherCpu = Session { vector() }
        val next = GemmaBackendSelection({ assertEquals(GemmaRuntimeBackend.CPU, it); otherCpu }, disabled)
        next.embed(longArrayOf(1)); assertEquals(1, otherCpu.calls)
        selector.close(); next.close()
    }

    @Test fun anUnavailableVulkanBackendKeepsTheCpuResult() {
        val cpu = Session { vector() }; val disabled = AtomicBoolean(false)
        val selector = GemmaBackendSelection({ if (it == GemmaRuntimeBackend.CPU) cpu else error("Unavailable") }, disabled)
        assertEquals(vector().toList(), selector.embed(longArrayOf(1)))
        assertTrue(disabled.get()); assertEquals(GemmaRuntimeBackend.CPU, selector.backend)
        selector.close()
    }

    @Test fun aLaterShapeFailureClosesGpuAndRetriesTheSameInputOnCpu() {
        val inputs = mutableListOf<List<Long>>()
        val cpu = Session { inputs += it.toList(); vector() }
        val gpu = Session { if (it.size > 1) error("Unsupported shape") else vector() }
        val disabled = AtomicBoolean(false)
        val selector = GemmaBackendSelection({ if (it == GemmaRuntimeBackend.CPU) cpu else gpu }, disabled)
        selector.embed(longArrayOf(1))
        assertEquals(vector().toList(), selector.embed(longArrayOf(2, 3)))
        assertEquals(listOf(listOf(1L), listOf(2L, 3L)), inputs)
        assertTrue(disabled.get()); assertTrue(gpu.closed)
        selector.close()
    }

    @Test fun everyVulkanOutputMustRemainFiniteAndNonzero() {
        for (invalid in listOf(FloatArray(768), vector().also { it[0] = Float.NaN })) {
            val cpu = Session { vector() }; var calls = 0
            val gpu = Session { if (++calls == 1) vector() else invalid }
            val disabled = AtomicBoolean(false)
            val selector = GemmaBackendSelection({ if (it == GemmaRuntimeBackend.CPU) cpu else gpu }, disabled)
            selector.embed(longArrayOf(1))
            assertEquals(vector().toList(), selector.embed(longArrayOf(2)))
            assertTrue(disabled.get()); assertEquals(2, cpu.calls)
            selector.close()
        }
    }

    @Test fun releaseAllowsFreshSessionsToBeValidatedAgain() {
        val sessions = mutableListOf<Session>()
        val selector = GemmaBackendSelection({ Session { vector() }.also { sessions += it } }, AtomicBoolean(false))
        selector.embed(longArrayOf(1)); selector.close()
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.closed })
        selector.embed(longArrayOf(2))
        assertEquals(4, sessions.size)
        assertEquals(GemmaRuntimeBackend.VULKAN, selector.backend)
        selector.close()
    }
}
