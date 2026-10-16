package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.player.FakeReaderEngine
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPlaybackIsolationTest {
    @Test fun discoveryNavigationQueriesAndPreviewEditsLeavePlayingSessionUntouched() = runBlocking {
        val dir = Files.createTempDirectory("discovery-playback").toFile()
        try {
            val document = ReaderDocument.fromChunkTexts("playing", "Playing", listOf("One", "Two", "Three"))
            val engine = FakeReaderEngine(document)
            val provider = object : EmbeddingProvider {
                override val identity = EmbeddingIdentity("test", 2)
                override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
                override suspend fun embed(text: String) = listOf(1f, 0f)
            }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")), FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            val app = ReaderApp(ReaderSession(engine), NoteViewer(document), ReaderNavigator(), DefaultLoadReaderDocument { document.copy(pageId = it) }, discovery = controller)
            app.playNote(); engine.nextParagraph()
            val snapshot = app.session.queueState.value
            val calls = engine.callCount
            controller.search("concept"); app.openRelatedNotes("a")
            app.prepareDiscoveryPreview(listOf(match("a"), match("b")), "Topic: Ecology")
            controller.movePreview("a", 1); controller.removePreview("a")
            app.addDiscoveryPreviewToQueue()
            controller.clearIndex()
            assertEquals(calls, engine.callCount)
            assertEquals(snapshot, app.session.queueState.value)
            assertTrue(engine.state.value.isPlaying)
            assertEquals(1, engine.state.value.currentParagraphIndex)
        } finally { dir.deleteRecursively() }
    }

    @Test fun explicitPreviewPlayFreezesVisibleOrderIndependentOfLaterPreviewEdits() = runBlocking {
        val dir = Files.createTempDirectory("discovery-snapshot").toFile()
        try {
            val document = ReaderDocument.fromChunkTexts("playing", "Playing", listOf("One"))
            val provider = object : EmbeddingProvider {
                override val identity = EmbeddingIdentity("test", 2)
                override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
                override suspend fun embed(text: String) = listOf(1f, 0f)
            }
            val controller = DiscoveryController(FileDocumentCache(dir.resolve("cache")), FileSemanticIndexStore(dir.resolve("index")), FileDiscoveryInterestStore(dir.resolve("interests")), provider)
            val app = ReaderApp(ReaderSession(FakeReaderEngine(document)), NoteViewer(document), ReaderNavigator(), DefaultLoadReaderDocument { document }, discovery = controller)
            app.prepareDiscoveryPreview(listOf(match("a"), match("b")), "Topic: Ecology")
            controller.movePreview("b", -1); app.playDiscoveryPreview()
            controller.removePreview("b")
            assertEquals(listOf("b", "a"), app.session.queueState.value.queue!!.items.map { it.pageId })
            assertEquals(QueueOrigin.Discovery("Topic: Ecology"), app.session.queueState.value.queue!!.origin)
        } finally { dir.deleteRecursively() }
    }

    private fun match(id: String) = SemanticMatch(id, SemanticMetadata(id), SemanticPassage(id, id, "s", null, "b", "c", 0, 4, "text", "text", 0), 1f)
}
