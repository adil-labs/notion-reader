package com.example.notionreader.data.local

import com.example.notionreader.domain.ReaderDocumentPosition
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.session.SESSION_SCHEMA_VERSION
import com.example.notionreader.domain.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * The wire form as of schema version 2 — the current one. Written out rather than generated, because a
     * generated constant would move with the encoder and pin nothing.
     */
    private val frozen = FROZEN_V2_WIRE_FORMAT

    @Test
    fun theEncodingOfAKnownRecordIsUnchanged() {
        assertEquals(
            "The stored session shape changed. See this test's documentation before updating it: a " +
                "renamed field does not fail to compile, it fails to decode - and a record that will not " +
                "decode is dropped silently and then overwritten, which erases history rather than " +
                "breaking a screen.",
            frozen,
            SessionCodec.encode(listOf(sample))
        )
    }

    @Test
    fun aFrozenRecordStillDecodes() {
        // The decoder direction, which the encoder pin cannot see: a field that became non-nullable, or a
        // key dropped from the type, still encodes without complaint and refuses to read what is on disk.
        assertEquals(listOf(sample), SessionCodec.decode(frozen))
    }

    @Test
    fun aRecordWrittenByALaterVersionStillDecodesAndIsThenRefused() {
        // Forward compatibility, in the two halves the design separates. Unknown keys must not make the
        // record unreadable - `ignoreUnknownKeys` is what lets a later version add a field without
        // breaking this one - and the version check is what then declines to use it, because the spec's
        // policy is to discard an unrecognised version rather than to migrate it.
        val fromTheFuture = frozen.replace("\"id\"", "\"addedLater\":123,\"id\"")

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

    // --- The origin change, and why the version bump is load-bearing ---------------------------------

    @Test
    fun aRecordWrittenBeforeTheOriginChangeIsRefused() {
        // ## The two halves, because only one of them is a consequence of changing a field
        //
        // **The shape refuses the records it can.** A version 1 record whose origin named a Saved Queue has no
        // queue identity in it, and the variant now requires one, so the record will not decode at all. This
        // half would work whether or not the version changed.
        assertEquals(emptyList<StoredSession>(), SessionCodec.decode(FROZEN_V1_SAVED_ORIGIN))

        // **The version refuses the rest.** A version 1 record whose origin is a Browse result set or a single
        // Note decodes perfectly, because those variants did not change - so nothing in the wire shape stops
        // it, and if the version had not been bumped it would be restored under a schema whose meaning of
        // "origin" this build has changed. That is the case the bump exists for, and the reason it is not
        // merely an artefact of adding a field.
        val restoredFromVersion1 = SessionCodec.decode(FROZEN_V1_WIRE_FORMAT)

        assertEquals(1, restoredFromVersion1.size)
        assertFalse(
            "A version 1 record must not be restorable under version 2",
            restoredFromVersion1.single().isValid
        )
    }

    @Test
    fun aSavedOriginCarriesItsIdentityThroughTheWireFormSoARenameCannotInvalidateIt() {
        // The point of the change. A stored name would go stale the moment the queue was renamed, and origin
        // must never be re-evaluated against its source - so the queue has to be named by something a rename
        // cannot touch. The label is kept because a session must be explicable without a lookup, and it is
        // allowed to go stale precisely because it is a label and not an identifier.
        val beforeRename = sample.copy(origin = QueueOrigin.Saved(queueId = "q-1", name = "Reading"))
        val afterRename = sample.copy(origin = QueueOrigin.Saved(queueId = "q-1", name = "Reading, 2026"))

        val decoded = listOf(beforeRename, afterRename).map {
            (SessionCodec.decode(SessionCodec.encode(listOf(it))).single().origin as QueueOrigin.Saved)
        }

        assertEquals("Reading", decoded.first().name)
        assertEquals("Reading, 2026", decoded.last().name)
        assertEquals(
            "both records must still name one queue",
            listOf("q-1", "q-1"),
            decoded.map { it.queueId }
        )
    }
}

/**
 * The exact bytes schema version 1 produced.
 *
 * Captured once from a deliberately failing assertion, not from whatever the encoder happened to emit on
 * the day it was pasted, because a constant regenerated from the current encoder pins the encoder to
 * itself and would never fail.
 *
 * Note the shape of `origin`: `{"type":"browse_results"}`. `QueueOrigin` is polymorphic, so its wire form
 * is a **class discriminator plus a serial name**, and either half changing breaks every stored record
 * while leaving a same-build round trip perfectly green. That is exactly the failure this constant exists
 * to make loud.
 *
 * **Kept after the version 2 bump, deliberately.** Its job changed from pinning the encoder to pinning what
 * is already on listeners' devices, and it is now only meaningful as input to a decode: the record it holds
 * must be refused. Deleting it would make "old records are discarded" untestable, which is the one property
 * the bump exists to provide.
 */
private const val FROZEN_V1_WIRE_FORMAT =
    """[{"id":"3f8a1c2e-0000-4000-8000-abcdefabcdef","notes":[{"pageId":"11111111-1111-1111-1111-111111111111","title":"First Note"},{"pageId":"22222222-2222-2222-2222-222222222222","title":"Second Note"}],"origin":{"type":"browse_results"},"index":1,"position":{"sourceBlockId":"block-1","chunkId":"block-1:0","sectionId":"section-1","characterOffset":42},"completed":false,"wasPlaying":true,"lastActivityAt":1700000000000,"schemaVersion":1}]"""

/**
 * A version 1 record whose origin named a Saved Queue — the shape version 2 replaced.
 *
 * Hand-written rather than derived, because it describes what a real device already holds: an origin with a
 * name and no queue identity. Version 2's `saved` variant requires the identity, so this is the half of the
 * old records that the wire shape refuses on its own.
 */
private const val FROZEN_V1_SAVED_ORIGIN =
    """[{"id":"old-saved","notes":[{"pageId":"11111111-1111-1111-1111-111111111111","title":"First Note"}],"origin":{"type":"saved","name":"Reading"},"index":0,"completed":false,"wasPlaying":false,"lastActivityAt":1700000000000,"schemaVersion":1}]"""

/**
 * The exact bytes schema version 2 produces.
 *
 * Identical to version 1's except for the version, because the sample's origin is a Browse result set — which
 * is exactly why the bump has to be asserted rather than inferred from this string: a variant that did not
 * change leaves the wire form saying nothing about the change.
 */
private const val FROZEN_V2_WIRE_FORMAT =
    """[{"id":"3f8a1c2e-0000-4000-8000-abcdefabcdef","notes":[{"pageId":"11111111-1111-1111-1111-111111111111","title":"First Note"},{"pageId":"22222222-2222-2222-2222-222222222222","title":"Second Note"}],"origin":{"type":"browse_results"},"index":1,"position":{"sourceBlockId":"block-1","chunkId":"block-1:0","sectionId":"section-1","characterOffset":42},"completed":false,"wasPlaying":true,"lastActivityAt":1700000000000,"schemaVersion":2}]"""
