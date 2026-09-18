package com.example.notionreader.domain.queue

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operations as seen through the store.
 *
 * Separate from [SavedQueueEditsTest] because it answers a different question. The model tests ask what an
 * operation *means*; these ask **whether it wrote anything**, which the resulting list cannot show: a reorder at
 * a boundary and a reorder that moved a member produce different lists and are both "changed", while a reorder
 * at a boundary and a reorder that was never attempted produce the *same* list. "Wrote nothing" is visible only
 * in the store's activity, so that is what these assert — against the recording double, which is why it counts
 * writes rather than only holding queues.
 *
 * The last test is the exception and the most important one: it is the regression guard for a whole-list write
 * built out of a read that failed.
 */
class SavedQueuesTest {

    // --- The no-write rules -------------------------------------------------------------------------

    @Test
    fun aMoveAtItsOwnBoundaryPerformsNoWrite() = runTest {
        // The listener pressing "Move up" on the first member is telling the app what they want, and the answer
        // is already true. Reaching storage to achieve it would rewrite the file for nothing.
        val stored = savedQueue("a", members = listOf(member("p1"), member("p2"), member("p3")))

        for (noOp in boundaryMoves()) {
            val store = InMemorySavedQueueStore(listOf(stored))
            val queues = SavedQueues(store, clock = { NOW })

            val outcome = queues.apply(noOp)

            assertFalse("$noOp is at its boundary", outcome.changed)
            assertEquals("$noOp must not reach the store", 0, store.writeCount)
            assertTrue("$noOp must not record a write", store.writes.isEmpty())
        }
    }

