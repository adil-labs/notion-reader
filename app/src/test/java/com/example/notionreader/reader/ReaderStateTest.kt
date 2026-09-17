package com.example.notionreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStateTest {

    private val paragraphs = listOf(
        "First paragraph.",
        "Second paragraph.",
        "Third paragraph."
    )

    private fun state() = ReaderState(paragraphs = paragraphs)

    @Test
    fun initialStateStartsAtFirstParagraphAndIsPaused() {
        val state = state()

        assertEquals(0, state.currentParagraphIndex)
        assertEquals("First paragraph.", state.currentParagraph)
        assertFalse(state.isPlaying)
    }

    @Test
    fun nextParagraphMovesToSecondParagraph() {
        val state = state().nextParagraph()

        assertEquals(1, state.currentParagraphIndex)
        assertEquals("Second paragraph.", state.currentParagraph)
    }

    @Test
    fun nextParagraphAtEndStaysAtLastParagraph() {
        val state = state()
            .nextParagraph()
            .nextParagraph()
            .nextParagraph()
            .nextParagraph()

        assertEquals(2, state.currentParagraphIndex)
        assertEquals("Third paragraph.", state.currentParagraph)
    }

    @Test
    fun previousParagraphMovesBackward() {
        val state = state()
            .nextParagraph()
            .nextParagraph()
            .previousParagraph()

        assertEquals(1, state.currentParagraphIndex)
        assertEquals("Second paragraph.", state.currentParagraph)
    }

    @Test
    fun previousParagraphAtBeginningStaysAtFirstParagraph() {
        val state = state()
            .previousParagraph()
            .previousParagraph()

        assertEquals(0, state.currentParagraphIndex)
        assertEquals("First paragraph.", state.currentParagraph)
    }

    @Test
    fun emptyDocumentReturnsEmptyCurrentParagraph() {
        val state = ReaderState(paragraphs = emptyList())

        assertEquals(0, state.currentParagraphIndex)
        assertEquals("", state.currentParagraph)
        assertFalse(state.isPlaying)
    }

    @Test
    fun navigationOnEmptyDocumentIsNoOp() {
        val state = ReaderState(paragraphs = emptyList())
            .nextParagraph()
            .previousParagraph()

        assertEquals(0, state.currentParagraphIndex)
        assertEquals("", state.currentParagraph)
    }

    @Test
    fun playMarksReaderAsPlaying() {
        val state = state().play()

        assertTrue(state.isPlaying)
        assertEquals(0, state.currentParagraphIndex)
    }

    @Test
    fun pauseMarksReaderAsPaused() {
        val state = state().play().pause()

        assertFalse(state.isPlaying)
        assertEquals(0, state.currentParagraphIndex)
    }

    @Test
    fun navigationPreservesPlaybackState() {
        val playing = state().play().nextParagraph()
        assertTrue(playing.isPlaying)

        val paused = state().nextParagraph()
        assertFalse(paused.isPlaying)
    }

    @Test
    fun navigationDoesNotMutateOriginalState() {
        val original = state()
        original.nextParagraph()

        assertEquals(0, original.currentParagraphIndex)
    }
}
