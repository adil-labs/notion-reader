package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.AutoCacheState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAutoCacheStateStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeDir: File
    private lateinit var store: FileAutoCacheStateStore

    @Before
    fun setUp() {
        storeDir = tempFolder.newFolder("auto_cache_state")
        store = FileAutoCacheStateStore(storeDir)
    }

    @Test
    fun readingNonExistentStateReturnsDefault() = runBlocking {
        val state = store.read()
        assertTrue(state.managedPageIds.isEmpty())
        assertNull(state.lastReconciledAt)
    }

    @Test
    fun writingAndReadingBackStatePreservesData() = runBlocking {
        val now = Instant.ofEpochMilli(1_700_000_000_000L)
        val initial = AutoCacheState(
            managedPageIds = setOf("page-1", "page-2", "page-3"),
            lastReconciledAt = now
        )

        store.write(initial)
        val loaded = store.read()

        assertEquals(setOf("page-1", "page-2", "page-3"), loaded.managedPageIds)
        assertEquals(now, loaded.lastReconciledAt)
    }

    @Test
    fun clearRemovesStateFile() = runBlocking {
        val state = AutoCacheState(
            managedPageIds = setOf("page-1"),
            lastReconciledAt = Instant.now()
        )
        store.write(state)
        val file = File(storeDir, "auto_cache_state.json")
        assertTrue(file.exists())

        store.clear()
        assertFalse(file.exists())

        val afterClear = store.read()
        assertTrue(afterClear.managedPageIds.isEmpty())
    }

    @Test
    fun corruptFileIsDeletedAndReturnsDefault() = runBlocking {
        val file = File(storeDir, "auto_cache_state.json")
        file.writeText("invalid-json{not_valid_at_all}")
        assertTrue(file.exists())

        val state = store.read()
        assertTrue(state.managedPageIds.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun incompatibleSchemaVersionIsDeletedAndReturnsDefault() = runBlocking {
        val file = File(storeDir, "auto_cache_state.json")
        file.writeText("""{"schemaVersion":999,"managedPageIds":["p1"]}""")
        assertTrue(file.exists())

        val state = store.read()
        assertTrue(state.managedPageIds.isEmpty())
        assertFalse(file.exists())
    }
}
