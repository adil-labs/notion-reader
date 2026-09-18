package com.example.notionreader.domain.queue

/**
 * The Saved Queues, and the one entry point that changes them.
 *
 * ## Why there is one `apply` rather than six methods
 *
 * For the reason [QueueEdit] gives: the rules a queue operation has to satisfy — no write when nothing changed,
 * no write when rejected, one record touched, everything else preserved — are stated once in [applyTo], and a
 * caller with a second way in would not inherit them. The same shape as `ReaderApp.invoke(command)`, and for the
 * same class of reason: one place where "what may happen" is decided means one place to be right.
 *
 * ## Why this class is the store's *only* caller for mutation
 *
 * Because the read and the write have to be one operation, and putting that behind a method rather than in the
 * caller's hands is what stops the next caller doing it in two steps. [SavedQueueStore.transact] carries the
 * reasoning, including the defect it exists to close.
 *
 * ## The clock is injected, and read once per operation
 *
 * `now` is taken before the transaction rather than inside it, so every timestamp an operation writes is the
 * same instant — a queue cannot come out of one edit with two different edit times, whatever a system clock
 * does mid-flight. It is a parameter of the class rather than a call to `System` inside a method so that a test
 * can read a timestamp instead of inferring one, which is the same argument [SavedQueueDraft.toQueue] makes.
 */
class SavedQueues(
    private val store: SavedQueueStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Every stored queue.
     *
     * Ordering is deliberately not applied here, even though the Saved tab lists most-recently-edited first:
     * that is a display decision, and keeping it out of the store is what makes it one implementation rather
     * than one per storage backend.
     */
    suspend fun all(): List<SavedQueue> = store.read()

    /**
     * Applies [edit] and reports what happened.
     *
     * Never throws for a storage failure and never reports one as a failure of [edit]: an unreadable store
     * answers [QueueEffect.StoreUnavailable] with `changed == false`, having written nothing.
     */
    suspend fun apply(edit: QueueEdit): QueueEditOutcome {
        val now = clock()
        return store.transact { current -> edit.applyTo(current, now) }
    }
}
