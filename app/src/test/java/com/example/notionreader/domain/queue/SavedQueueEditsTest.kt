package com.example.notionreader.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue operations, as a pure model.
 *
 * Everything here is a function of a stored list and an edit, which is why the same three or four lines of
 * fixture appear throughout: the point of these tests is what an operation *means*, and a fixture that needed a
 * store or a dispatcher would make the meaning harder to read than the mechanism.
 *
 * The rules with subtle failure modes — a no-op that must not write, a duplicate that must be judged by identity
 * and not by title, an edit that must leave every other queue alone — are each asserted from both directions
 * where that is possible, because a boundary rule that is only tested at the boundary cannot tell a correct
 * implementation from one that does nothing at all.
 */
class SavedQueueEditsTest {

    // --- Create -------------------------------------------------------------------------------------

    @Test
    fun creatingAQueueProducesAnEmptyOneWithTheSuppliedIdentityAndTime() {
        val outcome = QueueEdit.Create(SavedQueueDraft("Reading"), id = "q7").applyTo(emptyList(), NOW)

        assertEquals(
            listOf(SavedQueue(id = "q7", name = "Reading", notes = emptyList(), lastEditedAt = NOW)),
            outcome.queues
        )
        assertTrue(outcome.changed)
        assertEquals(QueueEffect.Created(outcome.queues.single()), outcome.effect)
    }

    @Test
    fun creatingAQueueLeavesTheQueuesAlreadyStoredAlone() {
        // The property every operation shares, asserted first on the one whose result does not depend on what
        // was read — the reason a whole-list write built from a failed read could destroy the whole library.
        val stored = listOf(queue("a"), queue("b"))

        val outcome = QueueEdit.Create(SavedQueueDraft("Reading"), id = "c").applyTo(stored, NOW)

        assertEquals(listOf("a", "b", "c"), outcome.ids())
        assertEquals(stored, outcome.queues.take(2))
    }

    @Test
    fun creatingAQueueWithABlankNameIsRejected() {
        // Blankness is judged after trimming, so a name of spaces is blank. The rule lives in the draft, so
        // this operation and the Create screen cannot disagree about it.
        for (name in listOf("", "   ", "\t")) {
            val outcome = QueueEdit.Create(SavedQueueDraft(name), id = "q7").applyTo(listOf(queue("a")), NOW)

            assertEquals("a rejected create must change nothing: '$name'", false, outcome.changed)
            assertEquals(listOf("a"), outcome.ids())
            assertEquals(QueueEffect.Rejected(QueueRejection.BlankName), outcome.effect)
        }
    }

    // --- Rename -------------------------------------------------------------------------------------

    @Test
    fun renamingChangesTheNameAndNothingElse() {
        val stored = listOf(queue("a", name = "Old", notes = listOf(note("p1"), note("p2"))))

        val outcome = QueueEdit.Rename("a", "New").applyTo(stored, NOW)

        val renamed = outcome.queues.single()
        assertEquals("New", renamed.name)
        assertEquals("a", renamed.id)
        assertEquals(listOf("p1", "p2"), outcome.members("a"))
        assertTrue(outcome.changed)
    }

    @Test
    fun renamingStoresTheTrimmedName() {
        val outcome = QueueEdit.Rename("a", "  Reading  ").applyTo(listOf(queue("a")), NOW)

        assertEquals("Reading", outcome.queues.single().name)
    }

    @Test
    fun renamingToTheNameItAlreadyHasChangesNothing() {
        val stored = listOf(queue("a", name = "Reading"))

        val outcome = QueueEdit.Rename("a", "Reading").applyTo(stored, NOW)

        assertFalse(outcome.changed)
        // Reported anyway: the listener asked for a name and that is the queue's name. Nothing is hidden, and
        // nothing is written — the change to `lastEditedAt` is what a naive implementation would use to make
        // this look like a real edit.
        assertEquals(QueueEffect.Renamed(stored.single()), outcome.effect)
    }

