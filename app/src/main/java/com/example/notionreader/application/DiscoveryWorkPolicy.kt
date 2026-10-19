package com.example.notionreader.application

import kotlin.math.ceil

data class DiscoveryPowerState(val percent: Int? = null, val connected: Boolean = false) {
    val eligible: Boolean get() = connected || percent != null && percent in 30..100
    fun allows(effort: com.example.notionreader.domain.settings.DiscoveryEffort): Boolean =
        if (effort == com.example.notionreader.domain.settings.DiscoveryEffort.CHARGING_ONLY) connected else eligible
}

data class ProcessingTimeRange(val minimumSeconds: Long, val maximumSeconds: Long)
data class DiscoveryEmbeddingWork(val remainingNotes: Int, val remainingPassages: Int,
    val estimate: ProcessingTimeRange? = null)

/** Active provider-call time only: query waits and battery pauses are never observations. */
internal class DiscoveryTimeEstimator {
    private var identity: Pair<String, Int>? = null
    private var cold = true
    private val samples = ArrayDeque<Long>()
    fun begin(key: String, passages: Int) {
        val next = key to passages
        if (identity != next) { identity = next; cold = true; samples.clear() }
    }
    fun record(nanos: Long) {
        if (nanos <= 0) return
        if (cold) { cold = false; return }
        samples.addLast(nanos)
        if (samples.size > 16) samples.removeFirst()
    }
    fun remaining(passages: Int): ProcessingTimeRange? {
        require(passages >= 0)
        if (passages == 0) return ProcessingTimeRange(0, 0)
        if (samples.size < 3) return null
        val seconds = samples.map { it.toDouble() }.average() * passages / 1_000_000_000.0
        return ProcessingTimeRange(ceil(seconds * .75).toLong().coerceAtLeast(1),
            ceil(seconds * 1.5).toLong().coerceAtLeast(1))
    }
}
