package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.notionreader.application.*
import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
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
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class DiscoveryTopicsNavigationAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val directory = File.createTempFile("topic-navigation", "test").apply { delete(); mkdirs() }
    @After fun cleanup() { directory.deleteRecursively() }

    private class FixedProvider : EmbeddingProvider {
        override val identity = EmbeddingIdentity("topic-navigation", 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text)
                .map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
        }
        override suspend fun embed(text: String) = listOf(1f, 0f)
    }
    private class MemoryTopics(var snapshot: TopicSnapshot) : TopicStore {
        override suspend fun read() = snapshot
        override suspend fun write(snapshot: TopicSnapshot): Boolean { this.snapshot = snapshot; return true }
        override suspend fun clear() { snapshot = TopicSnapshot() }
        override suspend fun bytes() = 0L
    }
    private data class Fixture(val app: ReaderApp, val cache: FileDocumentCache, val entries: List<ReaderIndexEntry>)

    private fun fixture(emptyInitial: Boolean = false): Fixture = runBlocking {
        val provider = FixedProvider()
        val documents = listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma").map { (id, title) ->
            ReaderDocument.fromChunkTexts(id, title, listOf("Forest water evidence for $title"))
        }
        val cache = FileDocumentCache(directory.resolve("cache"))
        val index = FileSemanticIndexStore(directory.resolve("index"))
        val members = documents.mapIndexed { order, document ->
            cache.put(CachedDocument(document, null, Instant.EPOCH))
            val passage = SemanticPassage("p-${document.pageId}", document.pageId, "section", "Ecology",
                "block-${document.pageId}", "chunk-${document.pageId}", 0, "Forest water evidence for ${document.title}".length,
                "Forest water evidence for ${document.title}", "Forest ecology", order)
            val metadata = SemanticMetadata(document.title)
            index.write(SemanticRecord(document.pageId, semanticRevision(document), provider.identity,
                metadata, listOf(EmbeddedPassage(passage, listOf(1f, 0f)))))
            TopicMembership(document.pageId, metadata, passage, .9f, passage, semanticRevision(document))
        }
        val topic = DiscoveredTopic("forest", "Forest ecology", members.first().passage,
            EmbeddedPassage(members.first().passage, listOf(1f, 0f)), members, 1)
        val store = MemoryTopics(TopicSnapshot(topics = if (emptyInitial) emptyList() else listOf(topic), identity = provider.identity,
            generation = 1, complete = !emptyInitial, connections = if (emptyInitial) emptyList() else listOf(NoteConnection("pair-ab", members[0], members[1], .9f))))
        val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(directory.resolve("interests")),
            provider, topicStore = store)
        val entries = documents.map { ReaderIndexEntry(it.pageId, it.title) }
        controller.reconcile(entries, false)
        val speech = object : SpeechEngine {
            override fun speak(text: String, onComplete: (() -> Unit)?) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val app = ReaderApp(ReaderSession(ReaderEngineImpl(speech, documents.first())), NoteViewer(documents.first()),
            ReaderNavigator(ReaderNavigation(top = ReaderDestination.Discover)), DefaultLoadReaderDocument { id -> documents.first { it.pageId == id } }, discovery = controller)
        app.loadBrowseIndex { entries }
        Fixture(app, cache, entries)
    }

    private fun show(app: ReaderApp) {
        compose.setContent {
            ReaderShell(app, null, { runBlocking { app.openNote(it) } }, onRetryNoteLoad = {},
                onRetryBrowseIndex = {}, onContinueSession = {}, onReplayCompleted = {}, onCreateSavedQueue = {})
        }
    }
    private fun openTopic() {
        compose.onNodeWithText("Forest ecology").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test fun interestCardsKeepTheirMatchingNotesOutOfTheMainFeed() {
        val (app, _, _) = fixture()
        runBlocking { app.discovery!!.saveInterest(null, "forest"); app.discovery!!.refreshRecommendations() }
        show(app)
        compose.onNodeWithText("Related knowledge").assertDoesNotExist()
        compose.onNodeWithText("View your interests").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("forest").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.discovery!!.state.value.interestMatches.size == 3 }
        compose.onNodeWithText("Matching Notes").performScrollTo().assertIsReadable()
    }

    @Test fun selectedInterestListeningUsesItsEditedPreviewAndPlayReturnsFocusCorrectly() {
        val (app, _, _) = fixture()
        runBlocking { app.discovery!!.saveInterest(null, "forest") }
        val interest = app.discovery!!.state.value.interests.single()
        runBlocking { app.discovery!!.updateFollow(interest.id, "Woodland listening", "forest") }
        val before = app.session.queueState.value
        show(app)
        compose.onNodeWithText("View your interests").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Woodland listening").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.discovery!!.state.value.interestMatches.size == 3 }
        compose.onNodeWithText("Preview listening").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Remove Alpha").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(before, app.session.queueState.value)
        compose.onNodeWithText("Play").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { runBlocking { app.loadPendingQueueNote() } }
        assertEquals(listOf("b", "c"), app.session.queueState.value.queue!!.items.map { it.pageId })
        assertEquals(com.example.notionreader.domain.queue.QueueOrigin.Discovery("Interest: Woodland listening"), app.session.queueState.value.queue!!.origin)
        org.junit.Assert.assertTrue(app.session.state.value.isPlaying)
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test fun anEmptyInspectedSnapshotOffersTheCompletedDiscoveriesInsteadOfLookingForever() {
        val (app, _, entries) = fixture(emptyInitial = true)
        show(app)
        compose.runOnIdle { runBlocking {
            app.discovery!!.resume()
            app.discovery!!.reconcile(entries, true)
        } }
        compose.waitUntil(5_000) { app.discovery!!.state.value.topicsUpdated }
        compose.onNodeWithText("Refresh").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("3 available Notes").performScrollTo().assertExists()
    }

    @Test fun followingSavesAnIndependentDefinitionAndOpensItsExistingInterest() {
        val (app, _, _) = fixture()
        val before = app.session.state.value
        show(app); openTopic()
        compose.onNodeWithText("Follow topic").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Interest name").performTextReplacement("My forests")
        compose.onNodeWithText("Matching text").performTextReplacement("woodland water cycles")
        compose.onNodeWithText("Save interest").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.discovery!!.state.value.interests.size == 1 }
        val saved = app.discovery!!.state.value.interests.single()
        assertNotEquals("forest", saved.id)
        assertEquals("forest", saved.originTopicId)
        assertEquals("woodland water cycles", saved.text)
        compose.onNodeWithText("View followed interest").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("My forests").assertIsReadable()
        compose.onNodeWithText("woodland water cycles").assertIsReadable()
        assertEquals(saved.id, app.navigator.state.value.currentSubjectId)
        assertEquals(before, app.session.state.value)
    }

    @Test fun openingAMemberAndReturningRestoresItsControlAndOriginatingTopic() {
        val (app, _, _) = fixture()
        show(app); openTopic()
        compose.onNodeWithText("Open Beta").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(ReaderDestination.Note, app.navigator.state.value.current)
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("Open Beta").assertIsFocused()
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("Forest ecology").assertIsFocused()
    }

    @Test fun preparingAndEditingTopicListeningLeavesTheSessionAlone() {
        val (app, _, _) = fixture()
        val before = app.session.state.value
        val queueBefore = app.session.queueState.value
        show(app); openTopic()
        compose.onNodeWithText("Preview listening").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(ReaderDestination.GuidedListening, app.navigator.state.value.current)
        compose.onNodeWithText("Remove Alpha").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Move Beta down").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("c", "b"), app.discovery!!.state.value.preview.map { it.pageId })
        assertEquals(before, app.session.state.value)
        assertEquals(queueBefore, app.session.queueState.value)
    }

    @Test fun sourceEvictionWithdrawsInspectedEvidenceWithoutDeletingTheFollow() {
        val (app, cache, entries) = fixture()
        runBlocking { app.discovery!!.followTopic("forest", "Saved forests", "forest water") }
        show(app); openTopic()
        compose.runOnIdle { runBlocking { cache.remove("b"); app.discovery!!.reconcile(entries, false) } }
        compose.onNodeWithText("Topic currently unavailable").assertIsReadable()
        compose.onNodeWithText("Forest water evidence for Beta").assertDoesNotExist()
        assertEquals("forest water", app.discovery!!.state.value.interests.single().text)
        assertEquals(ReaderDestination.DiscoveryTopic, app.navigator.state.value.current)
    }
    @Test fun topicEvidenceStartsCollapsedAndReturnsToItsSourceControl() {
        val (app, _, _) = fixture()
        val before = app.session.state.value
        show(app); openTopic()
        compose.onNodeWithText("Open label source Note").assertDoesNotExist()
        compose.onNodeWithText("Why these Notes?").performScrollTo()
            .assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Open label source Note").performScrollTo()
            .assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("Open label source Note").assertIsFocused()
        assertEquals(before, app.session.state.value)
    }

    @Test fun aConnectionShowsBothSourcesAndPreparesItsDisplayedOrderWithoutPlayback() {
        val (app, _, _) = fixture()
        val before = app.session.state.value
        show(app)
        compose.onNodeWithText("Forest water evidence for Beta").assertDoesNotExist()
        compose.onNodeWithText("Compare passages").performScrollTo()
            .assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Forest water evidence for Alpha").assertIsReadable()
        compose.onNodeWithText("Forest water evidence for Beta").assertIsReadable()
        compose.onNodeWithText("Open Alpha").assertIsReachable()
        compose.onNodeWithText("Open Beta").assertIsReachable()
        compose.onNodeWithText("Preview listening").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("a", "b"), app.discovery!!.state.value.preview.map { it.pageId })
        assertEquals(before, app.session.state.value)
    }

    @Test fun completedBackgroundTopicsRequireExplicitRefreshToChangeTheInspectedCount() {
        val (app, cache, entries) = fixture()
        show(app)
        compose.onNodeWithText("3 available Notes").performScrollTo().assertExists()
        compose.runOnIdle { runBlocking {
            val document = ReaderDocument.fromChunkTexts("d", "Delta", listOf("Forest ecology water evidence"))
            cache.put(CachedDocument(document, null, Instant.EPOCH))
            app.discovery!!.resume()
            app.discovery!!.reconcile(entries + ReaderIndexEntry("d", "Delta"), true)
        } }
        compose.waitUntil(5_000) { app.discovery!!.state.value.topicsUpdated }
        compose.onNodeWithText("3 available Notes").assertExists()
        compose.onNodeWithText("Refresh").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("4 available Notes").performScrollTo().assertExists()
    }

}