    @Test
    fun renamingOnlyTheCapitalisationIsARealChange() {
        // The case-insensitive comparison is the *warning's*, not the identity's or the storage's. "reading"
        // and "Reading" are two different names, and storing the one the listener typed is not optional.
        val outcome = QueueEdit.Rename("a", "reading").applyTo(listOf(queue("a", name = "Reading")), NOW)

        assertTrue(outcome.changed)
        assertEquals("reading", outcome.queues.single().name)
    }

    @Test
    fun renamingToABlankNameIsRejected() {
        val stored = listOf(queue("a", name = "Reading"))

        val outcome = QueueEdit.Rename("a", "   ").applyTo(stored, NOW)

        assertFalse(outcome.changed)
        assertEquals(QueueEffect.Rejected(QueueRejection.BlankName), outcome.effect)
    }

    @Test
    fun renamingAQueueThatIsNotThereIsRejected() {
        val outcome = QueueEdit.Rename("missing", "New").applyTo(listOf(queue("a")), NOW)

        assertFalse(outcome.changed)
        assertEquals(QueueEffect.Rejected(QueueRejection.NoSuchQueue), outcome.effect)
    }

    // --- Add ----------------------------------------------------------------------------------------

    @Test
    fun addingAppendsToTheEndOfTheQueue() {
        val stored = listOf(queue("a", notes = listOf(note("p1"))))

        val outcome = QueueEdit.Add("a", listOf(note("p2"), note("p3"))).applyTo(stored, NOW)

        assertEquals(listOf("p1", "p2", "p3"), outcome.members("a"))
    }

    @Test
    fun addingDeduplicatesByNoteIdentityRatherThanByTitle() {
        // Both halves matter and this is the case a title-keyed implementation gets wrong: two different pages
        // that share a title are two members, and one page under two titles is one member.
        val stored = listOf(queue("a", notes = listOf(note("p1", "Introduction"))))

        val outcome = QueueEdit.Add(
            "a",
            listOf(note("p2", "Introduction"), note("p1", "Introduction"))
        ).applyTo(stored, NOW)

        assertEquals(listOf("p1", "p2"), outcome.members("a"))
        assertEquals(
            QueueEffect.Added(outcome.queues.single(), added = 1, alreadyPresent = 1),
            outcome.effect
        )
    }

    @Test
    fun addingReportsHowManyWereAddedAndHowManyWereAlreadyThere() {
        val stored = listOf(queue("a", notes = listOf(note("p1"), note("p2"))))

        val outcome = QueueEdit.Add("a", listOf(note("p2"), note("p3"), note("p4"))).applyTo(stored, NOW)

        assertEquals(
            QueueEffect.Added(outcome.queues.single(), added = 2, alreadyPresent = 1),
            outcome.effect
        )
    }

    @Test
    fun addingOnlyMembersThatAreAlreadyThereChangesNothingButIsStillAResult() {
        // "Adding nothing is a success, not an error." The common case is `Add all to queue` over a result set
        // that overlaps the destination, and a listener who adds twelve things and is told nothing cannot tell
        // this apart from a failure.
        val stored = listOf(queue("a", notes = listOf(note("p1"), note("p2"))))

        val outcome = QueueEdit.Add("a", listOf(note("p1"), note("p2"))).applyTo(stored, NOW)

        assertFalse(outcome.changed)
        assertEquals(
            QueueEffect.Added(stored.single(), added = 0, alreadyPresent = 2),
            outcome.effect
        )
    }

    @Test
    fun addingDeduplicatesWithinTheRequestAsWellAsAgainstTheQueue() {
        // "A Note may appear at most once" is a rule about the queue, so a request naming one page twice must
        // append it once. Deduplicating only against the destination's existing members would append it twice.
        val outcome = QueueEdit.Add("a", listOf(note("p1"), note("p1"))).applyTo(
            listOf(queue("a", notes = emptyList())),
            NOW
        )

        assertEquals(listOf("p1"), outcome.members("a"))
        assertEquals(
            QueueEffect.Added(outcome.queues.single(), added = 1, alreadyPresent = 1),
            outcome.effect
        )
    }

