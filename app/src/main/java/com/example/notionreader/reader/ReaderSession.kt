package com.example.notionreader.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The listening session: what is being read aloud, out of what queue, and where in it.
 *
 * This type exists to be the **owner** of playback, so that playback is not owned by a *screen*. That
 * distinction is the whole point of the UI contract's "playback may outlive the Player screen" and
 * "navigation does not own playback": a session that belongs to the Player dies when the Player is
 * left, and a session that belongs to the application survives navigation — which is what makes the
 * mini-player expressible later without a second playback engine.
 *
 * The concrete coupling this removes: [ReaderEngineImpl.setDocument] stops speech and resets the
 * position, so calling it is *ending a listening session*. While the engine was held directly by the
 * Activity and its document doubled as the Note screen's content, loading a Note to read it was also
 * how the app started playback — one call, two unrelated meanings. Now [start] is the only path from a
 * Note into playback, and it is a deliberate one that a caller has to name.
 *
 * ## Queue-aware, and scope-free
 *
 * A session reads an [ActiveQueue]: an ordered, frozen list of [QueuedNote] identities with an origin.
 * Single-Note playback is a queue of one, which is what the product contract says it is.
 *
 * Content is loaded **lazily**, one Note at a time, and this class does not do the loading. It reports
 * that it is waiting for the current Note's document ([QueueState.awaiting]), and the caller fetches it
 * and hands it in via [supplyDocument]. Two consequences, both deliberate:
 *
 * - **Cancellation, retry and the error taxonomy stay out of the playback state machine.** They belong
 *   to the layer that performs I/O, and the taxonomy's `canRetry` is a UI decision, not a playback one.
 * - **This class needs no coroutine scope and no `suspend` functions**, so its whole state machine —
 *   including the between-Notes transition, which is the part most likely to be wrong — is tested
 *   synchronously, with no dispatcher and no waiting.
 *
 * The cost is that "advance the queue" is two steps in two places. That is intentional: the step that
 * cannot fail is here, and the step that can is where failure is handled.
 *
 * ## Deliberately thin
 *
 * No persistence. The integration spec explicitly refuses to own session persistence (see
 * `docs/decisions.md` §6, C1), so retention, resume and the listening history wait for the Active Queue
 * & Reader Session specification. This class holds a session for as long as the process lives.
 */
class ReaderSession(val engine: ReaderEngine) {

    /** The state the Player renders for the *document*: position, section, playing/paused. */
    val state: StateFlow<ReaderState> = engine.state

    /** The document this session is reading — *not* necessarily the document the Note screen shows. */
    val document: StateFlow<ReaderDocument> = engine.document

    private val _queueState = MutableStateFlow(QueueState())

    /** Where the session is in its queue, and whether it can proceed. */
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    val isPlaying: Boolean
        get() = state.value.isPlaying

    /** Whether the session is in a state where playback can start or resume. */
    val canPlay: Boolean
        get() = _queueState.value.let {
            it.queue != null && !it.awaiting && !it.isStalled && !it.completed
        }

    /**
     * Begins a new single-Note session that plays [document] from its first chunk.
     *
     * The contract's Play from the Note screen is "creates a new Active Queue containing only this
     * Note; starts from the beginning", so this starts a *new* session rather than resuming one: a
     * queued session is replaced, and any historical position on that Note is not resumed.
     *
     * The caller passes a document rather than a page id because the session must receive a
     * **snapshot**. The product contract's queue snapshot contract requires that "once the Active Queue
     * exists ... its membership and order remain fixed for the lifetime of that listening session", and
     * the source of the snapshot may change afterwards — the Note screen can load a different Note while
     * this one is playing. Holding the value the caller hands over also keeps this class free of any
     * loading concern.
     */
    fun start(document: ReaderDocument) {
        _queueState.value = QueueState(queue = ActiveQueue.of(document))

        // The index is given to setDocument rather than relying on its default, so "from the beginning"
        // is stated where it is meant instead of inherited from an unrelated default. This uses
        // setDocument + play rather than the engine's playFromStart: that operation restarts the
        // document already loaded, and here the document itself is being replaced. playFromStart keeps
        // its meaning for a future "restart this note" control.
        engine.setDocument(document, startParagraphIndex = 0)
        engine.play()
    }

