package com.example.notionreader.data.local

import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.discovery.CachedReaderIndex
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileReaderIndexStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writeAndReadRoundTripPreservesAllFields() = runBlocking {
        val dir = tempFolder.newFolder("index-cache")
        val store = FileReaderIndexStore(dir)

        val fetchedAt = Instant.parse("2026-09-30T12:00:00Z")
        val editAt = Instant.parse("2026-09-30T10:30:00Z")

        val entry1 = ReaderIndexEntry(
            pageId = "page-1",
            title = "Architecture Note",
            lastEditedAt = editAt,
            kind = "Documentation",
            area = "Core",
            topics = setOf("Kotlin", "Android"),
            project = "Notion Reader",
            status = "Done"
        )
        val entry2 = ReaderIndexEntry(
            pageId = "page-2",
            title = "Chess Tactics",
            lastEditedAt = null
        )

        val index = CachedReaderIndex(
            entries = listOf(entry1, entry2),
            fetchedAt = fetchedAt
        )

        store.write(index)

        val loaded = store.read()
        assertNotNull(loaded)
        assertEquals(fetchedAt, loaded!!.fetchedAt)
        assertEquals(2, loaded.entries.size)

        assertEquals("page-1", loaded.entries[0].pageId)
        assertEquals("Architecture Note", loaded.entries[0].title)
        assertEquals(editAt, loaded.entries[0].lastEditedAt)
        assertEquals("Documentation", loaded.entries[0].kind)
        assertEquals("Core", loaded.entries[0].area)
        assertEquals(setOf("Kotlin", "Android"), loaded.entries[0].topics)
        assertEquals("Notion Reader", loaded.entries[0].project)
        assertEquals("Done", loaded.entries[0].status)

        assertEquals("page-2", loaded.entries[1].pageId)
        assertEquals("Chess Tactics", loaded.entries[1].title)
        assertNull(loaded.entries[1].lastEditedAt)
    }

    @Test
    fun corruptedFileReturnsNullSafelyAndDeletesCorruptFile() = runBlocking {
        val dir = tempFolder.newFolder("index-cache-corrupt")
        val store = FileReaderIndexStore(dir)
        val file = File(dir, "reader_index.json")

        file.writeText("{ bad json garbage")

        assertNull(store.read())
        org.junit.Assert.assertFalse("Corrupt index file must be deleted on read failure", file.exists())
    }

    @Test
    fun incompatibleSchemaVersionReturnsNullAndDeletesFile() = runBlocking {
        val dir = tempFolder.newFolder("index-cache-schema")
        val store = FileReaderIndexStore(dir)
        val file = File(dir, "reader_index.json")

        file.writeText("""
            {
                "schemaVersion": 999,
                "fetchedAt": "2026-09-30T12:00:00Z",
                "entries": []
            }
        """.trimIndent())

        assertNull(store.read())
        org.junit.Assert.assertFalse("Incompatible schema index file must be deleted on read failure", file.exists())
    }

    @Test
    fun clearRemovesIndexFile() = runBlocking {
        val dir = tempFolder.newFolder("index-cache-clear")
        val store = FileReaderIndexStore(dir)

        store.write(CachedReaderIndex(emptyList(), Instant.now()))
        assertNotNull(store.read())

        store.clear()
        assertNull(store.read())
    }
}