    @Test
    fun addingToAQueueThatIsNotThereIsRejected() {
        val outcome = QueueEdit.Add("missing", listOf(note("p1"))).applyTo(listOf(queue("a")), NOW)

        assertFalse(outcome.changed)
        assertEquals(QueueEffect.Rejected(QueueRejection.NoSuchQueue), outcome.effect)
    }

    // --- Remove -------------------------------------------------------------------------------------

    @Test
    fun removingTakesOutTheMemberWithThatIdentity() {
        val stored = listOf(queue("a", notes = listOf(note("p1"), note("p2"), note("p3"))))

        val outcome = QueueEdit.Remove("a", "p2").applyTo(stored, NOW)

        assertEquals(listOf("p1", "p3"), outcome.members("a"))
        assertTrue(outcome.changed)
    }

    @Test
    fun removingANoteThatIsNotAMemberChangesNothingAndIsNotAnError() {
        // The postcondition the caller wanted already holds, so this is a success that changed nothing — the
        // same posture as an Add that added nothing.
        val stored = listOf(queue("a", notes = listOf(note("p1"))))

        val outcome = QueueEdit.Remove("a", "p9").applyTo(stored, NOW)

        assertFalse(outcome.changed)
        assertEquals(QueueEffect.MemberRemoved(stored.single(), member = null), outcome.effect)
    }

    @Test
    fun removingTheOnlyMemberLeavesAnEmptyQueueWhichIsStillValid() {
        // Not a delete. The listener emptied a queue, and an empty queue is what "Create saved queue" produces.
        val outcome = QueueEdit.Remove("a", "p1").applyTo(listOf(queue("a", notes = listOf(note("p1")))), NOW)

        val emptied = outcome.queues.single()
        assertEquals(emptyList<QueuedNote>(), emptied.notes)
        assertTrue(emptied.isValid)
        assertEquals(listOf("a"), outcome.ids())
    }

    @Test
    fun removingFromAQueueThatIsNotThereIsRejected() {
        val outcome = QueueEdit.Remove("missing", "p1").applyTo(listOf(queue("a")), NOW)

        assertFalse(outcome.changed)
        assertEquals(QueueEffect.Rejected(QueueRejection.NoSuchQueue), outcome.effect)
    }

    // --- Move ---------------------------------------------------------------------------------------

    @Test
    fun eachMoveSatisfiesItsPostcondition() {
        // The postcondition table, one row per line, asserted by the order it produces rather than by any index
        // arithmetic — which is what the specification defines the moves by.
        val expected = mapOf(
            QueueMove.Up to listOf("p2", "p1", "p3"),
            QueueMove.Down to listOf("p1", "p3", "p2"),
            QueueMove.Top to listOf("p3", "p1", "p2"),
            QueueMove.Bottom to listOf("p2", "p3", "p1")
        )
        val stored = listOf(queue("a", notes = listOf(note("p1"), note("p2"), note("p3"))))

        for ((move, order) in expected) {
            val member = if (move == QueueMove.Top) "p3" else if (move == QueueMove.Bottom) "p1" else "p2"

            val outcome = QueueEdit.Move("a", member, move).applyTo(stored, NOW)

            assertEquals("$move of $member", order, outcome.members("a"))
            assertTrue("$move of $member should change the order", outcome.changed)
        }
    }

    @Test
    fun aMoveAtItsOwnBoundaryChangesNothing() {
        val stored = listOf(queue("a", notes = listOf(note("p1"), note("p2"), note("p3"))))

        // Each of the four is a no-op at the end it moves towards, and *only* there — which is the pair of facts
        // a boundary test has to hold at once. The companion case is asserted in the same test so that an
        // implementation that never moves anything cannot pass it.
        val noOps = listOf(
            QueueEdit.Move("a", "p1", QueueMove.Up) to QueueEdit.Move("a", "p3", QueueMove.Up),
            QueueEdit.Move("a", "p3", QueueMove.Down) to QueueEdit.Move("a", "p1", QueueMove.Down),
            QueueEdit.Move("a", "p1", QueueMove.Top) to QueueEdit.Move("a", "p3", QueueMove.Top),
            QueueEdit.Move("a", "p3", QueueMove.Bottom) to QueueEdit.Move("a", "p1", QueueMove.Bottom)
        )

        for ((noOp, moves) in noOps) {
            val unchanged = noOp.applyTo(stored, NOW)
            assertFalse("$noOp is at its boundary", unchanged.changed)
            assertEquals(listOf("p1", "p2", "p3"), unchanged.members("a"))

            assertTrue("$moves should not be a no-op", moves.applyTo(stored, NOW).changed)
        }
    }

