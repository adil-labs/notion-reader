package com.example.notionreader.application

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries the existing optional observer through nested work, never evidence or control state.
 *
 * Every value is numeric or an opaque hash. No Note text, title, query text, or evidence excerpt
 * is ever recorded. The observer is best-effort: an observation failure can never control work.
 */
internal class DiscoveryWorkDiagnostics(
    private val observer: (Map<String, Long>) -> Unit,
    private val clock: () -> Long = System::nanoTime
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiscoveryWorkDiagnostics>

    // Bounded cardinality, process-local. Work keys are already opaque hashes; only the
    // unique/repeated distinction is emitted, never the key itself.
    private val supportWorkKeys = ConcurrentHashMap<String, Boolean>()
    private val supportKeyOverflow = AtomicLong()

    fun record(name: String, value: Long = 1) {
        try { observer(mapOf(name to value)) } catch (_: Exception) { /* Observation cannot control work. */ }
    }

    suspend fun <T> timed(name: String, operation: suspend () -> T): T {
        val started = clock()
        return try { operation() } finally { record(name, (clock() - started).coerceAtLeast(0)) }
    }

    /**
     * Count unique versus repeated passage-concept verification work in this process.
     * The key crosses no boundary: only "first time" / "seen before" is emitted.
     */
    fun recordSupportWork(workKey: String) {
        if (supportWorkKeys.size >= 8192 && !supportWorkKeys.containsKey(workKey)) {
            if (supportKeyOverflow.getAndIncrement() % 256L == 0L) record("supportWorkKeyOverflow")
            return
        }
        if (supportWorkKeys.putIfAbsent(workKey, true) == null) record("supportWorkKeyUnique")
        else record("supportWorkKeyRepeated")
    }
}
