package com.example.notionreader.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Listening history: what survives storage, what is kept, and what a restored session does.
 *
 * Every one of these traces to a numbered acceptance criterion or an architectural invariant in the
 * Active Queue & Reader Session spec rather than to a preference, and the ones that matter most are the
 * ones asserting that something is *not* done: that a retained position is not consumed by Play, that a
 * completed session is not restored, and that a restored queue is not re-derived from its origin.
 */
class SessionPersistenceTest {

    private val noteA = queuedNote("p1", "First")
    private val noteB = queuedNote("p2", "Second")

    // ---------------------------------------------------------------- the wire form

    @Test
    fun aRecordSurvivesTheWireFormUnchanged() {
        val record = storedSession(
            id = "s-1",
            notes = listOf(noteA, noteB),
            origin = QueueOrigin.Smart("Weekly review"),
            index = 1,
            position = ReaderDocumentPosition(
                sourceBlockId = "e",
                chunkId = "e:0",
                sectionId = "s2"
            ),
            wasPlaying = true,
            lastActivityAt = 1_700_000_000_000L
        )

        val decoded = SessionCodec.decode(SessionCodec.encode(listOf(record)))

        assertEquals(listOf(record), decoded)
    }

    @Test
    fun aOneNoteSessionAndAMultiNoteSessionUseTheSameStoredShape() {
        // The product contract: "A one-Note playback session follows exactly the same session model as a
        // multi-Note queue." Same encoding, same decoding, same record type - there is no second shape.
        val single = storedSession(id = "s-1", notes = listOf(noteA))
        val many = storedSession(id = "s-2", notes = listOf(noteA, noteB, queuedNote("p3")))

        val decoded = SessionCodec.decode(SessionCodec.encode(listOf(single, many)))

        assertEquals(listOf(single, many), decoded)
        assertEquals(1, decoded[0].notes.size)
        assertEquals(3, decoded[1].notes.size)
    }

    @Test
    fun theStoredPositionIsIdentitiesAndNeverANumericIndex() {
        val record = storedSession(position = ReaderDocumentPosition(chunkId = "e:0", sectionId = "s2"))

        val encoded = SessionCodec.encode(listOf(record))

        assertTrue("the stable identities must be what is on disk: $encoded", encoded.contains("\"chunkId\":\"e:0\""))
        assertTrue(encoded.contains("\"sectionId\":\"s2\""))
        // The invariant, asserted against the wire form rather than the type: a stored index invites
        // resuming at whatever now occupies it, which the integration spec forbids.
        assertFalse("no numeric playback coordinate may be persisted: $encoded", encoded.contains("paragraphIndex"))
    }

    @Test
    fun anUnknownSchemaVersionIsNotAUsableRecord() {
        assertFalse(storedSession(schemaVersion = SESSION_SCHEMA_VERSION + 1).isValid)
        assertTrue(storedSession().isValid)
    }

    @Test
    fun aRecordWithNoNotesIsNotAUsableRecord() {
        assertFalse(storedSession(notes = emptyList()).isValid)
    }

    @Test
    fun anUnreadableHistoryDecodesToNothingRatherThanThrowing() {
        assertEquals(emptyList<StoredSession>(), SessionCodec.decode("not json at all"))
        assertEquals(emptyList<StoredSession>(), SessionCodec.decode(""))
        assertEquals(emptyList<StoredSession>(), SessionCodec.decode("{\"not\":\"an array\"}"))
    }

    @Test
    fun oneUnreadableRecordDoesNotTakeTheRestOfTheHistoryWithIt() {
        val good = storedSession(id = "good")
        val encoded = SessionCodec.encode(listOf(good))
        // Splice in an element that no version of this schema could decode, keeping the array intact.
        val damaged = encoded.replaceFirst("[", "[{\"nonsense\":true},")

        val decoded = SessionCodec.decode(damaged)

        assertEquals("the readable record must survive a damaged neighbour", listOf(good), decoded)
    }

    // ---------------------------------------------------------------- retention and ordering

    private fun unfinished(id: String, at: Long) = storedSession(id = id, lastActivityAt = at)

    private fun completed(id: String, at: Long) = storedSession(id = id, completed = true, lastActivityAt = at)

    @Test
    fun retentionKeepsAtMostTenUnfinishedSessions() {
        val sessions = (1..15).map { unfinished("s$it", at = it.toLong()) }

        val kept = ListeningHistory.retain(sessions, now = 100L)

        assertEquals(ListeningHistory.MAX_UNFINISHED, kept.size)
        assertEquals("the ten most recently active, not the first ten", "s15", kept.first().id)
        assertFalse(kept.any { it.id == "s5" })
    }