    @Test
    fun aRenameToTheNameItAlreadyHasPerformsNoWrite() = runTest {
        // The case that makes stamping `lastEditedAt` before comparing the wrong order: the timestamp would
        // differ, the comparison would report a change, and the write would happen for nothing.
        val store = InMemorySavedQueueStore(listOf(savedQueue("a", name = "Reading")))
        val queues = SavedQueues(store, clock = { NOW })

        val outcome = queues.apply(QueueEdit.Rename("a", " Reading "))

        assertFalse(outcome.changed)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun anAddOfMembersThatAreAllAlreadyTherePerformsNoWriteButStillReportsTheCounts() = runTest {
        // The pair of facts that makes "nothing changed" and "nothing to say" two different answers.
        val store = InMemorySavedQueueStore(
            listOf(savedQueue("a", members = listOf(member("p1"), member("p2"))))
        )
        val queues = SavedQueues(store, clock = { NOW })

        val outcome = queues.apply(QueueEdit.Add("a", listOf(member("p1"), member("p2"))))

        assertFalse(outcome.changed)
        assertEquals(0, store.writeCount)
        assertEquals(QueueEffect.Added(store.stored.single(), added = 0, alreadyPresent = 2), outcome.effect)
    }

    @Test
    fun aRejectedEditPerformsNoWrite() = runTest {
        val store = InMemorySavedQueueStore(listOf(savedQueue("a")))
        val queues = SavedQueues(store, clock = { NOW })

        queues.apply(QueueEdit.Create(SavedQueueDraft("   "), id = "new"))
        queues.apply(QueueEdit.Rename("missing", "New"))
        queues.apply(QueueEdit.Delete("missing"))

        assertEquals(0, store.writeCount)
        assertEquals(listOf("a"), store.stored.map { it.id })
    }

    // --- A write that does happen --------------------------------------------------------------------

    @Test
    fun anEditThatChangesSomethingWritesExactlyOnce() = runTest {
        val store = InMemorySavedQueueStore(listOf(savedQueue("a", members = listOf(member("p1")))))
        val queues = SavedQueues(store, clock = { NOW })

        queues.apply(QueueEdit.Add("a", listOf(member("p2"))))

        assertEquals(1, store.writeCount)
        // The whole set is written, not the one queue that changed: an operation's postcondition is a statement
        // about the list, and writing the list whole is what makes the operation and its persistence the same.
        assertEquals(listOf("a"), store.writes.single().map { it.id })
        assertEquals(listOf("p1", "p2"), store.writes.single().single().notes.map { it.pageId })
    }

    @Test
    fun oneEditIsStampedWithOneTimeEvenIfTheClockMoves() = runTest {
        // The clock is read once per operation, before the transaction. A clock read inside would make the
        // timestamp depend on how many times the model happened to ask, which is not a property anything should
        // be able to observe — and a queue that came out of one edit with two edit times would reorder itself in
        // the Saved tab.
        var reads = 0
        val store = InMemorySavedQueueStore(listOf(savedQueue("a")))
        val queues = SavedQueues(store, clock = { reads++; NOW + reads })

        queues.apply(QueueEdit.Rename("a", "New"))

        assertEquals(1, reads)
        assertEquals(NOW + 1, store.stored.single().lastEditedAt)
    }

    @Test
    fun aDroppedWriteLeavesTheStoredQueuesAloneButIsStillReportedAsTheOperationItWas() = runTest {
        val store = InMemorySavedQueueStore(listOf(savedQueue("a", members = listOf(member("p1")))))
        store.failWrites = true
        val queues = SavedQueues(store, clock = { NOW })

        val outcome = queues.apply(QueueEdit.Add("a", listOf(member("p2"))))

        assertTrue(outcome.changed)
        assertEquals(listOf("p1"), store.stored.single().notes.map { it.pageId })
    }

    // --- The store that cannot be read, which is why `transact` exists --------------------------------

    @Test
    fun anUnreadableStorePerformsNothingRatherThanWritingOverWhatItCouldNotRead() = runTest {
        // **The regression guard.** A store reports a read it could not perform as "no queues", because that is
        // what a caller displaying queues needs. A create whose result does not depend on what was read would
        // then write a one-queue list over a library it never saw — and of the six operations, `Create` is the
        // only one that reaches this, because every other needs its target to have been read.
        val store = InMemorySavedQueueStore(listOf(savedQueue("a"), savedQueue("b")))
        store.failReads = true
        val queues = SavedQueues(store, clock = { NOW })

        val outcome = queues.apply(QueueEdit.Create(SavedQueueDraft("Reading"), id = "c"))

        assertEquals(QueueEffect.StoreUnavailable, outcome.effect)
        assertFalse(outcome.changed)
        assertEquals(0, store.writeCount)
        assertTrue(store.writes.isEmpty())
        // And the queues are still there, which the read cannot report: this is what the double holds, not what
        // an operation was allowed to replace.
        assertEquals(listOf("a", "b"), store.stored.map { it.id })

        store.failReads = false
        assertEquals(listOf("a", "b"), queues.all().map { it.id })
    }

    @Test
    fun readingThroughTheCoordinatorReturnsWhatTheStoreHolds() = runTest {
        val store = InMemorySavedQueueStore(listOf(savedQueue("a"), savedQueue("b")))
        val queues = SavedQueues(store, clock = { NOW })

        assertEquals(listOf("a", "b"), queues.all().map { it.id })
        assertEquals(1, store.readCount)
    }

    private companion object {
        const val NOW = 9_000L

        fun member(pageId: String) = QueuedNote(pageId, "Note $pageId")

        fun savedQueue(
            id: String,
            name: String = "Queue $id",
            members: List<QueuedNote> = emptyList()
        ) = SavedQueue(id = id, name = name, notes = members, lastEditedAt = 1_000L)

        /** One move per operation that is a no-op at the end it moves towards. */
        fun boundaryMoves() = listOf(
            QueueEdit.Move("a", "p1", QueueMove.Up),
            QueueEdit.Move("a", "p3", QueueMove.Down),
            QueueEdit.Move("a", "p1", QueueMove.Top),
            QueueEdit.Move("a", "p3", QueueMove.Bottom)
        )
    }
}
