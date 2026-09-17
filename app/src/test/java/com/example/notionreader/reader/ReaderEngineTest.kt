package com.example.notionreader.reader

import com.example.notionreader.speech.FakeSpeechEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderEngineTest {

    private val document = ReaderDocument.fromChunkTexts(
        pageId = "test",
        title = "Test document",
        texts = listOf(
            "First paragraph.",
            "Second paragraph.",
            "Third paragraph."
        )
    )

    private val speech = FakeSpeechEngine()

    private fun engine(startParagraphIndex: Int = 0) =
        ReaderEngineImpl(
            speechEngine = speech,
            initialDocument = document,
            initialParagraphIndex = startParagraphIndex
        )

    @Test
    fun initialStateIsNotPlayingAndSpeaksNothing() {
        val engine = engine()

        assertFalse(engine.state.value.isPlaying)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun playSpeaksCurrentParagraph() {
        val engine = engine()

        engine.play()

        assertTrue(engine.state.value.isPlaying)
        assertEquals(listOf("First paragraph."), speech.spoken)
    }

    @Test
    fun pauseStopsSpeech() {
        val engine = engine()
        engine.play()

        engine.pause()

        assertFalse(engine.state.value.isPlaying)
        assertEquals(1, speech.stopCount)
    }

    @Test
    fun playAfterPauseRestartsCurrentParagraphFromTheBeginning() {
        val engine = engine()
        engine.play()
        engine.nextParagraph()
        engine.pause()

        engine.play()

        assertEquals(1, engine.state.value.currentParagraphIndex)
        assertEquals(listOf("First paragraph.", "Second paragraph.", "Second paragraph."), speech.spoken)
    }

    @Test
    fun nextWhilePlayingStopsCurrentSpeechAndReadsNext() {
        val engine = engine()
        engine.play()

        engine.nextParagraph()

        assertEquals(1, engine.state.value.currentParagraphIndex)
        assertEquals(1, speech.stopCount)
        assertEquals(listOf("First paragraph.", "Second paragraph."), speech.spoken)
    }

    @Test
    fun nextWhilePausedDoesNotStartSpeech() {
        val engine = engine()

        engine.nextParagraph()

        assertEquals(1, engine.state.value.currentParagraphIndex)
        assertFalse(engine.state.value.isPlaying)
        assertEquals(0, speech.stopCount)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun previousWhilePlayingReadsPreviousParagraph() {
        val engine = engine(startParagraphIndex = 1)
        engine.play()

        engine.previousParagraph()

        assertEquals(0, engine.state.value.currentParagraphIndex)
        assertEquals(1, speech.stopCount)
        assertEquals(listOf("Second paragraph.", "First paragraph."), speech.spoken)
    }

    @Test
    fun previousWhilePausedDoesNotStartSpeech() {
        val engine = engine(startParagraphIndex = 1)

        engine.previousParagraph()

        assertEquals(0, engine.state.value.currentParagraphIndex)
        assertEquals(0, speech.stopCount)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun nextAtEndWhilePlayingDoesNotRestartSpeech() {
        val engine = engine(startParagraphIndex = 2)
        engine.play()

        engine.nextParagraph()

        assertEquals(2, engine.state.value.currentParagraphIndex)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(0, speech.stopCount)
        assertEquals(listOf("Third paragraph."), speech.spoken)
    }

    @Test
    fun playAtEmptyDocumentDoesNotSpeak() {
        val engine = ReaderEngineImpl(
            speechEngine = speech,
            initialDocument = ReaderDocument.Empty
        )

        engine.play()

        assertFalse(engine.state.value.isPlaying)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun navigationOnEmptyDocumentDoesNotSpeak() {
        val engine = ReaderEngineImpl(
            speechEngine = speech,
            initialDocument = ReaderDocument.Empty
        )

        engine.nextParagraph()
        engine.previousParagraph()

        assertEquals(0, engine.state.value.currentParagraphIndex)
        assertEquals("", engine.state.value.currentParagraph)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun setDocumentReplacesContentAndStopsSpeech() {
        val engine = engine()
        engine.play()

        val replacement = ReaderDocument.fromChunkTexts(
            pageId = "other",
            title = "Other document",
            texts = listOf("Only paragraph.")
        )
        engine.setDocument(replacement)

        assertEquals("Only paragraph.", engine.state.value.currentParagraph)
        assertEquals(0, engine.state.value.currentParagraphIndex)
        assertFalse(engine.state.value.isPlaying)
        assertEquals(1, speech.stopCount)
        assertEquals("Other document", engine.document.value.title)
    }

    @Test
    fun setDocumentClampsStartIndexIntoRange() {
        val engine = engine()

        engine.setDocument(document, startParagraphIndex = 99)

        assertEquals(2, engine.state.value.currentParagraphIndex)
    }

    @Test
    fun continuesAutomaticallyThroughTheDocument() {
        val engine = engine()
        engine.play()

        speech.completeUtterance()

        assertEquals(1, engine.state.value.currentParagraphIndex)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(listOf("First paragraph.", "Second paragraph."), speech.spoken)

        speech.completeUtterance()

        assertEquals(2, engine.state.value.currentParagraphIndex)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(listOf("First paragraph.", "Second paragraph.", "Third paragraph."), speech.spoken)
    }

    @Test
    fun finishingTheLastParagraphStopsPlaybackWithoutWrapping() {
        val engine = engine()
        engine.play()

        speech.completeUtterance()
        speech.completeUtterance()
        speech.completeUtterance()

        assertFalse(engine.state.value.isPlaying)
        assertEquals(2, engine.state.value.currentParagraphIndex)
        // Nothing is playing when the document runs out, so no utterance has to be interrupted.
        assertEquals(0, speech.stopCount)
        assertEquals(
            listOf("First paragraph.", "Second paragraph.", "Third paragraph."),
            speech.spoken
        )
    }

    @Test
    fun completingTheOnlyParagraphOfAOneParagraphDocumentStopsPlayback() {
        val engine = ReaderEngineImpl(
            speechEngine = speech,
            initialDocument = ReaderDocument.fromChunkTexts(
                pageId = "single",
                title = "Single",
                texts = listOf("Only paragraph.")
            )
        )

        engine.play()
        speech.completeUtterance()

        assertFalse(engine.state.value.isPlaying)
        assertEquals(0, engine.state.value.currentParagraphIndex)
        assertEquals(listOf("Only paragraph."), speech.spoken)
    }

    @Test
    fun aLateCompletionFromACancelledUtteranceIsIgnoredAfterPause() {
        val engine = engine()
        engine.play()
        engine.pause()

        speech.deliverCompletionOf(0)

        assertFalse(engine.state.value.isPlaying)
        assertEquals(0, engine.state.value.currentParagraphIndex)
        assertEquals(listOf("First paragraph."), speech.spoken)
    }

    @Test
    fun aLateCompletionCannotSkipAParagraphAfterNextWasPressed() {
        val engine = engine()
        engine.play()
        engine.nextParagraph()

        speech.deliverCompletionOf(0)

        assertEquals(1, engine.state.value.currentParagraphIndex)
        assertEquals(listOf("First paragraph.", "Second paragraph."), speech.spoken)

        // The paragraph that is actually playing still advances normally.
        speech.completeUtterance()

        assertEquals(2, engine.state.value.currentParagraphIndex)
    }

    @Test
    fun aLateCompletionAfterTheDocumentIsReplacedIsIgnored() {
        val engine = engine()
        engine.play()
        engine.setDocument(
            ReaderDocument.fromChunkTexts(
                pageId = "other",
                title = "Other",
                texts = listOf("Only paragraph.")
            )
        )

        speech.deliverCompletionOf(0)

        assertEquals("Only paragraph.", engine.state.value.currentParagraph)
        assertEquals(1, speech.utterances.size)
    }

    @Test
    fun pauseStopsAutomaticProgression() {
        val engine = engine()
        engine.play()
        speech.completeUtterance()

        engine.pause()
        speech.completeUtterance()

        assertFalse(engine.state.value.isPlaying)
        assertEquals(1, engine.state.value.currentParagraphIndex)
    }

    @Test
    fun resumeAfterAutomaticStopContinuesFromTheLastParagraph() {
        val engine = engine()
        engine.play()
        speech.completeUtterance()
        speech.completeUtterance()
        speech.completeUtterance()

        engine.play()

        assertTrue(engine.state.value.isPlaying)
        assertEquals(2, engine.state.value.currentParagraphIndex)
        assertEquals("Third paragraph.", speech.utterances.last().text)
    }

    @Test
    fun shutdownStopsAndReleasesSpeech() {
        val engine = engine()
        engine.play()

        engine.shutdown()

        assertEquals(1, speech.stopCount)
        assertEquals(1, speech.shutdownCount)
    }
}
