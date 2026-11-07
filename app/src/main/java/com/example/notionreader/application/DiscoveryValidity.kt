package com.example.notionreader.application

import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.canonicalSemanticId
import com.example.notionreader.domain.discovery.semanticRevision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal data class DiscoveryReadToken(
    val reset: Long,
    val cacheRevision: Long,
    val invalidationGeneration: Long = 0L
)

/** A caller must retry from a new source generation; stale eligibility is never returned. */
internal class DiscoveryEligibilityChanged : IllegalStateException("Discovery sources changed during eligibility read")

/** One process-local reset epoch and cache-bound eligibility shared by every Discovery reader. */
internal class DiscoveryValidity(private val cache: DocumentCache) {
    val generation = AtomicLong()
    val invalidationGeneration = AtomicLong()

    private data class CachedEligibility(
        val reset: Long,
        val invalidationGen: Long,
        val cacheRevision: Long,
        val sources: Map<String, String>
    )

    @Volatile private var eligibility: CachedEligibility? = null
    private val eligibilityMutex = Mutex()

    fun capture(): DiscoveryReadToken = DiscoveryReadToken(
        reset = generation.get(),
        cacheRevision = cache.revision.value,
        invalidationGeneration = invalidationGeneration.get()
    )

    fun isCurrent(token: DiscoveryReadToken): Boolean =
        token.reset == generation.get() &&
            token.cacheRevision == cache.revision.value &&
            token.invalidationGeneration == invalidationGeneration.get()

    fun remember(token: DiscoveryReadToken, sources: Map<String, String>) {
        if (isCurrent(token)) eligibility = CachedEligibility(token.reset, token.invalidationGeneration, token.cacheRevision, sources.toMap())
    }

    fun invalidate() {
        invalidationGeneration.incrementAndGet()
        eligibility = null
    }

    suspend fun eligibleRevisions(): Map<String, String> {
        fun cached(token: DiscoveryReadToken): Map<String, String>? = eligibility?.takeIf {
            it.reset == token.reset && it.invalidationGen == token.invalidationGeneration && it.cacheRevision == token.cacheRevision
        }?.sources?.takeIf { isCurrent(token) }
        val token = capture()
        cached(token)?.let { return it }
        return eligibilityMutex.withLock {
            val current = capture()
            cached(current)?.let { return@withLock it }
            val sources = cache.snapshot().associate {
                canonicalSemanticId(it.document.pageId) to semanticRevision(it.document)
            }
            if (!isCurrent(current)) throw DiscoveryEligibilityChanged()
            remember(current, sources)
            if (!isCurrent(current)) throw DiscoveryEligibilityChanged()
            sources
        }
    }
}
