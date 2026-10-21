package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewerTest {

    private val noteA = ReaderDocument.fromChunkTexts(
        pageId = "note_A",
        title = "Note A Title",
        texts = listOf("Chunk 1 of Note A")
    )

    private val failingLoader: LoadReaderDocument = DefaultLoadReaderDocument {
        throw IOException("Network unreachable")
    }

    @Test
    fun loadFailureForDifferentPageDoesNotRetainPreviousDocument() = runTest {
        val viewer = NoteViewer(initial = noteA)
        assertEquals("note_A", viewer.document.value.pageId)

        val success = viewer.load("note_B", failingLoader)

        assertFalse("Load must return false on error", success)
        assertTrue("Load state must be Error", viewer.loadState.value is DocumentLoadState.Error)

        // Failing-first assertion: previous document must NOT be retained
        assertNotEquals(
            "Viewer must not retain previous document when loading a different page fails",
            "note_A",
            viewer.document.value.pageId
        )
        assertEquals(
            "Document must be reset to Empty when loading an unrelated page fails",
            ReaderDocument.Empty.copy(pageId = "note_B"),
            viewer.document.value
        )
    }

    @Test
    fun loadFailureForSamePageRetainsExistingDocumentForOfflineReadability() = runTest {
        val viewer = NoteViewer(initial = noteA)
        assertEquals("note_A", viewer.document.value.pageId)

        val success = viewer.load("note_A", failingLoader)

        assertFalse(success)
        assertTrue(viewer.loadState.value is DocumentLoadState.Error)
        // Preserves deliberate contract: refresh failure of current note keeps offline content
        assertEquals("note_A", viewer.document.value.pageId)
        assertEquals("Note A Title", viewer.document.value.title)
    }
}