    @Test
    fun retentionKeepsAtMostTwentyCompletedSessions() {
        val sessions = (1..30).map { completed("c$it", at = it.toLong()) }

        val kept = ListeningHistory.retain(sessions, now = 100L)

        assertEquals(ListeningHistory.MAX_COMPLETED, kept.size)
        assertEquals("c30", kept.first().id)
    }

    @Test
    fun aCompletedSessionOlderThanThirtyDaysIsDroppedEvenWhenThereIsRoom() {
        val now = 100L * 24 * 60 * 60 * 1000
        val recent = completed("recent", at = now - 1_000L)
        val stale = completed("stale", at = now - ListeningHistory.COMPLETED_MAX_AGE_MILLIS - 1)

        val kept = ListeningHistory.retain(listOf(recent, stale), now = now)

        assertEquals(listOf(recent), kept)
    }

    @Test
    fun theAgeLimitAppliesOnlyToCompletedSessions() {
        val now = 100L * 24 * 60 * 60 * 1000
        val ancientButUnfinished = unfinished("unfinished", at = now - ListeningHistory.COMPLETED_MAX_AGE_MILLIS - 1)

        val kept = ListeningHistory.retain(listOf(ancientButUnfinished), now = now)

        assertEquals("an unfinished session is a place, and places do not expire", listOf(ancientButUnfinished), kept)
    }

    @Test
    fun retentionAlwaysKeepsTheSessionCurrentlyInMemory() {
        val live = unfinished("live", at = 1L)
        val others = (1..15).map { unfinished("s$it", at = 1_000L + it) }

        val kept = ListeningHistory.retain(listOf(live) + others, now = 2_000L, keepId = "live")

        assertTrue("the live session must never be evicted", kept.any { it.id == "live" })
    }

    @Test
    fun anUnusableRecordIsNeverRetained() {
        val kept = ListeningHistory.retain(
            listOf(storedSession(id = "bad", schemaVersion = 99), storedSession(id = "good")),
            now = 2_000L
        )

        assertEquals(listOf("good"), kept.map { it.id })
    }

    @Test
    fun theMostRecentRecordForASessionIdWinsRatherThanAccumulating() {
        // Snapshotting the same live session repeatedly is the common path, not an edge case: it happens
        // on every chunk. Without deduplication, one session would appear in history once per pause.
        val older = storedSession(id = "same", lastActivityAt = 10L)
        val newer = storedSession(id = "same", lastActivityAt = 20L, index = 1)

        val kept = ListeningHistory.retain(listOf(older, newer), now = 100L)

        assertEquals(1, kept.size)
        assertEquals(20L, kept.single().lastActivityAt)
    }

    @Test
    fun unfinishedSessionsAreOfferedMostRecentlyActiveFirst() {
        val sessions = listOf(unfinished("old", at = 1L), unfinished("new", at = 3L), unfinished("mid", at = 2L))

        assertEquals(listOf("new", "mid", "old"), ListeningHistory.unfinished(sessions).map { it.id })
    }

    @Test
    fun completingASessionMovesItFromContinueListeningToRecentlyCompleted() {
        val record = completed("done", at = 5L)

        assertTrue(ListeningHistory.unfinished(listOf(record)).isEmpty())
        assertEquals(listOf("done"), ListeningHistory.completed(listOf(record)).map { it.id })
    }

    // ---------------------------------------------------------------- restoring a session

    private fun resumeRecord() = storedSession(
        id = "s-1",
        notes = listOf(noteA, noteB, queuedNote("p3")),
        origin = QueueOrigin.BrowseResults,
        index = 1,
        position = ReaderDocumentPosition(chunkId = "e:0", sectionId = "s2")
    )

    @Test
    fun restoreAdoptsTheQueueIndexAndPositionWithoutLoading() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.restore(resumeRecord())

