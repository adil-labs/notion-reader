package com.example.notionreader.domain.queue

import java.util.UUID

sealed interface SmartQueueEffect {
    data class Created(val queue: SmartQueue) : SmartQueueEffect
    data class Renamed(val queue: SmartQueue) : SmartQueueEffect
    data class Deleted(val queue: SmartQueue) : SmartQueueEffect
    sealed interface Refused : SmartQueueEffect {
        data object EmptyName : Refused
        data object EmptyQuery : Refused
        data object NotFound : Refused
    }
    data object StoreUnavailable : SmartQueueEffect
}

data class SmartQueueEditOutcome(
    val queues: List<SmartQueue>,
    val changed: Boolean,
    val effect: SmartQueueEffect
) {
    companion object {
        fun unavailable() = SmartQueueEditOutcome(emptyList(), changed = false, effect = SmartQueueEffect.StoreUnavailable)
    }
}

/**
 * Coordinates operations on persistent Smart Queues.
 */
class SmartQueues(
    private val store: SmartQueueStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun list(): List<SmartQueue> =
        store.read().filter { it.isValid }.sortedByDescending { it.lastEditedAt }

    suspend fun create(name: String, queryText: String): SmartQueueEditOutcome {
        val trimmedName = name.trim()
        val trimmedQuery = queryText.trim()
        if (trimmedName.isEmpty()) return SmartQueueEditOutcome(list(), false, SmartQueueEffect.Refused.EmptyName)
        if (trimmedQuery.isEmpty()) return SmartQueueEditOutcome(list(), false, SmartQueueEffect.Refused.EmptyQuery)

        val newId = UUID.randomUUID().toString()
        val newQueue = SmartQueue(
            id = newId,
            name = trimmedName,
            queryText = trimmedQuery,
            lastEditedAt = clock()
        )

        var finalQueues = emptyList<SmartQueue>()
        val effect = store.transact { current ->
            finalQueues = current + newQueue
            finalQueues to SmartQueueEffect.Created(newQueue)
        }
        return SmartQueueEditOutcome(finalQueues, effect is SmartQueueEffect.Created, effect)
    }

    suspend fun rename(id: String, newName: String): SmartQueueEditOutcome {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return SmartQueueEditOutcome(list(), false, SmartQueueEffect.Refused.EmptyName)

        var finalQueues = emptyList<SmartQueue>()
        val effect = store.transact { current ->
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) {
                finalQueues = current
                current to SmartQueueEffect.Refused.NotFound
            } else {
                val updated = current.toMutableList()
                val renamed = updated[index].copy(name = trimmed, lastEditedAt = clock())
                updated[index] = renamed
                finalQueues = updated
                updated to SmartQueueEffect.Renamed(renamed)
            }
        }
        return SmartQueueEditOutcome(finalQueues, effect is SmartQueueEffect.Renamed, effect)
    }

    suspend fun delete(id: String): SmartQueueEditOutcome {
        var finalQueues = emptyList<SmartQueue>()
        val effect = store.transact { current ->
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) {
                finalQueues = current
                current to SmartQueueEffect.Refused.NotFound
            } else {
                val deletedQueue = current[index]
                finalQueues = current.filterNot { it.id == id }
                finalQueues to SmartQueueEffect.Deleted(deletedQueue)
            }
        }
        return SmartQueueEditOutcome(finalQueues, effect is SmartQueueEffect.Deleted, effect)
    }
}
