package com.example.notionreader.application

import com.example.notionreader.domain.document.*
import com.example.notionreader.domain.settings.ReaderSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StorageBudgetCoordinatorTest {
    @Test fun playedNotesStopBeingProtectedAsTheQueueAdvances() {
        assertEquals(setOf("current", "next"), cacheProtectedPageIds(listOf("played", "current", "next"), 1))
    }
    private val gib = 1024L * 1024 * 1024
    @Test fun automaticDiscoveryCanGrowBeyondTheHistoricalLimit() = runBlocking {
        val gate = StorageBudgetCoordinator({ ReaderSettings() }, { StorageSnapshot(32*gib, 12*gib) }, { 0L }, { 0L })
        assertEquals(42, gate.write(StorageCategory.DISCOVERY, 300L*1024*1024) { 42 })
    }
    @Test fun unknownAndLowSpaceNeverInvokeTheWriter() = runBlocking {
        for (snapshot in listOf(null, StorageSnapshot(32*gib, gib))) {
            val gate = StorageBudgetCoordinator({ ReaderSettings() }, { snapshot }, { 0L }, { 0L })
            try { gate.write(StorageCategory.DISCOVERY, 1) { fail("Writer must not run") }; fail("Expected refusal") }
            catch (_: StorageRefused) { assertNotNull(gate.status.value.message) }
        }
    }
    @Test fun customLimitCountsOldGenerationWhileReplacementIsStaged() = runBlocking {
        val gate = StorageBudgetCoordinator({ ReaderSettings(discoveryStorageAutomatic = false, discoveryCustomLimitBytes = 256L*1024*1024) },
            { StorageSnapshot(32*gib, 12*gib) }, { 0L }, { 200L*1024*1024 })
        try { gate.write(StorageCategory.DISCOVERY, 60L*1024*1024) { fail() }; fail() }
        catch (e: StorageRefused) { assertEquals(StorageFailure.CATEGORY_LIMIT, e.reason) }
    }
    @Test fun serializedWritersCannotSpendTheSameFreeSpace() = runBlocking {
        var free = 4*gib
        var used = 0L
        val gate = StorageBudgetCoordinator({ ReaderSettings() }, { StorageSnapshot(10*gib, free) }, { used }, { used })
        var successes = 0
        coroutineScope { List(2) { async { try { gate.write(StorageCategory.DISCOVERY, 1536L*1024*1024) {
            yield(); free -= 1536L*1024*1024; used += 1536L*1024*1024; successes++
        } } catch (_: StorageRefused) {} } }.awaitAll() }
        assertEquals(1, successes)
    }
    @Test fun cancellationReleasesAdmissionForTheNextWriter() = runBlocking {
        val gate = StorageBudgetCoordinator({ ReaderSettings() }, { StorageSnapshot(32*gib, 12*gib) }, { 0L }, { 0L })
        try { gate.write(StorageCategory.DISCOVERY, 1) { throw CancellationException() } } catch (_: CancellationException) {}
        assertEquals(7, gate.write(StorageCategory.DISCOVERY, 1) { 7 })
    }
    @Test fun hundredNoteDefaultsDoNotChangeDownloadPreferences() {
        val settings = ReaderSettings()
        assertEquals(128L*1024*1024, settings.noteCacheLimitBytes)
        assertTrue(settings.discoveryStorageAutomatic)
        assertEquals(256L*1024*1024, settings.discoveryCustomLimitBytes)
        assertEquals(7, settings.autoCacheTimeWindow.days)
    }
}
