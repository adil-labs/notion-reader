package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsFocused
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.application.*
import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.player.ReaderEngineImpl
import com.example.notionreader.player.SpeechEngine
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiscoveryNavigationAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val directory = File.createTempFile("discovery-navigation", "test").apply { delete(); mkdirs() }
    @After fun cleanup() { directory.deleteRecursively() }

    private class FixedProvider : EmbeddingProvider {
        override val identity = EmbeddingIdentity("navigation-fixture", 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text)
                .map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
        }
        override suspend fun embed(text: String): List<Float> = listOf(1f, 0f)
    }

    private class DelayedCache(private val delegate: DocumentCache) : DocumentCache by delegate {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var entered = false
        override suspend fun snapshot(): List<CachedDocument> {
            gate?.let { entered = true; it.await() }
            return delegate.snapshot()
        }
    }

    private fun fixture(): Pair<ReaderApp, DelayedCache> = runBlocking {
        val provider = FixedProvider()
        val cache = DelayedCache(FileDocumentCache(directory.resolve("cache")))
        val index = FileSemanticIndexStore(directory.resolve("index"))
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(directory.resolve("interests")), provider)
        fun document(id: String) = ReaderDocument.fromChunkTexts(id, if (id == "a") "Alpha" else "Beta", listOf("Evidence for $id"))
        listOf("a", "b").forEach { cache.put(CachedDocument(document(it), null, Instant.EPOCH)) }
        index.setPaused(false)
        val entries = listOf(ReaderIndexEntry("a", "Alpha"), ReaderIndexEntry("b", "Beta"))
        controller.reconcile(entries, true)
        controller.search("concept")
        val speech = object : SpeechEngine {
            override fun speak(text: String, onComplete: (() -> Unit)?) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val app = ReaderApp(ReaderSession(ReaderEngineImpl(speech, document("a"))), NoteViewer(document("a")),
            ReaderNavigator(ReaderNavigation(top = ReaderDestination.Discover)), DefaultLoadReaderDocument { document(it) }, discovery = controller)
        app.loadBrowseIndex { entries }
        app to cache
    }

    private fun show(app: ReaderApp) {
        compose.setContent {
            ReaderShell(app, null, { runBlocking { app.openNote(it) } },
                onRetryNoteLoad = {}, onRetryBrowseIndex = {}, onContinueSession = {},
                onReplayCompleted = {}, onCreateSavedQueue = {})
        }
    }

    @Test fun exploreLivesInBrowseAndBackRestoresItsControl() {
        val (app, _) = fixture()
        show(app)
        compose.onNodeWithText("Explore").assertDoesNotExist()
        compose.onNodeWithText("Browse").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Explore").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(ReaderDestination.ExploreCatalog, app.navigator.state.value.current)
        compose.runOnIdle { app.back() }
        assertEquals(ReaderDestination.Browse, app.navigator.state.value.current)
        compose.onNodeWithText("Explore").assertIsReachable().assertIsFocused()
    }

    @Test fun relatedLoadingSurvivesDisposalOfTheDiscoverSurfaceThatOpenedIt() {
        val (app, cache) = fixture()
        val gate = CompletableDeferred<Unit>()
        cache.gate = gate
        show(app)
        compose.onNodeWithText("Notes related to Alpha").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { cache.entered }
        compose.waitForIdle()
        assertEquals(ReaderDestination.RelatedNotes, app.navigator.state.value.current)
        gate.complete(Unit)
        compose.waitUntil(5_000) { app.discovery!!.state.value.related.any { it.pageId == "b" } }
        compose.onNodeWithText("Open Beta").assertExists()
    }

    @Test fun returningToAnEarlierRelatedDestinationRestoresItsSeedResults() {
        val (app, _) = fixture()
        runBlocking { app.discovery!!.relatedNotes("a") }
        app.navigator.open(ReaderDestination.RelatedNotes, subjectId = "a")
        show(app)
        compose.waitUntil(5_000) { app.discovery!!.state.value.related.any { it.pageId == "b" } }
        compose.onNodeWithText("Open Beta").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Related Notes").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.discovery!!.state.value.related.any { it.pageId == "a" } }
        compose.onNodeWithText("Open Alpha").assertExists()
        compose.runOnIdle { app.back(); app.back() }
        compose.waitUntil(5_000) { app.discovery!!.state.value.related.map { it.pageId } == listOf("b") }
        assertEquals("a", app.navigator.state.value.currentSubjectId)
        compose.onNodeWithText("Open Beta").assertExists()
        compose.onNodeWithText("Open Alpha").assertDoesNotExist()
    }
}
