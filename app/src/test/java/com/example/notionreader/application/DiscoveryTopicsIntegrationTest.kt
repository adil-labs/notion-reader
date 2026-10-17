package com.example.notionreader.application

import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTopicsIntegrationTest {
    @Test fun aDuplicateCurrentTitleWithdrawsAnIndependentReferenceFromTheInspectedGeneration() = runBlocking {
        val dir = Files.createTempDirectory("reference-title-invalidation").toFile()
        try {
            val model = EmbeddingIdentity("references", 2)
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            fun record(id: String, title: String, text: String): SemanticRecord {
                val p = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                return SemanticRecord(id, "r1", model, SemanticMetadata(title), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
            }
            val a = record("a", "Reading Guide", "Read Target Note.")
            val b = record("b", "Target Note", "A literal target context.")
            index.write(a); index.write(b); index.setPaused(false)
            val store = Store().apply { value = TopicSnapshot(identity = model, connections = discoverExplicitNoteReferences(listOf(a, b)), complete = true) }
            assertEquals(1, store.value!!.connections.size)
            var shown = TopicView()
            val coordinator = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, { shown = it }, engine = SectionHierarchicalEngine(model))
            coordinator.invalidate(mapOf("a" to "r1", "b" to "r1"))
            assertEquals(1, shown.snapshot.connections.size)
            index.write(record("c", "Target Note", "An unrelated duplicate title."))
            coordinator.invalidate(mapOf("a" to "r1", "b" to "r1", "c" to "r1"))
            assertTrue(store.value!!.connections.isEmpty())
            assertTrue(shown.snapshot.connections.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun experimentalFormationResumesAfterRestartAndMoreThanSixteenRounds() = runBlocking {
        val dir = Files.createTempDirectory("resumable-topics").toFile()
        try {
            val model = EmbeddingIdentity("resumable", 2)
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store(); val checkpoint = FileTopicStore(dir.resolve("checkpoint"))
            val sources = (0..5).associate { "n$it" to "r1" }
            for (page in sources.keys) {
                val heading = "Forest Ecosystems"
                val p = SemanticPassage("$page:p", page, "$page:s", heading, "$page:b", "$page:c", 0, "Forest ecosystems support wildlife through diverse habitats and food sources.".length,
                    "Forest ecosystems support wildlife through diverse habitats and food sources.", heading, 0)
                // Distinct directions keep this a multi-round resume test after exact duplicate contraction.
                val angle = Math.toRadians(page.removePrefix("n").toDouble() * 3.0)
                val vector = listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                index.write(SemanticRecord(page, "r1", model, SemanticMetadata(page), listOf(EmbeddedPassage(p, vector))))
            }
            index.setPaused(false)
            fun worker() = TopicCoordinator(cache, index, store, checkpoint, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, engine = SectionHierarchicalEngine(model, maxSectionsBudget = 1))
            var coordinator = worker(); coordinator.invalidate(sources)
            coordinator.build(sources, 0, cache.revision.value)
            assertNull("Partial work must not replace the published generation", store.value)
            assertNotNull(checkpoint.read()?.formationProgress)
            coordinator = worker(); coordinator.invalidate(sources)
            var rounds = 1
            do {
                coordinator.build(sources, 0, cache.revision.value); rounds++
            } while (coordinator.needsMoreWork && rounds < 1000)
            assertTrue("The fixture crosses the legacy foreground work limit", rounds > 16)
            assertTrue(store.value!!.complete)
            assertTrue(store.value!!.connectionPassComplete)
            assertEquals(6, store.value!!.topics.single().members.size)
            assertNull(checkpoint.read())
        } finally { dir.deleteRecursively() }
    }
    @Test fun unchangedPublishedMembershipReusesCompletedConnectionsAcrossGroupingAndRestart() = connectionReuseFixture(false)
    @Test fun newlyPublishedMembershipRecomputesConnectionsAndRemovesWithinTopicPairs() = connectionReuseFixture(true)
    private fun connectionReuseFixture(grounded: Boolean) = runBlocking {
        val dir = Files.createTempDirectory("topic-connection-reuse").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val disk = FileSemanticIndexStore(dir.resolve("index"))
            var reads = 0
            val index = object : SemanticIndexStore by disk {
                override suspend fun read(pageId: String): SemanticRecord? { reads++; return disk.read(pageId) }
            }
            val model = EmbeddingIdentity("connection-reuse", 13)
            val sources = (0..2).associate { "p$it" to "r" }
            for ((n, id) in sources.keys.withIndex()) {
                val passages = ((0..3).map { if (n == 1 && it == 0) 0 else n * 4 + it } + 12).mapIndexed { position, axis ->
                    val text = if (grounded) "Forest evidence for habitat $axis." else "Habitat$n Woodland$n Ecosystem$n Evidence$axis."
                    EmbeddedPassage(SemanticPassage("$id:$position", id, "$id:s$position", null, "$id:b$position",
                        "$id:c$position", 0, text.length, text, text, position), List(13) { if (it == axis) 1f else 0f })
                }
                disk.write(SemanticRecord(id, "r", model, SemanticMetadata(id), passages))
            }
            disk.setPaused(false)
            val store = Store()
            fun coordinator() = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            var worker = coordinator()
            worker.invalidate(sources); worker.build(sources, 0, cache.revision.value)
            val first = store.value!!
            assertTrue(first.connectionPassComplete)
            assertTrue("The retained result must be nonempty", first.connections.isNotEmpty())
            assertTrue(publishableDiscoveryTopics(first.topics).isEmpty())
            reads = 0
            worker.build(sources, 0, cache.revision.value)
            assertEquals(2, store.value!!.groupingPass)
            if (grounded) {
                assertTrue(publishableDiscoveryTopics(store.value!!.topics).isNotEmpty())
                assertTrue("A newly published shared topic excludes its within-topic pair", store.value!!.connections.isEmpty())
                assertEquals("Grouping and the newly required seed sweep both read the three Notes", 6, reads)
                return@runBlocking
            }
            assertEquals(first.connections, store.value!!.connections)
            assertTrue(store.value!!.connectionPassComplete)
            assertTrue("Grouping must not repeat completed exact connection searches: reads=$reads", reads <= 6)
            worker = coordinator(); worker.invalidate(sources); reads = 0
            worker.build(sources, 0, cache.revision.value)
            assertEquals(3, store.value!!.groupingPass)
            assertEquals(first.connections, store.value!!.connections)
            assertTrue("A process restart must preserve safe reuse: reads=$reads", reads <= 6)
            reads = 0
            worker.build(sources, 1, cache.revision.value)
            assertEquals(1, store.value!!.groupingPass)
            assertTrue("Changed metadata must reconcile connection evidence", reads > 6)
        } finally { dir.deleteRecursively() }
    }
    @Test fun browseOrderingAndUncachedMetadataDoNotRestartTheCandidateSweep() = runBlocking {
        val dir = Files.createTempDirectory("topic-metadata-order").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store()
            val model = EmbeddingIdentity("metadata-order", 13)
            val entries = (0..2).map { n ->
                val id = "p$n"
                val doc = ReaderDocument.fromChunkTexts(id, "Forest $n", listOf("Forest ecosystems shelter diverse wildlife."))
                cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val passages = ((0..3).map { n * 4 + it } + 12).mapIndexed { order, axis ->
                    val text = "Forest ecosystems shelter diverse wildlife in habitat $axis."
                    EmbeddedPassage(SemanticPassage("$id:$axis", id, "$id:section:$axis", "Forest ecosystems",
                        "$id:block:$axis", "$id:chunk:$axis", 0, text.length, text, text, order),
                        List(13) { if (it == axis) 1f else 0f })
                }
                index.write(SemanticRecord(id, semanticRevision(doc), model, SemanticMetadata(doc.title), passages))
                com.example.notionreader.domain.ReaderIndexEntry(id, doc.title, null)
            }
            index.setPaused(false)
            val provider = object : EmbeddingProvider by Provider() {
                override val identity = model
                override suspend fun embed(text: String): List<Float> = error("Unchanged embeddings must be reused")
            }
            var controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = store, topicCheckpointStore = Store())
            controller.reconcile(entries, true)
            assertEquals(1, store.value!!.groupingPass)
            controller.reconcile(entries.reversed(), true)
            assertEquals("Browse order is not a semantic input", 2, store.value!!.groupingPass)
            controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = store, topicCheckpointStore = Store())
            controller.reconcile(entries + com.example.notionreader.domain.ReaderIndexEntry("uncached", "Other", null), true)
            assertEquals("A new coordinator resumes unchanged cached inputs despite uncached changes", 3, store.value!!.groupingPass)
            val updated = entries.map { if (it.pageId == "p0") it.copy(title = "Updated forest title") else it }
            controller.reconcile(updated, true)
            assertEquals("Changed cached metadata must still start a fresh reconciliation", 1, store.value!!.groupingPass)
            assertEquals("Updated forest title", index.read("p0")!!.metadata.title)
        } finally { dir.deleteRecursively() }
    }
    @Test fun supportedIndividualConnectionsDoNotRequireEitherNoteToHaveATopic() = runBlocking {
        val dir = Files.createTempDirectory("ungrouped-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            for (id in listOf("a", "b")) cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, "Forest $id",
                listOf("Forest ecosystems retain water and shelter diverse animals.")), null, Instant.EPOCH))
            index.setPaused(false)
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(),
                topicStore = Store(), topicCheckpointStore = Store())
            controller.reconcile(emptyList(), true)
            assertTrue(controller.state.value.topicSnapshot.topics.isEmpty())
            assertEquals(1, controller.state.value.topicSnapshot.connections.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectedCheckpointKeepsPriorDiscoveriesAndReportsTheActualFailure() = runBlocking {
        val dir = Files.createTempDirectory("checkpoint-rejection").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = Provider().identity
            val text = "Forest ecosystems contain diverse trees and wildlife"
            val passages = (0 until 64).map { n ->
                EmbeddedPassage(SemanticPassage("p:$n", "p", "section:$n", null, "block:$n", "chunk:$n",
                    0, text.length, text, text, n), listOf(1f, 0f))
            }
            assertTrue(index.write(SemanticRecord("p", "r", model, SemanticMetadata("Forest"), passages)))
            index.setPaused(false)
            for (failure in TopicWriteFailure.entries) {
                val prior = TopicSnapshot(generation = 7)
                val store = Store().apply { value = prior }
                var attempted = false
                val checkpoint = object : TopicStore by Store() {
                    override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult {
                        attempted = true
                        return TopicWriteResult.Rejected(failure)
                    }
                }
                var view = TopicView()
                val coordinator = TopicCoordinator(cache, index, store, checkpoint, { model }, TopicPolicy(),
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, { view = it })
                coordinator.invalidate(mapOf("p" to "r"))
                coordinator.build(mapOf("p" to "r"), 0, cache.revision.value)
                assertTrue("The checkpoint boundary must actually be reached", attempted)
                assertEquals(prior, store.value)
                assertEquals(prior, view.snapshot)
                assertEquals(failure == TopicWriteFailure.BudgetExceeded, view.status.orEmpty().contains("storage is full"))
                assertTrue(view.status.orEmpty().startsWith("Topic checkpoint"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectedPublicationKeepsThePreviousGenerationAndReportsTheActualFailure() = runBlocking {
        val dir = Files.createTempDirectory("topic-rejection").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            index.setPaused(false)
            for (failure in TopicWriteFailure.entries) {
                val prior = TopicSnapshot(generation = 7)
                val backing = Store().apply { value = prior }
                val rejected = object : TopicStore by backing {
                    override suspend fun write(snapshot: TopicSnapshot) = false
                    override suspend fun writeDetailed(snapshot: TopicSnapshot) = TopicWriteResult.Rejected(failure)
                }
                var view = TopicView()
                val coordinator = TopicCoordinator(cache, index, rejected, null, { Provider().identity }, TopicPolicy(),
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, { view = it })
                coordinator.invalidate(emptyMap())
                coordinator.build(emptyMap(), 0, cache.revision.value)
                assertEquals(prior, backing.value)
                assertEquals(prior, view.snapshot)
                val status = view.status.orEmpty()
                assertEquals(failure == TopicWriteFailure.BudgetExceeded, status.contains("storage is full"))
                assertTrue(status.contains(when (failure) {
                    TopicWriteFailure.BudgetExceeded -> "storage is full"
                    TopicWriteFailure.InvalidSnapshot -> "validation"
                    TopicWriteFailure.IoFailure -> "I/O"
                    TopicWriteFailure.Unknown -> "unknown"
                }))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun aSupportedThemeAfterFourPrivatePassagesStillGetsDiscovered() = runBlocking {
        val dir = Files.createTempDirectory("topic-late-evidence").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store()
            val model = EmbeddingIdentity("late", 13)
            val sources = (0..2).associate { "p$it" to "r" }
            for ((n, id) in sources.keys.withIndex()) {
                val passages = ((0..3).map { n * 4 + it } + 12).mapIndexed { position, axis ->
                    val text = "Body evidence $axis"
                    EmbeddedPassage(SemanticPassage("$id:$axis", id, "$id:s$axis", null, "$id:b$axis",
                        "$id:c$axis", 0, text.length, text, text, position), List(13) { if (it == axis) 1f else 0f })
                }
                index.write(SemanticRecord(id, "r", model, SemanticMetadata(id), passages))
            }
            index.setPaused(false)
            val coordinator = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            repeat(10) { coordinator.build(sources, 0, cache.revision.value) }
            assertFalse("Later body evidence must receive a candidate opportunity", store.value!!.topics.isEmpty())
            assertEquals(sources.keys, store.value!!.topics.single().members.map { it.pageId }.toSet())
            assertTrue(store.value!!.topics.single().members.all { it.passage.order == 4 })
            assertFalse(coordinator.needsMoreWork)
        } finally { dir.deleteRecursively() }
    }
    @Test fun unchangedCapacityLimitedSourcesStopRescanningAndRotateCandidates() = runBlocking {
        val dir = Files.createTempDirectory("topic-capacity").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store()
            val model = Provider().identity
            val sources = (0..2).associate { "p$it" to "r" }
            for (id in sources.keys) {
                val passages = (0..1).map { axis ->
                    val text = "Body evidence $axis"
                    EmbeddedPassage(SemanticPassage("$id:$axis", id, "$id:s$axis", null, "$id:b$axis",
                        "$id:c$axis", 0, text.length, text, text, axis), if (axis == 0) listOf(1f, 0f) else listOf(0f, 1f))
                }
                index.write(SemanticRecord(id, "r", model, SemanticMetadata(id), passages))
            }
            index.setPaused(false)
            val coordinator = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(maxAnchors = 1),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            coordinator.build(sources, 0, cache.revision.value)
            assertEquals(1, store.value!!.candidateCursor)
            repeat(5) { coordinator.build(sources, 0, cache.revision.value) }
            val finished = store.value!!
            assertFalse(finished.complete)
            assertTrue(finished.connectionPassComplete)
            assertFalse(coordinator.needsMoreWork)
            assertTrue(finished.groupingPass <= finished.candidatePassLimit)
            coordinator.build(sources, 0, cache.revision.value)
            assertEquals(finished.generation, store.value!!.generation)
        } finally { dir.deleteRecursively() }
    }
    private class Provider : EmbeddingProvider {
        override val identity = EmbeddingIdentity("topics-test", 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text).map {
                SemanticToken(1, it.range.first, it.range.last + 1)
            }.toList()
        }
        override suspend fun embed(text: String) = if ("forest" in text) listOf(1f, 0f) else listOf(0f, 1f)
    }
    private class Store : TopicStore {
        var value: TopicSnapshot? = null
        override suspend fun read() = value
        override suspend fun write(snapshot: TopicSnapshot): Boolean { value = snapshot; return true }
        override suspend fun clear() { value = null }
        override suspend fun bytes() = 0L
    }
    @Test fun automaticTopicsWorkWithoutInterestsAndFollowSurvivesIndexClear() = runBlocking {
        val dir = Files.createTempDirectory("topics-integration").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val topics = Store()
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            val controller = DiscoveryController(cache, index, interests, Provider(), topicStore = topics)
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i",
                listOf("forest ecosystem trees")), null, Instant.EPOCH)) }
            index.setPaused(false)
            controller.reconcile(emptyList(), true)
            controller.refreshTopics()
            val topic = controller.state.value.topicSnapshot.topics.single()
            assertEquals(3, topic.members.map { it.pageId }.distinct().size)
            assertTrue(controller.state.value.interests.isEmpty())
            controller.followTopic(topic.id, "My ecology", "forest conservation")
            controller.followTopic(topic.id, "Duplicate", "different text")
            assertEquals(1, interests.read().size)
            val saved = interests.read().single()
            assertEquals("My ecology", saved.displayName)
            assertEquals("forest conservation", saved.text)
            controller.clearIndex()
            assertTrue(controller.state.value.topicSnapshot.topics.isEmpty())
            assertEquals(saved, interests.read().single())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aNewGenerationWaitsForExplicitRefreshButEvictionWithdrawsEvidence() = runBlocking {
        val dir = Files.createTempDirectory("topics-refresh").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = Store())
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest")), null, Instant.EPOCH)) }
            index.setPaused(false); controller.reconcile(emptyList(), true); controller.refreshTopics()
            val original = controller.state.value.topicSnapshot
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p3", "Forest 3", listOf("forest")), null, Instant.EPOCH))
            controller.reconcile(emptyList(), true)
            assertTrue(controller.state.value.topicsUpdated)
            assertEquals(original.topics.single().id, controller.state.value.topicSnapshot.topics.single().id)
            assertEquals(3, controller.state.value.topicSnapshot.topics.single().members.size)
            controller.refreshTopics()
            assertEquals(4, controller.state.value.topicSnapshot.topics.single().members.size)
            cache.remove("p0"); controller.reconcile(emptyList(), false)
            assertTrue(controller.state.value.topicSnapshot.topics.flatMap { it.members }.none { it.pageId == "p0" })
        } finally { dir.deleteRecursively() }
    }

    @Test fun permanentLossAfterEvictionPurgesContinuityButKeepsTheSavedFollow() = runBlocking {
        val dir = Files.createTempDirectory("topics-permanent").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); val store = Store()
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            val controller = DiscoveryController(cache, index, interests, Provider(), topicStore = store)
            repeat(4) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest")), null, Instant.EPOCH)) }
            index.setPaused(false); controller.reconcile(emptyList(), true); controller.refreshTopics()
            val topic = controller.state.value.topicSnapshot.topics.single()
            controller.followTopic(topic.id, "My forest", "forest ecosystem")
            cache.remove("p0"); controller.reconcile(emptyList(), false)
            assertTrue(store.value!!.continuity.any { "p0" in it.pageIds })
            assertEquals(topic.id, controller.state.value.topicSnapshot.topics.single().id)
            cache.remove("p0", com.example.notionreader.domain.document.CacheRemovalReason.PERMISSION_DENIED)
            controller.reconcile(emptyList(), false)
            assertTrue(store.value!!.continuity.none { "p0" in it.pageIds })
            assertTrue(cache.invalidations.value.isEmpty())
            assertEquals("My forest", interests.read().single().displayName)
        } finally { dir.deleteRecursively() }
    }

    @Test fun changingAnUngroupedConnectionEndpointWithdrawsItsOldPassageWhilePaused() = runBlocking {
        val dir = Files.createTempDirectory("topics-connection-revision").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); val store = Store()
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            val initial = DiscoveryController(cache, index, interests, Provider(), topicStore = store)
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest")), null, Instant.EPOCH)) }
            val ungrouped = ReaderDocument.fromChunkTexts("q", "Other", listOf("old private evidence"))
            cache.put(CachedDocument(ungrouped, null, Instant.EPOCH))
            index.setPaused(false); initial.reconcile(emptyList(), true)
            val left = store.value!!.topics.first().members.first()
            val record = index.read("q")!!
            val right = TopicMembership("q", record.metadata, record.passages.first().passage, .8f, left.passage, record.revision)
            store.value = store.value!!.copy(connections = listOf(NoteConnection("pair", left, right, .8f)),
                provisionalTopics = emptyList(), provisionalPasses = emptyMap())
            val controller = DiscoveryController(cache, index, interests, Provider(), topicStore = store)
            controller.reconcile(emptyList(), false)
            assertEquals(1, controller.state.value.topicSnapshot.connections.size)
            cache.put(CachedDocument(ungrouped.copy(title = "Changed title"), null, Instant.EPOCH))
            controller.reconcile(emptyList(), false)
            assertTrue(controller.state.value.topicSnapshot.connections.isEmpty())
            assertTrue(store.value!!.connections.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aCheckpointFinishingDuringResetCannotRecreateSourceEvidence() = runBlocking {
        val dir = Files.createTempDirectory("topics-reset-checkpoint").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); val store = Store(); val checkpoint = Store()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val gated = object : TopicStore by checkpoint {
                override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult =
                    if (write(snapshot)) TopicWriteResult.Written else TopicWriteResult.Rejected(TopicWriteFailure.Unknown)
                override suspend fun write(snapshot: TopicSnapshot): Boolean {
                    entered.complete(Unit); release.await(); return checkpoint.write(snapshot)
                }
            }
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", List(32) { "forest" }), null, Instant.EPOCH)) }
            index.setPaused(false)
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = store, topicCheckpointStore = gated)
            val build = async { controller.reconcile(emptyList(), true) }
            entered.await()
            val clear = async { controller.clearIndex() }
            yield(); release.complete(Unit); clear.await(); build.await()
            assertNull(checkpoint.value)
            assertNull(store.value)
            assertTrue(controller.state.value.topicSnapshot.topics.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRemovalObservedAfterEnumerationCannotBeRepublishedFromTheOldSourceMap() = runBlocking {
        val dir = Files.createTempDirectory("topics-enumeration-race").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store(); val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest")), null, Instant.EPOCH)) }
            index.setPaused(false)
            DiscoveryController(cache, index, interests, Provider(), topicStore = store).reconcile(emptyList(), true)
            // A legacy completed generation has no persisted build signature; it must still be safe.
            store.value = store.value!!.copy(sourceSignature = null)
            val removingStore = object : TopicStore by store {
                override suspend fun read(): TopicSnapshot? {
                    val result = store.read()
                    cache.remove("p0")
                    return result
                }
            }
            val controller = DiscoveryController(cache, index, interests, Provider(), topicStore = removingStore)
            controller.reconcile(emptyList(), true)
            controller.refreshTopics()
            assertTrue(controller.state.value.topicSnapshot.topics.flatMap { it.members }.none { it.pageId == "p0" })
            assertTrue(store.value!!.topics.flatMap { it.members }.none { it.pageId == "p0" })
            assertEquals(2, cache.count())
        } finally { dir.deleteRecursively() }
    }

    @Test fun evictionPreservesPreparedOrderButRemovesPrivateEvidenceAndRequiresReview() = runBlocking {
        val dir = Files.createTempDirectory("topic-preview-retention").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = Store())
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest")), null, Instant.EPOCH)) }
            index.setPaused(false); controller.reconcile(emptyList(), true); controller.refreshTopics()
            controller.preparePreview(controller.topicPreview(controller.state.value.topicSnapshot.topics.single().id))
            val before = controller.state.value.preview.map { it.pageId }
            cache.remove(before.first()); controller.reconcile(emptyList(), false)
            assertEquals(before, controller.state.value.preview.map { it.pageId })
            assertEquals(setOf(before.first()), controller.state.value.unavailablePreviewIds)
            assertEquals("", controller.state.value.preview.first().passage.excerpt)
            assertEquals("", controller.state.value.preview.first().passage.input)
            assertNotNull(controller.state.value.previewStatus)
            controller.removePreview(before.first())
            assertTrue(controller.state.value.unavailablePreviewIds.isEmpty())
            assertNull(controller.state.value.previewStatus)
        } finally { dir.deleteRecursively() }
    }

    @Test fun warmQueriesReuseEligibilityUntilTheCacheGenerationChanges() = runBlocking {
        val dir = Files.createTempDirectory("discovery-warm-eligibility").toFile()
        try {
            val backing = FileDocumentCache(dir.resolve("cache"))
            var enumerations = 0
            val cache = object : com.example.notionreader.domain.document.DocumentCache by backing {
                override suspend fun snapshot(): List<CachedDocument> { enumerations++; return backing.snapshot() }
            }
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Forest", listOf("forest")), null, Instant.EPOCH))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            index.setPaused(false)
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            controller.reconcile(emptyList(), true)
            val afterIndex = enumerations
            controller.search("forest"); controller.search("forest ecosystem")
            assertEquals(afterIndex, enumerations)
            cache.remove("p"); controller.search("forest")
            assertTrue(enumerations > afterIndex)
            assertTrue(controller.state.value.results.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun boundedConnectionPruningRetainsSupportedPairsOutsideTheHub() = runBlocking {
        val dir = Files.createTempDirectory("connection-diversity").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("diversity", 32); val policy = TopicPolicy()
            val ids = listOf("a0", "c0", "d0") + (0..24).map { "b%02d".format(it) }
            val sources = ids.associateWith { "r" }
            val members = ids.associateWith { id ->
                val text = "Forest roots absorb rainfall and protect woodland soil from erosion."
                val passage = SemanticPassage("$id:p", id, "$id:s", "Ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                // Early a0/b19 passages introduce one new strong pair to a full 20-pair reservoir.
                // Other private axes cannot regenerate the disjoint c0/d0 shared passage.
                val privateVector = List(32) { if (it == if (id == "b19") 0 else ids.indexOf(id)) 1f else 0f }
                val sharedVector = List(32) { axis -> when (axis) { 30 -> if (id.startsWith("b")) .95f else if (id == "d0") .85f else 1f
                    31 -> if (id.startsWith("b")) .3122499f else if (id == "d0") .5267827f else 0f
                    else -> 0f } }
                val early = (0..2).map { n -> EmbeddedPassage(passage.copy(id = "$id:private$n", sectionId = "$id:private-section$n"), privateVector) }
                index.write(SemanticRecord(id, "r", model, SemanticMetadata(id), early + EmbeddedPassage(passage, sharedVector)))
                TopicMembership(id, SemanticMetadata(id), passage, 1f, passage, "r")
            }
            fun topic(id: String, pageIds: List<String>, order: Long): DiscoveredTopic {
                val selected = pageIds.map { members.getValue(it) }
                return DiscoveredTopic(id, "Ecology", selected.first().passage,
                    EmbeddedPassage(selected.first().passage, List(32) { if (it == 30) 1f else 0f }), selected, order)
            }
            fun pair(a: String, b: String, score: Float) = NoteConnection(semanticHash(listOf(a, b).sorted().joinToString("|")),
                members.getValue(a), members.getValue(b), score)
            val diverse = pair("c0", "d0", .85f)
            val oldPairs = (0..18).map { pair("a0", "b%02d".format(it), .95f) } + diverse
            val token = semanticHash(model.toString() + policy.toString() + 0 + sources.toSortedMap().toString())
            val store = Store().apply { value = TopicSnapshot(topics = listOf(
                topic("left", listOf("a0", "c0"), 0), topic("right", ids.filter { it != "a0" && it != "c0" }, 1)),
                identity = model, complete = true, connections = oldPairs, sourceSignature = token,
                policyVersion = policy.version, groupingPass = 1, candidatePassLimit = 1) }
            index.setPaused(false)
            val coordinator = TopicCoordinator(cache, index, store, null, { model }, policy,
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            coordinator.invalidate(sources)
            coordinator.build(sources, 0, cache.revision.value)
            assertTrue("Score-only reservoir pruning must not discard the independent supported pair",
                store.value!!.connections.any { it.id == diverse.id })
            assertTrue(store.value!!.connections.flatMap { listOf(it.left.pageId, it.right.pageId) }
                .groupingBy { it }.eachCount().values.all { it <= 2 })
        } finally { dir.deleteRecursively() }
    }

}
