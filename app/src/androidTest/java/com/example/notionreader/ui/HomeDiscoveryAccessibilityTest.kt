package com.example.notionreader.ui

import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.notionreader.application.*
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.player.*
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.assertIsReadable
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class HomeDiscoveryAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("home-discovery-ui").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private fun fixture(): ReaderApp = runBlocking {
        val cache = FileDocumentCache(root.resolve("cache"))
        val docs = listOf("a", "b", "c").map { ReaderDocument.fromChunkTexts(it, "Note $it", listOf("Forest ecology")) }
        docs.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
        val provider = object : EmbeddingProvider {
            override val identity = EmbeddingIdentity("ui", 2)
            override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = Regex("\\S+").findAll(text)
                .map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList() }
            override suspend fun embed(text: String) = listOf(1f, 0f)
        }
        val index = FileSemanticIndexStore(root.resolve("index"))
        index.setPaused(false)
        val discovery = DiscoveryController(cache, index,
            FileDiscoveryInterestStore(root.resolve("interests")), provider)
        discovery.reconcile(emptyList(), true)
        discovery.saveInterest(null, "Forest interest")
        val speech = object : SpeechEngine {
            override fun speak(text: String, onComplete: (() -> Unit)?) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val recent = RecentNotesController(FileRecentNoteHistoryStore(root.resolve("recent")), cache)
        val app = ReaderApp(ReaderSession(ReaderEngineImpl(speech, docs.first())), NoteViewer(docs.first()), ReaderNavigator(),
            DefaultLoadReaderDocument { id: String -> docs.first { it.pageId == id } }, discovery = discovery, recentNotes = recent, documentCache = cache)
        app
    }
    private fun show(app: ReaderApp) {
        compose.setContent { ReaderShell(app, null, { runBlocking { app.openNote(it) } },
            onRetryNoteLoad = {}, onRetryBrowseIndex = {}, onContinueSession = { runBlocking { app.continueSession(it) } },
            onReplayCompleted = { runBlocking { app.replayCompleted(it) } }, onCreateSavedQueue = {}) }
    }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (text !in listOf("Home", "Discover", "Browse", "Queues", "Settings")) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test fun homeIsDefaultAndDiscoverHasNoListeningHistoryOrExpandedInterests() {
        val app = fixture()
        compose.runOnUiThread { app.playNote(); app.togglePlayPause(); app.openHome() }
        show(app)
        compose.onNodeWithText("Continue listening").assertIsReadable()
        compose.onNodeWithText("Recent listening").assertIsReadable()
        click("Discover")
        compose.onNodeWithText("Continue listening").assertDoesNotExist()
        compose.onNodeWithText("Recent listening").assertDoesNotExist()
        compose.onNodeWithText("Recently opened Notes").assertDoesNotExist()
        compose.onNodeWithText("Forest interest").assertDoesNotExist()
        compose.onNodeWithText("View your interests").performScrollTo().assertIsReachable()
        assertEquals(ReaderDestination.Discover, app.navigator.state.value.top)
    }
    @Test fun bothInterestEntryPointsReturnFocusToTheirOwnSource() {
        val app = fixture(); show(app)
        click("View all interests")
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("View all interests").assertIsFocused()
        click("Discover"); click("View your interests")
        click("Forest interest")
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("Forest interest").assertIsFocused()
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("View your interests").assertIsFocused()
    }
    @Test fun recentNoteOpensManualReadingAndBackRestoresItsCard() {
        val app = fixture()
        runBlocking { app.openNote("b"); app.back() }
        show(app)
        val before = app.session.queueState.value
        click("Note b")
        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        assertEquals(before, app.session.queueState.value)
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("Note b").assertIsFocused()
    }
    @Test fun anEvictedRecentNoteReturnsFocusToTheHomeHeading() {
        val app = fixture()
        runBlocking { app.openNote("b"); app.back() }; show(app)
        click("Note b")
        compose.runOnIdle { runBlocking { app.clearCache(); app.recentNotes!!.refresh() }; app.back() }
        compose.onNodeWithText("Note b").assertDoesNotExist()
        compose.onNode(hasText("Home") and !hasClickAction()).assertIsFocused()
    }
    @Test fun interestListeningFromHomeRequiresExplicitPlayAndKeepsItsFrozenQueue() {
        val app = fixture(); show(app)
        val before = app.session.queueState.value
        click("Forest interest")
        compose.waitUntil(5_000) { app.discovery!!.state.value.interestMatch.matches.size == 3 }
        click("Preview listening")
        assertEquals(before, app.session.queueState.value)
        click("Play")
        compose.runOnIdle { runBlocking { app.loadPendingQueueNote() } }
        assertEquals(3, app.session.queueState.value.queue!!.items.size)
        assertTrue(app.session.state.value.isPlaying)
        val frozen = app.session.queueState.value.queue
        compose.runOnIdle { app.back(); app.openHome() }
        listOf("Discover", "Browse", "Queues", "Settings", "Home").forEach { tab ->
            click(tab)
            assertEquals(frozen, app.session.queueState.value.queue)
            assertTrue(app.session.state.value.isPlaying)
        }
    }
    @Test fun unavailableModelStillOffersTheSharedInterestCollection() {
        compose.setContent { DiscoveryScreen("", {}, 0, 0, emptyList(), emptyList(), {}, {}, {}, { _, _ -> }, {}, {},
            semanticAvailable = false, onViewInterests = {}, onManageDiscovery = {}) }
        compose.onNodeWithText("View your interests").performScrollTo().assertIsReachable()
        compose.onNodeWithText("Search your cached Notes").assertDoesNotExist()
    }
    @Test fun recentNotesCanBeClearedWithoutAnyListeningSessionsAndFailuresStayVisible() {
        compose.setContent { com.example.notionreader.ui.settings.SettingsPrivacyScreen(0, 0,
            onClearHistory = {}, onClearCache = {}, onClearAllData = {}, onBack = {}, recentNotesCount = 1,
            historyMessage = "Recently opened Notes could not be cleared from storage. Retry clearing history.") }
        compose.onNodeWithText("Clear listening history").performScrollTo().assertIsEnabled().assertIsReachable()
        compose.onNodeWithText("Recently opened Notes could not be cleared from storage. Retry clearing history.")
            .performScrollTo().assertIsReadable()
    }
    @Test fun sharedCollectionAddsTextAndCachedSeedInterestsWithoutStartingPlayback() {
        val app = fixture(); show(app)
        val before = app.session.queueState.value
        click("View all interests"); click("Add interest")
        compose.onNodeWithText("Describe a topic").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("New interest").performTextReplacement("Astronomy")
        compose.onNodeWithText("Save interest").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.discovery!!.state.value.interests.size == 2 }
        compose.onNodeWithText("Astronomy").performScrollTo().assertIsReachable()
        click("Add interest")
        compose.onNodeWithText("Choose a Note").performSemanticsAction(SemanticsActions.OnClick)
        click("Select Note a")
        compose.waitUntil(5_000) { app.discovery!!.state.value.interests.size == 3 }
        assertEquals(ReaderDestination.Interests, app.navigator.state.value.current)
        compose.onNodeWithText("Add interest").assertIsFocused()
        assertEquals(before, app.session.queueState.value)
    }

    @Test
    fun interestsContentCardsDeclareCollectionItemInfoMatchingRowIndices() {
        val app = fixture()
        compose.setContent {
            InterestsContent(app = app, preview = false)
        }

        val state = app.discovery!!.state.value
        val interests = state.interests
        assertTrue("Fixture must contain interests", interests.isNotEmpty())

        interests.forEachIndexed { index, interest ->
            val expectedTitle = interest.displayName ?: interest.text
            compose.onNode(hasText(expectedTitle, substring = true) and hasClickAction())
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.CollectionItemInfo,
                        CollectionItemInfo(
                            rowIndex = index,
                            rowSpan = 1,
                            columnIndex = 0,
                            columnSpan = 1
                        )
                    )
                )
        }
    }
}
