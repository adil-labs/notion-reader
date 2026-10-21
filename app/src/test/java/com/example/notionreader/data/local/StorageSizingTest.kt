package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageSizingTest {
    @Test fun actualAtomicStagingCountsBothGenerationsAndFailurePreservesTheOldFile() {
        val dir = temporary.newFolder()
        val target = dir.resolve("record")
        atomicSemanticWrite(target, ByteArray(1024))
        var measured = 0L
        try {
            atomicSemanticWrite(target, ByteArray(2048), onStaged = { staged ->
                measured = dir.listFiles()!!.sumOf { it.length() }
                assertEquals(2048L, staged.length())
                throw java.io.IOException("Injected commit failure")
            })
            fail("Expected injected I/O failure")
        } catch (_: java.io.IOException) { }
        assertEquals(3072L, measured)
        assertEquals(1024L, target.length())
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun shippingTopicCatalogAndCheckpointBytesAreMeasuredSeparately() = runBlocking {
        fun membership(n: Int): TopicMembership {
            val id = "note$n"
            val text = "Storage sizing evidence paragraph $n"
            val p = SemanticPassage("p$n", id, "s$n", "Section", "b$n", "c$n", 0, text.length, text, text, 0)
            return TopicMembership(id, SemanticMetadata("Sizing $n"), p, .95f, p, "r")
        }
        val members = (0 until 100).map(::membership)
        val connections = (0 until 99).map { n -> NoteConnection("pair$n", members[n], members[n+1], .95f) }
        val topic = DiscoveredTopic("topic", "Storage sizing", members[0].passage,
            EmbeddedPassage(members[0].passage, List(768) { if (it == 0) 1f else 0f }), members.map { it.copy(anchor = members[0].passage) }, 0)
        val snapshot = TopicSnapshot(topics = listOf(topic), connections = connections, generation = 1,
            identity = EmbeddingIdentity("sizing", 768), acceptedRevisions = members.associate { it.pageId to "r" })
        val topicsDir = temporary.newFolder()
        val checkpointDir = temporary.newFolder()
        val topics = FileTopicStore(topicsDir)
        val checkpoint = FileTopicStore(checkpointDir)
        assertEquals(TopicWriteResult.Written, topics.writeDetailed(snapshot))
        assertEquals(TopicWriteResult.Written, checkpoint.writeDetailed(snapshot.copy(generation = 2)))
        val catalogBytes = topicsDir.listFiles()!!.filter { it.name.startsWith("connections-") }.sumOf { it.length() }
        assertTrue(catalogBytes > 0)
        assertTrue(topics.bytes() > catalogBytes)
        println("STORAGE_SIZING topicsWithCatalogBytes=${topics.bytes()} catalogBytes=$catalogBytes checkpointBytes=${checkpoint.bytes()}")
    }
    @Test fun automaticGrowthDoesNotRemoveThePerRecordMemoryBound() = runBlocking {
        val store = FileSemanticIndexStore(temporary.newFolder())
        val original = SemanticRecord("p", "old", EmbeddingIdentity("test", 1), SemanticMetadata("Title"), emptyList())
        assertTrue(store.write(original))
        val text = "x".repeat(5*1024*1024)
        val passage = SemanticPassage("s", "p", "s", null, "b", "c", 0, text.length, text, text, 0)
        assertFalse(store.write(original.copy(revision = "new", passages = listOf(EmbeddedPassage(passage, listOf(1f))))))
        assertEquals("old", store.read("p")!!.revision)
    }
    @Test fun refusingACacheWriteDoesNotMutateItsGenerationOrJournal() = runBlocking {
        val dir = temporary.newFolder()
        val gate = object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T = throw StorageRefused(StorageFailure.DEVICE_SPACE)
        }
        val cache = FileDocumentCache(dir, writeGate = gate)
        try { cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Title", listOf("Text")), null, Instant.EPOCH)); fail() }
        catch (_: StorageRefused) { }
        assertEquals(0L, cache.revision.value)
        assertFalse(dir.resolve(".invalidations").exists())
    }
    @get:Rule val temporary = TemporaryFolder()
    @Test fun hundredOneMiBNotesFitTheDefaultCache() = runBlocking {
        val cache = FileDocumentCache(temporary.newFolder())
        repeat(100) { n ->
            val source = CachedDocument(ReaderDocument.fromChunkTexts("note-$n", "Sizing", listOf("x".repeat(1024*1024-4096))), null, Instant.EPOCH)
            assertTrue(ReaderDocumentCodec.encode(source).toByteArray().size <= 1024*1024)
            cache.put(source)
        }
        assertEquals(100, cache.count())
        assertTrue(cache.stats().totalBytes <= 128L*1024*1024)
        println("STORAGE_SIZING source100Bytes=${cache.stats().totalBytes}")
    }
    @Test fun shippingFormatAccountsForWideVectorsAndProgressCopies() = runBlocking {
        val identity = EmbeddingIdentity("sizing-768", 768)
        val vector = List(768) { if (it == 0) 1f else 0f }
        for (perNote in listOf(10, 50, 100, 500)) {
            val indexDir = temporary.newFolder()
            val progressDir = temporary.newFolder()
            val index = FileSemanticIndexStore(indexDir, maxBytes = Long.MAX_VALUE)
            val progress = FileSemanticIndexStore(progressDir, maxBytes = Long.MAX_VALUE)
            repeat(100) { note ->
                val id = "note-$note"
                val text = "A quoted \"passage\" with a slash \\ and 中文. ".repeat(8)
                val passages = List(perNote) { p -> EmbeddedPassage(SemanticPassage("passage-$p", id,
                    "section-${p%7}", "Section", "block-$p", "chunk-$p", 0, text.length, text, "Sizing\n$text", p), vector) }
                val record = SemanticRecord(id, "revision", identity, SemanticMetadata("Sizing"), passages)
                assertTrue(index.write(record)); assertTrue(progress.write(record))
            }
            val payload = 100L*perNote*768*4
            assertTrue(index.bytes() > payload)
            assertEquals(index.bytes(), progress.bytes())
            println("STORAGE_SIZING notes=100 passagesPerNote=$perNote vectorBytes=$payload indexBytes=${index.bytes()} progressBytes=${progress.bytes()}")
        }
    }
    @Test fun automaticPutPruningProtectsTheActiveQueue() = runBlocking {
        val cache = FileDocumentCache(temporary.newFolder(), maxEntries = 1, protectedPageIds = { setOf("playing") })
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts("playing", "Playing", listOf("Text")), null, Instant.EPOCH))
        cache.put(CachedDocument(ReaderDocument.fromChunkTexts("prefetch", "Next", listOf("Text")), null, Instant.EPOCH))
        assertNotNull(cache.get("playing"))
        assertNull(cache.get("prefetch"))
    }
    @Test fun refusedWritesLeaveThePreviousSemanticGenerationAvailable() = runBlocking {
        val dir = temporary.newFolder()
        var refuse = false
        val gate = object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T {
                if (refuse) throw StorageRefused(StorageFailure.DEVICE_SPACE)
                return block()
            }
        }
        val store = FileSemanticIndexStore(dir, writeGate = gate)
        val record = SemanticRecord("a", "old", EmbeddingIdentity("test", 1), SemanticMetadata("Title"), emptyList())
        assertTrue(store.write(record)); refuse = true
        assertFalse(store.write(record.copy(revision = "new")))
        assertEquals("old", store.read("a")!!.revision)
    }
}
