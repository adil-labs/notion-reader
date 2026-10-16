package com.example.notionreader.data.local

import com.example.notionreader.data.discovery.LocalDiscoveryModelManager
import com.example.notionreader.data.discovery.ModelArtifact
import com.example.notionreader.domain.discovery.semanticHash
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryModelDownloadTest {
    @Test fun anIncorrectDownloadCannotBecomeAnInstalledModel() = runTest {
        val dir = Files.createTempDirectory("model-download").toFile()
        try {
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true }, artifacts = listOf(ModelArtifact("model", 4, semanticHash("good"))), open = { ByteArrayInputStream("evil".toByteArray()) }, ioDispatcher = testScheduler.let { kotlinx.coroutines.test.StandardTestDispatcher(it) })
            manager.download()
            testScheduler.runCurrent()
            assertFalse(manager.state.value.installed)
            assertNotNull(manager.state.value.message)
            assertFalse(dir.resolve("current/installed").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun verifiedArtifactsInstallAtomicallyAndDeletionInvalidatesDerivedData() = runTest {
        val dir = Files.createTempDirectory("model-good").toFile()
        try {
            var invalidated = false
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true }, onDeleted = { invalidated = true }, artifacts = listOf(ModelArtifact("model", 4, semanticHash("good"))), open = { ByteArrayInputStream("good".toByteArray()) }, ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            manager.download()
            testScheduler.runCurrent()
            assertTrue(manager.state.value.installed)
            assertTrue(dir.resolve("current/installed").isFile)
            manager.delete()
            assertTrue(invalidated)
            assertFalse(manager.state.value.installed)
            assertFalse(dir.resolve("current").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun wifiOnlyChoicePreventsOpeningTheNetworkOnMeteredConnections() = runTest {
        val dir = Files.createTempDirectory("model-wifi").toFile()
        try {
            var opened = false
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { false }, artifacts = listOf(ModelArtifact("model", 4, semanticHash("good"))), open = { opened = true; ByteArrayInputStream("good".toByteArray()) }, ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            manager.download(); testScheduler.runCurrent()
            assertFalse(opened)
            assertFalse(manager.state.value.installed)
            assertNotNull(manager.state.value.message)
        } finally { dir.deleteRecursively() }
    }
}
