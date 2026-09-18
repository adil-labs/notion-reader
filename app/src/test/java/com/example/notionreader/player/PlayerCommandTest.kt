package com.example.notionreader.player

import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.domain.queue.QueuedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command surface: which commands exist, and when each may run.
 *
 * Pure and synchronous, like everything else in this layer. The properties here are the ones the Core
 * Player & TTS Playback spec states normatively, and the reason they are worth asserting at this level is
 * that a media session, a headset button and a Player button will all read this one answer — so a mistake
 * here is a mistake in every transport at once, and the only place it can be caught cheaply.
 */
class PlayerCommandTest {

    private val parts = 4

    private fun state(index: Int = 0, isPlaying: Boolean = false, parts: Int = this.parts) = ReaderState(
        paragraphs = List(parts) { "part $it" },
        currentParagraphIndex = index,
        isPlaying = isPlaying
    )

    private fun queue(
        size: Int = 3,
        index: Int = 1,
        awaiting: Boolean = false,
        completed: Boolean = false,
        stalled: Boolean = false
    ) = QueueState(
        queue = ActiveQueue.snapshotOf(
            List(size) { QueuedNote(pageId = "page-$it", title = "Note $it") },
            QueueOrigin.BrowseResults
        ),
        index = index,
        awaiting = awaiting,
        completed = completed,
        failure = if (stalled) ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE) else null
    )

    private fun availability(
        state: ReaderState = state(),
        queue: QueueState = queue()
    ) = playerCommandAvailability(state, queue)

    // --- The ordinary case ------------------------------------------------------------------------

    @Test
    fun everyCommandIsAvailableInTheMiddleOfAnOrdinaryNote() {
        val availability = availability(state = state(index = 2, isPlaying = true))

        // Asserted as a whole set rather than command by command, so a command added to the enum without an
        // availability rule fails here instead of defaulting to something.
        val unavailable = PlayerCommand.entries.filterNot { availability.allows(it) }
        assertEquals(emptyList<PlayerCommand>(), unavailable)
    }

    /**
     * A mid-document, multi-Note, loaded state is the only one in which everything is available, so it is
     * also the state that proves the availability value is not simply `true` everywhere.
     */
    @Test
    fun theOrdinaryCaseIsNotTriviallyEverythingIsAvailable() {
        val atTheEdges = availability(state = state(index = 0, parts = 1), queue = queue(size = 1, index = 0))

        assertFalse(atTheEdges.canMoveToPreviousSection)
        assertFalse(atTheEdges.canMoveToNextSection)
        assertFalse(atTheEdges.canMoveToPreviousPart)
        assertFalse(atTheEdges.canMoveToNextPart)
        assertFalse(atTheEdges.canMoveToPreviousNote)
        assertFalse(atTheEdges.canMoveToNextNote)
    }

    // --- Boundaries -------------------------------------------------------------------------------

    @Test
    fun thePartAndSectionCommandsRefuseToWrapAtTheDocumentEdges() {
        val atFirst = availability(state = state(index = 0))
        assertFalse("there is nothing before the first part", atFirst.canMoveToPreviousPart)
        assertFalse("there is nothing before the first part", atFirst.canMoveToPreviousSection)
        assertTrue(atFirst.canMoveToNextPart)
        assertTrue(atFirst.canMoveToNextSection)

        val atLast = availability(state = state(index = parts - 1))
        assertFalse("there is nothing after the last part", atLast.canMoveToNextPart)
        assertFalse("there is nothing after the last part", atLast.canMoveToNextSection)
        assertTrue(atLast.canMoveToPreviousPart)
        assertTrue(atLast.canMoveToPreviousSection)
    }

    @Test
    fun theNoteCommandsRefuseToWrapAtTheQueueEdges() {
        val atFirst = availability(queue = queue(size = 3, index = 0))
        assertFalse(atFirst.canMoveToPreviousNote)
        assertTrue(atFirst.canMoveToNextNote)

        val atLast = availability(queue = queue(size = 3, index = 2))
        assertTrue(atLast.canMoveToPreviousNote)
        assertFalse(atLast.canMoveToNextNote)
    }

    // --- States in which nothing may act on the document ------------------------------------------

    @Test
    fun aFetchAStallOrCompletionDisablesEveryCommandThatActsOnTheDocument() {
        val fetching = availability(queue = queue(awaiting = true))
        val stalled = availability(queue = queue(stalled = true))
        val finished = availability(queue = queue(completed = true))

        for (unusable in listOf(fetching, stalled, finished)) {
            assertFalse(unusable.canPlay)
            assertFalse(unusable.canMoveToPreviousSection)
            assertFalse(unusable.canMoveToNextSection)
            assertFalse(unusable.canMoveToPreviousPart)
            assertFalse(unusable.canMoveToNextPart)
            assertFalse(unusable.canRestartNote)
        }
    }

    /**
     * The deliberate enablement: `docs/decisions.md` §22.6. A Note that will not load leaves the session
     * waiting with Retry as its only offer, and if moving on were also forbidden one missing page would cost
     * the listener every Note after it. The spec's "it is never skipped" is about the application never
     * advancing past a failure on its own, which is not this.
     */
    @Test
    fun theNoteCommandsStayAvailableWhileTheQueueIsStalled() {
        val stalled = availability(queue = queue(index = 1, stalled = true))

        assertTrue(stalled.canMoveToPreviousNote)
        assertTrue(stalled.canMoveToNextNote)
    }

    @Test
    fun theNoteCommandsAreUnavailableWhileANoteIsBeingFetched() {
        val fetching = availability(queue = queue(index = 1, awaiting = true))

        // Mid-fetch the session is not at a Note it can move relative to yet; enabling these would let a
        // press be dropped or issue a second transition the queue cannot honour.
        assertFalse(fetching.canMoveToPreviousNote)
        assertFalse(fetching.canMoveToNextNote)
        assertFalse(fetching.canJumpToNote)
    }

    @Test
    fun restartIsUnavailableOnceTheQueueHasCompleted() {
        // Completion is a fact the Active Queue & Reader Session spec owns, and a restart would produce a
        // session that is completed and playing at once. Replay is a queue-level action with its own home.
        assertFalse(availability(queue = queue(completed = true)).canRestartNote)
    }

    @Test
    fun aDocumentWithNothingToReadDoesNotOfferPlay() {
        val empty = availability(state = state(parts = 0))

        // An empty document is reported by the loader as UNSUPPORTED_DOCUMENT_STRUCTURE rather than being
        // left to produce an enabled, inert Play.
        assertFalse(empty.canPlay)
        assertFalse(empty.canRestartNote)
    }

    // --- Play, pause and the single action that stands for both ------------------------------------

    @Test
    fun togglingIsAvailableWheneverEitherHalfIs() {
        val paused = availability(state = state(isPlaying = false))
        assertTrue(paused.canPlay)
        assertFalse(paused.canPause)
        assertTrue("the single action is possible while paused", paused.allows(PlayerCommand.TogglePlayback))

        val playing = availability(state = state(isPlaying = true))
        assertFalse(playing.canPause.not())
        assertTrue("the single action is possible while playing", playing.allows(PlayerCommand.TogglePlayback))
    }

    @Test
    fun pauseIsUnavailableWhenNothingIsPlaying() {
        assertFalse(availability(state = state(isPlaying = false)).canPause)
    }

    // --- The transport split -----------------------------------------------------------------------

    @Test
    fun theQueueControlIsASurfaceActionRatherThanATransport() {
        // The UI contract names `Queue` among the four primary Player controls, and it is deliberately not
        // a transport command: its effect is to change what the phone is displaying, so a lock screen must
        // not be able to offer it.
        assertFalse(PlayerCommand.OpenQueue.isTransport)
        assertFalse(PlayerCommand.OpenMorePlaybackControls.isTransport)
        assertFalse(PlayerCommand.transports.contains(PlayerCommand.OpenQueue))
        assertFalse(PlayerCommand.transports.contains(PlayerCommand.OpenMorePlaybackControls))
    }

    @Test
    fun theAdvertisedTransportSetIsExactlyTheCommandsATransportMayInvoke() {
        val availability = availability(state = state(index = 2, isPlaying = true))

        // What a media session advertises. Surface actions are excluded by construction, so a transport
        // cannot be handed one by accident.
        assertEquals(PlayerCommand.transports.toSet(), availability.availableTransports)
        assertEquals(11, availability.availableTransports.size)
    }

    @Test
    fun theAdvertisedSetShrinksWithAvailabilityRatherThanBeingFixed() {
        val endOfQueue = availability(state = state(index = parts - 1), queue = queue(size = 1, index = 0))

        val advertised = endOfQueue.availableTransports
        assertFalse("there is no next part to seek to", advertised.contains(PlayerCommand.NextPart))
        assertFalse("there is no next Note to skip to", advertised.contains(PlayerCommand.NextNote))
        // And the way back, and the way to hear this Note, are still offered. Nothing is playing in this
        // fixture, so `Play` is the available half of the transport rather than `Pause`.
        assertTrue(advertised.contains(PlayerCommand.PreviousPart))
        assertTrue(advertised.contains(PlayerCommand.Play))
    }

    // --- Without a queue ---------------------------------------------------------------------------

    @Test
    fun thereIsNothingToShowOrNavigateWithoutAQueue() {
        val noQueue = availability(queue = QueueState())

        assertFalse(noQueue.canOpenQueue)
        assertFalse(noQueue.canJumpToNote)
        assertFalse(noQueue.canMoveToNextNote)
        assertFalse(noQueue.canMoveToPreviousNote)
    }
}
