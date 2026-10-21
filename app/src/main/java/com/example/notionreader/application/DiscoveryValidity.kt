package com.example.notionreader.application

import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.canonicalSemanticId
import com.example.notionreader.domain.discovery.semanticRevision
import java.util.concurrent.atomic.AtomicLong

internal data class DiscoveryReadToken(val reset: Long, val cacheRevision: Long)

/** One process-local reset epoch and cache-bound eligibility shared by every Discovery reader. */
internal class DiscoveryValidity(private val cache: DocumentCache) {
    val generation = AtomicLong()
    @Volatile private var eligibility: Pair<Long, Map<String, String>>? = null

    fun capture(): DiscoveryReadToken = DiscoveryReadToken(generation.get(), cache.revision.value)

    fun isCurrent(token: DiscoveryReadToken): Boolean =
        token.reset == generation.get() && token.cacheRevision == cache.revision.value

    fun remember(revision: Long, sources: Map<String, String>) {
        if (cache.revision.value == revision) eligibility = revision to sources
    }

    suspend fun eligibleRevisions(): Map<String, String> {
        val revision = cache.revision.value
        eligibility?.takeIf { it.first == revision }?.let { return it.second }
        val sources = cache.snapshot().associate {
            canonicalSemanticId(it.document.pageId) to semanticRevision(it.document)
        }
        remember(revision, sources)
        return sources
    }
}