    @Test
    fun aMoveReportsTheMemberAndItsPositionCountingFromOne() {
        // The number here is the one a surface speaks, so it counts from 1 rather than being an index a caller
        // has to remember to adjust.
        val stored = listOf(queue("a", notes = listOf(note("p1"), note("p2"), note("p3"))))

        val outcome = QueueEdit.Move("a", "p2", QueueMove.Up).applyTo(stored, NOW)

        assertEquals(
            QueueEffect.MemberMoved(outcome.queues.single(), member = note("p2"), position = 1),
            outcome.effect
        )
    }

    @Test
    fun aBoundaryMoveStillReportsTheMemberAndWhereItIs() {
        // The move did nothing, but the member is there and its position is a fact — so this is a reportable
        // outcome rather than a rejection, and a surface can say "already first" from `changed == false`.
        val stored = listOf(queue("a", notes = listOf(note("p1"), note("p2"))))

        val outcome = QueueEdit.Move("a", "p1", QueueMove.Up).applyTo(stored, NOW)

        assertFalse(outcome.changed)
        assertEquals(
            QueueEffect.MemberMoved(stored.single(), member = note("p1"), position = 1),
            outcome.effect
        )
    }

    @Test
    fun movingAMemberThatIsNotInTheQueueChangesNothing() {
        val stored = listOf(queue("a", notes = listOf(note("p1"))))

        val outcome = QueueEdit.Move("a", "p9", QueueMove.Top).applyTo(stored, NOW)

        assertFalse(outcome.changed)
        assertEquals(
            QueueEffect.MemberMoved(stored.single(), member = null, position = 0),
            outcome.effect
        )
    }

    @Test
    fun movingInAQueueThatIsNotThereIsRejected() {
        val outcome = QueueEdit.Move("missing", "p1", QueueMove.Top).applyTo(listOf(queue("a")), NOW)

        assertFalse(outcome.changed)
        assertEquals(QueueEffect.Rejected(QueueRejection.NoSuchQueue), outcome.effect)
    }

    // --- Delete -------------------------------------------------------------------------------------

    @Test
    fun deletingRemovesThatQueueAndItsMembersAndNothingElse() {
        val stored = listOf(queue("a"), queue("b"), queue("c"))

        val outcome = QueueEdit.Delete("b").applyTo(stored, NOW)

        assertEquals(listOf("a", "c"), outcome.ids())
        assertEquals(QueueEffect.Deleted(stored[1]), outcome.effect)
    }

    @Test
    fun deletingAQueueThatIsNotThereIsRejected() {
        // Also how a second Delete of the same queue is answered, which is the reachable version of this: the
        // postcondition has already been reached, and nothing is written to say so.
        val outcome = QueueEdit.Delete("missing").applyTo(listOf(queue("a")), NOW)

        assertFalse(outcome.changed)
        assertEquals(QueueEffect.Rejected(QueueRejection.NoSuchQueue), outcome.effect)
    }

    // --- Rules that hold for every operation --------------------------------------------------------

