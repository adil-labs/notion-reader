package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.player.FakeReaderEngine
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.example.notionreader.ReaderHost
import com.example.notionreader.player.FakeSpeechEngine
import com.example.notionreader.player.ReaderEngineImpl
import org.junit.Assert.*
import org.junit.Test

class InterestListeningTest {
    @Test fun discoveryKeepsTheCachedNotionIdentityWhenOpeningAndPlayingCanonicalMatches() = runBlocking {
        val root = Files.createTempDirectory("interest-notion-identity").toFile()
        try {
            val pageId = "3dec9345-e10a-81ab-aa68-cf0b2ee17e45"
            val document = ReaderDocument.fromChunkTexts(pageId, "Forest", listOf("Forest ecology"))
            val cache = FileDocumentCache(root.resolve("cache"))
            cache.put(CachedDocument(document, null, Instant.EPOCH))
            val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")), FileDiscoveryInterestStore(root.resolve("interests")), Provider())
            controller.resume(); controller.reconcile(listOf(ReaderIndexEntry(pageId, document.title)), true)
            controller.saveInterest(null, "forest")
            controller.loadInterestMatches(controller.state.value.interests.single().id)
            val matches = controller.state.value.interestMatches
            assertEquals(pageId.replace("-", ""), matches.single().pageId)
            val requested = mutableListOf<String>()
            val engine = FakeReaderEngine(document)
            val app = ReaderApp(ReaderSession(engine), NoteViewer(document), ReaderNavigator(),
                DefaultLoadReaderDocument { id -> requested += id; check(id == pageId); document }, discovery = controller)
            app.prepareDiscoveryPreview(matches, "Interest: forest")
            app.playDiscoveryPreview()
            assertEquals(pageId, app.session.queueState.value.queue!!.items.single().pageId)
            assertTrue(app.loadPendingQueueNote())
            assertTrue(engine.state.value.isPlaying)
            app.openNote(matches.single().pageId)
            assertEquals(listOf(pageId, pageId), requested)
            assertEquals(pageId, app.noteViewer.document.value.pageId)
            app.back()
            assertEquals(matches.single().pageId, app.navigator.state.value.focusTarget)
        } finally { root.deleteRecursively() }
    }
    @Test fun matchingAgainAfterIndexingFindsNotesThatWereNotReadyForTheFirstQuery() = runBlocking {
        val root = Files.createTempDirectory("interest-index-ready").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache"))
            val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")), FileDiscoveryInterestStore(root.resolve("interests")), Provider())
            val document = ReaderDocument.fromChunkTexts("forest", "Forest", listOf("Forest ecology"))
            cache.put(CachedDocument(document, null, Instant.EPOCH))
            controller.saveInterest(null, "forest")
            val id = controller.state.value.interests.single().id
            controller.loadInterestMatches(id)
            assertTrue(controller.state.value.interestMatches.isEmpty())
            controller.resume(); controller.reconcile(listOf(ReaderIndexEntry("forest", "Forest")), true)
            controller.loadInterestMatches(id)
            assertEquals(listOf("forest"), controller.state.value.interestMatches.map { it.pageId })
        } finally { root.deleteRecursively() }
    }
    @Test fun selectedInterestPlaybackAdvancesThroughItsFrozenNotesWhenTheInterestChanges() = runBlocking {
        val root = Files.createTempDirectory("interest-host").toFile()
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cache = FileDocumentCache(root.resolve("cache"))
            val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")), FileDiscoveryInterestStore(root.resolve("interests")), Provider())
            val documents = listOf("forest1", "forest2", "chess").map { ReaderDocument.fromChunkTexts(it, it, listOf("$it passage")) }
            documents.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
            controller.resume(); controller.reconcile(documents.map { ReaderIndexEntry(it.pageId, it.title) }, true)
            controller.saveInterest(null, "forest")
            val interest = controller.state.value.interests.single()
            controller.loadInterestMatches(interest.id)
            val speech = FakeSpeechEngine()
            val loaded = mutableListOf<String>()
            val app = ReaderApp(ReaderSession(ReaderEngineImpl(speech, documents.first())), NoteViewer(documents.first()),
                ReaderNavigator(), DefaultLoadReaderDocument { id -> loaded += id; documents.first { it.pageId == id } }, discovery = controller)
            val host = ReaderHost(app, processScope)
            host.start()
            app.prepareDiscoveryPreview(controller.state.value.interestMatches, "Interest: forest")
            app.playDiscoveryPreview()
            assertTrue(app.session.state.value.isPlaying)
            controller.updateFollow(interest.id, "Chess", "chess")
            repeat(20) { if (!app.session.queueState.value.completed) speech.completeUtterance() }
            assertEquals(listOf("forest1", "forest2"), loaded)
            assertTrue(app.session.queueState.value.completed)
            assertEquals(QueueOrigin.Discovery("Interest: forest"), app.session.queueState.value.queue!!.origin)
            assertEquals(listOf("forest1", "forest2"), app.session.queueState.value.queue!!.items.map { it.pageId })
        } finally { processScope.cancel(); root.deleteRecursively() }
    }
    private class Provider : EmbeddingProvider {
        var duringQuery: (suspend () -> Unit)? = null
        override val identity = EmbeddingIdentity("interest-listening", 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text).map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
        }
        override suspend fun embed(text: String): List<Float> {
            duringQuery?.invoke()
            return if (text.contains("chess", true)) listOf(0f, 1f) else listOf(1f, 0f)
        }
    }
    @Test fun selectingAnInterestReturnsItsOwnMatchesAndExplicitPlayFreezesTheirOrder() = runBlocking {
        val root = Files.createTempDirectory("interest-listening").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache"))
            val provider = Provider()
            val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")), FileDiscoveryInterestStore(root.resolve("interests")), provider)
            val documents = (1..6).map { ReaderDocument.fromChunkTexts("forest$it", "Forest $it", listOf("Forest water ecology")) } +
                ReaderDocument.fromChunkTexts("chess", "Chess", listOf("Chess calculation"))
            documents.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
            controller.resume(); controller.reconcile(documents.map { ReaderIndexEntry(it.pageId, it.title) }, true)
            controller.saveInterest(null, "forest"); controller.saveInterest(null, "chess")
            val forest = controller.state.value.interests.first { it.text == "forest" }
            val engine = FakeReaderEngine(documents.last())
            val app = ReaderApp(ReaderSession(engine), NoteViewer(documents.last()), ReaderNavigator(),
                DefaultLoadReaderDocument { id -> documents.first { it.pageId == id } }, discovery = controller)
            app.playNote()
            val before = app.session.queueState.value
            val calls = engine.callCount
            controller.loadInterestMatches(forest.id)
            assertEquals((1..6).map { "forest$it" }, controller.state.value.interestMatches.map { it.pageId })
            app.prepareDiscoveryPreview(controller.state.value.interestMatches, "Interest: forest")
            controller.movePreview("forest2", -1)
            assertEquals(before, app.session.queueState.value)
            assertEquals(calls, engine.callCount)
            val expected = controller.state.value.preview.map { it.pageId }
            app.playDiscoveryPreview()
            assertEquals(expected, app.session.queueState.value.queue!!.items.map { it.pageId })
            assertEquals(QueueOrigin.Discovery("Interest: forest"), app.session.queueState.value.queue!!.origin)
            assertEquals(5, expected.size)
            assertTrue(app.loadPendingQueueNote())
            assertTrue(engine.state.value.isPlaying)
            assertEquals("forest2", engine.document.value.pageId)
            controller.removePreview("forest2")
            assertEquals(expected, app.session.queueState.value.queue!!.items.map { it.pageId })
            controller.clearIndex()
            assertTrue(controller.state.value.interestMatches.isEmpty())
            assertEquals(2, controller.state.value.interests.size)
            assertEquals(expected, app.session.queueState.value.queue!!.items.map { it.pageId })
        } finally { root.deleteRecursively() }
    }

    @Test fun anEvictedSeedHasNoMatchesAndItsOldEvidenceIsWithdrawn() = runBlocking {
        val root = Files.createTempDirectory("seed-interest").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache"))
            val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")), FileDiscoveryInterestStore(root.resolve("interests")), Provider())
            val documents = listOf("seed", "other").map { ReaderDocument.fromChunkTexts(it, it, listOf("Forest ecology")) }
            documents.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
            val entries = documents.map { ReaderIndexEntry(it.pageId, it.title) }
            controller.resume(); controller.reconcile(entries, true)
            controller.saveInterest(null, "", "seed")
            val id = controller.state.value.interests.single().id
            controller.loadInterestMatches(id)
            assertEquals(listOf("other"), controller.state.value.interestMatches.map { it.pageId })
            cache.remove("seed"); controller.reconcile(entries, false); controller.loadInterestMatches(id)
            assertTrue(controller.state.value.interestMatches.isEmpty())
            assertTrue(controller.state.value.interestMessage!!.contains("recache", true))
        } finally { root.deleteRecursively() }
    }

    @Test fun editingTheInterestDuringInferenceRejectsTheOldDefinitionResults() = runBlocking {
        val root = Files.createTempDirectory("interest-race").toFile()
        try {
            val provider = Provider()
            val cache = FileDocumentCache(root.resolve("cache"))
            val controller = DiscoveryController(cache, FileSemanticIndexStore(root.resolve("index")), FileDiscoveryInterestStore(root.resolve("interests")), provider)
            val document = ReaderDocument.fromChunkTexts("forest", "Forest", listOf("Forest ecology"))
            cache.put(CachedDocument(document, null, Instant.EPOCH))
            controller.resume(); controller.reconcile(listOf(ReaderIndexEntry("forest", "Forest")), true)
            controller.saveInterest(null, "forest")
            val id = controller.state.value.interests.single().id
            provider.duringQuery = { controller.updateFollow(id, "Chess", "chess") }
            controller.loadInterestMatches(id)
            assertTrue(controller.state.value.interestMatches.isEmpty())
            provider.duringQuery = null
            controller.loadInterestMatches(id)
            assertEquals(id, controller.state.value.selectedInterestId)
            assertTrue(controller.state.value.interestMatches.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
