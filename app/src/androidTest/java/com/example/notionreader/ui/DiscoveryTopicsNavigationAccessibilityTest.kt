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

    private fun fixture(emptyInitial: Boolean = false, fallbackLabel: Boolean = false,
        namedReference: Boolean = false, mixedConnections: Boolean = false, emerging: Boolean = false,
        independentReference: Boolean = false): Fixture = runBlocking {
        val provider = FixedProvider()
        val documents = listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma").map { (id, title) ->
            ReaderDocument.fromChunkTexts(id, title, listOf(if ((namedReference || independentReference) && id == "a")
                "Open Beta. Forest water evidence explains how trees protect river habitat." else "Forest water evidence for $title"))
        }
        val cache = FileDocumentCache(directory.resolve("cache"))
        val index = FileSemanticIndexStore(directory.resolve("index"))
        val members = documents.mapIndexed { order, document ->
            cache.put(CachedDocument(document, null, Instant.EPOCH))
            val text = document.sections.flatMap { it.chunks }.single().text
            val passage = SemanticPassage("p-${document.pageId}", document.pageId, "section", "Forest ecology",
                "block-${document.pageId}", "chunk-${document.pageId}", 0, text.length,
                text, "Forest ecology", order)
            val metadata = SemanticMetadata(document.title)
            index.write(SemanticRecord(document.pageId, semanticRevision(document), provider.identity,
                metadata, listOf(EmbeddedPassage(passage, listOf(1f, 0f)))))
            TopicMembership(document.pageId, metadata, passage, .9f, passage, semanticRevision(document))
        }
        val topic = DiscoveredTopic("forest", if (fallbackLabel) "Notes related to Alpha" else "Forest ecology", members.first().passage,
            EmbeddedPassage(members.first().passage, listOf(1f, 0f)), if (emerging) members.take(1) else members, 1)
        val store = MemoryTopics(TopicSnapshot(topics = if (emptyInitial) emptyList() else listOf(topic), identity = provider.identity,
            generation = 1, complete = !emptyInitial, connections = if (emptyInitial) emptyList() else
                listOfNotNull(NoteConnection("pair-ab", members[0], members[1], .9f, independentNamedReference = independentReference),
                    if (mixedConnections) NoteConnection("pair-bc", members[1], members[2], .8f) else null)))
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

    @Test fun fallbackCandidatesRemainStoredWithoutBecomingTopicCards() {
        val (app, _, _) = fixture(fallbackLabel = true)
        show(app)
        compose.onNodeWithText("Notes related to Alpha").assertDoesNotExist()
        compose.onNodeWithText("See all topics").assertDoesNotExist()
        assertEquals("forest", app.discovery!!.state.value.topicSnapshot.topics.single().id)
    }

    @Test fun emergingSubjectsHaveASeparateCollectionAndUseTheExplicitFollowConfirmation() {
        val (app, _, _) = fixture(emerging = true)
        val before = app.session.queueState.value
        show(app)
        compose.onNodeWithText("Emerging topics").performScrollTo().assertIsReadable()
        compose.onNodeWithText("1 available Note").assertIsReachable()
        openTopic()
        compose.onNodeWithText("Emerging topic").assertIsReadable()
        compose.onNodeWithText("Follow topic").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, app.discovery!!.state.value.interests.size)
        compose.onNodeWithText("Save interest").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.discovery!!.state.value.interests.size == 1 }
        compose.runOnIdle {
            assertEquals(1, app.discovery!!.state.value.interests.size)
            assertEquals(before, app.session.queueState.value)
        }
    }

    @Test fun returningFromAllTopicsAfterSourceLossFocusesTheTopicsHeading() {
        val (app, cache, entries) = fixture()
        show(app)
        compose.onNodeWithText("See all topics").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            runBlocking {
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts("unrelated", "Other", listOf("Independent cached material.")), null, Instant.EPOCH))
                cache.remove("a"); cache.remove("b"); cache.remove("c"); app.discovery!!.reconcile(entries, false)
            }
            app.back()
        }
        compose.waitUntil(timeoutMillis = 5000) {
            compose.onAllNodes(hasText("Topics in your Notes") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Topics in your Notes").assertIsFocused()
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
        compose.onNodeWithText("Play").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
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
        compose.onNodeWithText("Why these Notes?").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onAllNodesWithText("Forest water evidence for Beta")[0].assertIsReadable()
        compose.runOnIdle { runBlocking { cache.remove("b"); app.discovery!!.reconcile(entries, false) } }
        compose.onNodeWithText("Topic currently unavailable").assertDoesNotExist()
        val surviving = app.discovery!!.state.value.topicSnapshot.topics.single()
        assertEquals(setOf("a", "c"), surviving.members.map { it.pageId }.toSet())
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

    @Test fun aNamedReferenceKeepsBothSourcesAndUsesItsOwnListeningOrigin() {
        val (app, _, _) = fixture(namedReference = true)
        val before = app.session.state.value
        val queueBefore = app.session.queueState.value
        show(app)
        compose.onNodeWithText("Named reference").performScrollTo().assertIsReadable()
        compose.onNodeWithText("Compare passages").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Named reference").assertIsReadable()
        compose.onNodeWithText("Open Beta").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { app.back() }
        compose.onNodeWithText("Open Beta").assertIsFocused()
        compose.onNodeWithText("Preview listening").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("a", "b"), app.discovery!!.state.value.preview.map { it.pageId })
        assertEquals("Named reference", app.discovery!!.state.value.previewLabel)
        assertEquals(before, app.session.state.value)
        assertEquals(queueBefore, app.session.queueState.value)
    }

    @Test fun independentReferencesExplainTheTargetExcerptAsContext() {
        val (app, _, _) = fixture(independentReference = true)
        val before = app.session.queueState.value
        show(app)
        compose.onNodeWithText("Compare passages").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Target context; not evidence of thematic similarity").performScrollTo().assertIsReadable()
        compose.onNodeWithText("These indexed passages matched by similarity.").assertDoesNotExist()
        compose.onNodeWithText("Open Beta").performScrollTo().assertIsReachable()
        assertEquals(before, app.session.queueState.value)
    }

    @Test fun allConnectionsSeparateReferenceAndSimilarityCounts() {
        val (app, _, _) = fixture(namedReference = true, mixedConnections = true)
        show(app)
        compose.onNodeWithText("See all connections").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Similar passages (1)").performScrollTo().assertIsReadable()
        compose.onNodeWithText("Named references (1)").performScrollTo().assertIsReadable()
    }

    @Test fun returningToConnectionsAfterSourceLossFocusesTheListHeading() {
        val (app, cache, entries) = fixture(namedReference = true)
        show(app)
        compose.onNodeWithText("See all connections").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Compare passages").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            runBlocking { cache.remove("a"); app.discovery!!.reconcile(entries, false) }
            app.back()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Connections between Notes") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Connections between Notes").assertIsFocused()
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
