package com.example.notionreader.application

import org.junit.Assert.*
import org.junit.Test

class DiscoveryMemoryGateTest {
    @Test fun lowOrUnverifiedDeviceMemoryCannotAdmitExpensiveWork() {
        val healthy = DiscoveryDeviceMemory(800, 1000, 100, false)
        val cases = listOf(null, healthy.copy(lowMemory = true), healthy.copy(availableBytes = 100),
            healthy.copy(availableBytes = 99), healthy.copy(totalBytes = 0), healthy.copy(availableBytes = -1),
            healthy.copy(availableBytes = 1001), healthy.copy(thresholdBytes = -1))
        for (memory in cases) assertFalse("Must defer: $memory", DiscoveryMemoryGate({ memory }, { 0 }).allows())
        assertFalse(DiscoveryMemoryGate({ error("No system sample") }, { 0 }).allows())
        assertTrue(DiscoveryMemoryGate({ healthy }, { 0 }).allows())
    }
    @Test fun frequentPolicyChecksReuseSamplesButWorkBoundariesCanForceFreshEvidence() {
        var calls = 0; var time = 0L
        val gate = DiscoveryMemoryGate({ calls++; DiscoveryDeviceMemory(800, 1000, 100, false) }, { time })
        repeat(50) { assertTrue(gate.allows()) }; assertEquals(1, calls)
        time = 999; assertTrue(gate.allows()); assertEquals(1, calls)
        time = 1000; assertTrue(gate.allows()); assertEquals(2, calls)
        assertTrue(gate.allows(force = true)); assertEquals(3, calls)
    }
    @Test fun pressureSignalsInvalidateTheCachedHealthySampleAndRecoveryNeedsAFreshSample() {
        var memory = DiscoveryDeviceMemory(800, 1000, 100, false)
        val gate = DiscoveryMemoryGate({ memory }, { 0 })
        assertTrue(gate.allows()); memory = memory.copy(lowMemory = true)
        gate.invalidate(); assertFalse(gate.allows())
        memory = memory.copy(lowMemory = false)
        assertFalse(gate.allows()); assertTrue(gate.allows(force = true))
    }
}
