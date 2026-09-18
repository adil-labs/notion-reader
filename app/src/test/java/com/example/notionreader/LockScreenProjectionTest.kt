package com.example.notionreader

import androidx.media3.common.Player
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.player.ReaderState
import com.example.notionreader.player.sectionSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue and the reading position as a media session renders them.
 *
 * Everything here is pure — `Player.STATE_*` are compile-time ints — so it needs no device, which matters
 * because the rest of the media-session adapter cannot be tested without one. The projection is also where
 * the interesting mistakes live: an identity scheme, a subtitle that must not claim to know something, and a
 * playback state whose tempting value would be a false one.
 */
class LockScreenProjectionTest {


    /**
     * A queue of the same Note twice — the case [ProjectedQueueItem.uid] exists for.
     *
     * Built with explicit page ids rather than derived from the index, because a fixture that keys page ids
     * off the position *cannot express a duplicate at all*: two entries titled "Alpha" would still get
     * `page-0` and `page-1`. The first version of this test did exactly that and passed its own premise by
     * accident, which is the same shape as asserting about a case that was never produced.
     */
    private fun repeatingQueue(pageId: String, times: Int) = QueueState(
        queue = ActiveQueue.snapshotOf(
            List(times) { QueuedNote(pageId = pageId, title = "Alpha") },
            QueueOrigin.BrowseResults
        ),
        index = 0
    )

    private fun queue(
        titles: List<String> = listOf("Alpha", "Beta", "Gamma"),
        index: Int = 0,
        awaiting: Boolean = false,
        completed: Boolean = false,
        stalled: Boolean = false
    ) = QueueState(
        queue = ActiveQueue.snapshotOf(
            titles.mapIndexed { i, title -> QueuedNote(pageId = "page-$i", title = title) },
            QueueOrigin.BrowseResults
        ),
        index = index,
        awaiting = awaiting,
        completed = completed,
        failure = if (stalled) ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE) else null
    )

    private fun state(index: Int = 0, parts: Int = 4) = ReaderState(
        paragraphs = List(parts) { "part $it" },
        currentParagraphIndex = index
    )

    // --- Identity -----------------------------------------------------------------------------------

    /**
     * The reason [ProjectedQueueItem.uid] is not the page id.
     *
     * `ActiveQueue` permits duplicates on purpose — "Two identical Notes in one queue is a legitimate thing for
     * a user to have asked for" — so two entries can share a page id while being distinct entries. Media3 uses
     * the uid to identify an item across state updates, so sharing one would make the two entries
     * indistinguishable when the current index moves between them.
     */
    @Test
    fun twoEntriesOfTheSameNoteGetDifferentUids() {
        val repeated = repeatingQueue(pageId = "page-0", times = 2)
        val items = projectedQueueItems(repeated, state())

        assertEquals("both entries are present", 2, items.size)
        assertEquals("and they are the same Note", items[0].pageId, items[1].pageId)
        assertNotEquals(
            "but they are different queue entries, which Media3 must be able to tell apart",
            items[0].uid,
            items[1].uid
        )
    }

    @Test
    fun theUidIsStableForAnEntryAcrossChangesOfTheCurrentIndex() {
        // The uid must not change when the *position* changes, or Media3 would see the item as replaced and
        // lose its identity. Asserted by projecting the same queue at two different indices.
        val atFirst = projectedQueueItems(queue(index = 0), state())
        val atSecond = projectedQueueItems(queue(index = 1), state())

        assertEquals(atFirst.map { it.uid }, atSecond.map { it.uid })
    }

    @Test
    fun theMediaIdIsTheNotesIdentityRatherThanTheEntryS() {
        val items = projectedQueueItems(queue(), state())

        // A stable Note identity is what a page id is for, and it is deliberately separate from the uid.
        assertEquals(listOf("page-0", "page-1", "page-2"), items.map { it.pageId })
    }

    // --- Subtitles ----------------------------------------------------------------------------------

    @Test
    fun theCurrentEntryShowsItsSectionAndTheOthersShowTheirPosition() {
        val document = ReaderDocument.fromChunkTexts(
            pageId = "page-0",
            title = "Alpha",
            texts = listOf("Intro.", "Heading", "Body under the heading.")
        )
        val reading = ReaderState(
            paragraphs = document.chunkTexts,
            currentParagraphIndex = 2,
            sections = document.sectionSpans()
        )

        val items = projectedQueueItems(queue(index = 1), reading)

        // Every entry gets something, because a lock screen with a blank second line looks broken.
        assertTrue(items.all { !it.subtitle.isNullOrBlank() })

        // The non-current entries state their position: a section is not knowable for a Note that has not been
        // fetched, and inventing one would be a label the state cannot support.
        assertEquals("Note 1 of 3", items[0].subtitle)
        assertEquals("Note 3 of 3", items[2].subtitle)
    }

    @Test
    fun aQueueOfOneHasNoPositionSubtitleBecauseThereIsNothingToPositionWithin() {
        val items = projectedQueueItems(queue(titles = listOf("Only")), state())

        // Same rule as the Player's queue line: "Note 1 of 1" is noise on the common path, since every Play
        // from the Note screen is a queue of one.
        assertNull(items.single().subtitle)
    }

    // --- Playback state -----------------------------------------------------------------------------

    @Test
    fun thePlaybackStateFollowsTheQueuesProgress() {
        assertEquals(Player.STATE_BUFFERING, playbackStateFor(queue(awaiting = true)))
        assertEquals(Player.STATE_ENDED, playbackStateFor(queue(completed = true)))
        assertEquals(Player.STATE_READY, playbackStateFor(queue()))
    }

    @Test
    fun noQueueIsIdleRatherThanReady() {
        // The app launches with a demo document loaded and no session at all, so this is a real state and not
        // a defensive branch.
        assertEquals(Player.STATE_IDLE, playbackStateFor(QueueState()))
        assertTrue(projectedQueueItems(QueueState(), state()).isEmpty())
    }

    /**
     * A stalled Note reports `READY`, not `BUFFERING`.
     *
     * This is the one state where the tempting answer is a false claim. A stall means the session is *not*
     * retrying and will not on its own — the Player shows Retry and waits for a person. `BUFFERING` would tell
     * the platform that progress is happening, and a lock screen spinner that never stops is worse than a
     * lock screen that says nothing about progress.
     */
    @Test
    fun aStalledNoteIsReadyAndLoadingRatherThanBuffering() {
        val stalled = queue(stalled = true)

        assertEquals(Player.STATE_READY, playbackStateFor(stalled))
        assertTrue("but something *is* being waited for", isLoadingFor(stalled))
    }

    @Test
    fun loadingIsReportedForBothAFetchAndAStall() {
        assertTrue(isLoadingFor(queue(awaiting = true)))
        assertTrue(isLoadingFor(queue(stalled = true)))
        assertFalse(isLoadingFor(queue()))
    }
}
