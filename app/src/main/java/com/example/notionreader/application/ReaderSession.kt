package com.example.notionreader.application

import com.example.notionreader.domain.positionAt
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderDocumentPosition
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.session.ResumeDecision
import com.example.notionreader.domain.session.ResumePolicy
import com.example.notionreader.domain.session.StoredSession
import com.example.notionreader.player.ReaderEngine
import com.example.notionreader.player.ReaderSectionSpan
import com.example.notionreader.player.ReaderState
import java.util.UUID
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * No persistence *inside* this class. The Active Queue & Reader Session specification owns persistence,
 * and it puts it here in a specific way: this class exposes itself as a [StoredSession] and can adopt
 * one, and it never reads or writes storage. Two consequences, both deliberate. Its whole state machine —
 * including the restore path, which is the part most likely to be wrong — stays synchronous and testable
 * with no dispatcher and no waiting. And a failed write cannot reach playback, because nothing on the
 * playback path performs one.
 */
class ReaderSession(val engine: ReaderEngine) {

    /**
     * This session's identity, assigned when it starts and adopted when it is restored.
     *
     * Sessions are identified by this, never by Note or title: the Active Queue & Reader Session spec
     * requires that "two sessions that read the same Note are two entries, because they are two listening
     * occurrences." A new id on every [start] is what makes that true — returning to the same Note through
     * Play creates a second session rather than overwriting the first one's history.
     */
    var id: String? = null
        private set

    /**
     * The position this session was restored to, until the current Note's document arrives.
     *
     * Held rather than applied because applying it needs the document, which this class does not fetch.
     * It survives a failed load on purpose: `retryCurrentNote` re-asks for the same Note, and a retry that
     * dropped the saved position would silently resume at the beginning of a Note the listener was
     * halfway through.
     */
    private var pendingPosition: ReaderDocumentPosition? = null

    /**
     * Whether the current Note is being resumed by Continue rather than advanced into by the queue.
     *
     * The distinction is audible, not cosmetic. Advancing into a Note announces its title, because that
     * is the only thing that tells the listener the Note changed. Continuing into one does not, because
     * the listener chose it a moment ago and the resume decision has already said anything that needed
     * saying.
     */
    private var restoring: Boolean = false

    /**
     * Which request for a Note the queue is on. See [pendingRequest].
     *
     * Held in [QueueState.requestToken] rather than as a field here, so that a superseding request is
     * *observable*. As a private field it was invisible: an observer asking "is a Note awaited?" saw the
     * same answer before and after a transition that replaced an in-flight request, so nothing fetched the
     * new one and the queue waited forever for a request no one had made. The token is bumped by every
     * transition that issues a request — starting, restoring, advancing, retrying — and deliberately *not*
     * by supplying a document or recording a failure, because those answer a request rather than make one.
     */
    private val requestToken: Int get() = _queueState.value.requestToken

    /**
     * Whether playback should begin once the awaited Note's document arrives.
     *
     * ## Why this has to be remembered
     *
     * A queue transition always pauses the engine before fetching, because the Note that is about to be
     * replaced must stop speaking immediately. What happens *after* the fetch is a different question, and
     * answering it with "always play" makes the Reader's Note controls own playback — pressing **Next
     * note** while paused would start speaking the following Note, which is the one thing the listener had
     * just said they did not want.
     *
     * It is not a hypothetical: it contradicts this project's own established behaviour. Moving a section
     * preserves the playing state (the engine's `move` re-speaks only when the state says it is playing),
     * and the README documents it — "while paused they only move the position". This is the same rule for
     * Notes, and the same rule the product contract states as "navigation does not own playback".
     *
     * Automatic progression is unaffected: it happens because a Note finished *while playing*, so the
     * recorded intent is true and the next Note starts speaking, which is what hands-free reading means.
     *
     * ## The invariant, because it is the kind a later edit breaks silently
     *
     * **Every path that sets `awaiting = true` also sets this.** There are six — [start] for a document,
     * [start] for a queue, [restore], [replay] (through [start]), [onCurrentNoteFinished],
     * [retryCurrentNote] and [beginManualTransition] — and a seventh that forgot to would inherit whatever
     * the previous transition happened to record. That is not theoretical: [onCurrentNoteFinished] needed
     * the assignment added *because* the fix above made a stale `false` reachable, and the bug it would
     * have caused (hands-free reading stopping dead after a paused manual move) is invisible until a
     * listener presses Play and hears nothing follow.
     */
    private var playWhenSupplied: Boolean = true

