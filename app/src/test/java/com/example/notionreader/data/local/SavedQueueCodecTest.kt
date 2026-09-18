package com.example.notionreader.data.local

import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.SAVED_QUEUE_SCHEMA_VERSION
import com.example.notionreader.domain.queue.SavedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a Saved Queue.
 *
 * Pure and offline, which is the point: the stored shape is the one thing in this design that a future
 * version cannot simply refactor, and "does this still decode" is a question worth being able to ask in a
 * unit test. The Queue Creation & Queue Management spec requires the store to reuse the session's policy,
 * and these are the tests that hold it to that — particularly the mixed-record case, which is the only shape
 * that can distinguish per-record decoding from set-wide decoding.
 */
class SavedQueueCodecTest {

    private fun queue(
        id: String = "queue-1",
        name: String = "Reading",
        notes: List<QueuedNote> = listOf(QueuedNote("page-1", "Alpha"), QueuedNote("page-2", "Beta")),
        lastEditedAt: Long = 1_000L,
        schemaVersion: Int = SAVED_QUEUE_SCHEMA_VERSION
    ) = SavedQueue(id = id, name = name, notes = notes, lastEditedAt = lastEditedAt, schemaVersion = schemaVersion)

    // --- Round trip ---------------------------------------------------------------------------------

