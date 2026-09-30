package com.example.notionreader.domain.queue

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartQueuesTest {

    private val now = 1_000_000L

    @Test
    fun listingReturnsQueuesSortedByLastEditedAtDescending() = runTest {
        val q1 = SmartQueue(id = "1", name = "Older", queryText = "tag:read", lastEditedAt = 100L)
        val q2 = SmartQueue(id = "2", name = "Newer", queryText = "tag:study", lastEditedAt = 200L)
        val store = InMemorySmartQueueStore(listOf(q1, q2))
        val manager = SmartQueues(store, clock = { now })

        val list = manager.list()

        assertEquals(listOf("2", "1"), list.map { it.id })
    }

    @Test
    fun listingFiltersInvalidQueues() = runTest {
        val valid = SmartQueue(id = "1", name = "Valid", queryText = "tag:work", lastEditedAt = 100L)
        val invalidName = SmartQueue(id = "2", name = "", queryText = "tag:work", lastEditedAt = 100L)
        val invalidVersion = SmartQueue(id = "3", name = "Future", queryText = "tag:work", lastEditedAt = 100L, schemaVersion = 99)
        val store = InMemorySmartQueueStore(listOf(valid, invalidName, invalidVersion))
        val manager = SmartQueues(store, clock = { now })

        val list = manager.list()

        assertEquals(listOf("1"), list.map { it.id })
    }

    @Test
    fun creatingRejectsBlankName() = runTest {
        val store = InMemorySmartQueueStore()
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.create(name = "   ", queryText = "books")

        assertFalse(outcome.changed)
        assertTrue(outcome.effect is SmartQueueEffect.Refused.EmptyName)
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun creatingRejectsBlankQuery() = runTest {
        val store = InMemorySmartQueueStore()
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.create(name = "Books", queryText = "   ")

        assertFalse(outcome.changed)
        assertTrue(outcome.effect is SmartQueueEffect.Refused.EmptyQuery)
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun creatingPersistsNewQueueAndReportsCreated() = runTest {
        val store = InMemorySmartQueueStore()
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.create(name = " Books ", queryText = " reading ")

        assertTrue(outcome.changed)
        val effect = outcome.effect as SmartQueueEffect.Created
        assertEquals("Books", effect.queue.name)
        assertEquals("reading", effect.queue.queryText)
        assertEquals(now, effect.queue.lastEditedAt)

        val stored = store.read()
        assertEquals(1, stored.size)
        assertEquals("Books", stored.first().name)
        assertEquals("reading", stored.first().queryText)
    }

    @Test
    fun renamingRejectsBlankName() = runTest {
        val existing = SmartQueue(id = "1", name = "Books", queryText = "books", lastEditedAt = 100L)
        val store = InMemorySmartQueueStore(listOf(existing))
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.rename(id = "1", newName = "  ")

        assertFalse(outcome.changed)
        assertTrue(outcome.effect is SmartQueueEffect.Refused.EmptyName)
        assertEquals("Books", store.read().first().name)
    }

    @Test
    fun renamingNonExistentQueueReportsNotFound() = runTest {
        val store = InMemorySmartQueueStore()
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.rename(id = "missing", newName = "New Name")

        assertFalse(outcome.changed)
        assertTrue(outcome.effect is SmartQueueEffect.Refused.NotFound)
    }

    @Test
    fun renamingUpdatesNameAndTimestampAndReportsRenamed() = runTest {
        val existing = SmartQueue(id = "1", name = "Books", queryText = "books", lastEditedAt = 100L)
        val store = InMemorySmartQueueStore(listOf(existing))
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.rename(id = "1", newName = " Novels ")

        assertTrue(outcome.changed)
        val effect = outcome.effect as SmartQueueEffect.Renamed
        assertEquals("Novels", effect.queue.name)
        assertEquals(now, effect.queue.lastEditedAt)
        assertEquals("Novels", store.read().first().name)
        assertEquals(now, store.read().first().lastEditedAt)
    }

    @Test
    fun deletingRemovesQueueAndReportsDeleted() = runTest {
        val existing = SmartQueue(id = "1", name = "Books", queryText = "books", lastEditedAt = 100L)
        val store = InMemorySmartQueueStore(listOf(existing))
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.delete(id = "1")

        assertTrue(outcome.changed)
        val effect = outcome.effect as SmartQueueEffect.Deleted
        assertEquals("1", effect.queue.id)
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun deletingNonExistentQueueReportsNotFound() = runTest {
        val store = InMemorySmartQueueStore()
        val manager = SmartQueues(store, clock = { now })

        val outcome = manager.delete(id = "missing")

        assertFalse(outcome.changed)
        assertTrue(outcome.effect is SmartQueueEffect.Refused.NotFound)
    }
}
