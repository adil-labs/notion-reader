package com.example.notionreader.player

import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderChunk
import com.example.notionreader.domain.ReaderChunkKind
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderSection
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

    // ---------------------------------------------------------------- sections

    /** Three sections over five paragraphs: 0-1, 2-3 and 4. */
    private val outline = listOf(
        ReaderSectionSpan("intro", null, 0, firstParagraphIndex = 0, lastParagraphIndex = 1),
        ReaderSectionSpan("h1", "First heading", 1, firstParagraphIndex = 2, lastParagraphIndex = 3),
        ReaderSectionSpan("h2", "Second heading", 1, firstParagraphIndex = 4, lastParagraphIndex = 4)
    )

    private fun sectionedState() = ReaderState(
        paragraphs = List(5) { "Paragraph $it" },
        sections = outline
    )

    @Test
    fun reportsTheSectionThePositionFallsIn() {
        assertEquals("intro", sectionedState().currentSection?.id)
        assertEquals("h1", sectionedState().nextParagraph().nextParagraph().currentSection?.id)
        assertEquals("h2", sectionedState().let { it.copy(currentParagraphIndex = 4) }.currentSection?.id)
    }

    @Test
    fun numbersSectionsFromOneForThePlayer() {
        assertEquals("Section 1 of 3", sectionedState().sectionPositionDescription)
        assertEquals(
            "Section 2 of 3",
            sectionedState().let { it.copy(currentParagraphIndex = 2) }.sectionPositionDescription
        )
    }

    @Test
    fun reportsNoSectionPositionWithoutAnOutline() {
        assertEquals(null, state().sectionPositionDescription)
        assertEquals(null, state().currentSection)
    }

    @Test
    fun nextSectionJumpsToTheStartOfTheFollowingSection() {
        val state = sectionedState().nextSection()

        assertEquals(2, state.currentParagraphIndex)
        assertEquals("h1", state.currentSection?.id)
    }

    @Test
    fun nextSectionSkipsTheRestOfTheCurrentSection() {
        val state = sectionedState().let { it.copy(currentParagraphIndex = 1) }.nextSection()

        assertEquals(2, state.currentParagraphIndex)
    }

    @Test
    fun nextSectionInTheFinalSectionGoesToTheLastParagraph() {
        val state = sectionedState().let { it.copy(currentParagraphIndex = 3) }.nextSection()

        assertEquals(4, state.currentParagraphIndex)
    }

    @Test
    fun previousSectionReturnsToTheStartOfTheCurrentSection() {
        val state = sectionedState().let { it.copy(currentParagraphIndex = 3) }.previousSection()

        assertEquals(2, state.currentParagraphIndex)
        assertEquals("h1", state.currentSection?.id)
    }

    @Test
    fun previousSectionAtASectionStartStepsBackASection() {
        val state = sectionedState().let { it.copy(currentParagraphIndex = 2) }.previousSection()

        assertEquals(0, state.currentParagraphIndex)
    }

    @Test
    fun previousSectionAtTheVeryBeginningStaysPut() {
        val state = sectionedState().previousSection()

        assertEquals(0, state.currentParagraphIndex)
    }

    @Test
    fun sectionNavigationDegradesToParagraphsWithoutAnOutline() {
        assertEquals(1, state().nextSection().currentParagraphIndex)
        assertEquals(0, state().nextParagraph().previousSection().currentParagraphIndex)
    }

    // ---------------------------------------------------------------- playback state

    @Test
    fun distinguishesPausedFromEndOfNote() {
        assertEquals("Paused", state().playbackStateDescription)
        assertEquals("Playing", state().play().playbackStateDescription)
        assertEquals("End of note", state().play().finish().playbackStateDescription)
    }

    @Test
    fun finishingClearsPlayingAndFlagsTheEnd() {
        val state = state().play().finish()

        assertFalse(state.isPlaying)
        assertTrue(state.hasReachedEnd)
    }

    @Test
    fun pausingIsNotFinishing() {
        assertFalse(state().play().pause().hasReachedEnd)
    }

    @Test
    fun movingAfterTheEndClearsTheEndFlag() {
        val state = state().nextParagraph().play().finish().previousParagraph()

        assertFalse(state.hasReachedEnd)
        assertEquals(0, state.currentParagraphIndex)
    }

    @Test
    fun playingClearsTheEndFlag() {
        assertFalse(state().play().finish().play().hasReachedEnd)
    }

    @Test
    fun aMoveThatChangesNothingReturnsTheSameState() {
        val atEnd = state().let { it.copy(currentParagraphIndex = 2) }.play().finish()

        assertEquals(atEnd, atEnd.nextParagraph())
        assertTrue(
            "a boundary press must not silently clear the end-of-note state",
            atEnd.nextParagraph().hasReachedEnd
        )
    }

    // ---------------------------------------------------------------- outline projection

    @Test
    fun projectsSectionRangesOntoParagraphIndexes() {
        val document = ReaderDocument(
            pageId = "p",
            title = "T",
            sections = listOf(
                ReaderSection("intro", null, 0, listOf(chunk("a"), chunk("b"))),
                ReaderSection("h1", "Heading", 1, listOf(chunk("c")))
            )
        )

        assertEquals(
            listOf(
                ReaderSectionSpan("intro", null, 0, 0, 1),
                ReaderSectionSpan("h1", "Heading", 1, 2, 2)
            ),
            document.sectionSpans()
        )
    }

    @Test
    fun skipsEmptySectionsWhenProjectingRanges() {
        val document = ReaderDocument(
            pageId = "p",
            title = "T",
            sections = listOf(
                ReaderSection("empty", "Nothing", 1, emptyList()),
                ReaderSection("h1", "Heading", 1, listOf(chunk("a")))
            )
        )

        assertEquals(listOf(ReaderSectionSpan("h1", "Heading", 1, 0, 0)), document.sectionSpans())
    }

    private fun chunk(text: String) = ReaderChunk(
        id = "$text:0",
        sourceBlockId = text,
        kind = ReaderChunkKind.PARAGRAPH,
        text = text
    )
}
