package com.example.notionreader.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic aggregation is bounded and off the work path. The per-event observer must not be
 * called once per event; sums and counts are emitted together at a bounded cadence and on demand.
 */
class DiscoveryWorkLogAggregatorTest {
    @Test
    fun eventsAreAggregatedUntilTheBoundAndThenFlushedWithCounts() {
        var now = 0L
        val emitted = mutableListOf<Map<String, Long>>()
        val aggregator = DiscoveryWorkLogAggregator({ emitted += it }, { now },
            flushEvents = 3, flushNanos = 1_000_000_000L)
        aggregator.accept(mapOf("scanNanos" to 10L))
        aggregator.accept(mapOf("scanNanos" to 15L))
        assertTrue("No per-event emission", emitted.isEmpty())
        aggregator.accept(mapOf("cacheHit" to 1L))
        assertEquals(1, emitted.size)
        assertEquals(25L, emitted.single()["scanNanos"])
        assertEquals(2L, emitted.single()["scanNanosCount"])
        assertEquals(1L, emitted.single()["cacheHit"])
        assertEquals(1L, emitted.single()["cacheHitCount"])
    }

    @Test
    fun aFlushAfterAnIdlePeriodEmitsTheRemainingEventsBeforeTheEventBound() {
        var now = 0L
        val emitted = mutableListOf<Map<String, Long>>()
        val aggregator = DiscoveryWorkLogAggregator({ emitted += it }, { now },
            flushEvents = 100, flushNanos = 2_000L)
        aggregator.accept(mapOf("reconcileNanos" to 4L))
        now = 3_000L
        aggregator.accept(mapOf("reconcileNanos" to 6L))
        assertEquals(1, emitted.size)
        assertEquals(10L, emitted.single()["reconcileNanos"])
        assertEquals(2L, emitted.single()["reconcileNanosCount"])
        aggregator.flush()
        assertEquals("A second flush with no new events emits nothing", 1, emitted.size)
    }
}
