package com.example.notionreader.application

import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileRecentNoteHistoryStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.*
import com.example.notionreader.domain.session.*
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.player.FakeReaderEngine
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecentNotesTest {
    @Test fun aRemovalArrivingDuringRefreshCannotBeAcknowledgedBeforeRecentsPersistIt() = runBlocking {
        val root = Files.createTempDirectory("recent-invalidation-race").toFile()
        try {
            val disk = FileDocumentCache(root.resolve("cache")); var removeDuringSnapshot = false
            val cache = object : DocumentCache by disk {
                override suspend fun snapshot(): List<CachedDocument> {
                    if (removeDuringSnapshot) { removeDuringSnapshot = false; disk.remove("p", CacheRemovalReason.DELETED) }
                    return disk.snapshot()
                }
            }
            val store = Store(); val recent = RecentNotesController(store, cache)
            cached(disk, "p"); recent.recordSuccessfulOpen("p", recent.generation)
            removeDuringSnapshot = true; assertTrue(recent.refresh())
            val provider = object : EmbeddingProvider {
                override val identity = EmbeddingIdentity("race", 2)
                override val tokenizer = object : SemanticTokenizer { override fun tokens(text: String) = emptyList<SemanticToken>() }
                override suspend fun embed(text: String) = listOf(1f, 0f)
            }
            val discovery = DiscoveryController(cache, com.example.notionreader.data.local.FileSemanticIndexStore(root.resolve("index")),
                com.example.notionreader.data.local.FileDiscoveryInterestStore(root.resolve("interests")), provider,
                topicStore = com.example.notionreader.data.local.FileTopicStore(root.resolve("topics")),
                invalidationAcknowledgementLimit = { recent.processedInvalidationGeneration })
            discovery.reconcile(emptyList(), false)
            assertTrue("The unprocessed removal must remain durable", disk.invalidations.value.isNotEmpty())
            store.fail = true
            assertFalse(recent.refresh()); discovery.reconcile(emptyList(), false)
            assertTrue(discovery.state.value.cachedPageIds.isEmpty())
            assertTrue(disk.invalidations.value.isNotEmpty())
            store.fail = false
            assertTrue(recent.refresh()); discovery.reconcile(emptyList(), false)
            assertTrue(disk.invalidations.value.isEmpty()); assertTrue(store.records.isEmpty())
            cached(disk, "p"); recent.refresh(); assertTrue(recent.cards.value.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedClearReportsFailureAndCanBeRetriedWithoutClaimingDurableDeletion() = runBlocking {
        val root = Files.createTempDirectory("recent-clear-failure").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache")); val store = Store()
            val recent = RecentNotesController(store, cache)
            cached(cache, "p"); recent.recordSuccessfulOpen("p", recent.generation)
            store.fail = true
            assertFalse(recent.clear()); assertNotNull(recent.message.value)
            val recreated = RecentNotesController(store, cache)
            recreated.refresh(); assertEquals(1, recreated.cards.value.size)
            store.fail = false
            assertTrue(recent.clear()); assertNull(recent.message.value)
            val afterRetry = RecentNotesController(store, cache)
            afterRetry.refresh(); assertTrue(afterRetry.cards.value.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun anUnsuccessfulOpenCannotRecordThePreviouslyDisplayedNote() = runBlocking {
        val root = Files.createTempDirectory("recent-failed").toFile()
        try {
            val store = Store(); val cache = FileDocumentCache(root.resolve("cache"))
            val recent = RecentNotesController(store, cache)
            val previous = ReaderDocument("previous", "Previous")
            val app = ReaderApp(ReaderSession(FakeReaderEngine()), NoteViewer(previous), ReaderNavigator(),
                DefaultLoadReaderDocument { _: String -> error("Unavailable") }, recentNotes = recent)
            app.openNote("failed")
            assertNotEquals(previous, app.noteViewer.document.value)
            assertEquals("failed", app.noteViewer.document.value.pageId)
            assertTrue(store.records.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun leavingTheNoteBeforeItsLoadFinishesDoesNotRecordAnOpen() = runBlocking {
        val root = Files.createTempDirectory("recent-back").toFile()
        try {
            val store = Store(); val recent = RecentNotesController(store, FileDocumentCache(root.resolve("cache")))
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val app = ReaderApp(ReaderSession(FakeReaderEngine()), NoteViewer(), ReaderNavigator(),
                DefaultLoadReaderDocument { id: String -> entered.complete(Unit); finish.await(); ReaderDocument(id, "Title") }, recentNotes = recent)
            val pending = async { app.openNote("p") }; entered.await(); app.back(); finish.complete(Unit); pending.await()
            assertTrue(store.records.isEmpty())
        } finally { root.deleteRecursively() }
    }
    private class Store : RecentNoteHistoryStore {
        var records = emptyList<RecentNoteReference>()
        var fail = false
        override suspend fun read() = records
        override suspend fun write(references: List<RecentNoteReference>) {
            if (fail) throw java.io.IOException("Storage unavailable")
            records = references
        }
    }
    private suspend fun cached(cache: FileDocumentCache, id: String) {
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, "Title $id", listOf("Body $id")), null, Instant.EPOCH))
    }
    @Test fun opensAreCanonicalDeduplicatedBoundedAndResolvedWithoutPersistedContent() = runBlocking {
        val root = Files.createTempDirectory("recent-notes").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache")); val store = Store()
            var now = 0L
            val recent = RecentNotesController(store, cache) { ++now }
            repeat(25) { cached(cache, "p$it"); recent.recordSuccessfulOpen("p$it", recent.generation) }
            assertEquals(20, store.records.size)
            assertEquals(listOf("p24", "p23", "p22", "p21", "p20"), recent.cards.value.map { it.pageId })
            cached(cache, "a-b"); recent.recordSuccessfulOpen("a-b", recent.generation)
            recent.recordSuccessfulOpen("ab", recent.generation)
            assertEquals(1, store.records.count { it.pageId.replace("-", "") == "ab" })
            assertEquals("a-b", recent.cards.value.first().pageId)
            val disk = FileRecentNoteHistoryStore(root.resolve("history"))
            disk.write(store.records)
            assertEquals(store.records, disk.read())
            val bytes = root.resolve("history/recent-notes.json").readText()
            assertFalse(bytes.contains("Title")); assertFalse(bytes.contains("Body"))
            root.resolve("history/recent-notes.json").writeText("interrupted invalid data")
            assertTrue(disk.read().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun evictionHidesContentButPermanentLossAfterEvictionRemovesTheReference() = runBlocking {
        val root = Files.createTempDirectory("recent-eviction").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache")); val store = Store()
            val recent = RecentNotesController(store, cache)
            cached(cache, "p"); recent.recordSuccessfulOpen("p", recent.generation)
            cache.remove("p", CacheRemovalReason.EVICTED); recent.refresh()
            assertTrue(recent.cards.value.isEmpty()); assertEquals(1, store.records.size)
            cache.remove("p", CacheRemovalReason.PERMISSION_DENIED); recent.refresh()
            assertTrue(store.records.isEmpty())
            cache.acknowledgeInvalidations(Long.MAX_VALUE)
            cached(cache, "p"); recent.refresh()
            assertTrue(recent.cards.value.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedDurableRemovalCannotBeAcknowledgedAsSuccessfulAndRetriesTheWrite() = runBlocking {
        val root = Files.createTempDirectory("recent-storage").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache")); val store = Store()
            val recent = RecentNotesController(store, cache)
            cached(cache, "p"); recent.recordSuccessfulOpen("p", recent.generation)
            store.fail = true; cache.remove("p", CacheRemovalReason.DELETED)
            assertFalse(recent.refresh()); assertTrue(recent.cards.value.isEmpty())
            assertEquals(1, store.records.size)
            store.fail = false; assertTrue(recent.refresh()); assertTrue(store.records.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun onlySuccessfulCurrentManualOpensCreateRecentsAndNeverCallPlayback() = runBlocking {
        val root = Files.createTempDirectory("recent-request").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache")); val store = Store()
            val recent = RecentNotesController(store, cache)
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val loader = DefaultLoadReaderDocument { id: String ->
                if (id == "slow") { entered.complete(Unit); finish.await() }
                if (id == "failed") error("Failed request")
                val doc = ReaderDocument.fromChunkTexts(id, id, listOf("Body"))
                cache.put(CachedDocument(doc, null, Instant.EPOCH)); doc
            }
            val engine = FakeReaderEngine()
            val app = ReaderApp(ReaderSession(engine), NoteViewer(), ReaderNavigator(), loader, recentNotes = recent)
            val old = async { app.openNote("slow") }; entered.await()
            app.openNote("current"); finish.complete(Unit); old.await()
            app.openNote("failed")
            app.noteViewer.load("background", loader)
            assertEquals(listOf("current"), store.records.map { it.pageId })
            assertEquals(0, engine.callCount)
            app.clearHistory(); assertTrue(store.records.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun clearingHistoryRejectsAnOpenThatStartedBeforeTheClear() = runBlocking {
        val root = Files.createTempDirectory("recent-reset-race").toFile()
        try {
            val cache = FileDocumentCache(root.resolve("cache")); val store = Store()
            val recent = RecentNotesController(store, cache)
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val app = ReaderApp(ReaderSession(FakeReaderEngine()), NoteViewer(), ReaderNavigator(),
                DefaultLoadReaderDocument { id: String -> entered.complete(Unit); finish.await(); ReaderDocument(id, "Title") }, recentNotes = recent)
            val pending = async { app.openNote("p") }; entered.await()
            app.clearHistory(); finish.complete(Unit); pending.await()
            assertTrue(store.records.isEmpty())
            app.clearAllLocalData(); assertTrue(store.records.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledManualOpensLeaveNoRecentReference() = runBlocking {
        val root = Files.createTempDirectory("recent-cancel").toFile()
        try {
            val store = Store(); val recent = RecentNotesController(store, FileDocumentCache(root.resolve("cache")))
            val entered = CompletableDeferred<Unit>()
            val app = ReaderApp(ReaderSession(FakeReaderEngine()), NoteViewer(), ReaderNavigator(),
                DefaultLoadReaderDocument { _: String -> entered.complete(Unit); awaitCancellation() }, recentNotes = recent)
            val pending = launch { app.openNote("p") }; entered.await(); pending.cancelAndJoin()
            assertTrue(store.records.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
