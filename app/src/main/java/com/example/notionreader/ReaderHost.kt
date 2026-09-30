package com.example.notionreader

import com.example.notionreader.application.ReaderApp
import com.example.notionreader.domain.PositionMatch
import com.example.notionreader.application.pendingRequest
import kotlinx.coroutines.CancellationException
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
    private val log: (String) -> Unit = {},
    private val networkState: NetworkState = UnmeteredNetworkState
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

        loadCachedIndex()
        advanceTheQueue()
        feedTheQueue()
        recordHistory()
        recordResumeQuality()
        readSavedQueues()
        readSmartQueues()
        observeSettings()
    }

    private fun loadCachedIndex(): Job = scope.launch {
        app.loadCachedIndex()
    }

    private fun observeSettings() = scope.launch {
        app.settingsRepo.settings.collect { settings ->
            app.applySettings(settings)
        }
    }

    private fun readSmartQueues(): Job = scope.launch {
        app.loadSmartQueues()
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

    private var queuePrefetchCollectorJob: Job? = null
    private var activeQueuePrefetchJob: Job? = null

    /**
     * Opportunistically prefetches upcoming Notes in the active queue into the document cache.
     *
     * Kept separate from [start] so unit tests that isolate queue feeds do not trigger background fetches.
     */
    fun startQueuePrefetch() {
        queuePrefetchCollectorJob?.cancel()
        queuePrefetchCollectorJob = scope.launch {
            app.session.queueState
                .map { state -> Pair(state.items, state.index) }
                .distinctUntilChanged()
                .collect { (items, currentIndex) ->
                    activeQueuePrefetchJob?.cancel()
                    val settings = app.settingsRepo.settings.value
                    if (!settings.prefetchUpcomingNotes) return@collect
                    if (!settings.cacheNotesForOffline) return@collect
                    if (settings.downloadNetworkPolicy == com.example.notionreader.domain.settings.DownloadNetworkPolicy.WIFI_ONLY && !networkState.isUnmetered) {
                        return@collect
                    }
                    if (items.isNotEmpty() && currentIndex >= 0) {
                        val upcoming = items.drop(currentIndex + 1).take(2)
                        activeQueuePrefetchJob = launch {
                            for (queuedNote in upcoming) {
                                try {
                                    app.prefetchNote(queuedNote.pageId, queuedNote.lastEditedAt)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Prefetch failure is non-fatal and silent
                                }
                            }
                        }
                    }
                }
        }
    }

    private var recentPrefetchCollectorJob: Job? = null
    private var activeRecentPrefetchJob: Job? = null

    /**
     * Opportunistically pre-caches recent Notes from the Browse index upon app launch / index load,
     * ensuring immediate offline availability and zero-latency playback.
     *
     * Respects the user's configured auto-cache mode (by time window or by note count),
     * applies polite Notion API pacing (350ms delay), and yields immediately to live playback.
     */
    fun startRecentNotesPrefetch(maxNotes: Int? = null) {
        recentPrefetchCollectorJob?.cancel()
        recentPrefetchCollectorJob = scope.launch {
            combine(
                app.browse.state.map { it.entries }.distinctUntilChanged(),
                app.settingsRepo.settings
            ) { entries, settings -> Pair(entries, settings) }
                .collect { (entries, settings) ->
                    activeRecentPrefetchJob?.cancel()
                    if (!settings.cacheNotesForOffline) return@collect
                    if (settings.autoCacheMode == com.example.notionreader.domain.settings.AutoCacheMode.OFF) return@collect
                    if (settings.downloadNetworkPolicy == com.example.notionreader.domain.settings.DownloadNetworkPolicy.WIFI_ONLY && !networkState.isUnmetered) {
                        return@collect
                    }

                    val now = java.time.Instant.ofEpochMilli(app.currentTimeMillis())
                    val plan = com.example.notionreader.domain.discovery.AutoCachePlanner.plan(entries, settings, now)
                    val notesToPrefetch = if (maxNotes != null) plan.notesToPrefetch.take(maxNotes) else plan.notesToPrefetch

                    if (notesToPrefetch.isEmpty() && plan.desiredPageIds.isEmpty()) return@collect

                    val activeQueueIds = app.session.queueState.value.items.map { it.pageId }.toSet()
                    val currentPlayingId = app.session.queueState.value.currentItem?.pageId
                    val savedQueueMemberIds = app.savedQueues.value.flatMap { it.notes }.map { it.pageId }.toSet()
                    val protectedIds = activeQueueIds + listOfNotNull(currentPlayingId) + savedQueueMemberIds

                    activeRecentPrefetchJob = launch {
                        val reconciler = app.autoCacheReconciler
                        if (reconciler != null) {
                            reconciler.reconcile(
                                entries = entries,
                                settings = settings,
                                now = now,
                                protectedPageIds = protectedIds,
                                maxNotes = maxNotes
                            )
                        } else {
                            for (entry in notesToPrefetch) {
                                try {
                                    if (!app.isNoteCached(entry.pageId)) {
                                        app.prefetchNote(entry.pageId, entry.lastEditedAt)
                                        kotlinx.coroutines.delay(350)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Non-fatal background prefetch failure
                                }
                            }
                            app.pruneCacheExcept(plan.desiredPageIds + protectedIds)
                        }
                    }
                }
        }
    }

    /**
     * Ends the session and the scope.
     *
     * Called only when the process is going away, which is why nothing routine calls it: the point of this
     * type is that a dismissed task must not. `ReaderViewModel.onCleared` deliberately does **not** reach
     * here, and that is the whole correction.
     */
    fun shutdown() {
        pendingAdvanceJob?.cancel()
        activeRecentPrefetchJob?.cancel()
        activeQueuePrefetchJob?.cancel()
        recentPrefetchCollectorJob?.cancel()
        queuePrefetchCollectorJob?.cancel()
        scope.cancel()
        app.shutdown()
    }

    private var pendingAdvanceJob: Job? = null

    /**
     * Advances the session to the next Note when the engine finishes the current one.
     *
     * Honors the configured pause-between-notes delay. Manual navigation or session changes
     * invalidate and cancel any scheduled automatic transition via identity token checks.
     */
    private fun advanceTheQueue() {
        scope.launch {
            app.session.state
                .map { it.hasReachedEnd }
                .distinctUntilChanged()
                .collect { finished ->
                    if (finished) {
                        scheduleQueueAdvance()
                    }
                }
        }
    }

    private fun scheduleQueueAdvance() {
        val settings = app.settingsRepo.settings.value
        val queueState = app.session.queueState.value
        val delayMs = settings.pauseBetweenNotes.delayMs

        if (settings.continueToNextNote && queueState.hasNext && delayMs > 0) {
            val pageId = queueState.currentItem?.pageId
            val index = queueState.index
            val token = queueState.requestToken

            pendingAdvanceJob?.cancel()
            pendingAdvanceJob = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                val currentState = app.session.queueState.value
                val isSameNote = currentState.currentItem?.pageId == pageId &&
                    currentState.index == index &&
                    currentState.requestToken == token
                if (isSameNote && app.session.state.value.hasReachedEnd) {
                    app.onNoteFinished()
                }
            }
        } else {


            app.onNoteFinished()
        }
    }

    /**
     * Fetches whatever Note the queue is waiting for, whenever that changes.
     *
     * Any new pending request (e.g. manual Next/Previous Note) cancels pending automatic advance delays.
     */
    private fun feedTheQueue() {
        scope.launch {
            app.session.queueState
                .map { it.pendingRequest }
                .distinctUntilChanged()
                .collect { request ->
                    pendingAdvanceJob?.cancel()
                    if (request != null) {
                        // Playback takes priority: cancel running prefetch I/O so bandwidth is fully dedicated
                        activeRecentPrefetchJob?.cancel()
                        activeQueuePrefetchJob?.cancel()
                        app.loadPendingQueueNote()
                    }
                }
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