    @Test
    fun aRejectedEditNeverChangesAnything() {
        // One property over every rejection rather than seven separate assertions, because the rule is about
        // rejections as a class: nothing is written to record a refusal, and no queue is touched on the way.
        val stored = listOf(queue("a", notes = listOf(note("p1"))))
        val rejections = listOf(
            QueueEdit.Create(SavedQueueDraft("  "), id = "new"),
            QueueEdit.Rename("a", ""),
            QueueEdit.Rename("missing", "New"),
            QueueEdit.Add("missing", listOf(note("p2"))),
            QueueEdit.Remove("missing", "p1"),
            QueueEdit.Move("missing", "p1", QueueMove.Up),
            QueueEdit.Delete("missing")
        )

        for (edit in rejections) {
            val outcome = edit.applyTo(stored, NOW)

            assertFalse("$edit must not change anything", outcome.changed)
            assertEquals("$edit must not alter the stored list", stored, outcome.queues)
            assertTrue(
                "$edit must be a rejection",
                outcome.effect is QueueEffect.Rejected
            )
        }
    }

    @Test
    fun anEditStampsOnlyTheQueueItTouched() {
        // The Saved tab orders by `lastEditedAt`, so an edit that advanced an unrelated queue's time would
        // reorder a list the listener did not touch.
        val stored = listOf(queue("a", at = 1_000L), queue("b", at = 2_000L))

        val outcome = QueueEdit.Rename("a", "New").applyTo(stored, NOW)

        assertEquals(NOW, outcome.queues.first { it.id == "a" }.lastEditedAt)
        assertEquals(2_000L, outcome.queues.first { it.id == "b" }.lastEditedAt)
    }

    @Test
    fun noOperationDisturbsAQueueItDidNotTouch() {
        // Asserted on the records themselves rather than on their ids, because a record that kept its identity
        // and lost its members or its name would pass an identity-only check.
        val untouched = queue("b", name = "Beta", notes = listOf(note("p5")), at = 2_000L)
        val stored = listOf(queue("a"), untouched)
        val edits = listOf(
            QueueEdit.Create(SavedQueueDraft("New"), id = "c"),
            QueueEdit.Rename("a", "Renamed"),
            QueueEdit.Add("a", listOf(note("p9"))),
            QueueEdit.Remove("a", "p1"),
            QueueEdit.Move("a", "p1", QueueMove.Bottom),
            QueueEdit.Delete("a")
        )

        for (edit in edits) {
            val outcome = edit.applyTo(stored, NOW)

            val survivor = outcome.queues.firstOrNull { it.id == "b" }
            assertEquals("$edit must leave b exactly as it was", untouched, survivor)
        }
    }

    // --- The duplicate-name warning ------------------------------------------------------------------

    @Test
    fun theNameWarningFindsAQueueWhoseNameDiffersOnlyInCapitalisation() {
        // "Reading" and "reading" are two names a listener will read as the same one, so the warning covers both.
        val stored = listOf(queue("a", name = "Reading"), queue("b", name = "Notes"))

        assertEquals("a", nameWarning(stored, "reading")?.id)
        assertEquals("a", nameWarning(stored, "  READING  ")?.id)
        assertNull(nameWarning(stored, "Reading elsewhere"))
    }

    @Test
    fun theNameWarningExcludesTheQueueBeingRenamed() {
        // Otherwise every rename would warn about the queue it is renaming, which is the one case where the
        // warning is certainly wrong.
        val stored = listOf(queue("a", name = "Reading"), queue("b", name = "reading"))

        assertEquals("b", nameWarning(stored, "reading", excludingId = "a")?.id)
        assertNull(nameWarning(listOf(queue("a", name = "Reading")), "Reading", excludingId = "a"))
    }

    @Test
    fun thereIsNoNameWarningForABlankName() {
        // A blank name is a rejection rather than a warning, and the Create screen has nothing to warn about
        // while the field is empty.
        assertNull(nameWarning(listOf(queue("a", name = "Reading")), "   "))
    }

    private fun QueueEditOutcome.ids() = queues.map { it.id }

    private fun QueueEditOutcome.members(id: String) = queues.first { it.id == id }.notes.map { it.pageId }
}

private const val NOW = 9_000L

private fun note(pageId: String, title: String = "Note $pageId") = QueuedNote(pageId, title)

private fun queue(
    id: String,
    name: String = "Queue $id",
    notes: List<QueuedNote> = emptyList(),
    at: Long = 1_000L
) = SavedQueue(id = id, name = name, notes = notes, lastEditedAt = at)
