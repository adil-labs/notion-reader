package com.example.notionreader.application

/**
 * Debug-only bounded aggregation at the diagnostic edge.
 *
 * The work path still records one numeric event at a time; this aggregator turns those events
 * into a small summary at a bounded cadence so per-event logging cannot dominate the work it is
 * measuring. It never controls work: a sink failure is swallowed.
 */
internal class DiscoveryWorkLogAggregator(
    private val sink: (Map<String, Long>) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val flushEvents: Int = 128,
    private val flushNanos: Long = 2_000_000_000L
) {
    private val lock = Any()
    private val sums = HashMap<String, Long>()
    private val counts = HashMap<String, Long>()
    private var events = 0
    private var lastFlush = clock()

    fun accept(values: Map<String, Long>) {
        val snapshot = synchronized(lock) {
            values.forEach { (key, value) ->
                sums.merge(key, value, Long::plus)
                counts.merge(key, 1L, Long::plus)
            }
            events++
            if (events >= flushEvents || clock() - lastFlush >= flushNanos) drainLocked() else null
        }
        emit(snapshot)
    }

    fun flush() {
        emit(synchronized(lock) { drainLocked() })
    }

    private fun drainLocked(): Map<String, Long>? {
        if (events == 0) return null
        val result = HashMap<String, Long>((sums.size + counts.size) * 2)
        sums.forEach { (key, value) -> result[key] = value }
        counts.forEach { (key, value) -> result["${key}Count"] = value }
        sums.clear()
        counts.clear()
        events = 0
        lastFlush = clock()
        return result
    }

    private fun emit(snapshot: Map<String, Long>?) {
        if (snapshot == null) return
        try { sink(snapshot) } catch (_: Exception) { /* Observation cannot control work. */ }
    }
}
