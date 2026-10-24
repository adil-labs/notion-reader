package com.example.notionreader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.example.notionreader.application.DefaultLoadReaderDocument
import com.example.notionreader.application.DocumentLoadCoordinator
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discoveryForeground = MutableStateFlow(false)
    private val discoveryResourcesAvailable = MutableStateFlow(true)
    private val discoveryPower = MutableStateFlow(com.example.notionreader.application.DiscoveryPowerState())
    private val discoveryBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else null
            discoveryPower.value = com.example.notionreader.application.DiscoveryPowerState(percent,
                connected = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0)
        }
    }
    private var processDiscovery: com.example.notionreader.application.DiscoveryController? = null
    private lateinit var discoveryWork: suspend () -> Unit
    private lateinit var discoveryCoordinator: com.example.notionreader.application.DiscoveryWorkCoordinator
    private val discoveryScheduler by lazy { DiscoveryScheduler(this) }
    @Volatile private var scheduledDiscovery = false
    @Volatile private var activeEffort = com.example.notionreader.domain.settings.DiscoveryEffort.CHARGING_ONLY
    private val scheduledWindowLock = Mutex()
    private lateinit var verifyDiscoveryModel: suspend () -> Unit

    internal suspend fun runScheduledDiscovery(): com.example.notionreader.application.DiscoveryWindowResult =
        scheduledWindowLock.withLock {
            host
            verifyDiscoveryModel()
            val memory = android.app.ActivityManager.MemoryInfo()
            (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
            discoveryResourcesAvailable.value = !memory.lowMemory
            scheduledDiscovery = true
            try { discoveryCoordinator.runWindow(120_000) }
            finally { scheduledDiscovery = false }
        }

    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, discoveryBatteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                started++; discoveryResourcesAvailable.value = true; discoveryForeground.value = true
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                started = (started - 1).coerceAtLeast(0); discoveryForeground.value = started > 0
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            discoveryResourcesAvailable.value = false
            discoveryScope.launch { processDiscovery?.releaseModel() }
        }
    }

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
        FileDocumentCache(File(noBackupFilesDir, "document-cache"),
            byteLimit = { storagePreferences?.noteCacheLimitBytes ?: Long.MAX_VALUE },
            protectedPageIds = { protectedCacheIds() }, writeGate = storageWrites)
    }

    private val settingsRepository by lazy { DataStoreSettingsRepository(context = this) }
    @Volatile private var storagePreferences: com.example.notionreader.domain.settings.ReaderSettings? = null
    private val storageWrites = object : com.example.notionreader.domain.document.StorageWriteGate {
        override suspend fun <T> write(category: com.example.notionreader.domain.document.StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T {
            // StateFlow's initial defaults must never shrink a restored explicit limit.
            storagePreferences = settingsRepository.readCurrentSettings()
            return storageBudget.write(category, additionalPeakBytes, block)
        }
    }
    @Volatile private var protectedCacheIds: () -> Set<String> = { emptySet() }
    private fun directoryBytes(directory: File): Long = if (!directory.exists()) 0L else
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private val derivedNames = listOf("semantic-index", "semantic-passage-progress", "discovery-topics", "discovery-topic-checkpoint")
    private val storageBudget by lazy {
        val availability = com.example.notionreader.platform.AndroidStorageAvailability(noBackupFilesDir)
        com.example.notionreader.application.StorageBudgetCoordinator(
            { storagePreferences ?: com.example.notionreader.domain.settings.ReaderSettings() }, { availability.snapshot()?.let { com.example.notionreader.domain.document.StorageSnapshot(it.first, it.second) } },
            { (derivedNames + listOf("document-cache", "discovery-model")).sumOf { directoryBytes(File(noBackupFilesDir, it)) } },
            { derivedNames.sumOf { directoryBytes(File(noBackupFilesDir, it)) } },
            { directoryBytes(File(noBackupFilesDir, "discovery-model")) })
    }

    val loadCoordinator: DocumentLoadCoordinator by lazy {
        DocumentLoadCoordinator()
    }

    val cacheTelemetry: com.example.notionreader.domain.document.CacheTelemetry by lazy {
        com.example.notionreader.domain.document.CacheTelemetry()
    }

    val host: ReaderHost by lazy { buildHost() }

    private fun buildHost(): ReaderHost {
        var host: ReaderHost? = null

        val speechEngine = AndroidSpeechEngine(this, onUnavailable = { message ->
            // The engine is built before the host exists, so the failure is held until there is something to
            // hold it. A device with no usable voice reports once, rather than producing a silent session
            // that looks like a working one.
            host?.reportSpeechUnavailable(message)
        }, diagnostics = if (BuildConfig.DEBUG) { event, utterance, status ->
            Log.d("SpeechTrace", "$event utterance=$utterance status=$status")
        } else null)

        val sessionStore = DataStoreSessionStore(this)
        val savedQueueStore = DataStoreSavedQueueStore(this)
        val smartQueueStore = DataStoreSmartQueueStore(this)

        val indexStore = com.example.notionreader.data.local.FileReaderIndexStore(
            directory = File(noBackupFilesDir, "index-cache")
        )

        val autoCacheStateStore = com.example.notionreader.data.local.FileAutoCacheStateStore(
            directory = File(noBackupFilesDir, "cache-metadata")
        )

        val semanticDirectory = File(noBackupFilesDir, "semantic-index")
        val passageProgressDirectory = File(noBackupFilesDir, "semantic-passage-progress")
        val topicDirectory = File(noBackupFilesDir, "discovery-topics")
        val topicCheckpointDirectory = File(noBackupFilesDir, "discovery-topic-checkpoint")
        fun storageBytes(directory: File): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L
        val semanticIndex = com.example.notionreader.data.local.FileSemanticIndexStore(semanticDirectory,
            writeGate = storageWrites,
            otherBytes = { storageBytes(passageProgressDirectory) + storageBytes(topicDirectory) + storageBytes(topicCheckpointDirectory) })
        val passageProgressStore = com.example.notionreader.data.local.FileSemanticIndexStore(passageProgressDirectory,
            writeGate = storageWrites,
            otherBytes = { storageBytes(semanticDirectory) + storageBytes(topicDirectory) + storageBytes(topicCheckpointDirectory) })
        val topicStore = com.example.notionreader.data.local.FileTopicStore(topicDirectory,
            writeGate = storageWrites,
            otherBytes = { storageBytes(passageProgressDirectory) + storageBytes(semanticDirectory) + storageBytes(topicCheckpointDirectory) })
        val topicCheckpointStore = com.example.notionreader.data.local.FileTopicStore(topicCheckpointDirectory,
            writeGate = storageWrites,
            otherBytes = { storageBytes(passageProgressDirectory) + storageBytes(semanticDirectory) + storageBytes(topicDirectory) })
        val interestStore = com.example.notionreader.data.local.FileDiscoveryInterestStore(File(noBackupFilesDir, "discovery-interests"))
        val recentNotes = com.example.notionreader.application.RecentNotesController(
            com.example.notionreader.data.local.FileRecentNoteHistoryStore(File(noBackupFilesDir, "recent-note-history")), documentCache)
        lateinit var discovery: com.example.notionreader.application.DiscoveryController
        lateinit var discoveryApp: ReaderApp
        val discoveryModel = com.example.notionreader.data.discovery.LocalDiscoveryModelManager(
            File(noBackupFilesDir, "discovery-model"), discoveryScope,
            writeGate = storageWrites,
            unmetered = { AndroidNetworkState(this).isUnmetered },
            onInstalled = { discovery.resume() },
            onDeleted = { discovery.releaseModel(); discovery.clearIndex() },
            artifacts = com.example.notionreader.data.discovery.GemmaModelPack.artifacts,
            repository = com.example.notionreader.data.discovery.GemmaModelPack.repository,
            revision = com.example.notionreader.data.discovery.GemmaModelPack.revision,
            description = com.example.notionreader.data.discovery.GemmaModelPack.description
        )
        discovery = com.example.notionreader.application.DiscoveryController(
            documentCache, semanticIndex, interestStore,
            com.example.notionreader.data.discovery.GemmaEmbeddingProvider(discoveryModel.modelDirectory),
            modelAvailable = { discoveryModel.state.value.installed },
            recommendationThreshold = com.example.notionreader.data.discovery.GemmaModelPack.recommendationThreshold,
            topicPolicy = com.example.notionreader.data.discovery.GemmaModelPack.topicPolicy,
            queryTimings = { timings ->
                if (BuildConfig.DEBUG) android.util.Log.d("DiscoveryTiming", timings.toString())
            },
            topicStore = topicStore, topicCheckpointStore = topicCheckpointStore, passageProgressStore = passageProgressStore,
            invalidationAcknowledgementLimit = { recentNotes.processedInvalidationGeneration },
            mayContinueIndexing = { discoveryCoordinator.windowOpen && (discoveryForeground.value || scheduledDiscovery) &&
                discoveryResourcesAvailable.value && discoveryPower.value.allows(activeEffort) &&
                discoveryPower.value.allows(discoveryApp.settings.value.discoveryEffort) &&
                discoveryModel.state.value.installed && !discoveryApp.session.state.value.isPlaying }
        )
        processDiscovery = discovery

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
            documentCache = documentCache,
            indexStore = indexStore,
            autoCacheStateStore = autoCacheStateStore,
            cacheTelemetry = cacheTelemetry,
            discovery = discovery,
            discoveryModel = discoveryModel,
            storageStatus = storageBudget.status,
            recentNotes = recentNotes,
            isNotionConfigured = BuildConfig.NOTION_TOKEN.isNotBlank()
        )

        discoveryApp = app
        discoveryScope.launch {
            app.navigator.state.map { it.inspectsDiscovery }.distinctUntilChanged().collect {
                discovery.setTopicsInspected(it)
            }
        }
        protectedCacheIds = {
            val queue = app.session.queueState.value
            com.example.notionreader.application.cacheProtectedPageIds(queue.items.map { it.pageId }, queue.index)
        }
        discoveryScope.launch {
            combine(settingsRepository.settings, app.session.queueState) { _, queue -> queue }.collect {
                storagePreferences = settingsRepository.readCurrentSettings()
                storageBudget.refresh()
                documentCache.enforceBudget(protectedCacheIds())
                app.updateCacheStats()
                discovery.requestAutomaticContinuation()
            }
        }
        verifyDiscoveryModel = { discoveryModel.checkInstalled() }
        discoveryCoordinator = com.example.notionreader.application.DiscoveryWorkCoordinator(
            android.os.SystemClock::elapsedRealtime) {
            activeEffort = settingsRepository.readCurrentSettings().discoveryEffort
            storageBudget.refresh()
            discovery.updateWorkAvailability(activeEffort, when {
                app.session.state.value.isPlaying -> com.example.notionreader.application.DiscoveryExecutionAvailability.WaitingForPlayback
                !discoveryResourcesAvailable.value -> com.example.notionreader.application.DiscoveryExecutionAvailability.WaitingForResources
                !discoveryForeground.value && !scheduledDiscovery -> com.example.notionreader.application.DiscoveryExecutionAvailability.WaitingForAndroid
                else -> com.example.notionreader.application.DiscoveryExecutionAvailability.Available
            })
            discovery.updatePowerState(discoveryPower.value)
            val eligible = (discoveryForeground.value || scheduledDiscovery) && discoveryResourcesAvailable.value &&
                discoveryPower.value.allows(activeEffort) && discoveryModel.state.value.installed && !app.session.state.value.isPlaying
            app.recentNotes?.refresh()
            discovery.reconcile(app.browse.state.value.entries, eligible)
            com.example.notionreader.application.discoveryWindowResult(discoveryModel.state.value.installed,
                discovery.state.value, eligible, discovery.hasPendingWork)
        }
        discoveryWork = {
            discoveryModel.checkInstalled()
            discoveryScope.launch {
                discoveryScheduler.completions().map { infos -> infos.map { it.id to it.state } }.distinctUntilChanged().collect { infos ->
                    if (infos.any { it.second.isFinished } && discovery.hasPendingWork && !discovery.state.value.paused)
                        discovery.requestAutomaticContinuation()
                }
            }
            val inputs = combine(
                documentCache.revision,
                app.browse.state.map { it.entries }.distinctUntilChanged(),
                discoveryForeground,
                app.session.state.map { it.isPlaying }.distinctUntilChanged(),
                combine(discovery.revision, app.settings) { revision, settings -> revision to settings.discoveryEffort }
            ) { _, entries, foreground, playing, _ -> Triple(entries, foreground, playing) }
            val constraints = combine(discoveryResourcesAvailable, discoveryPower) { resources, power -> resources to power }
            combine(inputs, discoveryModel.state.map { it.installed }.distinctUntilChanged(), constraints) { input, installed, limits ->
                Triple(input.first, input.second && !input.third && installed && limits.first && limits.second.eligible, installed) to limits.second
            }.conflate().collect { (work, power) ->
                val (entries, eligible, installed) = work
                discoveryCoordinator.runWindow(120_000)
                if (!installed || discovery.state.value.paused) discoveryScheduler.cancel()
                else discoveryScheduler.request(settingsRepository.readCurrentSettings().discoveryEffort, discovery.hasPendingWork)
                if (!installed) discovery.releaseModel()
            }
        }

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
            discoveryScope.launch { discoveryWork() }
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
    private fun notionLoader(settingsRepo: SettingsRepository): LoadReaderDocument = DefaultLoadReaderDocument { reference, knownRemoteLastEditedAt, forceRefresh, purpose ->
        loadCoordinator.load(reference, purpose, forceRefresh) {
            val token = tokenProvider.getToken() ?: BuildConfig.NOTION_TOKEN
            val remoteSource = NotionRepository(KtorNotionApi(token))
            val cachedRepository = CachedDocumentRepository(
                remote = remoteSource,
                cache = documentCache,
                isCachingEnabled = { settingsRepo.settings.value.cacheNotesForOffline },
                freshnessPolicy = { settingsRepo.settings.value.cacheFreshnessPolicy },
                telemetry = cacheTelemetry
            )
            try {
                cachedRepository.getDocument(reference, knownRemoteLastEditedAt, forceRefresh, purpose)
            } finally {
                remoteSource.close()
            }
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
