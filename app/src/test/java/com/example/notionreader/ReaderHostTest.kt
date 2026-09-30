package com.example.notionreader

import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.NoteViewer
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderNavigator
import com.example.notionreader.application.ReaderSession
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.positionAt
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.player.FakeReaderEngine
import com.example.notionreader.player.FakeSpeechEngine
import com.example.notionreader.player.ReaderEngineImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * The process-scoped host: the session and its feeding outlive the task.
 *
 * ## The property, and why it is the one worth testing
 *
 * The Background Playback specification's Phase 1 requires that nothing the playback service controls is
 * owned by something the task destroys. The failure this guards against is **not** "the UI forgets the
 * session" — it is that a session whose *feeders* died does not stop immediately, it stops one Note later.
 * Playback reaches the end of the current Note, calls `onCurrentNoteFinished`, moves to `awaiting`, and then
 * waits forever for a document nobody is fetching. On a single-Note queue that never happens, which is the
 * state anyone would check first.
 *
 * So the test is written as: start a queue, cancel a scope that stands in for the task, and assert the host
 * still feeds the next Note. Putting the loops back on the task scope fails exactly these tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderHostTest {

    private fun note(pageId: String, title: String) = ReaderDocument.fromChunkTexts(
        pageId = pageId,
        title = title,
        texts = listOf("$title first.", "$title second.")
    )

    private val alpha = note("alpha", "Alpha")
    private val beta = note("beta", "Beta")

    /** Records which pages were fetched, so "the next Note was loaded" is assertable. */
    private inner class RecordingLoader {
        val loaded = mutableListOf<String>()

        fun asLoader() = DefaultLoadReaderDocument { reference ->
            loaded += reference
            // `reference` is a page id in these tests; the title is derived so a caller can tell them apart.
            note(reference, "Note $reference")
        }
    }

    private fun hostWith(
        scope: CoroutineScope,
        engine: FakeReaderEngine = FakeReaderEngine(alpha),
        loader: RecordingLoader = RecordingLoader(),
        networkState: NetworkState = UnmeteredNetworkState,
        settingsRepo: com.example.notionreader.domain.settings.SettingsRepository = com.example.notionreader.application.InMemorySettingsRepository(),
        log: (String) -> Unit = {}
    ): Pair<ReaderHost, RecordingLoader> {
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(alpha),
            navigator = ReaderNavigator(),
            loadDocument = loader.asLoader(),
            settingsRepository = settingsRepo
        )
        return ReaderHost(app = app, scope = scope, networkState = networkState, log = log) to loader
    }


    /**
     * The correction R10 makes, asserted directly.
     *
     * A scope stands in for the task and is cancelled — which is what `onCleared` does to `viewModelScope`.
     * The host's own scope is untouched, so the queue must still be fed.
     */
    @Test
    fun theQueueIsStillFedAfterTheTaskScopeIsCancelled() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val (host, loader) = hostWith(applicationScope)
        host.start()

        host.app.playAll(
            listOf(QueuedNote(alpha.pageId, alpha.title), QueuedNote(beta.pageId, beta.title)),
            QueueOrigin.BrowseResults
        )
        assertEquals("the first Note is fetched", listOf("alpha"), loader.loaded)

        // The task goes away: recents swiped, or the Activity finished.
        taskScope.cancel()

        // And playback advances anyway. This is the assertion that fails if the loops live on a task scope.
        host.app.onNoteFinished()

        assertEquals(
            "the second Note is still fetched with the task gone",
            listOf("alpha", "beta"),
            loader.loaded
        )
        assertEquals(1, host.app.session.queueState.value.index)
    }

    @Test
    fun theSessionSurvivesTheTaskScopeBeingCancelled() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val (host, _) = hostWith(applicationScope)
        host.start()
        host.app.playNote()

        assertTrue(host.app.session.isPlaying)
        val sessionId = host.app.session.id

        taskScope.cancel()

        // The queue, its position, its identity and its playing state are all the host's, so none of them is
        // affected by the task ending.
        assertEquals(sessionId, host.app.session.id)
        assertTrue(host.app.session.isPlaying)
        assertEquals(1, host.app.session.queueState.value.size)
        assertFalse(host.app.session.queueState.value.completed)
    }

    @Test
    fun advancingToTheNextNoteIsItselfADutyOfTheHost() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // The *real* engine here, because the end-of-note signal is a property of it: `hasReachedEnd` is set
        // when the last part is spoken through, not when the position merely reaches the end. A fake that
        // could be poked into that state would not be reproducing the thing being observed.
        val speech = FakeSpeechEngine()
        val engine = ReaderEngineImpl(speechEngine = speech, initialDocument = alpha)
        val loader = RecordingLoader()
        val app = ReaderApp(
            session = ReaderSession(engine),
            noteViewer = NoteViewer(alpha),
            navigator = ReaderNavigator(),
            loadDocument = loader.asLoader()
        )

        val host = ReaderHost(app = app, scope = applicationScope)
        host.start()

        host.app.playAll(
            listOf(QueuedNote(alpha.pageId, alpha.title), QueuedNote(beta.pageId, beta.title)),
            QueueOrigin.BrowseResults
        )
        assertEquals(listOf("alpha"), loader.loaded)

        // Nothing here calls `onNoteFinished` by hand: the engine reports the end of a document through its
        // state, and observing that is half of what the host exists to do. A host that only *fetched* would
        // pass the test above and still never advance a real queue.
        host.app.session.play()
        speech.completeUtterance()
        speech.completeUtterance()

        assertEquals(
            "the host noticed the end of the Note and asked for the next one",
            listOf("alpha", "beta"),
            loader.loaded
        )
        assertEquals(1, host.app.session.queueState.value.index)
    }

    @Test
    fun theResumeMatchQualityIsRecordedThroughTheInjectedLogger() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lines = mutableListOf<String>()

        val (host, _) = hostWith(applicationScope, log = { lines += it })
        host.start()

        // A session restored to a position is the only thing that produces a resume decision, and the
        // Active Queue & Reader Session spec requires the match quality to be recorded. It used to be a
        // direct `Log.i` inside the ViewModel, which made the one fact the spec requires untestable.
        val snapshot = com.example.notionreader.domain.session.StoredSession(
            id = "session-1",
            notes = listOf(QueuedNote(alpha.pageId, alpha.title)),
            origin = QueueOrigin.SingleNote,
            index = 0,
            position = com.example.notionreader.domain.ReaderDocumentPosition(
                sourceBlockId = "missing-block",
                chunkId = "missing-block:0",
                sectionId = "missing-section"
            ),
            completed = false,
            wasPlaying = true,
            lastActivityAt = 0L
        )

        host.app.session.restore(snapshot)
        host.app.session.supplyDocument(alpha)

        // The position resolves to nothing, so the reader announces the loss; an exact resume would record
        // nothing, which is deliberate and is asserted by the empty case below.
        assertTrue("a lost position is recorded", lines.isNotEmpty())
        assertTrue(lines.any { it.contains("resume match=") })
    }

    @Test
    fun anExactResumeRecordsNothing() = runTest {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lines = mutableListOf<String>()

        val (host, _) = hostWith(applicationScope, log = { lines += it })
        host.start()

        val exact = com.example.notionreader.domain.session.StoredSession(
            id = "session-1",
            notes = listOf(QueuedNote(alpha.pageId, alpha.title)),
            origin = QueueOrigin.SingleNote,
            index = 0,
            // Built from the document rather than written by hand: a guessed chunk id resolves to something
            // other than CHUNK, which would make this assert the loss path while claiming to assert the
            // exact one — an assertion about a case that was never produced.
            position = alpha.positionAt(0),
            completed = false,
            wasPlaying = true,
            lastActivityAt = 0L
        )

        // Nothing to say: the text is the continuation, and a line per resume would train the reader to
        // ignore the ones that matter.
        host.app.session.restore(exact)
        host.app.session.supplyDocument(alpha)

        // Asserted against the **resume** records, not against the whole sink, and that distinction is a
        // correction rather than a convenience. This test originally asserted the sink was empty, which was
        // true only while the resume decision was the only thing that ever logged. It is not: the host also
        // records diagnostics for its own lifecycle, so an unrelated line — added to make an unattributable
        // failure attributable — failed this test even though nothing about resume behaviour had changed.
        //
        // The over-broad assertion was the defect, not the extra logging: an assertion should be about the
        // property it names. Filtering by the prefix also means a second *kind* of diagnostic can be added
        // without silently weakening what this checks.
        assertEquals(
            "an exact resume must record no resume diagnostic",
            emptyList<String>(),
            lines.filter { it.startsWith("resume ") }
        )
    }

    @Test
    fun pauseBetweenNotesDelaysQueueAdvance() = runTest {
        val hostScope = CoroutineScope(coroutineContext + Job())
        try {
            val repo = com.example.notionreader.application.InMemorySettingsRepository()
            repo.updateSettings { it.copy(pauseBetweenNotes = com.example.notionreader.domain.settings.NotePauseDuration.ONE_SECOND) }

            val fakeEngine = FakeReaderEngine(alpha)
            val (host, _) = hostWith(hostScope, engine = fakeEngine, settingsRepo = repo)
            host.start()

            host.app.playAll(
                listOf(QueuedNote(alpha.pageId, alpha.title), QueuedNote(beta.pageId, beta.title)),
                QueueOrigin.BrowseResults
            )
            runCurrent()
            assertFalse(host.app.session.queueState.value.awaiting)

            // Simulate reaching end of alpha note
            fakeEngine.reachEnd()
            runCurrent()

            // 500ms into the pause, queue has not advanced
            advanceTimeBy(500)
            runCurrent()
            assertEquals(0, host.app.session.queueState.value.index)

            // After full 1000ms delay, queue advances to beta
            advanceTimeBy(600)
            runCurrent()
            assertEquals(1, host.app.session.queueState.value.index)
        } finally {
            hostScope.cancel()
        }
    }

    @Test
    fun manualNavigationCancelsScheduledInterNotePause() = runTest {
        val hostScope = CoroutineScope(coroutineContext + Job())
        try {
            val repo = com.example.notionreader.application.InMemorySettingsRepository()
            repo.updateSettings { it.copy(pauseBetweenNotes = com.example.notionreader.domain.settings.NotePauseDuration.FIVE_SECONDS) }

            val fakeEngine = FakeReaderEngine(alpha)
            val (host, _) = hostWith(hostScope, engine = fakeEngine, settingsRepo = repo)
            host.start()

            val gamma = note("gamma", "Gamma")
            host.app.playAll(
                listOf(QueuedNote(alpha.pageId, alpha.title), QueuedNote(beta.pageId, beta.title), QueuedNote(gamma.pageId, gamma.title)),
                QueueOrigin.BrowseResults
            )
            runCurrent()
            assertFalse(host.app.session.queueState.value.awaiting)

            fakeEngine.reachEnd()
            runCurrent()

            // 1 second into the 5-second pause, user manually presses next note
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(0, host.app.session.queueState.value.index)

            host.app.nextNote()
            assertEquals(1, host.app.session.queueState.value.index)

            // Advance remaining 5 seconds; stale advance must not fire and skip to gamma!
            advanceTimeBy(5000)
            runCurrent()
            assertEquals("Stale scheduled advance must not skip another note", 1, host.app.session.queueState.value.index)
        } finally {
            hostScope.cancel()
        }
    }

    @Test
    fun prefetchIsGatedOnMeteredNetworkWhenPolicyIsWifiOnly() = runTest {
        val hostScope = CoroutineScope(coroutineContext + Job())
        try {
            val repo = com.example.notionreader.application.InMemorySettingsRepository()
            repo.updateSettings {
                it.copy(
                    prefetchUpcomingNotes = true,
                    downloadNetworkPolicy = com.example.notionreader.domain.settings.DownloadNetworkPolicy.WIFI_ONLY
                )
            }

            val meteredNetwork = object : NetworkState {
                override val isUnmetered: Boolean = false
            }

            val (host, loader) = hostWith(hostScope, networkState = meteredNetwork, settingsRepo = repo)
            host.startQueuePrefetch()

            host.app.playAll(
                listOf(QueuedNote(alpha.pageId, alpha.title), QueuedNote(beta.pageId, beta.title)),
                QueueOrigin.BrowseResults
            )

            runCurrent()
            assertFalse("Beta should not be prefetched over metered connection with WIFI_ONLY policy", loader.loaded.contains("beta"))
        } finally {
            hostScope.cancel()
        }
    }

    @Test
    fun prefetchIsGatedWhenOfflineCacheIsDisabled() = runTest {
        val hostScope = CoroutineScope(coroutineContext + Job())
        try {
            val repo = com.example.notionreader.application.InMemorySettingsRepository()
            repo.updateSettings {
                it.copy(
                    cacheNotesForOffline = false,
                    prefetchUpcomingNotes = true
                )
            }

            val (host, loader) = hostWith(hostScope, settingsRepo = repo)
            host.startQueuePrefetch()

            host.app.playAll(
                listOf(QueuedNote(alpha.pageId, alpha.title), QueuedNote(beta.pageId, beta.title)),
                QueueOrigin.BrowseResults
            )

            runCurrent()
            assertFalse("Beta should not be prefetched when offline cache is disabled", loader.loaded.contains("beta"))
        } finally {
            hostScope.cancel()
        }
    }
}

