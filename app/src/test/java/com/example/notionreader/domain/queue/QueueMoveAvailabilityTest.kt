package com.example.notionreader.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `QueueMove.isAvailableFor`: whether a move is worth offering, asked before the press.
 *
 * ## Why this is derived from `applyTo` and not restated
 *
 * The boundary rules are trivially expressible twice — `Up` needs `index > 0`, `Down` needs
 * `index < size - 1` — and restating them is how the control's availability and the operation's no-op rule
 * would come to be two rules that agree until one of them changes. The implementation answers the question by
 * applying the move and comparing, so these tests are checks on the *consequence* rather than on a second
 * statement of it.
 *
 * That is also why the tests below assert the pair at each position: a move available at one end and
 * unavailable at the other, in one assertion, so an implementation that reported "always true" or "always
 * false" could not pass either half.
 */
class QueueMoveAvailabilityTest {

    private val three = listOf(note("p1"), note("p2"), note("p3"))
    private val one = listOf(note("p1"))
    private val two = listOf(note("p1"), note("p2"))

    @Test
    fun movingUpIsUnavailableAtTheTopAndAvailableEverywhereElse() {
        assertFalse(QueueMove.Up.isAvailableFor(three, 0))
        assertTrue(QueueMove.Up.isAvailableFor(three, 1))
        assertTrue(QueueMove.Up.isAvailableFor(three, 2))
    }

    @Test
    fun movingDownIsUnavailableAtTheBottomAndAvailableEverywhereElse() {
        assertTrue(QueueMove.Down.isAvailableFor(three, 0))
        assertTrue(QueueMove.Down.isAvailableFor(three, 1))
        assertFalse(QueueMove.Down.isAvailableFor(three, 2))
    }

    @Test
    fun movingToTheTopIsUnavailableOnlyWhenAlreadyFirst() {
        assertFalse(QueueMove.Top.isAvailableFor(three, 0))
        assertTrue(QueueMove.Top.isAvailableFor(three, 1))
        assertTrue(QueueMove.Top.isAvailableFor(three, 2))
    }

    @Test
    fun movingToTheBottomIsUnavailableOnlyWhenAlreadyLast() {
        assertTrue(QueueMove.Bottom.isAvailableFor(three, 0))
        assertTrue(QueueMove.Bottom.isAvailableFor(three, 1))
        assertFalse(QueueMove.Bottom.isAvailableFor(three, 2))
    }

    @Test
    fun everyMoveIsUnavailableInAQueueOfOne() {
        // The degenerate case, and it is the one every queue passes through: a queue created from the Saved
        // tab is empty and one created from the chooser has the added Notes. With one member there is nowhere
        // to move it, so all four are unavailable together — which is also the case a screen shows on the
        // first member of any queue, so it is not exotic.
        for (move in QueueMove.entries) {
            assertFalse("$move in a queue of one", move.isAvailableFor(one, 0))
        }
    }

    @Test
    fun theTwoMovesInAQueueOfTwoAreEachAvailableForExactlyOneMember() {
        // A stronger statement than the boundary tests above, because it covers each move for the whole queue
        // at once: in a two-member queue, each move is available for exactly one of the two.
        for (move in QueueMove.entries) {
            val available = (0..1).count { move.isAvailableFor(two, it) }
            assertEquals("$move in a queue of two", 1, available)
        }
    }

    @Test
    fun everyMoveIsUnavailableForAnIndexThatIsNotAMember() {
        // Reachable: the screen renders from the card's members, so an index outside them means the list
        // changed under it. Answering "available" would offer a control whose operation is a no-op.
        for (move in QueueMove.entries) {
            assertFalse("$move at -1", move.isAvailableFor(three, -1))
            assertFalse("$move at 3", move.isAvailableFor(three, 3))
        }
    }

    @Test
    fun availabilityAgreesWithWhetherTheMoveWouldChangeAnything() {
        // The property the implementation claims, asserted directly rather than assumed: for every move and
        // every position in every list size that matters, availability is exactly "this would change the
        // order". A future move added to the enum is covered by this without being named.
        val lists = listOf(one, two, three, List(5) { note("p$it") })

        for (notes in lists) {
            for (index in notes.indices) {
                for (move in QueueMove.entries) {
                    assertEquals(
                        "$move at $index in a queue of ${notes.size}",
                        move.applyTo(notes, index) != notes,
                        move.isAvailableFor(notes, index)
                    )
                }
            }
        }
    }

    @Test
    fun availabilityDoesNotDependOnTitles() {
        // Identity is the page id, and two members may share a title. A move's availability is about
        // *position*, so two queues of the same size must answer identically whatever their members are
        // called — including when every title is the same, which is the case a title-based implementation
        // would get wrong.
        val distinct = listOf(note("p1", "Alpha"), note("p2", "Beta"), note("p3", "Gamma"))
        val identical = List(3) { note("p$it", "Reading") }

        for (index in distinct.indices) {
            for (move in QueueMove.entries) {
                assertEquals(
                    "$move at $index should not depend on titles",
                    move.isAvailableFor(distinct, index),
                    move.isAvailableFor(identical, index)
                )
            }
        }
    }

    private companion object {
        fun note(pageId: String, title: String = "Note $pageId") = QueuedNote(pageId, title)
    }
}
