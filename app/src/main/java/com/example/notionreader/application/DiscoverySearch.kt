package com.example.notionreader.application

import com.example.notionreader.domain.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Orchestrates semantic queries (free-text search and related notes).
 *
 * Concurrency, epoch checks, and background dispatch strictly respect [workDispatcher].
 */
internal class DiscoverySearch(
    private val runtime: DiscoveryRuntime,
    private val retrieval: DiscoveryRetrieval,
    private val mutable: MutableStateFlow<DiscoveryState>,
    private val workDispatcher: CoroutineDispatcher,
    private val modelAvailable: () -> Boolean,
    private val recommendationThreshold: Float,
    private val recordTiming: (Map<String, Long>) -> Unit
) {
    private val queries = DiscoveryRequests(runtime.validity)
    private val related = DiscoveryRequests(runtime.validity)

    fun reset() {
        queries.reset()
        related.reset()
    }

    suspend fun search(text: String, filters: BrowseQuery) {
        val started = System.nanoTime()
        val request = queries.begin()
        queries.publish(mutable, request) { it.copy(query = text, filters = filters, results = emptyList(), searching = true, message = null,
            searchFailure = null, searchCompleted = false) }
        try {
            withContext(workDispatcher) { searchInBackground(text, filters, request, started) }
        } finally {
            queries.finish(mutable, request) { it.copy(searching = false) }
        }
    }

    private suspend fun searchInBackground(text: String, filters: BrowseQuery, request: DiscoveryRequests.Ticket, started: Long) {
        if (!queries.isCurrent(request)) return
        var waitNs = 0L
        var embeddingNs = 0L
        var retrievalNs = 0L
        var completed = 0L
        try {
            if (text.isBlank()) {
                queries.publish(mutable, request) { it.copy(results = emptyList()) }
                return
            }
            if (!modelAvailable()) {
                queries.publish(mutable, request) {
                    it.copy(results = emptyList(), message = "Download the Discovery model to search cached Notes.")
                }
                return
            }
            runtime.foregroundQueries.incrementAndGet()
            val results = try {
                runtime.bulkGate.withLock {
                    val vector = runtime.inference.withLock {
                        waitNs = System.nanoTime() - started
                        val embeddingStarted = System.nanoTime()
                        runtime.embedExactInput(text).also { embeddingNs = System.nanoTime() - embeddingStarted }
                    }
                    val retrievalStarted = System.nanoTime()
                    retrieval.retrieve(vector, filters, 20).also { retrievalNs = System.nanoTime() - retrievalStarted }
                }
            } finally {
                runtime.foregroundQueries.decrementAndGet()
            }
            queries.publish(mutable, request) { it.copy(results = results, searchCompleted = true) }
            if (queries.isCurrent(request)) completed = 1L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            queries.publish(mutable, request) {
                it.copy(message = "Semantic search failed. Try again or rebuild the index.",
                    searchFailure = "Semantic search failed. Try again or rebuild the index.")
            }
        } finally {
            recordTiming(mapOf(
                "searchTotalNs" to System.nanoTime() - started,
                "waitNs" to waitNs,
                "embeddingNs" to embeddingNs,
                "retrievalNs" to retrievalNs,
                "completed" to completed
            ))
        }
    }

    suspend fun relatedNotes(pageId: String) {
        val request = related.begin()
        related.publish(mutable, request) {
            it.copy(relatedPageId = canonicalSemanticId(pageId), related = emptyList(), message = "Finding related Notes…")
        }
        withContext(workDispatcher) {
            if (!related.isCurrent(request)) return@withContext
            try {
                val vector = retrieval.seedVector(pageId)
                val matches = vector?.let { retrieval.retrieve(it, BrowseQuery.Default, 5, pageId, recommendationThreshold) }.orEmpty()
                related.publish(mutable, request) {
                    it.copy(related = matches,
                        message = if (vector == null) "This Note must be cached and indexed before related Notes are available." else null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                related.publish(mutable, request) {
                    it.copy(related = emptyList(), message = "Related Notes are unavailable. Rebuild the index to retry.")
                }
            }
        }
    }
}
