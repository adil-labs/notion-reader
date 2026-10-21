package com.example.notionreader.application

import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueEdit
import com.example.notionreader.domain.queue.QueueEditOutcome
import com.example.notionreader.domain.queue.QueueEffect
import com.example.notionreader.domain.queue.QueueMove
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.domain.queue.SavedQueueDraft
import com.example.notionreader.domain.queue.SavedQueueStore
import com.example.notionreader.domain.queue.SavedQueues
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates Saved Queue persistence, transactional edits, and Queue Destination additions.
 */
class SavedQueueCoordinator(
    private val savedQueueStore: SavedQueueStore?,
    private val session: ReaderSession,
    private val navigator: ReaderNavigator,
    private val clock: () -> Long
) {
    private val queues: SavedQueues? = savedQueueStore?.let { SavedQueues(it, clock) }

    private val _savedQueues = MutableStateFlow<List<SavedQueue>>(emptyList())
    val savedQueues: StateFlow<List<SavedQueue>> = _savedQueues.asStateFlow()

    private val _pendingAdd = MutableStateFlow<List<QueuedNote>>(emptyList())
    val pendingAdd: StateFlow<List<QueuedNote>> = _pendingAdd.asStateFlow()

    val pendingAddCount: Int get() = _pendingAdd.value.size

    private fun setPendingAdd(notes: List<QueuedNote>) {
        _pendingAdd.value = notes
    }

    private val _queueMessage = MutableStateFlow<String?>(null)
    val queueMessage: StateFlow<String?> = _queueMessage.asStateFlow()

    fun postMessage(message: String) {
        _queueMessage.value = message
    }

    suspend fun loadSavedQueues() {
        _savedQueues.value = queues?.all() ?: return
    }

    suspend fun createSavedQueue(name: String): QueueEditOutcome {
        val incoming = _pendingAdd.value
        val edit = QueueEdit.Create(SavedQueueDraft(name), id = UUID.randomUUID().toString())
        val outcome = transact(edit)

        val created = (outcome.effect as? QueueEffect.Created)?.queue ?: return outcome
        if (incoming.isEmpty()) return outcome

        setPendingAdd(incoming)
        return addPendingToQueue(created.id)
    }

    fun playSavedQueue(queueId: String): Boolean {
        val queue = _savedQueues.value.firstOrNull { it.id == queueId } ?: return false
        if (queue.notes.isEmpty()) return false

        session.start(
            ActiveQueue.snapshotOf(
                queue.notes,
                QueueOrigin.Saved(queueId = queue.id, name = queue.name)
            )
        )
        navigator.open(ReaderDestination.Player, fromFocusId = ReaderFocus.SavedQueuePlay)
        return true
    }

    fun openSavedQueueDetails(queueId: String, fromFocusId: String? = null) {
        navigator.open(ReaderDestination.SavedQueueDetails, fromFocusId = fromFocusId, subjectId = queueId)
    }

    fun openCreateSavedQueue() {
        navigator.open(ReaderDestination.CreateSavedQueue, fromFocusId = ReaderFocus.SavedQueueCreate)
    }

    fun savedQueueCards(): List<SavedQueueCard> = _savedQueues.value.toCards()

    fun savedQueueCard(id: String): SavedQueueCard? = _savedQueues.value.firstOrNull { it.id == id }?.toCard()

    fun beginAddToQueue(notes: List<QueuedNote>, fromFocusId: String? = null): Boolean {
        if (notes.isEmpty()) return false
        setPendingAdd(notes.toList())
        _queueMessage.value = null
        navigator.open(ReaderDestination.QueueDestination, fromFocusId = fromFocusId)
        return true
    }

    fun cancelPendingAdd() {
        setPendingAdd(emptyList())
    }

    suspend fun addPendingToQueue(queueId: String): QueueEditOutcome {
        val incoming = _pendingAdd.value
        setPendingAdd(emptyList())

        val outcome = transact(QueueEdit.Add(queueId, incoming))
        val added = outcome.effect as? QueueEffect.Added
        if (added != null && added.added + added.alreadyPresent > 0) {
            _queueMessage.value = describeAdd(added)
        }
        return outcome
    }

    fun consumeQueueMessage() {
        _queueMessage.value = null
    }

    suspend fun moveSavedQueueMember(
        queueId: String,
        pageId: String,
        move: QueueMove
    ): QueueEditOutcome {
        val outcome = transact(QueueEdit.Move(queueId, pageId, move))
        val moved = outcome.effect as? QueueEffect.MemberMoved
        if (outcome.changed && moved?.member != null) {
            _queueMessage.value = "Moved ${moved.member.title} to position ${moved.position} of ${moved.queue.notes.size}."
        }
        return outcome
    }

    suspend fun removeSavedQueueMember(queueId: String, pageId: String): QueueEditOutcome {
        val outcome = transact(QueueEdit.Remove(queueId, pageId))
        val removed = outcome.effect as? QueueEffect.MemberRemoved
        if (outcome.changed && removed?.member != null) {
            _queueMessage.value = "Removed ${removed.member.title}."
        }
        return outcome
    }

    suspend fun renameSavedQueue(queueId: String, newName: String): QueueEditOutcome {
        val outcome = transact(QueueEdit.Rename(queueId, newName))
        val renamed = outcome.effect as? QueueEffect.Renamed
        if (outcome.changed && renamed != null) {
            _queueMessage.value = "Renamed queue to ${renamed.queue.name}."
        }
        return outcome
    }

    suspend fun deleteSavedQueue(queueId: String): QueueEditOutcome {
        val outcome = transact(QueueEdit.Delete(queueId))
        val deleted = outcome.effect as? QueueEffect.Deleted
        if (outcome.changed && deleted != null) {
            _queueMessage.value = "Deleted ${deleted.queue.name}."
            if (navigator.state.value.current == ReaderDestination.SavedQueueDetails &&
                navigator.state.value.currentSubjectId == queueId
            ) {
                navigator.back()
            }
        }
        return outcome
    }

    suspend fun clearSavedQueues() {
        _savedQueues.value = emptyList()
        savedQueueStore?.clear()
    }

    private fun describeAdd(added: QueueEffect.Added): String {
        val skipped = added.alreadyPresent

        if (added.added == 0) {
            return if (skipped == 1) {
                "Nothing was added, because 1 note was already in this queue."
            } else {
                "Nothing was added, because all $skipped notes were already in this queue."
            }
        }

        val placed = if (added.added == 1) "Added 1 note." else "Added ${added.added} notes."
        if (skipped == 0) return placed

        val tail = if (skipped == 1) {
            "1 was already in this queue."
        } else {
            "$skipped were already in this queue."
        }
        return "$placed $tail"
    }

    private suspend fun transact(edit: QueueEdit): QueueEditOutcome {
        val outcome = queues?.apply(edit) ?: QueueEditOutcome.unavailable()
        if (outcome.changed) _savedQueues.value = outcome.queues
        return outcome
    }
}
