package com.example.notionreader.domain.queue

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Saved Queue record and the store contract.
 *
 * Two things are tested here and they are different in kind. [SavedQueue] and [SavedQueueDraft] are values,
 * so their rules are asserted directly. The store is an interface, so what is asserted is the behaviour the
 * *specification* requires of any implementation — and it is asserted against the in-memory double, which is
 * the same double the model tests will use, so a rule cannot be satisfied in one place and not the other.
 */
class SavedQueueTest {

    private fun queue(
        id: String = "queue-1",
        name: String = "Reading",
        notes: List<QueuedNote> = listOf(QueuedNote("page-1", "Alpha")),
        lastEditedAt: Long = 1_000L,
        schemaVersion: Int = SAVED_QUEUE_SCHEMA_VERSION
    ) = SavedQueue(id = id, name = name, notes = notes, lastEditedAt = lastEditedAt, schemaVersion = schemaVersion)

    // --- What makes a record usable -----------------------------------------------------------------

    @Test
    fun anEmptyQueueIsValidBecauseCreatingOneDeliberatelyProducesIt() {
        // The contract names "create an empty Saved Queue" as a supported operation, so a validity rule that
        // rejected emptiness would make the feature's own first step produce something unusable.
        assertTrue(queue(notes = emptyList()).isValid)
    }

    @Test
    fun aRecordFromAnUnknownVersionIsNotValid() {
        assertFalse(queue(schemaVersion = SAVED_QUEUE_SCHEMA_VERSION + 1).isValid)
    }

    @Test
    fun aRecordWithNoIdentityOrNoNameIsNotValid() {
        // No identity means it cannot be referred to; no name means it cannot be listed.
        assertFalse(queue(id = "").isValid)
        assertFalse(queue(name = "").isValid)
        assertFalse(queue(name = "   ").isValid)
    }

    @Test
    fun theValidityRuleDoesNotDependOnTheMemberList() {
        // Asserted as a property rather than as two cases, because "an empty queue is valid" and "a populated
        // one is" being separately true is weaker than the members being irrelevant to validity.
        val members = listOf(emptyList(), listOf(QueuedNote("p", "T")), List(50) { QueuedNote("p$it", "T$it") })

        for (notes in members) {
            assertTrue("members should not affect validity: ${notes.size}", queue(notes = notes).isValid)
        }
    }

    // --- The duplicate diagnostic -------------------------------------------------------------------

    @Test
    fun theDuplicateRuleIsADiagnosticAndDoesNotMakeARecordUnusable() {
        // A stored record comes from disk rather than from a value this application just built, so a
        // duplicate is only reachable through a bug in an add path. Discarding a listener's whole queue over
        // it would be a disproportionate response, so it is reported rather than enforced — and the rule the
        // *operations* must maintain is still assertable.
        val duplicated = queue(
            notes = listOf(QueuedNote("page-1", "Alpha"), QueuedNote("page-1", "Alpha"))
        )

        assertFalse(duplicated.hasUniqueMembers)
        assertTrue("still usable, because it can still be listed and played", duplicated.isValid)
    }

    @Test
    fun theDuplicateRuleComparesIdentityAndNotTitle() {
        // Two different Notes with the same title are two members, which is the case a title-keyed
        // implementation gets wrong.
        val sameTitle = queue(
            notes = listOf(QueuedNote("page-1", "Same"), QueuedNote("page-2", "Same"))
        )

        assertTrue(sameTitle.hasUniqueMembers)
    }

    // --- The draft ----------------------------------------------------------------------------------

    @Test
    fun aDraftWithOnlyWhitespaceCannotBecomeAQueue() {
        assertFalse(SavedQueueDraft("   ").isCompletable)
        assertFalse(SavedQueueDraft("").isCompletable)
        assertNull(SavedQueueDraft("  ").toQueue(id = "q", now = 0L))
    }

    @Test
    fun aCompletableDraftIsTrimmed() {
        assertEquals("Reading", SavedQueueDraft("  Reading  ").trimmedName)
        assertEquals("Reading", SavedQueueDraft("  Reading  ").toQueue(id = "q", now = 0L)?.name)
    }

    @Test
    fun aNewQueueStartsWithNoMembersAndTheGivenIdentity() {
        // Identity and time are parameters rather than generated inside, because a value that embedded a
        // clock or a UUID generator could not be tested.
        val created = SavedQueueDraft("Reading").toQueue(id = "queue-7", now = 42L)

        assertEquals("queue-7", created?.id)
        assertEquals(emptyList<QueuedNote>(), created?.notes)
        assertEquals(42L, created?.lastEditedAt)
    }

    // --- The store contract -------------------------------------------------------------------------

    @Test
    fun anUnreadableStoreReportsNoQueuesRatherThanFailing() = runTest {
        val store = InMemorySavedQueueStore(listOf(queue())).apply { failReads = true }

        assertEquals(emptyList<SavedQueue>(), store.read())
    }

    @Test
    fun aFailedWriteLeavesTheStoredQueuesAsTheyWereRatherThanPartiallyApplied() = runTest {
        // The spec: a failed write is a lost edit, never a failure the listener is told about — and it must
        // not leave a half-applied list behind either.
        val original = queue(name = "Original")
        val store = InMemorySavedQueueStore(listOf(original)).apply { failWrites = true }

        store.write(listOf(queue(name = "Replacement")))

        assertEquals(listOf(original), store.read())
    }

    @Test
    fun writesAreWholeListReplacements() = runTest {
        // The operation's postcondition is a statement about an ordering, so writing the list whole is what
        // makes the operation and its persistence the same thing.
        val store = InMemorySavedQueueStore()

        store.write(listOf(queue(id = "a")))
        assertEquals(listOf("a"), store.read().map { it.id })

        store.write(listOf(queue(id = "a"), queue(id = "b")))
        assertEquals(listOf("a", "b"), store.read().map { it.id })

        store.write(listOf(queue(id = "b")))
        assertEquals(listOf("b"), store.read().map { it.id })
    }

    @Test
    fun theDoubleRecordsWhatWasWrittenSoNoWriteRulesAreAssertable() = runTest {
        // This exists for Phase 2: "a move at a boundary performs no write" is invisible in the resulting
        // list, so it has to be asserted against the store's activity instead. The double is built for that
        // now so the model tests cannot later be tempted to infer it.
        val store = InMemorySavedQueueStore()

        store.write(listOf(queue()))
        store.write(listOf(queue()))

        assertEquals(2, store.writeCount)
        assertEquals(2, store.writes.size)
    }
}