        val queue = session.queueState.value
        assertEquals(3, queue.size)
        assertEquals(1, queue.index)
        assertTrue("a restored session waits for its Note the same way a transition does", queue.awaiting)
        assertEquals("s-1", session.id)
        assertEquals("nothing is fetched, and nothing plays, until the caller supplies the document", 0, engine.playCount)
    }

    @Test
    fun aRestoredSessionResumesAtTheSavedChunkWhenItsDocumentArrives() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.restore(resumeRecord())

        session.supplyDocument(originalDocument())

        assertEquals(4, session.state.value.currentParagraphIndex)
        assertEquals(
            "an exact resume is silent: the text is the continuation",
            0,
            engine.announcements.size
        )
        assertTrue(session.state.value.isPlaying)
    }

    @Test
    fun aRestoredSessionWhosePositionMovedAnnouncesTheSectionInsteadOfTheNoteTitle() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.restore(
            storedSession(
                id = "s-2",
                notes = listOf(noteA, noteB),
                index = 1,
                // Captured the way playback captures it, from the document while it holds it, so the
                // position carries all three identities.
                position = originalDocument().positionAt(4)
            )
        )

        // `e` survives only as `e:1`, so the resume is inexact and lands at the start of the block.
        session.supplyDocument(
            testDocument(
                testSection("s1", "One", testChunk("a"), testChunk("b"), testChunk("c")),
                testSection("s2", "Two", testChunk("d"), testChunk("e", part = 1))
            )
        )

        assertEquals("exactly one announcement, and the resume decision outranks the Note title", 1, engine.announcements.size)
        assertEquals("Two", engine.announcements.single())
        // Regression: `announceThenPlay` used to reset the document to its first chunk, so this resume
        // said "Two" and then read the Note from the top. The announcement and the start index have to
        // travel together.
        assertEquals("the announced section is also where playback starts", 4, session.state.value.currentParagraphIndex)
    }

    @Test
    fun aQueueTransitionStillAnnouncesTheNoteTitle() {
        // The restore path must not have replaced the transition announcement, which is the only thing
        // that tells a listener the Note changed.
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(ActiveQueue.snapshotOf(listOf(noteA, noteB), QueueOrigin.BrowseResults))

        session.supplyDocument(originalDocument())
        assertEquals(0, engine.announcements.size)

        session.onCurrentNoteFinished()
        session.supplyDocument(originalDocument())

        assertEquals(listOf(noteB.title), engine.announcements)
    }

    @Test
    fun restoreRefusesACompletedSession() {
        // Completed sessions are history, not resumable state. Replaying one creates a *new* session.
        val session = ReaderSession(FakeReaderEngine())

        session.restore(resumeRecord().copy(completed = true))

        assertNull(session.queueState.value.queue)
        assertNull(session.id)
    }

    @Test
    fun replayStartsFromTheBeginningAsANewSession() {
        val historical = resumeRecord().copy(completed = true)
        val session = ReaderSession(FakeReaderEngine())

        session.replay(historical)

        val queue = session.queueState.value
        assertEquals("the same Notes in the same order", historical.notes, queue.queue?.items)
        assertEquals("from the beginning", 0, queue.index)
        assertNotEquals("a new session, so the historical record is not overwritten", historical.id, session.id)
        assertEquals(historical.origin, queue.queue?.origin)
    }

    @Test
    fun anExactRestoreIsRecordedAsExact() {
        val session = ReaderSession(FakeReaderEngine())
        session.restore(resumeRecord())

        session.supplyDocument(originalDocument())

        assertEquals(PositionMatch.CHUNK, session.lastResume.value?.match)
        assertNull("an exact resume says nothing", session.lastResume.value?.announcement)
    }

    @Test
    fun anInexactRestoreIsRecordedAsInexactWithWhatItAnnounced() {
        // The spec requires the match quality to be recorded as a fact. Without this record, an exact resume
        // and a fallback to the start of a section are indistinguishable from outside - both produce audio
        // near the right place, and only one of them is what the listener asked for.
        val session = ReaderSession(FakeReaderEngine())
        session.restore(
            storedSession(
                id = "s-8",
                notes = listOf(noteA),
                position = originalDocument().positionAt(4)
            )
        )

        session.supplyDocument(
            testDocument(
                testSection("s1", "One", testChunk("a"), testChunk("b"), testChunk("c")),
                testSection("s2", "Two", testChunk("d"))
            )
        )

        assertEquals(PositionMatch.SECTION, session.lastResume.value?.match)
        assertEquals("Two", session.lastResume.value?.announcement)
    }

    @Test
    fun aQueueTransitionIsNotRecordedAsARestore() {
        // A transition also produces a decision ("start at 0"). Recording it would report the previous
        // restore's match quality as if it belonged to this Note.
        val session = ReaderSession(FakeReaderEngine())
        session.restore(resumeRecord())
        session.supplyDocument(originalDocument())
        val afterRestore = session.lastResume.value

        session.onCurrentNoteFinished()
        session.supplyDocument(originalDocument())

        assertEquals("the record still describes the restore", afterRestore, session.lastResume.value)
    }

    @Test
    fun startingASessionNeverAdoptsARetainedIdentityOrPosition() {
        val retained = resumeRecord()
        val session = ReaderSession(FakeReaderEngine())
        session.restore(retained)
        val restoredId = session.id

        // Play on a Note: "creates a new Active Queue containing only this Note; starts from the beginning".
        session.start(originalDocument())

        assertNotEquals(restoredId, session.id)
        assertEquals(0, session.queueState.value.index)

        // The new session does have a position - its own, at its own first chunk. The requirement is that
        // it is not the *retained* one, which was index 4 of a queue this session has never seen.
        val position = session.snapshot(1L)?.position
        assertEquals(
            "the new session starts at its own beginning",
            0,
            originalDocument().resolve(position!!).paragraphIndex
        )
    }

    @Test
    fun aSnapshotCapturesThePositionAtTheCurrentChunk() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(originalDocument())
        repeat(4) { engine.nextParagraph() }

        val record = session.snapshot(now = 42L)!!

        assertEquals(42L, record.lastActivityAt)
        assertEquals("A Note", record.notes.single().title)
        assertTrue(record.wasPlaying)

        val resolution = originalDocument().resolve(record.position!!)
        assertEquals("the position is the chunk being read", PositionMatch.CHUNK, resolution.match)
        assertEquals(4, resolution.paragraphIndex)
    }

    @Test
    fun aSnapshotWhileAwaitingTakesNoPositionFromThePreviousNote() {
        // The engine still holds the Note that just ended, so capturing from it would record a position in
        // a document the session is no longer reading - and that position belongs to a different Note.
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(ActiveQueue.snapshotOf(listOf(noteA, noteB), QueueOrigin.BrowseResults))
        session.supplyDocument(originalDocument())
        repeat(2) { engine.nextParagraph() }

        session.onCurrentNoteFinished()

        assertNull(session.snapshot(1L)?.position)
    }

    @Test
    fun aRestoredPositionSurvivesAFailedLoadSoRetryResumesAtTheSamePlace() {
        val session = ReaderSession(FakeReaderEngine())
        session.restore(resumeRecord())

        session.failCurrentNote(ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE, "offline"))
        assertTrue(session.queueState.value.isStalled)
        assertEquals(
            "a stalled session still knows the position of the Note it is trying to load, so an " +
                "interruption while stalled does not lose it",
            resumeRecord().position,
            session.currentPosition
        )

        session.retryCurrentNote()
        session.supplyDocument(originalDocument())

        assertEquals("Retry must not restart a Note the listener was halfway through", 4, session.state.value.currentParagraphIndex)
    }

    @Test
    fun aRestoredQueueKeepsItsStoredMembershipRatherThanReEvaluatingItsOrigin() {
        val session = ReaderSession(FakeReaderEngine())
        val record = storedSession(
            id = "s-3",
            notes = listOf(noteB, noteA),
            origin = QueueOrigin.Smart("Weekly review"),
            index = 0
        )

        session.restore(record)

        // The order is the stored order, not re-sorted, and not the order any source would produce now.
        assertEquals(listOf(noteB, noteA), session.queueState.value.queue?.items)
        assertEquals(QueueOrigin.Smart("Weekly review"), session.queueState.value.queue?.origin)
    }

    // ---------------------------------------------------------------- the store

    @Test
    fun aFailedWriteLeavesTheStoreReadableAndPlaybackUntouched() = runTest {
        val store = InMemorySessionStore()
        store.failWrites = true
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)
        session.start(originalDocument())
        session.pause()

        store.write(listOf(session.snapshot(1L)!!))

        assertTrue("the write failed", store.writeCount == 1)
        assertEquals("and nothing was stored", emptyList<StoredSession>(), store.stored)
        assertEquals("but the session is exactly where it was", 1, engine.pauseCount)
    }

    @Test
    fun aFailedReadReportsAnEmptyHistory() = runTest {
        val store = InMemorySessionStore(listOf(storedSession()))
        store.failReads = true

        val history = ListeningHistory.retain(store.read(), now = 100L)

        assertTrue(history.isEmpty())
    }

    @Test
    fun storedHistoryRoundTripsThroughTheStore() = runTest {
        val store = InMemorySessionStore()
        val record = resumeRecord()

        store.write(listOf(record))

        assertEquals(listOf(record), store.read())
    }
}
