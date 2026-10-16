package com.example.notionreader.data.local

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SemanticStoreTest {
    @Test fun anEqualChecksumCannotHideChangedSourceMetadataInTheReadCache() = runBlocking {
        val dir = Files.createTempDirectory("semantic-header-collision").toFile()
        try {
            val store = FileSemanticIndexStore(dir)
            val passage = SemanticPassage("p", "page", "s", null, "b", "c", 0, 4, "text", "text", 0)
            assertTrue(store.write(SemanticRecord("page", "r", EmbeddingIdentity("test", 2), SemanticMetadata("Title"),
                listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
            assertNotNull(store.read("page"))
            val file = dir.listFiles()!!.single { it.extension == "record" }
            val bytes = file.readBytes()
            val length = java.nio.ByteBuffer.wrap(bytes, 8, 4).int
            val original = bytes.copyOfRange(12, 12 + length)
            fun crc(value: ByteArray) = java.util.zip.CRC32().apply { update(value) }.value
            val changed = original.copyOf().apply { this[0] = '!'.code.toByte(); fill(0, size - 4, size) }
            val base = crc(changed)
            val basis = LongArray(32); val masks = LongArray(32)
            for (bit in 0 until 32) {
                val candidate = changed.copyOf()
                val at = candidate.size - 4 + bit / 8
                candidate[at] = (1 shl (bit % 8)).toByte()
                var value = crc(candidate) xor base; var mask = 1L shl bit
                for (pivot in 31 downTo 0) if (value and (1L shl pivot) != 0L) {
                    if (basis[pivot] == 0L) { basis[pivot] = value; masks[pivot] = mask; break }
                    value = value xor basis[pivot]; mask = mask xor masks[pivot]
                }
            }
            var wanted = crc(original) xor base; var solution = 0L
            for (pivot in 31 downTo 0) if (wanted and (1L shl pivot) != 0L) {
                wanted = wanted xor basis[pivot]; solution = solution xor masks[pivot]
            }
            assertEquals(0L, wanted)
            for (bit in 0 until 32) if (solution and (1L shl bit) != 0L) {
                val at = changed.size - 4 + bit / 8
                changed[at] = (changed[at].toInt() xor (1 shl (bit % 8))).toByte()
            }
            assertEquals(crc(original), crc(changed))
            assertFalse(original.contentEquals(changed))
            changed.copyInto(bytes, 12); file.writeBytes(bytes)
            assertNull(store.read("page"))
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun semanticWritesAccountForTopicBytesAndKeepThePriorRecordWhenFull() = runBlocking {
        val dir = Files.createTempDirectory("semantic-shared-budget").toFile()
        try {
            var topicBytes = 0L
            val index = FileSemanticIndexStore(dir, maxBytes = 10_000, otherBytes = { topicBytes })
            val passage = SemanticPassage("p", "page", "s", null, "b", "c", 0, 4, "text", "text", 0)
            val record = SemanticRecord("page", "first", EmbeddingIdentity("test", 2), SemanticMetadata("Title"),
                listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
            assertTrue(index.write(record))
            topicBytes = 10_000
            assertFalse(index.write(record.copy(revision = "replacement")))
            assertEquals("first", index.read("page")!!.revision)
            topicBytes = 0
            assertTrue(index.write(record.copy(revision = "replacement")))
            assertEquals("replacement", index.read("page")!!.revision)
        } finally { dir.deleteRecursively() }
    }

    @Test fun obsoleteJsonRecordsAreRejectedForRebuild() = runBlocking {
        val dir=Files.createTempDirectory("semantic-old-format").toFile()
        try {
            val record=SemanticRecord("page","rev",EmbeddingIdentity("model",2,storage=1),SemanticMetadata("Title"),emptyList())
            java.io.File(dir,"${semanticHash("page")}.record").writeText(kotlinx.serialization.json.Json.encodeToString(SemanticRecord.serializer(),record))
            assertNull(FileSemanticIndexStore(dir).read("page"))
            assertTrue(FileSemanticIndexStore(dir).pageIds().isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun aTruncatedVectorPayloadIsRemovedForRebuild() = runBlocking {
        val dir = Files.createTempDirectory("semantic-vector-corrupt").toFile()
        try {
            val store = FileSemanticIndexStore(dir)
            val passage = SemanticPassage("c", "page", "s", null, "b", "c", 0, 4, "text", "text", 0)
            val record = SemanticRecord("page", "rev", EmbeddingIdentity("model", 2), SemanticMetadata("Title"), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))
            assertTrue(store.write(record))
            val file = dir.listFiles()!!.single { it.extension == "record" }
            val original = file.readBytes()
            file.writeBytes(original.copyOf(original.size - 4))
            assertEquals(original.size - 4, file.length().toInt())
            assertNull(store.read("page"))
            assertTrue(store.pageIds().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun nonFiniteBinaryVectorsAreNeverReturnedAndAreRemoved() = runBlocking {
        val dir=Files.createTempDirectory("semantic-nonfinite").toFile()
        try {
            val store=FileSemanticIndexStore(dir)
            val passage=SemanticPassage("c","page","s",null,"b","c",0,4,"text","text",0)
            assertTrue(store.write(SemanticRecord("page","rev",EmbeddingIdentity("model",2),SemanticMetadata("Title"),listOf(EmbeddedPassage(passage,listOf(1f,0f))))))
            val target=dir.listFiles()!!.single {it.extension=="record"}
            val bytes=target.readBytes()
            java.nio.ByteBuffer.wrap(bytes).putFloat(bytes.size-4,Float.NaN)
            target.writeBytes(bytes)
            assertNull(store.read("page"))
            assertTrue(store.pageIds().isEmpty())
        } finally {dir.deleteRecursively()}
    }

    @Test fun atomicRecordsSurviveReopeningAndCorruptRecordsBecomeRebuildable() = runBlocking {
        val dir = Files.createTempDirectory("semantic-store").toFile()
        try {
            val record = SemanticRecord("page", "rev", EmbeddingIdentity("model", 2), SemanticMetadata("Title"), emptyList())
            val store = FileSemanticIndexStore(dir)
            assertTrue(store.write(record))
            assertEquals(record, FileSemanticIndexStore(dir).read("page"))
            dir.listFiles()!!.first { it.name.endsWith(".record") }.writeText("broken")
            assertNull(store.read("page"))
            assertTrue(store.pageIds().isEmpty())
            store.setPaused(false)
            store.clear()
            assertTrue(FileSemanticIndexStore(dir).isPaused())
        } finally { dir.deleteRecursively() }
    }

    @Test fun exhaustingTheBudgetDoesNotReplaceAnExistingCompleteRecord() = runBlocking {
        val dir = Files.createTempDirectory("semantic-budget").toFile()
        try {
            val store = FileSemanticIndexStore(dir, maxBytes = 1000)
            val record = SemanticRecord("page", "rev", EmbeddingIdentity("model", 2), SemanticMetadata("Title"), emptyList())
            assertTrue(store.write(record))
            assertFalse(store.write(record.copy(metadata = SemanticMetadata("x".repeat(2000)))))
            assertEquals(record, store.read("page"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun cacheEnumerationDoesNotTouchLruAndSignalsInternalEviction() = runBlocking {
        val dir = Files.createTempDirectory("semantic-cache").toFile()
        try {
            val cache = FileDocumentCache(dir, maxEntries = 1)
            fun doc(id: String) = CachedDocument(ReaderDocument.fromChunkTexts(id, id, listOf("text")), null, Instant.EPOCH)
            cache.put(doc("first"))
            val file = dir.listFiles { f -> f.extension == "json" }!!.single()
            file.setLastModified(1000)
            val firstRevision = cache.revision.value
            assertEquals(listOf("first"), cache.snapshot().map { it.document.pageId })
            assertEquals(1000L, file.lastModified())
            cache.put(doc("second"))
            assertTrue(cache.revision.value > firstRevision)
            assertEquals(listOf("second"), cache.snapshot().map { it.document.pageId })
            cache.clear()
            assertTrue(cache.snapshot().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun explicitInterestsPersistAndCanBeCompletelyCleared() = runBlocking {
        val dir = Files.createTempDirectory("semantic-interests").toFile()
        try {
            val store = FileDiscoveryInterestStore(dir)
            val interests = listOf(DiscoveryInterest("one", "human motivation"), DiscoveryInterest("two", seedPageId = "page"))
            store.write(interests)
            assertEquals(interests, FileDiscoveryInterestStore(dir).read())
            store.write(emptyList())
            assertTrue(FileDiscoveryInterestStore(dir).read().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
