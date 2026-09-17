package com.example.notionreader.data.local

import com.example.notionreader.domain.ReaderDocumentPosition
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.session.SESSION_SCHEMA_VERSION
import com.example.notionreader.domain.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored shape is frozen against a known-good encoding.
 *
 * ## The hazard this exists for
 *
 * Every other persistence test in this project encodes and decodes **within one build**, which cannot
 * fail for the reason that matters. Rename `chunkId`, or change a `QueueOrigin` wire name, or make a field
 * non-nullable, and the encoder and the decoder agree with each other perfectly — while disagreeing with
 * every record already on a listener's device.
 *
 * What makes that worse than an ordinary compatibility break is what the application does next. A record
 * that will not decode is silently dropped, by design, because the spec requires a corrupt history to
 * read as an empty history rather than as an error the listener did not ask for. So the sequence is:
 * launch after an upgrade, history reads as empty, the first position change writes the retained set —
 * which is now history-without-those-records — and the listener's entire Continue listening list is gone,
 * with nothing shown, nothing logged, and no way to tell it from a first run. A round-trip test inside one
 * build is green through all of it.
 *
 * ## What is asserted
 *
 * Two directions, because they fail separately. The encoder is pinned to an exact string, so a renamed
 * field or a changed wire name fails here rather than on a device. The decoder is fed that same frozen
 * string, so a field made non-nullable, or a key removed, fails here too — and the frozen string is what
 * a real record already looks like.
 *
 * When this test fails, the question is not "how do I update the constant". It is: **does the change break
 * records already written**, and if so, does [SESSION_SCHEMA_VERSION] need to be bumped so the old ones are
 * discarded deliberately instead of by accident? The spec's policy is that a record from an unknown version
 * is discarded rather than migrated, which is a decision to take on purpose.
 */
class SessionWireFormatTest {

    /**
     * A record with no field left at its default, so that a field omitted from the encoding is a failure
     * rather than a coincidence.
     */
    private val sample = StoredSession(
        id = "3f8a1c2e-0000-4000-8000-abcdefabcdef",
        notes = listOf(
            QueuedNote("11111111-1111-1111-1111-111111111111", "First Note"),
            QueuedNote("22222222-2222-2222-2222-222222222222", "Second Note")
        ),
        origin = QueueOrigin.BrowseResults,
        index = 1,
        position = ReaderDocumentPosition(
            sourceBlockId = "block-1",
            chunkId = "block-1:0",
            sectionId = "section-1",
            characterOffset = 42
        ),
        completed = false,
        wasPlaying = true,
        lastActivityAt = 1_700_000_000_000L,
        schemaVersion = SESSION_SCHEMA_VERSION
    )

    /**
     * The wire form as of schema version 1. Written out rather than generated, because a generated
     * constant would move with the encoder and pin nothing.
     */
    private val frozenV1 = FROZEN_WIRE_FORMAT

    @Test
    fun theEncodingOfAKnownRecordIsUnchanged() {
        assertEquals(
            "The stored session shape changed. See this test's documentation before updating it: a " +
                "renamed field does not fail to compile, it fails to decode - and a record that will not " +
                "decode is dropped silently and then overwritten, which erases history rather than " +
                "breaking a screen.",
            frozenV1,
            SessionCodec.encode(listOf(sample))
        )
    }

    @Test
    fun aFrozenRecordStillDecodes() {
        // The decoder direction, which the encoder pin cannot see: a field that became non-nullable, or a
        // key dropped from the type, still encodes without complaint and refuses to read what is on disk.
        assertEquals(listOf(sample), SessionCodec.decode(frozenV1))
    }

    @Test
    fun aRecordWrittenByALaterVersionStillDecodesAndIsThenRefused() {
        // Forward compatibility, in the two halves the design separates. Unknown keys must not make the
        // record unreadable - `ignoreUnknownKeys` is what lets a later version add a field without
        // breaking this one - and the version check is what then declines to use it, because the spec's
        // policy is to discard an unrecognised version rather than to migrate it.
        val fromTheFuture = frozenV1.replace("\"id\"", "\"addedLater\":123,\"id\"")

        assertEquals(listOf(sample), SessionCodec.decode(fromTheFuture))

        val nextVersion = sample.copy(schemaVersion = SESSION_SCHEMA_VERSION + 1)
        assertTrue(
            "A record from an unknown version must not be restorable",
            !nextVersion.isValid
        )
    }

    @Test
    fun oneUnreadableRecordDoesNotDiscardTheOthers() {
        // The reason the encoding is an array of encoded elements rather than an array of records decoded
        // in one call. A set-wide decode makes a record-wide failure fatal to the whole history, and the
        // very next write persists the shortened set to disk.
        val two = SessionCodec.encode(listOf(sample, sample.copy(id = "second")))

        // The second element is replaced with something whose shape is JSON but not a record.
        val damaged = two.replaceFirst(Regex("\"schemaVersion\":\\d+", RegexOption.IGNORE_CASE), "\"schemaVersion\":\"nonsense\"")

        assertEquals(1, SessionCodec.decode(damaged).size)
    }
}

/**
 * The exact bytes schema version 1 produces.
 *
 * Captured once from a deliberately failing assertion, not from whatever the encoder happened to emit on
 * the day it was pasted, because a constant regenerated from the current encoder pins the encoder to
 * itself and would never fail.
 *
 * Note the shape of `origin`: `{"type":"browse_results"}`. `QueueOrigin` is polymorphic, so its wire form
 * is a **class discriminator plus a serial name**, and either half changing breaks every stored record
 * while leaving a same-build round trip perfectly green. That is exactly the failure this constant exists
 * to make loud.
 */
private const val FROZEN_WIRE_FORMAT =
    """[{"id":"3f8a1c2e-0000-4000-8000-abcdefabcdef","notes":[{"pageId":"11111111-1111-1111-1111-111111111111","title":"First Note"},{"pageId":"22222222-2222-2222-2222-222222222222","title":"Second Note"}],"origin":{"type":"browse_results"},"index":1,"position":{"sourceBlockId":"block-1","chunkId":"block-1:0","sectionId":"section-1","characterOffset":42},"completed":false,"wasPlaying":true,"lastActivityAt":1700000000000,"schemaVersion":1}]"""
