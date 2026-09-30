package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.DocumentLoadPurpose
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
 * Prioritizes foreground playback and interactive requests over speculative background prefetching:
 * when a foreground load starts, unrelated background prefetch I/O is cancelled immediately to free
 * bandwidth and avoid saturating Notion API limits.
 */
class DocumentLoadCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private data class InFlightLoad(
        val deferred: Deferred<ReaderDocument>,
        var purpose: DocumentLoadPurpose,
        val isForceRefresh: Boolean
    )

    private val inFlight = ConcurrentHashMap<String, InFlightLoad>()

    /**
     * Executes or joins an in-flight load for [pageId].
     *
     * If [purpose] is [DocumentLoadPurpose.PLAYBACK] or [DocumentLoadPurpose.INTERACTIVE], any unrelated
     * in-flight [DocumentLoadPurpose.PREFETCH] operations are cancelled immediately.
     *
     * If an existing in-flight load for the same page was [DocumentLoadPurpose.PREFETCH] and this caller is
     * foreground ([DocumentLoadPurpose.PLAYBACK] or [DocumentLoadPurpose.INTERACTIVE]), its purpose is
     * promoted so it joins the single-flight and is protected against future prefetch cancellations.
     *
     * If [forceRefresh] is true, any existing non-force-refresh load is superseded so the caller
     * is guaranteed a fresh fetch. Otherwise, any active load is joined.
     */
    suspend fun load(
        pageId: String,
        purpose: DocumentLoadPurpose = DocumentLoadPurpose.PLAYBACK,
        forceRefresh: Boolean = false,
        loader: suspend () -> ReaderDocument
    ): ReaderDocument {
        val canonicalId = canonicalizePageId(pageId)

        // Preempt any unrelated background prefetch loads if this request is foreground
        if (purpose != DocumentLoadPurpose.PREFETCH) {
            cancelPrefetchLoadsExcept(canonicalId)
        }

        val target = getOrStart(canonicalId, purpose, forceRefresh, loader)
        return target.deferred.await()
    }

    /**
     * Cancels any in-flight background prefetch loads whose canonical pageId does not match [exceptCanonicalId].
     */
    fun cancelPrefetchLoadsExcept(exceptCanonicalId: String? = null) {
        val iterator = inFlight.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != exceptCanonicalId && entry.value.purpose == DocumentLoadPurpose.PREFETCH) {
                entry.value.deferred.cancel()
                iterator.remove()
            }
        }
    }

    private fun getOrStart(
        canonicalId: String,
        purpose: DocumentLoadPurpose,
        forceRefresh: Boolean,
        loader: suspend () -> ReaderDocument
    ): InFlightLoad {
        var startedDeferred: Deferred<ReaderDocument>? = null
        val targetEntry = inFlight.compute(canonicalId) { _, current ->
            if (current != null && current.deferred.isActive) {
                // If the existing load was PREFETCH, promote to the higher-priority purpose
                if (current.purpose == DocumentLoadPurpose.PREFETCH && purpose != DocumentLoadPurpose.PREFETCH) {
                    current.purpose = purpose
                }

                // If the existing load satisfies the request (non-force or already a force-refresh), join it
                if (!forceRefresh || current.isForceRefresh) {
                    return@compute current
                }
            }
            val newDeferred = scope.async {
                loader()
            }
            startedDeferred = newDeferred
            InFlightLoad(newDeferred, purpose, forceRefresh)
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

    /** Number of currently active prefetch loads. */
    val prefetchInFlightCount: Int
        get() = inFlight.values.count { it.deferred.isActive && it.purpose == DocumentLoadPurpose.PREFETCH }

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
