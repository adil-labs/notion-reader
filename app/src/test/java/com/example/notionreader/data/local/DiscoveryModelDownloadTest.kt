package com.example.notionreader.data.local

import com.example.notionreader.data.discovery.LocalDiscoveryModelManager
import com.example.notionreader.data.discovery.ModelArtifact
import com.example.notionreader.domain.discovery.semanticHash
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryModelDownloadTest {
    @Test fun modelStorageMeasuresOwnedFilesAndExcludesSiblingModelPacks() = runTest {
        val dir = Files.createTempDirectory("model-accounting").toFile()
        try {
            dir.resolve("current").mkdirs(); dir.resolve("current/model").writeText("good")
            dir.resolve("current/installed").writeText("test-revision")
            dir.resolve("generative/current").mkdirs(); dir.resolve("generative/current/model").writeText("unrelated pack")
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true },
                artifacts = listOf(ModelArtifact("model", 4, semanticHash("good"))), revision = "test-revision",
                ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            assertNull(manager.state.value.storageBytes)
            manager.checkInstalled()
            assertEquals(17L, manager.state.value.storageBytes)
            manager.delete()
            assertEquals(0L, manager.state.value.storageBytes)
            assertEquals("unrelated pack", dir.resolve("generative/current/model").readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun storageRefusalPreservesTheOldPackAndDoesNotStartTheNetwork() = runTest {
        val dir = Files.createTempDirectory("model-space").toFile()
        try {
            dir.resolve("current").mkdirs()
            dir.resolve("current/model").writeText("old")
            var opened = false
            val gate = object : com.example.notionreader.domain.document.StorageWriteGate {
                override suspend fun <T> write(category: com.example.notionreader.domain.document.StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T {
                    throw com.example.notionreader.domain.document.StorageRefused(com.example.notionreader.domain.document.StorageFailure.DEVICE_SPACE)
                }
            }
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true },
                artifacts = listOf(ModelArtifact("model", 4, semanticHash("good"))), writeGate = gate,
                open = { opened = true; ByteArrayInputStream("good".toByteArray()) },
                ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            manager.download(); testScheduler.runCurrent()
            assertFalse(opened)
            assertEquals("old", dir.resolve("current/model").readText())
            assertFalse(dir.resolve("staging").exists())
            assertTrue(manager.state.value.message!!.contains("storage"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun changingQuantizationAtTheSameRevisionRequiresAnExplicitDownloadAndRetainsTheOldPack() = runTest {
        val dir = Files.createTempDirectory("model-quantization-migration").toFile()
        try {
            dir.resolve("current").mkdirs()
            dir.resolve("current/model_quantized.onnx").writeText("old int8 graph")
            dir.resolve("current/model_quantized.onnx_data").writeText("old int8 weights")
            dir.resolve("current/installed").writeText("same-export-revision")
            var opened = 0
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true },
                artifacts = listOf(ModelArtifact("model_q4.onnx", 4, semanticHash("good"))),
                revision = "same-export-revision",
                open = { opened++; ByteArrayInputStream("good".toByteArray()) },
                ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            manager.checkInstalled()
            assertFalse(manager.state.value.installed)
            assertEquals(0, opened)
            assertEquals("old int8 graph", dir.resolve("current/model_quantized.onnx").readText())
            assertEquals("old int8 weights", dir.resolve("current/model_quantized.onnx_data").readText())
            manager.download(); testScheduler.runCurrent()
            assertTrue(manager.state.value.installed)
            assertEquals(1, opened)
            assertEquals("good", dir.resolve("current/model_q4.onnx").readText())
            assertFalse(dir.resolve("current/model_quantized.onnx").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun aDownloadQueuedDuringIntegrityCheckingDoesNotFetchAnAlreadyInstalledPack() = runTest {
        val dir = Files.createTempDirectory("model-startup-download").toFile()
        try {
            dir.resolve("current").mkdirs()
            dir.resolve("current/model").writeText("good")
            dir.resolve("current/installed").writeText(LocalDiscoveryModelManager.Revision)
            var opened = 0
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true },
                artifacts = listOf(ModelArtifact("model", 4, semanticHash("good"))),
                open = { opened++; ByteArrayInputStream("good".toByteArray()) },
                ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            val checking = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { manager.checkInstalled() }
            manager.download()
            testScheduler.runCurrent()
            checking.await()
            assertTrue(manager.state.value.installed)
            assertEquals("An already verified pack must not be downloaded again", 0, opened)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aPreviousModelMarkerCannotMakeANewPackAvailable() = runTest {
        val dir = Files.createTempDirectory("model-migration").toFile()
        try {
            dir.resolve("current").mkdirs()
            dir.resolve("current/model").writeText("good")
            dir.resolve("current/installed").writeText("previous-model")
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true },
                artifacts = listOf(ModelArtifact("model", 4, semanticHash("good"))),
                ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            manager.checkInstalled()
            assertFalse(manager.state.value.installed)
        } finally { dir.deleteRecursively() }
    }

    @Test fun externalWeightsUseTheirDeclaredRemotePath() = runTest {
        val dir = Files.createTempDirectory("external-model").toFile()
        try {
            val urls = mutableListOf<String>()
            val manager = LocalDiscoveryModelManager(dir, backgroundScope, { true },
                artifacts = listOf(ModelArtifact("model.onnx_data", 4, semanticHash("good"), "onnx/model.onnx_data")),
                open = { urls += it; ByteArrayInputStream("good".toByteArray()) },
                ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            manager.download(); testScheduler.runCurrent()
            assertTrue(manager.state.value.installed)
            assertTrue(urls.single().endsWith("/onnx/model.onnx_data"))
            assertTrue(dir.resolve("current/model.onnx_data").isFile)
        } finally { dir.deleteRecursively() }
    }

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
