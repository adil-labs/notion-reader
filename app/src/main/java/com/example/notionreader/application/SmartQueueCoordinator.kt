package com.example.notionreader.application

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.SmartQueue
import com.example.notionreader.domain.queue.SmartQueueEditOutcome
import com.example.notionreader.domain.queue.SmartQueueEffect
import com.example.notionreader.domain.queue.SmartQueueFilter
import com.example.notionreader.domain.queue.SmartQueueStore
import com.example.notionreader.domain.queue.SmartQueues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates Smart Queue persistence, query-based queue evaluation, and playback.
 */
class SmartQueueCoordinator(
    private val smartQueueStore: SmartQueueStore?,
    private val session: ReaderSession,
    private val navigator: ReaderNavigator,
    private val clock: () -> Long,
    private val getBrowseEntries: () -> List<ReaderIndexEntry>,
    private val postQueueMessage: (String) -> Unit
) {
    private val smartQueuesManager: SmartQueues? = smartQueueStore?.let { SmartQueues(it, clock) }

    private val _smartQueues = MutableStateFlow<List<SmartQueue>>(emptyList())
    val smartQueues: StateFlow<List<SmartQueue>> = _smartQueues.asStateFlow()

    suspend fun loadSmartQueues() {
        _smartQueues.value = smartQueuesManager?.list() ?: return
    }

    fun smartQueueCards(): List<SmartQueueCard> {
        val entries = getBrowseEntries()
        return _smartQueues.value.map { queue ->
            val count = entries.count { SmartQueueFilter.matches(it, queue.queryText) }
            val memberDesc = if (count == 1) "1 note" else "$count notes"
            SmartQueueCard(
                queueId = queue.id,
                name = queue.name,
                queryText = queue.queryText,
                memberDescription = memberDesc,
                lastEditedAt = queue.lastEditedAt
            )
        }
    }

    fun smartQueueCard(id: String): SmartQueueCard? = smartQueueCards().firstOrNull { it.queueId == id }

    suspend fun createSmartQueue(name: String, queryText: String): SmartQueueEditOutcome {
        val manager = smartQueuesManager ?: return SmartQueueEditOutcome.unavailable()
        val outcome = manager.create(name, queryText)
        if (outcome.effect is SmartQueueEffect.Created) {
            _smartQueues.value = manager.list()
            postQueueMessage("Created Smart Queue ${name.trim()}.")
        }
        return outcome
    }

    suspend fun renameSmartQueue(id: String, newName: String): SmartQueueEditOutcome {
        val manager = smartQueuesManager ?: return SmartQueueEditOutcome.unavailable()
        val outcome = manager.rename(id, newName)
        if (outcome.effect is SmartQueueEffect.Renamed) {
            _smartQueues.value = manager.list()
            postQueueMessage("Renamed Smart Queue to ${newName.trim()}.")
        }
        return outcome
    }

    suspend fun deleteSmartQueue(id: String): SmartQueueEditOutcome {
        val manager = smartQueuesManager ?: return SmartQueueEditOutcome.unavailable()
        val queue = _smartQueues.value.firstOrNull { it.id == id }
        val outcome = manager.delete(id)
        if (outcome.effect is SmartQueueEffect.Deleted) {
            _smartQueues.value = manager.list()
            if (queue != null) {
                postQueueMessage("Deleted Smart Queue ${queue.name}.")
            }
            if (navigator.state.value.current == ReaderDestination.SmartQueueDetails &&
                navigator.state.value.currentSubjectId == id
            ) {
                navigator.back()
            }
        }
        return outcome
    }

    fun playSmartQueue(queueId: String): Boolean {
        val queue = _smartQueues.value.firstOrNull { it.id == queueId } ?: return false
        val entries = getBrowseEntries()
        val matching = SmartQueueFilter.filter(entries, queue.queryText)
        val notes = if (matching.isNotEmpty()) {
            matching.map { QueuedNote(it.pageId, it.title) }
        } else {
            listOf(QueuedNote(pageId = "smart-$queueId", title = "${queue.name} (${queue.queryText})"))
        }

        session.start(
            ActiveQueue.snapshotOf(
                notes,
                QueueOrigin.Smart(queueId = queue.id, name = queue.name)
            )
        )
        navigator.open(ReaderDestination.Player)
        return true
    }

    fun openSmartQueueDetails(queueId: String, fromFocusId: String? = null) {
        navigator.open(ReaderDestination.SmartQueueDetails, fromFocusId = fromFocusId, subjectId = queueId)
    }

    fun openCreateSmartQueue() {
        navigator.open(ReaderDestination.CreateSmartQueue, fromFocusId = ReaderFocus.SmartQueueCreate)
    }

    fun matchingNotesForSmartQueue(queryText: String): List<ReaderIndexEntry> {
        val entries = getBrowseEntries()
        return entries.filter { it.title.contains(queryText, ignoreCase = true) }
    }

    suspend fun clearSmartQueues() {
        _smartQueues.value = emptyList()
        smartQueueStore?.clear()
    }
}
