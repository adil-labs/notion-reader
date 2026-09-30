package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren

/**
 * Process-wide single-flight coordinator for document loads.
 *
 * Ensures that concurrent requests for the same page (e.g. from foreground playback,
 * Note screen display, queue prefetch, and recent-notes prefetch) share the same underlying
 * load operation rather than issuing duplicate network requests and redundant block downloads.
 *
 * Distinct callers awaiting the same document are isolated from each other's cancellation:
 * if a prefetch coroutine is cancelled when the user starts playback, the leader load
 * coroutine continues running in this coordinator's [scope], allowing foreground playback
 * to join or await the in-flight result and write the normalized document to cache.
 */
class DocumentLoadCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private data class InFlightLoad(
        val deferred: Deferred<ReaderDocument>,
        val isForceRefresh: Boolean
    )

    private val inFlight = ConcurrentHashMap<String, InFlightLoad>()

    /**
     * Executes or joins an in-flight load for [pageId].
     *
     * If [forceRefresh] is true, any existing non-force-refresh load is superseded so the caller
     * is guaranteed a fresh fetch. Otherwise, any active load is joined.
     */
    suspend fun load(
        pageId: String,
        forceRefresh: Boolean = false,
        loader: suspend () -> ReaderDocument
    ): ReaderDocument {
        val canonicalId = canonicalizePageId(pageId)
        val target = getOrStart(canonicalId, forceRefresh, loader)
        return target.deferred.await()
    }

    private fun getOrStart(
        canonicalId: String,
        forceRefresh: Boolean,
        loader: suspend () -> ReaderDocument
    ): InFlightLoad {
        var startedDeferred: Deferred<ReaderDocument>? = null
        val targetEntry = inFlight.compute(canonicalId) { _, current ->
            if (current != null && current.deferred.isActive) {
                // If the existing load satisfies the request (non-force or already a force-refresh), join it
                if (!forceRefresh || current.isForceRefresh) {
                    return@compute current
                }
            }
            val newDeferred = scope.async {
                loader()
            }
            startedDeferred = newDeferred
            InFlightLoad(newDeferred, forceRefresh)
        }!!

        startedDeferred?.let { d ->
            d.invokeOnCompletion {
                inFlight.remove(canonicalId, targetEntry)
            }
        }

        return targetEntry
    }

    /** Number of currently active loads. Useful for diagnostics and test assertions. */
    val inFlightCount: Int
        get() = inFlight.values.count { it.deferred.isActive }

    /** Cancels all in-flight jobs and clears state. */
    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
        inFlight.clear()
    }

    companion object {
        private val NOTION_ID_REGEX = Regex(
            "[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}"
        )

        /**
         * Normalizes page identifiers, URLs, or hex strings to a dash-free canonical lowercase key.
         */
        fun canonicalizePageId(raw: String): String {
            val trimmed = raw.trim()
                .trim('"', '\'')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore('&')

            val match = NOTION_ID_REGEX.findAll(trimmed).lastOrNull()?.value
            return (match ?: trimmed).lowercase().replace("-", "")
        }
    }
}