    @Test
    fun aQueueRoundTripsWithItsIdentityNameMembersAndTimestamp() {
        val original = queue()

        val decoded = SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(original)))

        assertEquals(listOf(original), decoded)
    }

    @Test
    fun anEmptyQueueRoundTripsBecauseAnEmptyQueueIsValid() {
        // "Create saved queue" deliberately produces one, so a codec that lost it would break the feature's
        // first step.
        val empty = queue(notes = emptyList())

        val decoded = SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(empty)))

        assertEquals(listOf(empty), decoded)
    }

    @Test
    fun memberOrderSurvivesAByteRoundTrip() {
        // Order is what Play snapshots, so it is not incidental data.
        val ordered = queue(
            notes = listOf(QueuedNote("c", "C"), QueuedNote("a", "A"), QueuedNote("b", "B"))
        )

        val decoded = SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(ordered)))

        assertEquals(listOf("c", "a", "b"), decoded.single().notes.map { it.pageId })
    }

    @Test
    fun theTitlesAreStoredSoAMemberCanBeNamedBeforeItsDocumentIsFetched() {
        // A page id cannot be spoken, and listing a queue must not fetch anything.
        val decoded = SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(queue())))

        assertEquals(listOf("Alpha", "Beta"), decoded.single().notes.map { it.title })
    }

    // --- Version policy -----------------------------------------------------------------------------

    @Test
    fun aRecordFromAnUnknownVersionIsDiscarded() {
        val future = queue(schemaVersion = SAVED_QUEUE_SCHEMA_VERSION + 1)

        assertEquals(emptyList<SavedQueue>(), SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(future))))
    }

    /**
     * The case a single bad record cannot test.
     *
     * One unusable record among usable ones is the only shape that distinguishes per-record decoding from
     * decoding the array in one call: with a single bad record, both return nothing, so that test would pass
     * for the wrong reason.
     */
    @Test
    fun oneUnusableRecordDoesNotMakeTheOthersUnreadable() {
        val good = queue(id = "keep-me")
        val future = queue(id = "discard-me", schemaVersion = SAVED_QUEUE_SCHEMA_VERSION + 1)

        val decoded = SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(good, future)))

        assertEquals(1, decoded.size)
        assertEquals("keep-me", decoded.single().id)
    }

    @Test
    fun anUnrecognisedRecordIsDiscardedEvenWhenItSitsBesideAValidOneOfTheSameName() {
        // Identity is what decides, so a discarded record cannot be confused with a kept one by its label.
        val valid = queue(id = "a", name = "Same name")
        val invalid = queue(id = "b", name = "Same name", schemaVersion = 99)

        val decoded = SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(valid, invalid)))

        assertEquals(listOf("a"), decoded.map { it.id })
    }

    // --- Corruption ---------------------------------------------------------------------------------

    @Test
    fun unreadableBytesReportNoQueuesRatherThanRaising() {
        // Bytes on disk are untrusted: truncated, from another app, or from a future schema. None is a
        // programming error, so none is an exception the caller should handle.
        for (text in listOf("", "not json at all", "{", "[1,2,3]", "null")) {
            assertEquals("for input '$text'", emptyList<SavedQueue>(), SavedQueueCodec.decode(text))
        }
    }

    @Test
    fun aTruncatedArrayKeepsNothingRatherThanPartOfAQueue() {
        val encoded = SavedQueueCodec.encode(listOf(queue()))

        assertEquals(emptyList<SavedQueue>(), SavedQueueCodec.decode(encoded.dropLast(10)))
    }

    @Test
    fun aRecordMissingItsIdentityIsDiscardedBecauseItCouldNotBeReferredTo() {
        // `id` is a required field, so this is a decode failure rather than an `isValid` failure — either way
        // it must not reach a caller.
        val withoutId = """[{"name":"Reading","notes":[],"lastEditedAt":1}]"""

        assertEquals(emptyList<SavedQueue>(), SavedQueueCodec.decode(withoutId))
    }

    @Test
    fun aRecordWithABlankNameIsDiscardedByTheRecordsOwnValidityRule() {
        // This one *decodes* — every field is present — and is rejected by `isValid`, which is the case that
        // proves the version check and the blankness check are both applied rather than only one.
        val blank = queue(name = "   ")

        assertEquals(emptyList<SavedQueue>(), SavedQueueCodec.decode(SavedQueueCodec.encode(listOf(blank))))
    }

    // --- Canonical order ----------------------------------------------------------------------------

    /**
     * The one place this codec differs from `SessionCodec`, and the reason is different too.
     *
     * Sessions are stored in recency order because retention reads that order. Queues are a set with no
     * meaningful order between them, so the bytes must not depend on the order a caller happened to collect
     * them in — otherwise editing one queue rewrites the bytes of every other and the store churns for
     * nothing.
     */
    @Test
    fun theStoredBytesDoNotDependOnTheOrderTheCallerDecidedToHandThemOver() {
        val a = queue(id = "aaa")
        val b = queue(id = "bbb")
        val c = queue(id = "ccc")

        assertEquals(
            SavedQueueCodec.encode(listOf(a, b, c)),
            SavedQueueCodec.encode(listOf(c, a, b))
        )
    }

    @Test
    fun encodingTheSameQueuesTwiceProducesTheSameBytes() {
        val queues = listOf(queue(id = "aaa"), queue(id = "bbb"))

        assertEquals(SavedQueueCodec.encode(queues), SavedQueueCodec.encode(queues))
    }

    @Test
    fun aRenameDoesNotMoveARecordWithinTheStoredBytes() {
        // Canonical order is by identity precisely so that a rename — the one edit that changes a label and
        // nothing else — cannot change where a record sits.
        val before = queue(id = "aaa", name = "Old name")
        val after = before.copy(name = "New name")

        val encodedBefore = SavedQueueCodec.encode(listOf(before, queue(id = "bbb")))
        val encodedAfter = SavedQueueCodec.encode(listOf(after, queue(id = "bbb")))

        // The bytes differ, because the name did — but the *order* of identities does not.
        assertNotEquals(encodedBefore, encodedAfter)
        assertEquals(
            listOf("aaa", "bbb"),
            SavedQueueCodec.decode(encodedAfter).map { it.id }
        )
    }

    // --- The two schemas version independently ------------------------------------------------------

    @Test
    fun theSavedQueueSchemaVersionIsItsOwnAndNotTheSessions() {
        // They happen to both be 1 today. Asserting that the *policy* is the same while the *constant* is
        // separate is what stops a future session bump silently forcing a queue re-read decision.
        assertTrue(SavedQueueCodec.isCurrentVersion(SAVED_QUEUE_SCHEMA_VERSION))
        assertFalse(SavedQueueCodec.isCurrentVersion(SAVED_QUEUE_SCHEMA_VERSION + 1))
    }
}
