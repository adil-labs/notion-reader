package com.example.notionreader

import com.example.notionreader.application.ReaderApp
import com.example.notionreader.domain.PositionMatch
import com.example.notionreader.application.pendingRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The process-scoped host of the listening session: what is being read, and the scope that keeps feeding it.
 *
 * ## Why this exists, and why it is not the service
 *
 * A listening session must outlive the *task*, not merely the screen. The Background Playback specification's
 * Phase 1 says so, and the reason is that a `MediaSessionService` has its own lifecycle which the system may
 * extend past the task's — so nothing the service controls may be owned by something the task destroys.
 *
 * It is deliberately **not** "the session moves into the service". The service stops when playback pauses,
 * and the UI must still be able to see and drive the session while it is stopped; a session living in the
 * service would be unreachable exactly when a listener returns to the app. So this is a holder that belongs
 * to neither the service nor the task, and both reach it.
 *
 * ## The finding that shaped it: the session is not the only thing that must outlive the task
 *
 * The session used to be owned by `ReaderViewModel`, and so was **the loop that fetches each Note in turn**.
 * Moving only the session would therefore not have been enough, and the failure would have been quiet and
 * confusing: with the task dismissed, the session survives, playback continues to the *end of the current
 * Note*, and then stops dead — because the collector that loads the next Note died with the ViewModel, and
 * a session that is `awaiting` a document nobody fetches never advances. On a single-Note queue it would
 * look like it worked, which is the state anyone would have tested first.
 *
 * So this type owns two things, and the second is the one that is easy to miss:
 *
 * | Owned here | Owned by the task |
 * |---|---|
 * | the [ReaderApp] — session, queue, position, history | which screen is showing |
 * | the scope that feeds it and records it | the I/O a screen's own action starts |
 *
 * ## What is deliberately not here
 *
 * No Android type and no dispatcher, so the whole thing is JVM-testable: the constructor takes a
 * [CoroutineScope] and a logger rather than creating either. `ReaderApplication` is what wires the real
 * ones, and `docs/decisions.md` §23.16 records how the test that proves the property is written.
 */
