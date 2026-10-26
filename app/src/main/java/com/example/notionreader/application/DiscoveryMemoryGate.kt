package com.example.notionreader.application

data class DiscoveryDeviceMemory(val availableBytes: Long, val totalBytes: Long,
    val thresholdBytes: Long, val lowMemory: Boolean)

/** Process-owned sampling policy; Android supplies facts, never an assumed model working set. */
class DiscoveryMemoryGate(private val sample: () -> DiscoveryDeviceMemory?,
    private val monotonicMillis: () -> Long, private val intervalMillis: Long = 1000) {
    init { require(intervalMillis > 0) }
    private var sampledAt: Long? = null
    private var permitted = false
    @Synchronized fun allows(force: Boolean = false): Boolean {
        val now = monotonicMillis()
        val previous = sampledAt
        if (force || previous == null || now < previous || now - previous >= intervalMillis) {
            val memory = try { sample() } catch (_: Exception) { null }
            permitted = memory != null && !memory.lowMemory && memory.totalBytes > 0 &&
                memory.availableBytes in 0..memory.totalBytes && memory.thresholdBytes in 0..memory.totalBytes &&
                memory.availableBytes > memory.thresholdBytes
            sampledAt = now
        }
        return permitted
    }
    @Synchronized fun invalidate() { sampledAt = null; permitted = false }
}
