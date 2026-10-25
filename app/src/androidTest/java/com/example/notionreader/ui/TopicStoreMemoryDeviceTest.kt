package com.example.notionreader.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.data.local.FileTopicStore
import com.example.notionreader.domain.discovery.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic evidence only, isolated from the installed library and model. */
class TopicStoreMemoryDeviceTest {
    @Test fun largeTopicGenerationsSaveAndReopenWithinTheOrdinaryAppHeap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "topic-memory-test-${UUID.randomUUID()}")
        try {
            val passage = SemanticPassage("p", "p", "s", null, "b", "c", 0, 4, "body", "body", 0)
            val label = "x".repeat(1024 * 1024)
            fun snapshot(count: Int) = TopicSnapshot(generation = count.toLong(),
                identity = EmbeddingIdentity("synthetic-memory-test", 2),
                topics = (0 until count).map { n -> DiscoveredTopic("t$n", label, passage,
                    EmbeddedPassage(passage, listOf(1f, 0f)), emptyList(), n.toLong()) })
            repeat(2) {
                assertEquals(TopicWriteResult.Written, FileTopicStore(directory).writeDetailed(snapshot(48)))
                val saved = File(directory, "topics.json")
                assertTrue(saved.length() in (48L * 1024 * 1024)..(64L * 1024 * 1024))
                val restored = FileTopicStore(directory).read()!!
                assertEquals(48L, restored.generation)
                assertEquals(48, restored.topics.size)
                assertTrue(restored.topics.all { it.label == label })
            }
            assertEquals(TopicWriteResult.Rejected(TopicWriteFailure.RecordMemoryLimit),
                FileTopicStore(directory).writeDetailed(snapshot(70)))
            assertEquals(48L, FileTopicStore(directory).read()!!.generation)
            assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
        } finally {
            directory.deleteRecursively()
        }
    }
}
