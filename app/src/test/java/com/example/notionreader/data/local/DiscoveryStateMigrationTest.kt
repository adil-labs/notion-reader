package com.example.notionreader.data.local

import com.example.notionreader.domain.discovery.*
import com.example.notionreader.application.DiscoveryController
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import java.time.Instant
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryStateMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = EmbeddingIdentity("upgrade-model", 2)
    @Test fun anUpgradeReusesEligibleEmbeddingsAndKeepsConfirmedInterests() = runBlocking {
        val root = temporary.newFolder()
        val cache = FileDocumentCache(File(root, "cache"))
        val index = FileSemanticIndexStore(File(root, "index"))
        val interests = FileDiscoveryInterestStore(File(root, "interests"))
        val topicDirectory = File(root, "topics").apply { mkdirs() }
        val provider = object : EmbeddingProvider {
            override val identity = model
            override val tokenizer = object : SemanticTokenizer {
                override fun tokens(text: String) = Regex("\\S+").findAll(text).map {
                    SemanticToken(1, it.range.first, it.range.last + 1)
                }.toList()
            }
            override suspend fun embed(text: String): List<Float> = error("A valid existing embedding must not be recomputed")
        }
        val document = ReaderDocument.fromChunkTexts("a", "Database reliability", listOf(
            "Database transactions protect atomic changes and recover committed records after interruption."))
        cache.put(CachedDocument(document, null, Instant.EPOCH))
        val existing = SemanticRecord("a", semanticRevision(document), model, SemanticMetadata(document.title),
            SemanticChunker(provider.tokenizer).chunk(document).map { EmbeddedPassage(it, listOf(1f, 0f)) })
        assertTrue(index.write(existing)); index.setPaused(false)
        val confirmed = DiscoveryInterest("interest", "atomic updates", displayName = "My confirmed interest", originTopicId = "old-topic")
        interests.write(listOf(confirmed))
        File(topicDirectory, "topics.json").writeText("""{"engineVersion":0,"generation":12,"complete":true}""")
        val controller = DiscoveryController(cache, index, interests, provider, topicStore = FileTopicStore(topicDirectory))
        controller.reconcile(emptyList(), true)
        assertEquals(existing, index.read("a"))
        assertEquals(listOf(confirmed), interests.read())
        assertEquals(document, cache.snapshot().single().document)
        assertTrue(controller.state.value.topicSnapshot.complete)
        assertEquals(setOf("a"), controller.state.value.topicSnapshot.acceptedRevisions.keys)
        assertEquals(SectionHierarchicalEngine.VERSION, controller.state.value.topicSnapshot.engineVersion)
    }
    private fun record(id: String, axis: Int = 0): SemanticRecord {
        val text = "Database transactions protect atomic changes and recover committed records after interruption."
        val p = SemanticPassage("$id:p", id, "$id:s", "Database transactions", "$id:b", "$id:c", 0, text.length, text, text, 0)
        return SemanticRecord(id, "r1", model, SemanticMetadata(id),
            listOf(EmbeddedPassage(p, if (axis == 0) listOf(1f, 0f) else listOf(0f, 1f))))
    }

    @Test fun aLegacyGenerationCannotSurviveAsCurrentDiscoveryEvidence() = runBlocking {
        val directory = temporary.newFolder()
        val old = SectionHierarchicalEngine(model).discover(listOf(record("a"), record("b")), null, TopicPolicy())
            .copy(engineVersion = 0, sourceSignature = "legacy", generation = 8)
        File(directory, "topics.json").writeText(Json.encodeToString(old))
        val unrelated = File(temporary.root, "explicit-interests-and-listening.txt").apply {
            writeText("user-confirmed matching text; frozen queue; saved listening position")
        }
        val originalBytes = unrelated.readBytes()
        assertNull("An incompatible generation must be rebuilt, never exposed", FileTopicStore(directory).read())
        assertFalse(File(directory, "topics.json").exists())
        assertNull("A restart must not resurrect legacy state", FileTopicStore(directory).read())
        assertArrayEquals(originalBytes, unrelated.readBytes())
    }

    @Test fun aLegacyCheckpointCannotResumeAnchorSelection() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "topics.json").writeText("""{"engineVersion":0,"generation":2,"acceptedRevisions":{"a":"r1"},"candidateSeeds":{"a":1}}""")
        assertNull(FileTopicStore(directory).read())
        assertEquals(0L, FileTopicStore(directory).bytes())
    }

    @Test fun aCompatibleHierarchicalCheckpointResumesAllEligibleSections() = runBlocking {
        val records = listOf(record("a"), record("b", 1), record("c"))
        val worker = SectionHierarchicalEngine(model)
        val progress = worker.step(records, null, TopicPolicy(), workBudget = 1)
        assertNotNull(progress.formationProgress)
        val directory = temporary.newFolder()
        assertTrue(FileTopicStore(directory).write(progress))
        val restored = FileTopicStore(directory).read()!!
        assertEquals(progress, restored)
        val completed = worker.discover(records, restored, TopicPolicy())
        assertTrue(completed.complete)
        assertEquals(records.map { it.pageId }.toSet(), completed.acceptedRevisions.keys)
    }

    @Test fun interruptedMigrationCannotPromoteItsTemporaryLegacyFile() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "topics.json").writeText("""{"engineVersion":0,"generation":4}""")
        File(directory, "topics.json.tmp").writeText("{partial")
        assertNull(FileTopicStore(directory).read())
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
