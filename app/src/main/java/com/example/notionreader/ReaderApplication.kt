package com.example.notionreader

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.LoadReaderDocument
import com.example.notionreader.application.NoteViewer
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.application.ReaderNavigator
import com.example.notionreader.application.ReaderSession
import com.example.notionreader.data.document.CachedDocumentRepository
import com.example.notionreader.data.document.DocumentCache
import com.example.notionreader.data.local.DataStoreSavedQueueStore
import com.example.notionreader.data.local.DataStoreSessionStore
import com.example.notionreader.data.local.DataStoreSettingsRepository
import com.example.notionreader.data.local.DataStoreSmartQueueStore
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.notion.KtorNotionApi
import com.example.notionreader.data.notion.BuildConfigTokenProvider
import com.example.notionreader.data.notion.NotionRepository
import com.example.notionreader.data.notion.NotionTokenProvider
import com.example.notionreader.domain.ReaderDocument
import java.io.File
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.settings.SettingsRepository
import com.example.notionreader.platform.AndroidSpeechEngine
import com.example.notionreader.player.ReaderEngineImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The composition root's lifetime half: the object graph now belongs to the process, not to a task.
 *
 * ## What changed, and the defect it corrects
 *
 * Until this round the graph was built by `ReaderViewModel`, which meant the graph's lifetime was the task's.
 * A `ViewModel` is destroyed when the Activity is **finished** — Back out of the app, or the task swiped from
 * recents — and `onCleared` called `app.shutdown()`. That was the right answer to the question it was written
 * for, "nothing that must outlive a *screen* lives in a screen" (`docs/decisions.md` §19.4), and the wrong
 * answer one level up: with background playback, a dismissed task must not end the reading.
 *
 * An [Application] is the platform's only object whose lifetime is exactly the process's, which is what makes
 * it the place for this. `ReaderHost` holds the state; this class holds the real dependencies that build it.
 *
 * ## Why the wiring is here rather than in the host
 *
 * `ReaderHost` takes a [CoroutineScope] and a logger rather than creating them, which is what keeps it free of
 * Android types and testable on the JVM. Everything that genuinely needs a `Context` — the speech engine, the
 * DataStore, the Notion client — is constructed here and handed over.
 */
class ReaderApplication : Application() {

    private companion object {
        const val TAG = "NotionReader"
    }

    /**
     * The application's state and the scope that feeds it.
     *
     * Lazy rather than built in `onCreate`, so a process that only shows a launcher icon does not open a
     * TextToSpeech engine or a DataStore file. The first surface that reads it pays for it, and every later
     * one shares the same object — which is the point.
     */
    val tokenProvider: NotionTokenProvider by lazy {
        BuildConfigTokenProvider()
    }

    val documentCache: DocumentCache by lazy {
        migrateLegacyCacheDir()
        FileDocumentCache(File(noBackupFilesDir, "document-cache"))
    }

    val host: ReaderHost by lazy { buildHost() }

    private fun buildHost(): ReaderHost {
        var host: ReaderHost? = null

        val speechEngine = AndroidSpeechEngine(this) { message ->
            // The engine is built before the host exists, so the failure is held until there is something to
            // hold it. A device with no usable voice reports once, rather than producing a silent session
            // that looks like a working one.
            host?.reportSpeechUnavailable(message)
        }

        val sessionStore = DataStoreSessionStore(this)
        val savedQueueStore = DataStoreSavedQueueStore(this)
        val smartQueueStore = DataStoreSmartQueueStore(this)
        val settingsRepository = DataStoreSettingsRepository(context = this)

        val app = ReaderApp(
            session = ReaderSession(
                ReaderEngineImpl(
                    speechEngine = speechEngine,
                    initialDocument = ReaderDocument.Demo
                )
            ),
            noteViewer = NoteViewer(ReaderDocument.Demo),
            navigator = ReaderNavigator(),
            loadDocument = notionLoader(settingsRepository),
            sessionStore = sessionStore,
            savedQueueStore = savedQueueStore,
            smartQueueStore = smartQueueStore,
            settingsRepository = settingsRepository,
            speechEngine = speechEngine,
            documentCache = documentCache
        )

        return ReaderHost(
            app = app,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            log = { message -> Log.i(TAG, message) },
            networkState = AndroidNetworkState(this)
        ).also { built ->
            host = built
            built.start()
            built.startQueuePrefetch()
            built.startRecentNotesPrefetch()
        }
    }

    private class AndroidNetworkState(private val context: Context) : NetworkState {
        override val isUnmetered: Boolean
            get() {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
                return !cm.isActiveNetworkMetered
            }
    }

    /**
     * Builds the use case that fetches one page by reference.
     *
     * A fresh client per call, deliberately rather than sharing one: a retry that reuses a closed client is a
     * bug that only appears on the failure path, and this is called once per Note in a multi-Note queue rather
     * than once per screen.
     */
    private fun notionLoader(settingsRepo: SettingsRepository): LoadReaderDocument = DefaultLoadReaderDocument { reference, knownRemoteLastEditedAt, forceRefresh ->
        val token = tokenProvider.getToken() ?: BuildConfig.NOTION_TOKEN
        val remoteSource = NotionRepository(KtorNotionApi(token))
        val cachedRepository = CachedDocumentRepository(
            remote = remoteSource,
            cache = documentCache,
            isCachingEnabled = { settingsRepo.settings.value.cacheNotesForOffline },
            freshnessPolicy = { settingsRepo.settings.value.cacheFreshnessPolicy }
        )
        try {
            cachedRepository.getDocument(reference, knownRemoteLastEditedAt, forceRefresh)
        } finally {
            remoteSource.close()
        }
    }

    /** Also used by the Browse index, which is the same request with a different projection. */
    suspend fun readBrowseIndex(): List<ReaderIndexEntry> {
        val token = tokenProvider.getToken() ?: BuildConfig.NOTION_TOKEN
        val repository = NotionRepository(KtorNotionApi(token))
        return try {
            repository.getBrowseIndex()
        } finally {
            repository.close()
        }
    }

    /**
     * Called only in emulators and in tests, so it cannot be relied on for a real shutdown.
     *
     * That is fine: the process ending releases the speech engine and the DataStore, which is what
     * process-scoped ownership means. Recorded rather than implied, because a reader who expects `onTerminate`
     * to be the teardown on a device would be wrong.
     */
    private fun migrateLegacyCacheDir() {
        try {
            val legacyDir = File(filesDir, "document-cache")
            if (legacyDir.exists()) {
                val newDir = File(noBackupFilesDir, "document-cache")
                if (!newDir.exists()) {
                    legacyDir.renameTo(newDir)
                } else {
                    legacyDir.deleteRecursively()
                }
            }
        } catch (_: Exception) {
            // Non-fatal migration cleanup
        }
    }

    override fun onTerminate() {
        host.shutdown()
        super.onTerminate()
    }
}
