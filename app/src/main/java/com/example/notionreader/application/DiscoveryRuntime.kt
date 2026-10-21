package com.example.notionreader.application

import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.EmbeddingProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** Shared execution resources; collaborators never create competing locks or inference caches. */
internal class DiscoveryRuntime(cache: DocumentCache, private val provider: EmbeddingProvider) {
    val validity = DiscoveryValidity(cache)
    val generation get() = validity.generation
    val embeddingMemo = ExactEmbeddingMemo()
    val foregroundQueries = AtomicInteger()
    val inference = Mutex()
    val bulkGate = Mutex()
    val commits = Mutex()

    suspend fun embedExactInput(text: String): List<Float> {
        embeddingMemo.get(provider.identity, text)?.let { return it }
        val token = embeddingMemo.token()
        return provider.embed(text).also { embeddingMemo.put(provider.identity, text, it, token) }
    }

    suspend fun <T> bulkStep(action: suspend () -> T): T {
        while (true) {
            while (foregroundQueries.get() > 0) delay(10)
            bulkGate.lock()
            if (foregroundQueries.get() == 0) {
                try { return action() } finally { bulkGate.unlock() }
            }
            bulkGate.unlock()
        }
    }
}