    /**
     * Begins a new session over [queue], starting by fetching its first Note.
     *
     * Nothing is playing yet: the first Note's document is not known, so this leaves the session
     * [QueueState.awaiting] and the caller supplies the document. Every later Note takes the same path,
     * so the first transition is not a special case — which is the commonest place for a bug to hide.
     *
     * Any current playback is stopped, because "Play starts a new listening session" and replacing one
     * session with another is not a resume.
     */
    fun start(queue: ActiveQueue) {
        if (isPlaying) engine.pause()
        _queueState.value = QueueState(queue = queue, index = 0, awaiting = true)
    }

    /**
     * Hands the session the document it was waiting for.
     *
     * Ignored when nothing is awaited, so a load that finishes after the session moved on cannot pull the
     * queue backwards. That is the same stale-result problem the engine solves with its utterance token,
     * and the same answer: a result that no longer has a claimant is dropped rather than applied.
     */
    fun supplyDocument(document: ReaderDocument) {
        val current = _queueState.value
        if (!current.awaiting) return

        engine.setDocument(document, startParagraphIndex = 0)

        // A queue of one is the Note screen's Play, where the title is already on screen and the listener
        // chose it a moment ago; announcing it there would be repetition. At a transition it is the only
        // thing that tells the listener the Note changed.
        if (current.index > 0) {
            engine.announceThenPlay(current.currentItem?.title ?: document.title)
        } else {
            engine.play()
        }

        _queueState.value = current.copy(awaiting = false, failure = null)
    }

    /**
     * The current Note finished being read.
     *
     * Advances to the next Note, or completes the queue. No engine call is made in either case, and that
     * is correct rather than an omission: the engine stops itself at the end of a document, so reaching
     * the end has already left nothing playing.
     *
     * Ignored when the queue is not on its current Note — awaiting a document, stalled on a failure, or
     * already completed. The signal arrives from an observer of the engine's state, and an observer that
     * re-fires after a transition must not be able to advance the queue twice.
     */
    fun onCurrentNoteFinished() {
        val current = _queueState.value
        if (current.queue == null) return
        if (current.awaiting || current.isStalled || current.completed) return

        _queueState.value = if (current.hasNext) {
            current.copy(index = current.index + 1, awaiting = true)
        } else {
            // "At the final Note: announce that the queue has ended; stop automatic progression; mark
            // the session completed". Marked here as a fact. What a completed session is *retained* for
            // belongs to the Reader Session specification, which does not exist, so nothing is invented.
            current.copy(completed = true)
        }
    }

    /**
     * The current Note's document could not be loaded.
     *
     * **The queue stalls** — the position is kept and nothing advances. This is the product decision
     * recorded in `docs/decisions.md` §14.3 (B3); neither contract stated it. Skipping the Note would
     * silently drop something the user asked to hear, which is the audible form of the mutation the queue
     * snapshot rule exists to prevent. Aborting the queue would discard Notes that are still perfectly
     * playable, for a failure the error taxonomy already classifies as often transient. Stalling keeps
     * the user's options: Retry, or leave.
     */
    fun failCurrentNote(failure: ReaderLoadException) {
        val current = _queueState.value
        if (current.queue == null) return

        _queueState.value = current.copy(awaiting = false, failure = failure)
    }

    /** Clears a stalled Note's failure and asks for its document again. */
    fun retryCurrentNote() {
        val current = _queueState.value
        if (!current.isStalled) return

        _queueState.value = current.copy(failure = null, awaiting = true)
    }

    /**
     * Resumes from the current position.
     *
     * A no-op while the queue is waiting for a document, stalled, or finished. Without that guard, Play
     * would call into the engine and replay the Note that just ended, because the engine is still holding
     * it — the queue would appear to jump backwards.
     */
    fun play() {
        if (!canPlay) return
        engine.play()
    }

    /** Stops the utterance and keeps the position. Android TTS has no real pause. */
    fun pause() = engine.pause()

    fun nextSection() = engine.nextSection()

    fun previousSection() = engine.previousSection()

    /** Releases speech resources. The session must not be used afterwards. */
    fun shutdown() = engine.shutdown()
}
