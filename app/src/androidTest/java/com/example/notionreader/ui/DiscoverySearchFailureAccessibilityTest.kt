package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.example.notionreader.application.*
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.player.ReaderEngineImpl
import com.example.notionreader.player.SpeechEngine
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.assertIsReadable
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic private test files only; never clears or changes the installed library. */
class DiscoverySearchFailureAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val directory = File.createTempFile("discovery-search-audit", "test").apply { delete(); mkdirs() }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun failedSearchShowsAnErrorWithoutOldMatchesAndBackgroundWorkCannotHideIt() {
        val doc = ReaderDocument.fromChunkTexts("audit-note", "Forest audit fixture", listOf("Forest ecology evidence"))
        val cache = FileDocumentCache(File(directory, "cache"))
        val index = FileSemanticIndexStore(File(directory, "index"))
        val provider = object : EmbeddingProvider {
            @Volatile var fail = false
            override val identity = EmbeddingIdentity("search-audit", 2)
            override val tokenizer = object : SemanticTokenizer {
                override fun tokens(text: String) = Regex("\\S+").findAll(text).map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
            }
            override suspend fun embed(text: String): List<Float> { check(!fail) { "Injected inference failure" }; return listOf(1f, 0f) }
        }
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(File(directory, "interests")), provider)
        val speech = object : SpeechEngine {
            override fun speak(text: String, onComplete: (() -> Unit)?) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val app = ReaderApp(ReaderSession(ReaderEngineImpl(speech, doc)), NoteViewer(doc),
            ReaderNavigator(ReaderNavigation(top = ReaderDestination.Discover)), DefaultLoadReaderDocument { doc }, discovery = controller)
        runBlocking {
            cache.put(CachedDocument(doc, null, Instant.EPOCH))
            index.setPaused(false); controller.reconcile(emptyList(), true)
            controller.search("first question")
        }
        val sessionBefore = app.session.state.value
        compose.setContent { DiscoveryContent(app) {} }
        compose.onNodeWithText("Forest audit fixture").assertIsReadable()
        provider.fail = true
        compose.onNodeWithText("Search your cached Notes").performTextReplacement("different question")
        compose.onNodeWithContentDescription("Search cached Notes").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { !controller.state.value.searching && controller.state.value.searchFailure != null }
        compose.onNodeWithText("Forest audit fixture").assertDoesNotExist()
        compose.onNodeWithText("Semantic search failed. Try again or rebuild the index.").assertIsReadable()
        compose.onNodeWithText("No matching cached Notes.").assertDoesNotExist()
        runBlocking { controller.reconcile(emptyList(), false) }
        compose.onNodeWithText("Semantic search failed. Try again or rebuild the index.").assertIsReadable()
        compose.onNodeWithText("No matching cached Notes.").assertDoesNotExist()
        assertEquals("Searching never changes the listening session", sessionBefore, app.session.state.value)
        runBlocking { controller.clearIndex() }
        compose.onNodeWithText("Semantic search failed. Try again or rebuild the index.").assertDoesNotExist()
        compose.onNodeWithText("No matching cached Notes.").assertDoesNotExist()
    }
}
