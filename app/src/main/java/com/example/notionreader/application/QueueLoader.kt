package com.example.notionreader.application

import com.example.notionreader.application.LoadReaderDocument
import com.example.notionreader.application.ReaderSession
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives a queue's lazy loading: the half of the queue transition that performs I/O.
 *
 * ## Why this is separate from [ReaderSession]
 *
 * The session holds the queue, the position and the playback state, and it is deliberately synchronous:
 * it cannot fetch anything, cannot fail, and needs no coroutine scope. This class is the opposite — its
 * only job is I/O, so it is the only place cancellation, retry and the error taxonomy appear in the queue
 * path.
 *
 * That split is what keeps "advance to the next Note" honest. The state machine decides *what should
 * happen*; this decides *whether it can*. A failure is reported back into the state machine as a fact
 * ([ReaderSession.failCurrentNote]) rather than thrown through it, so a Note that will not load leaves a
 * session that is stalled and intact rather than a session in a half-advanced state.
 *
 * ## One step per call
 *
 * [loadPendingQueueNote] performs exactly one Note's load and reports whether it did anything. It is not
 * a loop, because a loop would need to decide when to stop when a Note fails, and that decision is
 * [ReaderSession]'s (stall) rather than this class's. The caller re-invokes it while
 * [com.example.notionreader.domain.queue.QueueState.awaiting] stays true, which also means a test can drive one
 * transition at a time and assert on the state between them.
 *
 * ## The stale-result rule
 *
 * A load that completes after the session moved on is dropped, not applied. Two guards do it and neither
 * is redundant: this class checks [com.example.notionreader.domain.queue.QueueState.awaiting] before starting,
 * and [ReaderSession.supplyDocument] checks **which** Note is awaited before accepting. The window
 * between them is a suspension point, which is exactly where a session gets replaced — leaving by
 * pressing `Play all` again, for instance — and at that point "a Note is awaited" is still true while
 * "this Note is awaited" has stopped being true. It is the second question that decides.
 */
class QueueLoader(
    private val session: ReaderSession,
    private val loadNote: LoadReaderDocument
) {

    /**
     * The request whose load is currently in flight, or null.
     *
     * ## Why the session already knowing "a Note is awaited" is not enough
     *
     * Because two callers ask. Continue restores a session *and* the queue observer sees the same state
     * change, so `loadPendingQueueNote` is reached twice for one Note. Both calls used to load it, which
     * cost a duplicate fetch of a page that is several HTTP requests — and, worse, left an inconsistent
     * state if exactly one of the two failed: the queue would stall on a Note that had in fact arrived,
     * because `failCurrentNote` cannot tell a failure from a request that is still running.
     *
     * Guarding on the *request* rather than on "something is in flight" is what keeps superseding
     * immediate. A new `Play all`, or a Continue on a different session, is a different request, so it
     * starts its load at once instead of queueing behind the one it replaced — whose result the session
     * will refuse anyway, because it is no longer the Note being awaited.
     */
    private var inFlight: PendingNoteRequest? = null

    /**
     * Loads the Note the queue is waiting for and hands it to the session.
     *
     * Returns true when a load was attempted, whether it succeeded or failed, so a caller looping on
     * "is anything awaited?" makes progress instead of spinning: a failed load clears `awaiting` and sets
     * a failure, so the loop stops on its own and waits for the user's Retry. It returns false when there
     * is nothing to do *or* when this exact request is already being fetched.
     *
     * Cancellation propagates rather than being reported as a failure. Leaving a screen is not a note
     * that could not be loaded, and the session must not be left stalled by a navigation.
     */
    suspend fun loadPendingQueueNote(): Boolean {
        val request = session.pendingRequest ?: return false
        if (inFlight == request) return false

        inFlight = request

        return try {
            loadNote(request.pageId).fold(
                onSuccess = { document ->
                    // A Note the reader cannot speak is reported exactly like one that did not load, so
                    // the listener is told and the queue keeps its position. See [unplayable].
                    if (document.chunkTexts.isEmpty()) {
                        session.failCurrentNote(request.pageId, unplayableNote())
                    } else {
                        session.supplyDocument(document)
                    }
                    true
                },
                onFailure = { failure ->
                    // The page id travels with the failure, so a load for a session the listener has
                    // already replaced cannot stall the one they replaced it with.
                    session.failCurrentNote(request.pageId, failure.asReaderLoadException())
                    true
                }
            )
        } catch (e: CancellationException) {
            throw e
        } finally {
            // Only if this is still our marker: a superseding load may have taken it over, and clearing
            // it here would then let a third call duplicate *that* one.
            if (inFlight == request) inFlight = null
        }
    }

    /**
     * The failure for a Note that loaded and has nothing to read.
     *
     * The integration deliberately treats a page with **no blocks** as a valid empty document rather than
     * a failure — only a page that *has* content yielding no chunk is an error — and that is right: the
     * document is a faithful description of the page.
     *
     * It is not, however, a *playable* document, and the difference matters here because a queue advances
     * on a completion signal an empty document never produces. `ReaderEngineImpl.play()` returns
     * immediately when there is nothing to speak, so `hasReachedEnd` never becomes true,
     * `onCurrentNoteFinished` is never called, and the session would sit on that Note for the life of the
     * process: an enabled Play button that does nothing, no message, and no way past it.
     *
     * A titled but empty Notion page is enough to reach this, and Browse will happily list one, so
     * `Play all` over a result set containing it would end there. Reporting it as a failed load is the
     * smallest correction that removes the dead end: the listener is told what happened, the queue keeps
     * its position, and the Note is not silently skipped — the same choice §14.3 (B3) already made for a
     * Note that will not load at all.
     */
    private fun unplayableNote() = ReaderLoadException(
        category = ReaderErrorCategory.UNSUPPORTED_DOCUMENT_STRUCTURE,
        diagnostic = "loaded document produced no speakable chunks"
    )
}