    private val _lastResume = MutableStateFlow<ResumeDecision?>(null)

    /**
     * How precisely the last restore located its saved position, or null when nothing has been restored.
     *
     * Exposed because the Active Queue & Reader Session spec requires the match quality to be "recorded as
     * a fact" on an inexact restore, and because without it an exact resume and a fallback to the start of
     * a section are indistinguishable from outside: both produce audio, at a position near the right one,
     * and only one of them is what the listener asked for. A field here rather than a log line, because
     * this class has no logger and should not acquire one — recording is the caller's business, and a value
     * can be asserted in a test.
     */
    val lastResume: StateFlow<ResumeDecision?> = _lastResume.asStateFlow()

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
        id = newSessionId()
        restoring = false
        pendingPosition = null
        playWhenSupplied = true
        _queueState.value = QueueState(queue = ActiveQueue.of(document), requestToken = requestToken + 1)

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
        id = newSessionId()
        restoring = false
        pendingPosition = null
        // A new session begins by playing: the contract's Play starts reading, and `Play all` is Play over
        // many Notes rather than a different verb.
        playWhenSupplied = true
        _queueState.value = QueueState(
            queue = queue,
            index = 0,
            awaiting = true,
            requestToken = requestToken + 1
        )
    }

    /**
     * Adopts [snapshot] as this session: its identity, its queue, its current Note and its saved position.
     *
     * Nothing is fetched and nothing plays until the caller supplies the current Note's document, so a
     * restored session is `awaiting` — the same state the queue is in mid-advance, which is why restore is
     * not a separate code path from ordinary loading.
     *
     * **A completed session is refused.** Completed sessions are history, not resumable state: the product
     * contract's Continue "resumes an existing listening session", and a session whose queue has ended has
     * no position to resume. Replaying one is [replay], which deliberately creates a *new* session. The
     * guard is here rather than only at the call site so that the difference cannot be lost by a caller
     * forgetting it.
     */
    fun restore(snapshot: StoredSession) {
        if (!snapshot.isValid || snapshot.completed) return
        if (isPlaying) engine.pause()

        val queue = ActiveQueue.snapshotOf(snapshot.notes, snapshot.origin)

        id = snapshot.id
        restoring = true
        pendingPosition = snapshot.position
        // Continue is the spec's "restores the snapshot and the position, opens the Player, and begins
        // playback", so a restore resumes speaking rather than waiting to be told to.
        playWhenSupplied = true

        _queueState.value = QueueState(
            queue = queue,
            index = snapshot.index.coerceIn(0, queue.size - 1),
            awaiting = true,
            requestToken = requestToken + 1
        )
    }

    /**
     * Replays a historical session: the same Notes in the same order, from the beginning, as a new session.
     *
     * This is what activating a completed session does. The historical record is left exactly as it was,
     * which is why this is [start] rather than [restore] — the spec requires a new session with a new
     * identity, and reusing the old one would overwrite the history entry the listener was looking at.
     * The origin is carried over and remains descriptive: nothing is re-evaluated, so the same `Smart`
     * origin will not produce a different set of Notes than it did the first time.
     */
    fun replay(snapshot: StoredSession) {
        if (!snapshot.isValid) return
        start(ActiveQueue.snapshotOf(snapshot.notes, snapshot.origin))
    }

    /**
     * The current structural position, in stable identities, or null when there is nothing to record.
     *
     * Captured from the document the engine holds, which is the only moment the conversion from a
     * transient chunk index to stable identities can be made. Null in two situations that are genuinely
     * different from "position unknown": while the current Note is being fetched there is no document to
     * capture from, and while a load has failed the engine still holds the *previous* Note's document —
     * capturing from that would record a position in a document the session is no longer reading.
     */
    val currentPosition: ReaderDocumentPosition?
        get() {
            pendingPosition?.let { return it }

            val current = _queueState.value
            if (current.queue == null || current.awaiting || current.isStalled) return null

            return engine.document.value.positionAt(state.value.currentParagraphIndex)
        }

    /**
     * The Note the queue is currently waiting for, or null when it is waiting for nothing.
     *
     * ## Why a page id is not enough to say *which* request this is
     *
     * A queue asks for a Note in several ways — starting, advancing, restoring, retrying — and the same
     * page id can be asked for twice in a row (a queue whose first two Notes are the same page, or a retry
     * after a failure). The loader needs to tell "I am already fetching this" from "this is a new ask for a
     * page that happens to look the same", because those have opposite answers: the first must wait, the
     * second must fetch.
     *
     * [token] is that distinction. It changes on every transition that issues a request, so two requests are
     * the same request exactly when the token matches — which is the whole of the comparison
     * [com.example.notionreader.application.QueueLoader] needs to fetch a Note once while still being able
     * to supersede an in-flight fetch immediately.
     *
     * The session, not the loader, decides this: which Note is wanted is session state, and a loader that
     * tracked it itself would be a second copy of the queue that could disagree with the first.
     */
    val pendingRequest: PendingNoteRequest?
        get() = _queueState.value.pendingRequest

    /**
     * This session as a record, or null when it has no queue or no identity.
     *
     * A pure read of current state: it writes nothing and changes nothing, which is what lets the caller
     * decide *when* to persist without this class knowing that persistence exists.
     */
    fun snapshot(now: Long): StoredSession? {
        val current = _queueState.value
        val queue = current.queue ?: return null
        val sessionId = id ?: return null

        return StoredSession(
            id = sessionId,
            notes = queue.items,
            origin = queue.origin,
            index = current.index,
            position = currentPosition,
            completed = current.completed,
            wasPlaying = state.value.isPlaying,
            lastActivityAt = now
        )
    }

    private fun newSessionId(): String = UUID.randomUUID().toString()

    /**
     * Hands the session the document it was waiting for, if it is still waiting for *that* document.
     *
     * ## Why "something is awaited" is not the same question as "this is what is awaited"
     *
     * The first version of this method checked only [QueueState.awaiting], which identifies that *a* Note
     * is wanted and not *which* one. That is not enough, and the gap was reachable:
     *
     * ```
     * playAll(A)         queue = A, awaiting, A0's fetch begins and suspends
     * playAll(B)         queue = B, awaiting, B0's fetch begins
     * A0's fetch returns
     *                    -> accepted, because the session is awaiting something
     * ```
     *
     * …and the session then read A0 aloud as B's first Note, with B's queue position. If A0 is not in B
     * at all, the listener hears a Note they did not queue, from a position that claims to be note 1.
     * A stale *failure* had the mirror-image effect: a failed load for A would stall the session that had
     * already moved to B, and the stall would name a Note B had never asked for.
     *
     * ## The rule
     *
     * A document may enter a session only when its `pageId` is the page the queue is currently waiting for.
     * That is the whole of "the document this queue awaited", because a queue identifies its Notes by page
     * id — two entries with the same page id are the same Note, so a document that matches is the right
     * document by definition, and one that does not match cannot be.
     *
     * The check is here rather than only at the call site because a caller cannot be trusted to know
     * whether its own request is still current: by the time it returns, the thing it was loading for may
     * not exist. Being a rule of the state machine, it is the same rule for every caller — the queue
     * loader today, and whatever loads a Note next.
     */
    fun supplyDocument(document: ReaderDocument) {
        val current = _queueState.value
        if (!current.awaiting) return
        if (current.currentItem?.pageId != document.pageId) return

        val pending = pendingPosition
        val wasRestoring = restoring
        pendingPosition = null
        restoring = false

        // Where to start, and whether anything has to be said first. Null means this Note has no saved
        // position, which is a fresh start rather than a lost one — see ResumePolicy.
        val decision = ResumePolicy.decide(pending, document)

        // Recorded only for a genuine restore. A Note transition also produces a decision ("start at 0"),
        // and recording it would report the previous restore's match quality as if it were this Note's.
        if (pending != null) _lastResume.value = decision

        engine.setDocument(document, startParagraphIndex = decision.startIndex)

        // One announcement at most, and the resume decision outranks the Note title: a listener who is
        // being told "Section 4" because their paragraph moved does not also need the Note's name read to
        // them, and two announcements back to back before content is worse than one.
        val announcement = decision.announcement
            ?: if (!wasRestoring && current.index > 0) current.currentItem?.title ?: document.title else null

        // Whether anything is spoken at all is decided by the intent the transition recorded, not by the
        // fact that a document arrived. `announceThenPlay` speaks and then reads on, so it is only reached
        // when the session is meant to be reading: announcing a Note and then continuing into it is exactly
        // the behaviour that makes `Next note` while paused start speaking.
        if (!playWhenSupplied) {
            // Loaded, on screen, and silent. The position moved and the listener can read where to; the
            // heading and the position line show the new Note, and Play begins it from its first chunk.
        } else if (announcement != null) {
            // The start index goes with the announcement. Announcing a section and then beginning at the
            // document's first chunk would be worse than saying nothing: it would name where the listener
            // is and then read from somewhere else entirely.
            engine.announceThenPlay(announcement, decision.startIndex)
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
            // Automatic progression is always a continue-reading transition: it happens because a Note was
            // read to its end, so the next one must start speaking. Stated explicitly rather than inherited,
            // because a *manual* move made while paused records "stay silent" — and if that value were still
            // in effect here, the Note after a silent load would load silently too and hands-free reading
            // would stop dead the first time a listener used `Next note` while paused.
            playWhenSupplied = true

            // A transition issues a new request, so a fetch of the Note that just ended can no longer be
            // mistaken for the fetch this session is now waiting on.
            current.copy(
                index = current.index + 1,
                awaiting = true,
                requestToken = current.requestToken + 1
            )
        } else {
            // "At the final Note: announce that the queue has ended; stop automatic progression; mark the
            // session completed."
            //
            // **Announced here, at the event, rather than by a surface that renders the completed state.**
            // A surface would re-speak it every time the Player was opened, and the Core Player & TTS
            // Playback spec requires it to be said once per completion. This is also the only place that
            // knows the queue has *ended* rather than that a Note has: a one-Note queue ends here too, and
            // it gets one announcement rather than an "end of note" followed by an "end of queue".
            //
            // Spoken rather than left to the Player's "End of queue" text, which is a live region: a live
            // region is read to nobody when no screen reader is running, and with the screen off the
            // reader's own voice is the listener's only channel. A queue that simply stops is
            // indistinguishable, by ear, from a reader that has failed.
            //
            // Marked completed as a fact after the announcement, because the guard at the top of this
            // method reads it and a re-entrant call must not announce twice. What a completed session is
            // *retained* for belongs to the Active Queue & Reader Session spec, which owns persistence and
            // history; nothing about it is invented here.
            engine.announce(END_OF_QUEUE_ANNOUNCEMENT)
            current.copy(completed = true)
        }
    }

    /**
     * Advances to the next Note in the queue.
     *
     * Returns true when the transition was initiated, false if already at the last Note or if no queue exists.
     */
    fun nextNote(): Boolean {
        val current = _queueState.value
        if (current.queue == null || !current.hasNext) return false

        beginManualTransition(index = current.index + 1)
        return true
    }

    /**
     * Steps back to the previous Note in the queue.
     *
     * Returns true when the transition was initiated, false if already at the first Note or if no queue exists.
     *
     * **A whole Note back, including from the middle of one.** That is what the control says, and it is
     * deliberately not the rule its section counterpart follows — "Previous section" returns to the start
     * of the current section first, so one press re-reads and two go back one. The asymmetry is chosen
     * rather than overlooked: a listener who wants to restart the Note they are part-way through has
     * "Previous section" for the section they are in, and a control named "Previous note" that sometimes
     * did nothing visible would be the surprising one.
     */
    fun previousNote(): Boolean {
        val current = _queueState.value
        if (current.queue == null || !current.hasPrevious) return false

        beginManualTransition(index = current.index - 1)
        return true
    }

    /**
     * Jumps directly to a specific Note index within the active queue.
     *
     * **Selecting the Note already playing is not a transition.** It used to re-load the current Note from
     * its first chunk, so tapping the row a listener was already on silently rewound what they were
     * listening to — a destructive reading of an action that says only "go here". Returning true anyway,
     * because the target was valid and the caller's job (closing the queue view) should happen either way.
     */
    fun jumpToNote(targetIndex: Int): Boolean {
        val current = _queueState.value
        val queue = current.queue ?: return false
        if (targetIndex !in 0 until queue.size) return false
        if (targetIndex == current.index) return true

        beginManualTransition(index = targetIndex)
        return true
    }

    /**
     * The shared body of a listener-initiated move to another Note.
     *
     * Three things have to happen together and in this order, which is why they are in one place rather
     * than repeated across the three controls:
     *
     * 1. **The current Note stops speaking.** A manual move is not a cross-fade, and leaving the old Note
     *    talking while the new one loads would speak content the listener has just navigated away from.
     * 2. **Whether to speak the new one is recorded, not assumed.** Captured *before* the pause above, so
     *    it is the listener's intent rather than a consequence of the engine having been stopped.
     * 3. **A new request is issued**, with a new token, so that a fetch of the Note being left behind
     *    cannot be mistaken for the one now awaited — and so that the change is *observable*, which is what
     *    makes something actually fetch it.
     */
    private fun beginManualTransition(index: Int) {
        playWhenSupplied = isPlaying
        if (isPlaying) engine.pause()

        pendingPosition = null
        restoring = false
        _queueState.value = _queueState.value.copy(
            index = index,
            awaiting = true,
            completed = false,
            failure = null,
            requestToken = requestToken + 1
        )
    }

    /**
     * The document of the Note identified by [pageId] could not be loaded.
     *
     * **The queue stalls** — the position is kept and nothing advances. This is the product decision
     * recorded in `docs/decisions.md` §14.3 (B3); neither contract stated it. Skipping the Note would
     * silently drop something the user asked to hear, which is the audible form of the mutation the queue
     * snapshot rule exists to prevent. Aborting the queue would discard Notes that are still perfectly
     * playable, for a failure the error taxonomy already classifies as often transient. Stalling keeps
     * the user's options: Retry, or leave.
     *
     * [pageId] is required for the same reason [supplyDocument] checks identity: a failure belongs to the
     * request that produced it. Without it, a slow failure from a session the listener has already
     * replaced — `playAll(A)` then `playAll(B)`, with A0's fetch failing afterwards — would stall B on a
     * Note B never asked for, and the Player would offer Retry for A0 while claiming to be B.
     */
    fun failCurrentNote(pageId: String, failure: ReaderLoadException) {
        val current = _queueState.value
        if (current.queue == null) return
        if (current.currentItem?.pageId != pageId) return

        _queueState.value = current.copy(awaiting = false, failure = failure)
    }

    /** Clears a stalled Note's failure and asks for its document again. */
    fun retryCurrentNote() {
        val current = _queueState.value
        if (!current.isStalled) return

        // Retrying means "read this Note", so it resumes speaking. A stall stopped playback as a
        // consequence of not having a document, not because the listener asked for silence.
        playWhenSupplied = true
        _queueState.value = current.copy(
            failure = null,
            awaiting = true,
            requestToken = current.requestToken + 1
        )
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

    /** One speakable part forward. The transport's `NextPart`; never crosses a Note boundary. */
    fun nextPart() = engine.nextParagraph()

    /** One speakable part back. The transport's `PreviousPart`. */
    fun previousPart() = engine.previousParagraph()

    /**
     * Restarts the current Note from its first part, preserving the playing state.
     *
     * The transport's `RestartNote`. It does not restart the queue, does not start a new session, and does
     * not change queue membership or position — those are different operations with different names.
     */
    fun restartNote() = engine.restartNote()

    /** Jumps to a specific section, preserving the playing state. */
    fun jumpToSection(span: ReaderSectionSpan) = engine.jumpToParagraph(span.firstParagraphIndex)

    /** Jumps to a specific paragraph index, preserving the playing state. */
    fun jumpToParagraph(index: Int) = engine.jumpToParagraph(index)

    /** Releases speech resources. The session must not be used afterwards. */
    fun shutdown() = engine.shutdown()

    private companion object {
        /**
         * What the reader says when the last Note of a queue has been read.
         *
         * The words are the ones the Player's playback line shows at that moment, deliberately: the spoken
         * and the displayed vocabulary should be one vocabulary, so a listener who can both hear and see
         * the Player is not told two different things about one event.
         */
        const val END_OF_QUEUE_ANNOUNCEMENT = "End of queue"
    }
}

