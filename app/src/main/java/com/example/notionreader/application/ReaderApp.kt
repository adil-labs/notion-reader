package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CacheRemovalReason
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.document.DocumentCacheStats
import com.example.notionreader.domain.document.CacheTelemetry
import com.example.notionreader.domain.discovery.AutoCacheStateStore
import com.example.notionreader.domain.discovery.DiscoveryModelManager
import com.example.notionreader.domain.discovery.ReaderIndexStore
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueMove
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.domain.queue.SavedQueueStore
import com.example.notionreader.domain.queue.SmartQueue
import com.example.notionreader.domain.queue.SmartQueueStore
import com.example.notionreader.domain.session.SessionStore
import com.example.notionreader.domain.session.StoredSession
import com.example.notionreader.domain.settings.NotionConnectionStatus
import com.example.notionreader.domain.settings.ReaderSettings
import com.example.notionreader.domain.settings.SettingsRepository
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.ReaderSectionSpan
import com.example.notionreader.player.SpeechEngine
import com.example.notionreader.player.SpeechEngineInfo
import com.example.notionreader.player.SpeechEngineState
import com.example.notionreader.player.playerCommandAvailability
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** How many sessions Home offers. The contract asks for "a small useful subset"; the rest live under Queues. */
const val HOME_HISTORY_LIMIT: Int = 3

/**
 * The application's state, and the coordinating facade wiring surfaces and operations together.
 *
 * ## Ownership
 *
 * The reason this type exists is that "who owns playback" is a decision that has to be made *once* and
 * then be visible, rather than being an emergent property of which screen happens to hold which
 * variable.
 *
 * | State | Owner | Lifetime |
 * |---|---|---|
 * | what is being read aloud, and where | [ReaderSession] | the process, across every navigation |
 * | what the Note screen is showing | [NoteViewer] | until another Note is loaded |
 * | which surface is on screen | [ReaderNavigator] | the process |
 *
 * Held by the Activity, above the surface being rendered — never by a screen.
 */
