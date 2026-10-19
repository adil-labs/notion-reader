package com.example.notionreader.application

import org.junit.Assert.*
import org.junit.Test
import com.example.notionreader.domain.settings.DiscoveryEffort

class DiscoveryWorkPolicyTest {
    @Test fun absentOrFutureEffortValuesKeepTheBalancedMigrationDefault() {
        assertEquals(DiscoveryEffort.BALANCED, DiscoveryEffort.fromStored(null))
        assertEquals(DiscoveryEffort.BALANCED, DiscoveryEffort.fromStored("future-mode"))
        assertEquals(DiscoveryEffort.CHARGING_ONLY, DiscoveryEffort.fromStored("CHARGING_ONLY"))
        assertEquals(DiscoveryEffort.BALANCED, DiscoveryEffort.fromStored("BALANCED"))
    }
    @Test fun effortChangesAvailabilityWithoutLoweringTheBatteryFloor() {
        val balanced = com.example.notionreader.domain.settings.DiscoveryEffort.BALANCED
        val charging = com.example.notionreader.domain.settings.DiscoveryEffort.CHARGING_ONLY
        assertEquals(balanced, com.example.notionreader.domain.settings.ReaderSettings().discoveryEffort)
        assertFalse(DiscoveryPowerState().allows(balanced))
        assertFalse(DiscoveryPowerState(29).allows(balanced))
        assertTrue(DiscoveryPowerState(30).allows(balanced))
        assertFalse(DiscoveryPowerState(100).allows(charging))
        assertTrue(DiscoveryPowerState(1, true).allows(charging))
        assertTrue(DiscoveryPowerState(null, true).allows(charging))
    }
    @Test fun bulkWorkNeedsThirtyPercentOrExternalPower() {
        assertFalse(DiscoveryPowerState(29).eligible)
        assertTrue(DiscoveryPowerState(30).eligible)
        assertTrue(DiscoveryPowerState(1, connected = true).eligible)
        assertFalse(DiscoveryPowerState().eligible)
        assertFalse(DiscoveryPowerState(101).eligible)
        assertTrue(DiscoveryPowerState(connected = true).eligible)
    }

    @Test fun estimatesUseActualRemainingPassagesAndIgnoreColdOrInvalidSamples() {
        val estimator = DiscoveryTimeEstimator()
        estimator.begin("model-source", 10)
        estimator.record(100_000_000_000) // First call may initialize the model.
        estimator.record(0)
        estimator.record(-1)
        repeat(2) { estimator.record(1_000_000_000) }
        assertNull(estimator.remaining(4))
        estimator.record(1_000_000_000)
        assertEquals(ProcessingTimeRange(3, 6), estimator.remaining(4))
        assertEquals(ProcessingTimeRange(0, 0), estimator.remaining(0))
        estimator.begin("other-model-source", 10)
        assertNull(estimator.remaining(4))
    }

    @Test fun unchangedWorkKeepsItsRateButDifferentWorkResetsIt() {
        val estimator = DiscoveryTimeEstimator()
        estimator.begin("same", 5)
        repeat(4) { estimator.record(2_000_000_000) }
        estimator.begin("same", 5)
        assertEquals(ProcessingTimeRange(3, 6), estimator.remaining(2))
        estimator.begin("same", 6)
        assertNull(estimator.remaining(2))
    }
}