/**
 * One request for one Note: which page, and which ask for it.
 *
 * It is a value rather than a pair of properties so that "is this the same request?" is a single equality
 * on two facts that must travel together — the identity of the Note and the identity of the request — and
 * so that comparing them cannot accidentally compare only one of the two.
 *
 * [token] is an implementation detail of that comparison. It has no meaning outside the session and the
 * loader, and in particular it is never persisted: the Active Queue & Reader Session specification forbids
 * storing a numeric playback coordinate, and a load token is even less durable than one.
 */
data class PendingNoteRequest(
    val pageId: String,
    /** Opaque. Only equality against another request's token means anything. */
    val token: Int,
    val lastEditedAt: java.time.Instant? = null
)

/**
 * The Note this queue state is waiting for, or null when it is waiting for nothing.
 *
 * ## One definition, four users
 *
 * This is the single answer to "what needs loading?", and it is here rather than inside any one caller
 * because four of them have to agree about it: [ReaderSession] exposes it, the loader fetches it, the
 * observer in `ReaderViewModel` triggers on it, and the tests assert it. They used to disagree. The loader
 * and the session compared *requests* (so a superseded fetch could be refused), while the observer watched
 * `awaiting` — a boolean that is `true` on both sides of a superseding transition. So a move that replaced
 * an in-flight fetch produced no observable change, nothing fetched the replacement, and the queue waited
 * forever for a request nobody had made.
 *
 * Deriving it from the state rather than storing it means there is no second copy to fall out of step, and
 * it is why [QueueState.requestToken] is published at all.
 *
 * It is an extension here rather than a member of [QueueState] because [PendingNoteRequest] belongs to this
 * layer: the domain model describes where the queue is, and "which fetch is outstanding" is an application
 * concern. Keeping it beside the type it produces is what makes the two impossible to redefine separately.
 */
val QueueState.pendingRequest: PendingNoteRequest?
    get() = if (!awaiting) {
        null
    } else {
        currentItem?.let { PendingNoteRequest(pageId = it.pageId, token = requestToken, lastEditedAt = it.lastEditedAt) }
    }