class ReaderApp(
    val session: ReaderSession,
    val noteViewer: NoteViewer,
    val navigator: ReaderNavigator,
    /** How any Note is fetched — the one the Note screen shows, and any Note in a queue. */
    private val loadDocument: LoadReaderDocument,
    val browse: BrowseModel = BrowseModel(),
    private val sessionStore: SessionStore? = null,
    private val savedQueueStore: SavedQueueStore? = null,
    private val smartQueueStore: SmartQueueStore? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val speechEngine: SpeechEngine? = null,
    private val documentCache: DocumentCache? = null,
    private val indexStore: ReaderIndexStore? = null,
    val autoCacheStateStore: AutoCacheStateStore? = null,
    val cacheTelemetry: CacheTelemetry? = null,
    private val isNotionConfigured: Boolean = true,
    val discovery: DiscoveryController? = null,
    val discoveryModel: DiscoveryModelManager? = null,
    /** Injected so that retention and recency are assertable without waiting for a clock. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    val recentNotes: RecentNotesController? = null,
    val storageStatus: StateFlow<com.example.notionreader.domain.document.StorageStatus> = kotlinx.coroutines.flow.MutableStateFlow(com.example.notionreader.domain.document.StorageStatus())
) {
    val notionConnectionStatus: NotionConnectionStatus
        get() {
            if (!isNotionConfigured) return NotionConnectionStatus.NOT_CONFIGURED
            val indexStatus = browse.state.value.index
            return if (indexStatus is BrowseIndexStatus.Error && !indexStatus.canRetry) {
                NotionConnectionStatus.CONNECTION_PROBLEM
            } else {
                NotionConnectionStatus.CONNECTED
            }
        }

    val settingsRepo: SettingsRepository = settingsRepository ?: InMemorySettingsRepository()
    val settings: StateFlow<ReaderSettings> = settingsRepo.settings

    init {
        applySettings(settingsRepo.settings.value)
    }

    private val queueLoader = QueueLoader(session, loadDocument)

    // --- Coordinators -------------------------------------------------------------------------------

    val cacheCoordinator = DocumentCacheCoordinator(
        documentCache = documentCache,
        autoCacheStateStore = autoCacheStateStore,
        cacheTelemetry = cacheTelemetry,
        loadDocument = loadDocument
    )

    val savedQueueCoordinator = SavedQueueCoordinator(
        savedQueueStore = savedQueueStore,
        session = session,
        navigator = navigator,
        clock = clock
    )

    val smartQueueCoordinator = SmartQueueCoordinator(
        smartQueueStore = smartQueueStore,
        session = session,
        navigator = navigator,
        clock = clock,
        getBrowseEntries = { browse.state.value.entries },
        postQueueMessage = savedQueueCoordinator::postMessage
    )

    val historyCoordinator = ListeningHistoryCoordinator(
        sessionStore = sessionStore,
        session = session,
        navigator = navigator,
        recentNotes = recentNotes,
        clock = clock,
        onBeforeLoadHistory = { cacheCoordinator.updateCacheStats() },
        loadPendingQueueNote = { loadPendingQueueNote() }
    )

    val exploreCoordinator = ExploreCoordinator(
        browse = browse,
        navigator = navigator
    )

    // --- Cache Operations ---------------------------------------------------------------------------

    val autoCacheReconciler: AutoCacheReconciler? get() = cacheCoordinator.autoCacheReconciler
    val cacheStats: StateFlow<DocumentCacheStats> get() = cacheCoordinator.cacheStats

    suspend fun updateCacheStats() = cacheCoordinator.updateCacheStats()
    suspend fun pruneCacheOlderThan(maxAgeMillis: Long, preservePageIds: Set<String> = emptySet()) =
        cacheCoordinator.pruneCacheOlderThan(maxAgeMillis, preservePageIds)
    suspend fun pruneCacheExcept(keepPageIds: Set<String>) = cacheCoordinator.pruneCacheExcept(keepPageIds)
    suspend fun removeCachedNotes(pageIds: Set<String>) = cacheCoordinator.removeCachedNotes(pageIds)
    suspend fun isNoteCached(pageId: String) = cacheCoordinator.isNoteCached(pageId)
    suspend fun prefetchNote(pageId: String, knownRemoteLastEditedAt: Instant? = null, forceRefresh: Boolean = false) =
        cacheCoordinator.prefetchNote(pageId, knownRemoteLastEditedAt, forceRefresh)

    suspend fun clearCache() {
        documentCache?.clear()
        discovery?.clearIndex(preserveTopicContinuity = true)
        updateCacheStats()
    }

    fun currentTimeMillis(): Long = clock()

    // --- Saved Queues -------------------------------------------------------------------------------

    val savedQueues: StateFlow<List<SavedQueue>> get() = savedQueueCoordinator.savedQueues
    val pendingAdd: StateFlow<List<QueuedNote>> get() = savedQueueCoordinator.pendingAdd
    val pendingAddCount: Int get() = savedQueueCoordinator.pendingAddCount
    val queueMessage: StateFlow<String?> get() = savedQueueCoordinator.queueMessage

    suspend fun loadSavedQueues() = savedQueueCoordinator.loadSavedQueues()
    suspend fun createSavedQueue(name: String) = savedQueueCoordinator.createSavedQueue(name)
    fun playSavedQueue(queueId: String) = savedQueueCoordinator.playSavedQueue(queueId)
    fun openSavedQueueDetails(queueId: String, fromFocusId: String? = null) =
        savedQueueCoordinator.openSavedQueueDetails(queueId, fromFocusId)
    fun openCreateSavedQueue() = savedQueueCoordinator.openCreateSavedQueue()
    fun savedQueueCards() = savedQueueCoordinator.savedQueueCards()
    fun savedQueueCard(id: String) = savedQueueCoordinator.savedQueueCard(id)
    fun beginAddToQueue(notes: List<QueuedNote>, fromFocusId: String? = null) =
        savedQueueCoordinator.beginAddToQueue(notes, fromFocusId)
    fun cancelPendingAdd() = savedQueueCoordinator.cancelPendingAdd()
    suspend fun addPendingToQueue(queueId: String) = savedQueueCoordinator.addPendingToQueue(queueId)
    fun consumeQueueMessage() = savedQueueCoordinator.consumeQueueMessage()
    suspend fun moveSavedQueueMember(queueId: String, pageId: String, move: QueueMove) =
        savedQueueCoordinator.moveSavedQueueMember(queueId, pageId, move)
    suspend fun removeSavedQueueMember(queueId: String, pageId: String) =
        savedQueueCoordinator.removeSavedQueueMember(queueId, pageId)
    suspend fun renameSavedQueue(queueId: String, newName: String) =
        savedQueueCoordinator.renameSavedQueue(queueId, newName)
    suspend fun deleteSavedQueue(queueId: String) = savedQueueCoordinator.deleteSavedQueue(queueId)

    // --- Smart Queues -------------------------------------------------------------------------------

    val smartQueues: StateFlow<List<SmartQueue>> get() = smartQueueCoordinator.smartQueues

    suspend fun loadSmartQueues() = smartQueueCoordinator.loadSmartQueues()
    fun smartQueueCards() = smartQueueCoordinator.smartQueueCards()
    fun smartQueueCard(id: String) = smartQueueCoordinator.smartQueueCard(id)
    suspend fun createSmartQueue(name: String, queryText: String) =
        smartQueueCoordinator.createSmartQueue(name, queryText)
    suspend fun renameSmartQueue(id: String, newName: String) =
        smartQueueCoordinator.renameSmartQueue(id, newName)
    suspend fun deleteSmartQueue(id: String) = smartQueueCoordinator.deleteSmartQueue(id)
    fun playSmartQueue(queueId: String) = smartQueueCoordinator.playSmartQueue(queueId)
    fun openSmartQueueDetails(queueId: String, fromFocusId: String? = null) =
        smartQueueCoordinator.openSmartQueueDetails(queueId, fromFocusId)
    fun openCreateSmartQueue() = smartQueueCoordinator.openCreateSmartQueue()
    fun matchingNotesForSmartQueue(queryText: String) =
        smartQueueCoordinator.matchingNotesForSmartQueue(queryText)

    // --- Listening History --------------------------------------------------------------------------

    val history: StateFlow<List<StoredSession>> get() = historyCoordinator.history
    val resumableSessions: List<StoredSession> get() = historyCoordinator.resumableSessions
    val completedSessions: List<StoredSession> get() = historyCoordinator.completedSessions

    fun continueListeningCards(limit: Int = HOME_HISTORY_LIMIT) = historyCoordinator.continueListeningCards(limit)
    fun listeningContinueEntries() = historyCoordinator.listeningContinueEntries()
    fun listeningCompletedEntries() = historyCoordinator.listeningCompletedEntries()
    fun homeRecentListeningEntries() = historyCoordinator.homeRecentListeningEntries()
    suspend fun loadHistory() = historyCoordinator.loadHistory()
    suspend fun persistSession() = historyCoordinator.persistSession()
    suspend fun continueSession(id: String) = historyCoordinator.continueSession(id)
    suspend fun replayCompleted(id: String) = historyCoordinator.replayCompleted(id)
    suspend fun clearHistory() = historyCoordinator.clearHistory()
    val isSessionResumable: Boolean get() = historyCoordinator.isSessionResumable
    fun continueListeningCard() = historyCoordinator.continueListeningCard()
    fun continueListening() = historyCoordinator.continueListening()

    // --- Explore Taxonomy ---------------------------------------------------------------------------

    fun exploreCatalog() = exploreCoordinator.exploreCatalog()
    fun exploreCards() = exploreCoordinator.exploreCards()
    fun openExploreCard(card: ExploreCard) = exploreCoordinator.openExploreCard(card)
    fun openExploreCatalog() = exploreCoordinator.openExploreCatalog()
    fun openExplore(queryText: String) = exploreCoordinator.openExplore(queryText)

    // --- Navigation & Inspection --------------------------------------------------------------------

    fun openNote() = navigator.open(
        ReaderDestination.Note,
        fromFocusId = ReaderFocus.NotePlay,
        subjectId = noteViewer.document.value.pageId.takeIf { it.isNotEmpty() } ?: noteViewer.pageId.value
    )
    fun openHome() = navigator.openTopLevel(ReaderDestination.Home)
    fun openDiscover() = navigator.openTopLevel(ReaderDestination.Discover)
    fun openInterests(fromFocusId: String) = navigator.open(ReaderDestination.Interests, fromFocusId)
    fun openSettingsDiscovery() = navigator.open(ReaderDestination.SettingsDiscovery, fromFocusId = "settings_row_discovery")

    suspend fun openRelatedNotes(pageId: String, fromFocusId: String? = "note_related") {
        navigator.open(ReaderDestination.RelatedNotes, fromFocusId = fromFocusId, subjectId = pageId)
    }

    fun prepareDiscoveryPreview(matches: List<com.example.notionreader.domain.discovery.SemanticMatch>, label: String = "Discovery recommendations") {
        discovery?.preparePreview(matches, label)
        navigator.open(ReaderDestination.GuidedListening, fromFocusId = "preview_guided")
    }

    fun playDiscoveryPreview() {
        if (discovery?.state?.value?.unavailablePreviewIds?.isNotEmpty() == true) return
        val notes = discovery?.state?.value?.preview.orEmpty().map { it.metadata.entry(discovery?.sourcePageId(it.pageId) ?: it.pageId).toQueuedNote() }
        playAll(notes, QueueOrigin.Discovery(discovery?.state?.value?.previewLabel ?: "Discovery recommendations"), "preview_play")
    }

    fun addDiscoveryPreviewToQueue() {
        if (discovery?.state?.value?.unavailablePreviewIds?.isNotEmpty() == true) return
        val notes = discovery?.state?.value?.preview.orEmpty().map { it.metadata.entry(discovery?.sourcePageId(it.pageId) ?: it.pageId).toQueuedNote() }
        beginAddToQueue(notes, "preview_save")
    }

    suspend fun selectDiscoverySeed(pageId: String) {
        if (com.example.notionreader.domain.discovery.canonicalSemanticId(pageId) !in discovery?.state?.value?.cachedPageIds.orEmpty()) return
        val interestId = navigator.state.value.currentSubjectId.takeIf { navigator.state.value.current == ReaderDestination.DiscoverySeeds }
        discovery?.saveInterest(interestId, "", pageId)
        navigator.back()
    }

    fun openBrowse() = navigator.openTopLevel(ReaderDestination.Browse)
    fun openQueues() = navigator.openTopLevel(ReaderDestination.Queues)
    fun openSettings() = navigator.openTopLevel(ReaderDestination.Settings)
    fun openSettingsPlayback(fromFocusId: String? = "settings_row_playback") =
        navigator.open(ReaderDestination.SettingsPlayback, fromFocusId = fromFocusId)
    fun openSettingsSpeech(fromFocusId: String? = "settings_row_speech") =
        navigator.open(ReaderDestination.SettingsSpeech, fromFocusId = fromFocusId)
    fun openSettingsOffline(fromFocusId: String? = "settings_row_offline") =
        navigator.open(ReaderDestination.SettingsOffline, fromFocusId = fromFocusId)
    fun openSettingsAppearance(fromFocusId: String? = "settings_row_appearance") =
        navigator.open(ReaderDestination.SettingsAppearance, fromFocusId = fromFocusId)
    fun openSettingsNotion(fromFocusId: String? = "settings_row_notion") =
        navigator.open(ReaderDestination.SettingsNotion, fromFocusId = fromFocusId)
    fun openSettingsPrivacy(fromFocusId: String? = "settings_row_privacy") =
        navigator.open(ReaderDestination.SettingsPrivacy, fromFocusId = fromFocusId)
    fun openSettingsAbout(fromFocusId: String? = "settings_row_about") =
        navigator.open(ReaderDestination.SettingsAbout, fromFocusId = fromFocusId)

    suspend fun openNote(
        pageId: String,
        knownRemoteLastEditedAt: Instant? = null,
        forceRefresh: Boolean = false,
        fromFocusId: String = pageId
    ) {
        navigator.open(ReaderDestination.Note, fromFocusId = fromFocusId, subjectId = pageId)
        val openedStop = navigator.state.value.secondary.last()
        val generation = recentNotes?.generation
        if (noteViewer.load(discovery?.sourcePageId(pageId) ?: pageId, loadDocument, knownRemoteLastEditedAt, forceRefresh)) {
            if (navigator.state.value.secondary.lastOrNull() === openedStop) {
                generation?.let { recentNotes.recordSuccessfulOpen(noteViewer.document.value.pageId, it) }
            }
        }
    }

    suspend fun refreshCurrentNote() {
        noteViewer.pageId.value?.let { pageId ->
            noteViewer.load(pageId, loadDocument, knownRemoteLastEditedAt = null, forceRefresh = true)
            updateCacheStats()
        }
    }

    suspend fun retryNoteLoad() {
        noteViewer.pageId.value?.let { noteViewer.load(it, loadDocument) }
    }

    suspend fun loadNote(pageId: String, loader: LoadReaderDocument) = noteViewer.load(pageId, loader)

    suspend fun loadBrowseIndex(
        forceRefresh: Boolean = true,
        loader: suspend () -> List<ReaderIndexEntry>
    ) {
        if (!forceRefresh) {
            val cached = indexStore?.read()
            val now = Instant.ofEpochMilli(clock())
            if (cached != null && cached.isFresh(now)) {
                browse.setLoadedEntries(cached.entries)
                return
            }
        }

        browse.load {
            val entries = loader()
            indexStore?.write(
                com.example.notionreader.domain.discovery.CachedReaderIndex(
                    entries = entries,
                    fetchedAt = Instant.ofEpochMilli(clock())
                )
            )
            entries
        }
    }

    suspend fun loadCachedIndex() {
        val cached = indexStore?.read() ?: return
        if (!browse.state.value.hasIndex) {
            browse.setLoadedEntries(cached.entries)
        }
    }

    fun playAllVisibleResults() = playAll(browse.state.value.results.toQueuedNotes())

    // --- Playback & Transport -----------------------------------------------------------------------

    fun playNote() {
        session.start(noteViewer.document.value)
        navigator.open(ReaderDestination.Player, fromFocusId = ReaderFocus.NotePlay)
    }

    fun togglePlayPause() {
        if (session.isPlaying) session.pause() else session.play()
    }

    fun invoke(command: PlayerCommand, index: Int? = null): Boolean {
        val availability = playerCommandAvailability(session.state.value, session.queueState.value)
        if (!availability.allows(command)) return false

        return when (command) {
            PlayerCommand.Play -> {
                session.play()
                true
            }
            PlayerCommand.Pause -> {
                session.pause()
                true
            }
            PlayerCommand.TogglePlayback -> {
                togglePlayPause()
                true
            }
            PlayerCommand.NextSection -> {
                session.nextSection()
                true
            }
            PlayerCommand.PreviousSection -> {
                session.previousSection()
                true
            }
            PlayerCommand.NextPart -> {
                session.nextPart()
                true
            }
            PlayerCommand.PreviousPart -> {
                session.previousPart()
                true
            }
            PlayerCommand.RestartNote -> {
                session.restartNote()
                true
            }
            PlayerCommand.NextNote -> nextNote()
            PlayerCommand.PreviousNote -> previousNote()
            PlayerCommand.JumpToNote -> index?.let { jumpToNote(it) } ?: false
            PlayerCommand.OpenQueue,
            PlayerCommand.OpenMorePlaybackControls -> false
        }
    }

    fun jumpToSection(span: ReaderSectionSpan) {
        session.jumpToSection(span)
    }

    fun playAll(notes: List<QueuedNote>, origin: QueueOrigin = QueueOrigin.BrowseResults, fromFocusId: String = ReaderFocus.BrowsePlayAll) {
        if (notes.isEmpty()) return
        session.start(ActiveQueue.snapshotOf(notes, origin))
        navigator.open(ReaderDestination.Player, fromFocusId = fromFocusId)
    }

    fun onNoteFinished() = session.onCurrentNoteFinished()
    fun nextNote(): Boolean = session.nextNote()
    fun previousNote(): Boolean = session.previousNote()
    fun jumpToNote(index: Int): Boolean = session.jumpToNote(index)

    suspend fun loadPendingQueueNote(): Boolean {
        val loaded = queueLoader.loadPendingQueueNote()
        updateCacheStats()
        return loaded
    }

    fun retryQueueNote() {
        session.retryCurrentNote()
    }

    fun focusHandled() = navigator.focusHandled()
    fun back(): Boolean = navigator.back()

    // --- Settings & Diagnostics ---------------------------------------------------------------------

    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        settingsRepo.updateSettings(transform)
    }

    fun applySettings(current: ReaderSettings) {
        session.continueToNextNote = current.continueToNextNote
        session.pauseBetweenNotesMs = current.pauseBetweenNotes.delayMs
        session.announcementVerbosity = current.playbackAnnouncements
        speechEngine?.setEngine(current.ttsEnginePackage)

        val rate = when (val sr = current.speechRate) {
            is com.example.notionreader.domain.settings.SpeechRate.SystemDefault ->
                speechEngine?.systemSpeechRate() ?: 1.0f
            is com.example.notionreader.domain.settings.SpeechRate.Multiplier ->
                sr.value
        }
        val pitch = when (current.voicePitch) {
            com.example.notionreader.domain.settings.VoicePitch.SYSTEM_DEFAULT ->
                speechEngine?.systemPitch() ?: 1.0f
            else -> current.voicePitch.factor
        }
        speechEngine?.setSpeechRate(rate)
        speechEngine?.setPitch(pitch)
    }

    val speechEngineState: StateFlow<SpeechEngineState> =
        speechEngine?.engineState ?: kotlinx.coroutines.flow.MutableStateFlow(SpeechEngineState())

    fun getAvailableSpeechEngines(): List<SpeechEngineInfo> =
        speechEngine?.getAvailableEngines() ?: emptyList()

    suspend fun clearAllLocalData() {
        recentNotes?.clear()
        discoveryModel?.delete()
        discovery?.clearIndex()
        discovery?.clearInterests()
        historyCoordinator.clearHistory()
        savedQueueCoordinator.clearSavedQueues()
        smartQueueCoordinator.clearSmartQueues()
        cacheCoordinator.clearCache(CacheRemovalReason.RESET)
        indexStore?.clear()
        settingsRepo.resetToDefaults()
        updateCacheStats()
    }

    fun shutdown() = session.shutdown()
}
