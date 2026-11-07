package com.example.notionreader.application

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the existing optional observer through nested work, never evidence or control state. */
internal class DiscoveryWorkDiagnostics(
    private val observer: (Map<String, Long>) -> Unit,
    private val clock: () -> Long = System::nanoTime
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiscoveryWorkDiagnostics>
    fun record(name: String, value: Long = 1) {
        try { observer(mapOf(name to value)) } catch (_: Exception) { /* Observation cannot control work. */ }
    }
    suspend fun <T> timed(name: String, operation: suspend () -> T): T {
        val started = clock()
        return try { operation() } finally { record(name, (clock() - started).coerceAtLeast(0)) }
    }
}