class ReaderHost(
    /** The application state every surface reads. */
    val app: ReaderApp,
    /**
     * The scope the queue's feeding and the session's recording run on.
     *
     * Called "application" rather than "playback" because it must outlive the task, and that is the
     * lifetime this is. It is passed in rather than created here so that a test can own it and observe what
     * happens when a *different* scope is cancelled.
     */
    private val scope: CoroutineScope,
    /**
     * Where the resume match quality is recorded.
     *
     * Injected so that this class stays Android-free and the recording becomes assertable. It used to be a
     * direct `Log.i` call inside the ViewModel, which meant the one fact the Active Queue & Reader Session
     * spec requires to be recorded was untestable.
     */
    private val log: (String) -> Unit = {}
) {

    private val _speechMessage = MutableStateFlow<String?>(null)

    /**
     * Set when the device cannot speak at all, which no amount of retrying will fix.
     *
     * Here rather than on the ViewModel because the speech engine's lifetime is now this object's: a
     * device-level speech failure outlives any screen that would report it.
     */
    val speechMessage: StateFlow<String?> = _speechMessage.asStateFlow()

    /** Reports a device-level speech failure. Called by the engine that was built with this host. */
    fun reportSpeechUnavailable(message: String) {
        _speechMessage.value = message
    }

    /**
     * Starts feeding and recording the session. Idempotent by construction — the [scope] is started once per
     * process — and separate from the constructor so that a test can build the graph and then decide when it
     * begins, rather than having a collector start during construction.
     */
    fun start() {
        // Logged so that "a fresh process built a fresh host" is observable rather than assumed. A session
        // that appears to survive a force-stop is either a real defect or a misreading, and the two need
        // opposite responses — so the fact that this line appears tells us which process we are looking at.
        log("host: starting the session's loops")

        advanceTheQueue()
        feedTheQueue()
        recordHistory()
        recordResumeQuality()
        readSavedQueues()
    }

    /**
     * Reads the Saved Queues once, so that any surface can list them.
     *
     * ## Why this is here and not in a screen
     *
     * The Queue Creation & Queue Management spec's invariant is "a task-scoped surface owns no store, and the
     * store is reached from the application layer". A screen that read the store itself would do that work
     * again on every visit, and would do it inside composition; this is one read per process, exactly like
     * [recordHistory]'s, and the surfaces observe the published list.
     *
     * ## Why it is not paired with a `distinctUntilChanged` collector like the loops above
     *
     * Because there is nothing to collect. A queue write is performed by this application at a known moment
     * and publishes its own result ([ReaderApp]'s `transact`), so a listener on the store would be a second
     * notification path for a change the writer already announced — and the one that could race it.
     */
    private fun readSavedQueues() {
        scope.launch { app.loadSavedQueues() }
    }

    /**
     * Ends the session and the scope.
     *
     * Called only when the process is going away, which is why nothing routine calls it: the point of this
     * type is that a dismissed task must not. `ReaderViewModel.onCleared` deliberately does **not** reach
     * here, and that is the whole correction.
     */
    fun shutdown() {
        scope.cancel()
        app.shutdown()
    }

    /**
     * Advances the session to the next Note when the engine finishes the current one.
     *
     * This is the other half of feeding the queue, and it is the half that would have made the R10 finding
     * look like an integration bug rather than an ownership one. Without it the session never *asks* for the
     * next Note, so the fetcher below has nothing to fetch: playback would reach the end of the current Note
     * and stop, with the queue still showing the Note it had just finished.
     *
     * `distinctUntilChanged` matters: the engine keeps reporting `hasReachedEnd` until a new document is set,
     * and without it the queue would advance once per emission rather than once per Note.
     */
    private fun advanceTheQueue() {
        scope.launch {
            app.session.state
                .map { it.hasReachedEnd }
                .distinctUntilChanged()
                .collect { finished -> if (finished) app.onNoteFinished() }
        }
    }

    /**
     * Fetches whatever Note the queue is waiting for, whenever that changes.
     *
     * **The trigger is the request, not "is something awaited".** Those look equivalent and are not:
     * `awaiting` stays true across a *superseding* transition, so mapping to the boolean and applying
     * `distinctUntilChanged` meant that replacing an in-flight request produced no emission at all —
     * nothing fetched the new Note, while the arrival of the superseded one was correctly refused by
     * the session's identity check. The session then waited for a request no one had made: the Player showed
     * "Loading…" with every control disabled, permanently, and the only way out was to leave and start again.
     *
     * It is reachable with two presses: `Next note` twice on a page that takes a few seconds to load, which
     * on this integration is every page with more than a hundred blocks.
     */
    private fun feedTheQueue() {
        scope.launch {
            app.session.queueState
                .map { it.pendingRequest }
                .distinctUntilChanged()
                .collect { request -> if (request != null) app.loadPendingQueueNote() }
        }
    }

    /**
     * Records the live session into history.
     *
     * ## The load must finish before this subscribes, and that is a correctness requirement
     *
     * [ReaderApp.persistSession] writes the retained set, which is history **plus** the live session. If a
     * write could happen first, it would persist an empty history plus the live session and silently discard
     * every session already on disk — the listener's entire Continue listening list, erased by the launch of
     * the app. So history is loaded in the same coroutine, before the collector starts.
     *
     * Collecting the position rather than a timer is what makes a session interrupted *while playing*
     * resumable. Writing only on pause would mean the common case — the phone being taken out of the
     * listener's hand mid-Note — loses everything since the last pause.
     */
    private fun recordHistory() {
        scope.launch {
            app.loadHistory()

            combine(
                app.session.state.map { listOf(it.currentParagraphIndex, it.isPlaying, it.hasReachedEnd) },
                app.session.queueState.map { listOf(it.index, it.awaiting, it.completed) }
            ) { reading, queue -> reading + queue }
                .distinctUntilChanged()
                .collect { app.persistSession() }
        }
    }

    /**
     * Records the match quality of a restore.
     *
     * The Active Queue & Reader Session spec requires this to be recorded, and it is the only way to tell an
     * exact resume from a fallback: both start playback at a plausible place, and the difference is invisible
     * in the audio until the listener notices they have heard a part before. An exact resume records nothing,
     * because there is nothing to say and a line per resume would train the reader to ignore the ones that
     * matter.
     */
    private fun recordResumeQuality() {
        scope.launch {
            // No `distinctUntilChanged`: the source is already a StateFlow, which conflates, so applying it
            // to the flow directly is a deprecated no-op. The filter below is what decides what is worth
            // recording.
            app.session.lastResume.collect { decision ->
                val match = decision?.match ?: return@collect
                val announcement = decision.announcement

                if (match == PositionMatch.CHUNK && announcement == null) return@collect

                log(
                    "resume match=$match startIndex=${decision.startIndex} " +
                        "announcement=${announcement ?: "none"}"
                )
            }
        }
    }

    /**
     * Calls [onChange] whenever the session's reading position or queue changes, until the returned job is
     * cancelled.
     *
     * This is how the **second front end** stays in step. The media session renders the same session the
     * Player does, and it must not keep its own copy of it — the specification's invariant is "one session
     * object, two front ends. Neither is a second source of state." So the media session subscribes to the
     * session rather than being told about it, exactly as the Player's composables do.
     *
     * The scope is the host's, which is the process's: a listener whose task is dismissed still has a media
     * session, and a subscription on a task scope would die with the task while the session it describes
     * carried on.
     *
     * `combine` over the two flows rather than a shared mutable value, so any change to either re-derives the
     * projection. The first emission arrives immediately, which is what makes a freshly-created media session
     * describe the session it found rather than an empty one.
     */
    fun observeSession(onChange: () -> Unit): Job =
        scope.launch {
            combine(app.session.state, app.session.queueState) { _, _ -> Unit }.collect { onChange() }
        }
}
