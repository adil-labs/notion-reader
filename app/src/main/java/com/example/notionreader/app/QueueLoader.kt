package com.example.notionreader.app

import com.example.notionreader.reader.LoadReaderDocument
import com.example.notionreader.reader.ReaderSession
import com.example.notionreader.reader.asReaderLoadException
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
 * [com.example.notionreader.reader.QueueState.awaiting] stays true, which also means a test can drive one
 * transition at a time and assert on the state between them.
 *
 * ## The stale-result rule, again
 *
 * A load that completes after the session moved on is dropped, not applied. Two guards do it and neither
 * is redundant: this class checks [com.example.notionreader.reader.QueueState.awaiting] before starting,
 * and [ReaderSession.supplyDocument] checks it again before accepting. The window between them is a
 * suspension point, which is exactly where a session gets replaced — leaving by pressing Play from
 * another Note, for instance.
 */
class QueueLoader(
    private val session: ReaderSession,
    private val loadNote: LoadReaderDocument
) {

    /**
     * Loads the Note the queue is waiting for and hands it to the session.
     *
     * Returns true when a load was attempted, whether it succeeded or failed, so a caller looping on
     * "is anything awaited?" makes progress instead of spinning: a failed load clears `awaiting` and sets
     * a failure, so the loop stops on its own and waits for the user's Retry.
     *
     * Cancellation propagates rather than being reported as a failure. Leaving a screen is not a note
     * that could not be loaded, and the session must not be left stalled by a navigation.
     */
    suspend fun loadPendingQueueNote(): Boolean {
        val queueState = session.queueState.value
        if (queueState.queue == null || !queueState.awaiting) return false

        val item = queueState.currentItem ?: return false

        return try {
            loadNote(item.pageId).fold(
                onSuccess = { document ->
                    session.supplyDocument(document)
                    true
                },
                onFailure = { failure ->
                    session.failCurrentNote(failure.asReaderLoadException())
                    true
                }
            )
        } catch (e: CancellationException) {
            throw e
        }
    }
}
