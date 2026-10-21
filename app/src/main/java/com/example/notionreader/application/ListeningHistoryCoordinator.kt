package com.example.notionreader.application

import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.session.ListeningHistory
import com.example.notionreader.domain.session.SessionStore
import com.example.notionreader.domain.session.StoredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates playback history retention, session persistence, and Continue Listening projections.
 */
class ListeningHistoryCoordinator(
    private val sessionStore: SessionStore?,
    private val session: ReaderSession,
    private val navigator: ReaderNavigator,
    private val recentNotes: RecentNotesController?,
    private val clock: () -> Long,
    private val onBeforeLoadHistory: suspend () -> Unit = {},
    private val loadPendingQueueNote: suspend () -> Boolean
) {
    private val _history = MutableStateFlow<List<StoredSession>>(emptyList())
    val history: StateFlow<List<StoredSession>> = _history.asStateFlow()

    suspend fun loadHistory() {
        recentNotes?.refresh()
        onBeforeLoadHistory()
        val stored = sessionStore?.read() ?: return
        _history.value = ListeningHistory.retain(stored, clock())
    }

    suspend fun persistSession() {
        if (session.queueState.value.queue == null) return
        val next = historyIncludingLiveSession()
        _history.value = next
        sessionStore?.write(next)
    }

    fun historyIncludingLiveSession(): List<StoredSession> {
        val live = session.snapshot(clock()) ?: return _history.value
        return ListeningHistory.retain(_history.value + live, clock(), keepId = live.id)
    }

    val resumableSessions: List<StoredSession>
        get() = ListeningHistory.unfinished(historyIncludingLiveSession())

    val completedSessions: List<StoredSession>
        get() = ListeningHistory.completed(historyIncludingLiveSession())

    fun continueListeningCards(limit: Int = HOME_HISTORY_LIMIT): List<ContinueListeningCard> =
        resumableSessions.take(limit).map { it.toCard() }

    fun listeningContinueEntries(): List<HistoryEntry> = resumableSessions.map { it.toHistoryEntry() }

    fun listeningCompletedEntries(): List<HistoryEntry> = completedSessions.map { it.toHistoryEntry() }

    fun homeRecentListeningEntries(): List<HistoryEntry> {
        val primary = continueListeningCards(1).firstOrNull()?.sessionId
        return historyIncludingLiveSession().filter { it.id != primary }
            .sortedWith(compareByDescending<StoredSession> { it.lastActivityAt }.thenBy { it.id }).take(3)
            .map { stored ->
                stored.toHistoryEntry().copy(
                    originLabel = when (val origin = stored.origin) {
                        is QueueOrigin.Saved -> "Saved Queue: ${origin.name}"
                        is QueueOrigin.Smart -> "Smart Queue: ${origin.name}"
                        is QueueOrigin.Discovery -> origin.label
                        QueueOrigin.SingleNote -> "Single Note"
                        QueueOrigin.BrowseResults -> "Browse selection"
                    }
                )
            }
    }

    suspend fun continueSession(id: String) {
        val record = resumableSessions.firstOrNull { it.id == id } ?: return
        if (record.id != session.id) session.restore(record)
        navigator.open(ReaderDestination.Player, fromFocusId = id)
        if (session.queueState.value.awaiting) loadPendingQueueNote()
    }

    suspend fun replayCompleted(id: String) {
        val record = ListeningHistory.completed(_history.value).firstOrNull { it.id == id } ?: return
        session.replay(record)
        navigator.open(ReaderDestination.Player, fromFocusId = id)
        loadPendingQueueNote()
    }

    suspend fun clearHistory() {
        recentNotes?.clear()
        _history.value = emptyList()
        sessionStore?.write(emptyList())
    }

    fun resetState() {
        _history.value = emptyList()
    }

    val isSessionResumable: Boolean
        get() = session.queueState.value.let { it.queue != null && !it.completed }

    fun continueListeningCard(): ContinueListeningCard? =
        if (isSessionResumable) describeLive() else null

    fun continueListening() {
        if (isSessionResumable) navigator.open(ReaderDestination.Player)
    }

    fun describeLive(): ContinueListeningCard {
        val queueState = session.queueState.value
        val state = session.state.value

        return ContinueListeningCard(
            sessionId = session.id.orEmpty(),
            title = queueState.currentItem?.title ?: session.document.value.title,
            queuePosition = queueState.positionDescription,
            stateDescription = when {
                queueState.isStalled -> "Could not load"
                queueState.awaiting -> "Loading…"
                else -> state.playbackStateDescription
            },
            isPlaying = state.isPlaying,
            lastActivityAt = clock()
        )
    }

    private fun StoredSession.toCard(): ContinueListeningCard {
        val isLive = id == session.id

        return ContinueListeningCard(
            sessionId = id,
            title = currentNote?.title ?: notes.firstOrNull()?.title.orEmpty(),
            queuePosition = if (notes.size > 1) "Note ${index + 1} of ${notes.size}" else null,
            stateDescription = if (isLive) describeLive().stateDescription else "Paused",
            isPlaying = isLive && session.isPlaying,
            lastActivityAt = lastActivityAt
        )
    }

    private fun StoredSession.toHistoryEntry(): HistoryEntry {
        val isLive = id == session.id

        return HistoryEntry(
            sessionId = id,
            title = currentNote?.title ?: notes.firstOrNull()?.title.orEmpty(),
            membership = when {
                completed -> if (notes.size == 1) "1 note" else "${notes.size} notes"
                notes.size > 1 -> "Note ${index + 1} of ${notes.size}"
                else -> null
            },
            stateDescription = when {
                completed -> "Completed"
                isLive -> describeLive().stateDescription
                else -> "Paused"
            },
            isPlaying = isLive && session.isPlaying && !completed,
            isCompleted = completed,
            lastActivityAt = lastActivityAt
        )
    }
}
