package com.example.notionreader.application

import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.SavedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Saved Queue read model: what a surface is handed, and the two decisions it carries.
 *
 * Tested with no store and no device, which is the point of the projection living in the application layer
 * rather than in a screen. The rules here are the kind that a screen would otherwise implement for itself,
 * twice, differently: **the order** and **how a count is spoken**.
 */
class SavedQueueCardTest {

    // --- Ordering -----------------------------------------------------------------------------------

    @Test
    fun theListsMostRecentlyEditedQueueFirst() {
        // The contract's requirement for the Saved tab. The store deliberately returns queues in no
        // particular order, so this is the one place the order is decided.
        val cards = listOf(
            queue("a", at = 1_000L),
            queue("b", at = 3_000L),
            queue("c", at = 2_000L)
        ).toCards()

        assertEquals(listOf("b", "c", "a"), cards.map { it.queueId })
    }

    @Test
    fun queuesEditedAtTheSameInstantKeepTheOrderTheyWereStoredIn() {
        // The sort is stable, and that is a property rather than an accident: two rows swapping on every
        // recomposition would look to a listener like the list had lost their place.
        val stored = listOf(queue("a", at = 5_000L), queue("b", at = 5_000L), queue("c", at = 5_000L))

        assertEquals(listOf("a", "b", "c"), stored.toCards().map { it.queueId })
        // And again, because a non-stable sort can happen to be right once.
        assertEquals(listOf("a", "b", "c"), stored.toCards().map { it.queueId })
    }

    @Test
    fun oneQueueCreatesTheSameCardAsTheListDoes() {
        // The single-queue mapping is what Saved Queue Details reads, so a queue opened from a list and the
        // same queue rendered on its own screen must not be able to describe themselves differently.
        val stored = queue("a", name = "Reading", members = listOf(note("p1"), note("p2")), at = 7_000L)

        assertEquals(stored.toCard(), listOf(stored).toCards().single())
    }

    // --- How a count is spoken ----------------------------------------------------------------------

    @Test
    fun oneNoteIsSpokenInTheSingular() {
        // The contract's examples are all plural, and `1 notes` is only ever written by an implementation
        // that never handled the case. It is visible to a listener on every single-member queue.
        assertEquals("1 note", queue("a", members = listOf(note("p1"))).toCard().memberDescription)
    }

    @Test
    fun everyOtherCountIsSpokenInThePluralIncludingZero() {
        // Zero reads as `0 notes` rather than as a word like "Empty": it is the same statement as every other
        // count, and inventing vocabulary for one value would make the line mean something different
        // depending on which queue it describes.
        for ((count, expected) in listOf(0 to "0 notes", 2 to "2 notes", 11 to "11 notes")) {
            val members = List(count) { note("p$it") }
            assertEquals(expected, queue("a", members = members).toCard().memberDescription)
        }
    }

    @Test
    fun theCountIsNotStoredSeparatelyFromTheMembers() {
        // The spec's rule for the stored record is that it holds "no member count ... a stored copy is a
        // second thing to keep in step". The card keeps the same rule, so the count cannot disagree with the
        // list a surface is about to render.
        val card = queue("a", members = listOf(note("p1"), note("p2"), note("p3"))).toCard()

        assertEquals(card.members.size, card.memberCount)
    }

    // --- What Play is offered on ---------------------------------------------------------------------

    @Test
    fun anEmptyQueueIsNotPlayableAndAPopulatedOneIs() {
        // "Play on an empty queue is not offered." The rule is stated once, here, so the screen that disables
        // the control and the operation that refuses it are reading the same fact rather than each deciding.
        assertFalse(queue("a", members = emptyList()).toCard().isPlayable)
        assertTrue(queue("a", members = listOf(note("p1"))).toCard().isPlayable)
    }

    // --- What the card preserves ---------------------------------------------------------------------

    @Test
    fun theMembersKeepTheListenersOrderRatherThanBeingSortedHere() {
        // The spec: "Order is a property of the list, and it is the user's. Nothing sorts a Saved Queue
        // implicitly — not title, not date, not 'recently added'." The queue's own ordering is what Play
        // snapshots, so a projection that sorted members would change what gets played.
        val members = listOf(note("p9", "Zebra"), note("p1", "Apple"), note("p5", "Mango"))

        assertEquals(listOf("p9", "p1", "p5"), queue("a", members = members).toCard().members.map { it.pageId })
    }

    @Test
    fun withNoQueuesThereAreNoCards() {
        assertEquals(emptyList<SavedQueueCard>(), emptyList<SavedQueue>().toCards())
    }

    @Test
    fun aCardCarriesTheInstantRatherThanAFormattedTimeAndEachMembersPageId() {
        // The contract requires relative time and forbids a raw one, so the fact is carried and the
        // formatting belongs to the UI. A card that formatted here would be stale the moment it was built.
        // The page id is carried for the same class of reason: Saved Queue Details opens each member's Note,
        // and a title cannot open anything.
        val card = queue("a", members = listOf(note("p1", "Alpha")), at = 1_700_000_000_000L).toCard()

        assertEquals(1_700_000_000_000L, card.lastEditedAt)
        assertEquals(QueuedNote("p1", "Alpha"), card.members.single())
    }

    @Test
    fun aCardIsIdentifiedByItsQueueAndNotByItsName() {
        // Two queues may share a name, so a name cannot be what a row refers back to. Nothing about a card's
        // identity depends on the label.
        val first = queue("a", name = "Reading").toCard()
        val second = queue("b", name = "Reading").toCard()

        assertEquals("a", first.queueId)
        assertEquals("b", second.queueId)
        assertEquals(first.name, second.name)
    }

    private companion object {
        fun note(pageId: String, title: String = "Note $pageId") = QueuedNote(pageId, title)

        fun queue(
            id: String,
            name: String = "Queue $id",
            members: List<QueuedNote> = emptyList(),
            at: Long = 1_000L
        ) = SavedQueue(id = id, name = name, notes = members, lastEditedAt = at)
    }
}
