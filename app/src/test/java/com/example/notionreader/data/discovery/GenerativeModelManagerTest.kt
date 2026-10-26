package com.example.notionreader.data.discovery

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GenerativeModelManagerTest {
    @Test fun deletingTheGenerativePackPreservesTheEmbeddingPackAndOtherFiles() = runTest {
        val root = Files.createTempDirectory("separate-generative-pack").toFile()
        try {
            val embedding = root.resolve("current/embedding.litertlm")
            embedding.parentFile!!.mkdirs(); embedding.writeText("keep embedding")
            val unrelated = root.resolve("preferences").apply { writeText("keep settings") }
            val manager = GenerativeModelManager(root, backgroundScope, { false })
            manager.modelDirectory.mkdirs()
            manager.modelDirectory.resolve("partial-model").writeText("generative bytes")
            manager.delete()
            assertEquals("keep embedding", embedding.readText())
            assertEquals("keep settings", unrelated.readText())
            assertFalse(manager.modelDirectory.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun constructionDoesNotAcquireFilesAndAnUnverifiedPackIsUnavailable() = runTest {
        val root = Files.createTempDirectory("unverified-generative-pack").toFile()
        try {
            val manager = GenerativeModelManager(root, backgroundScope, { false })
            assertEquals(2588147712L, manager.state.value.totalBytes)
            assertFalse(manager.state.value.downloading)
            assertFalse(manager.modelDirectory.exists())
            manager.modelDirectory.mkdirs()
            manager.modelDirectory.resolve("installed").writeText(GenerativeModelPack.revision)
            manager.modelDirectory.resolve(GenerativeModelPack.bundleFile).writeText("wrong artifact")
            manager.checkInstalled()
            assertFalse(manager.state.value.installed)
            assertNull(manager.verifiedBundle())
        } finally { root.deleteRecursively() }
    }
}
