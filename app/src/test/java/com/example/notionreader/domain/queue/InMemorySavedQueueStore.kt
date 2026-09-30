package com.example.notionreader.domain.queue

/**
 * A [SavedQueueStore] that keeps queues in memory, and can be made to fail.
 *
 * Deliberately not a trivial stub, for the reason [com.example.notionreader.domain.session.InMemorySessionStore]
 * gives and because this specification has a rule that only a recording store can test: **an operation that
 * changes nothing must perform no write.** "Move up" at the top of a list, or a rename to the name a queue
 * already has, produces no visible difference, so the assertion has to be about the store's activity rather
 * than about the resulting list — and a double that cannot fail or cannot count cannot make it.
 *
 * [writes] keeps every list passed to [write] and the count is the cheapest thing to assert on, but the
 * *lists* are kept too because "wrote the same thing again" is a different bug from "wrote something
 * different", and only the second is caught by comparing values.
 */
class InMemorySavedQueueStore(
    initial: List<SavedQueue> = emptyList()
) : SavedQueueStore {

    var stored: List<SavedQueue> = initial
        private set

    /** When true, [read] reports an unreadable store, which the spec says must be harmless. */
    var failReads: Boolean = false

    /** When true, [write] silently does nothing, which the spec says must be harmless. */
    var failWrites: Boolean = false

    var readCount: Int = 0
        private set

    var writeCount: Int = 0
        private set

    /**
     * Every list this store was asked to persist, in order, whether or not the write was allowed to land.
     *
     * "Was a write attempted" is the question a no-op rule asks, so a dropped write is recorded here and
     * leaves [stored] alone; the two facts are separately visible on purpose.
     */
    val writes: MutableList<List<SavedQueue>> = mutableListOf()

    override suspend fun read(): List<SavedQueue> {
        readCount++
        return if (failReads) emptyList() else stored
    }

    override suspend fun transact(decide: (List<SavedQueue>) -> QueueEditOutcome): QueueEditOutcome {
        // A store that cannot be read performs nothing at all, which is the whole point of the transaction:
        // the caller's decision is never taken, so a failed read can never be written back.
        if (failReads) return QueueEditOutcome.unavailable()

        val next = decide(stored)
        if (next.changed) {
            writeCount++
            writes += next.queues
            // A dropped write leaves the queues as they were rather than half-applied. The decision is still
            // reported: a storage failure is never a failure of the operation the listener performed.
            if (!failWrites) stored = next.queues
        }
        return next
    }

    override suspend fun clear() {
        stored = emptyList()
    }

    /** The queue with [id], or null. A convenience for assertions, not a lookup the production code uses. */
    fun queue(id: String): SavedQueue? = stored.firstOrNull { it.id == id }
}
