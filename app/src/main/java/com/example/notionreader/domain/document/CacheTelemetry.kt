package com.example.notionreader.domain.document

import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory thread-safe observability counters for document and index cache operations.
 */
class CacheTelemetry {
    private val _hits = AtomicLong(0)
    private val _misses = AtomicLong(0)
    private val _staleFallbacks = AtomicLong(0)
    private val _prefetchScheduled = AtomicLong(0)
    private val _prefetchCompleted = AtomicLong(0)
    private val _prefetchCancelled = AtomicLong(0)
    private val _evictions = AtomicLong(0)

    val hits: Long get() = _hits.get()
    val misses: Long get() = _misses.get()
    val staleFallbacks: Long get() = _staleFallbacks.get()
    val prefetchScheduled: Long get() = _prefetchScheduled.get()
    val prefetchCompleted: Long get() = _prefetchCompleted.get()
    val prefetchCancelled: Long get() = _prefetchCancelled.get()
    val evictions: Long get() = _evictions.get()

    fun recordHit() = _hits.incrementAndGet()
    fun recordMiss() = _misses.incrementAndGet()
    fun recordStaleFallback() = _staleFallbacks.incrementAndGet()
    fun recordPrefetchScheduled(count: Int = 1) = _prefetchScheduled.addAndGet(count.toLong())
    fun recordPrefetchCompleted(count: Int = 1) = _prefetchCompleted.addAndGet(count.toLong())
    fun recordPrefetchCancelled(count: Int = 1) = _prefetchCancelled.addAndGet(count.toLong())
    fun recordEviction(count: Int = 1) = _evictions.addAndGet(count.toLong())

    fun reset() {
        _hits.set(0)
        _misses.set(0)
        _staleFallbacks.set(0)
        _prefetchScheduled.set(0)
        _prefetchCompleted.set(0)
        _prefetchCancelled.set(0)
        _evictions.set(0)
    }

    data class Snapshot(
        val hits: Long,
        val misses: Long,
        val staleFallbacks: Long,
        val prefetchScheduled: Long,
        val prefetchCompleted: Long,
        val prefetchCancelled: Long,
        val evictions: Long
    )

    fun snapshot(): Snapshot = Snapshot(
        hits = hits,
        misses = misses,
        staleFallbacks = staleFallbacks,
        prefetchScheduled = prefetchScheduled,
        prefetchCompleted = prefetchCompleted,
        prefetchCancelled = prefetchCancelled,
        evictions = evictions
    )
}
