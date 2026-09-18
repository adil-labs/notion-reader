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

    /** Every list passed to [write], in order, so a test can see what was written and when. */
    val writes: MutableList<List<SavedQueue>> = mutableListOf()

    override suspend fun read(): List<SavedQueue> {
        readCount++
        return if (failReads) emptyList() else stored
    }

    override suspend fun write(queues: List<SavedQueue>) {
        writeCount++
        writes += queues
        if (failWrites) return
        stored = queues
    }

    /** The queue with [id], or null. A convenience for assertions, not a lookup the production code uses. */
    fun queue(id: String): SavedQueue? = stored.firstOrNull { it.id == id }
}
