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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTopicsIntegrationTest {
    @Test fun metadataChangesKeepAutomaticAnalysisPendingWhileBulkWorkIsDeferred() = runBlocking {
        val dir = Files.createTempDirectory("automatic-metadata-work").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("a", "Forest ecosystems",
                listOf("Forest ecosystems sustain diverse wildlife populations through habitats and connected food sources.")), null, Instant.EPOCH))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), topicStore = Store())
            index.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertFalse("Initial analysis must be complete", controller.hasPendingWork)
            controller.reconcile(listOf(com.example.notionreader.domain.ReaderIndexEntry("a", "Forest ecosystems", kind = "Research")), false)
            assertTrue("Changed eligible metadata must arrange a future analysis window", controller.hasPendingWork)
            controller.reconcile(listOf(com.example.notionreader.domain.ReaderIndexEntry("a", "Forest ecosystems", kind = "Research")), true)
            assertFalse(controller.hasPendingWork)
        } finally { dir.deleteRecursively() }
    }
    @Test fun unfinishedDiscoveryRequestsItsNextRoundWithoutAContinueAction() = runBlocking {
        val dir = Files.createTempDirectory("automatic-discovery-rounds").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val complete = CompletableDeferred<TopicSnapshot>()
            val stored = Store()
            val provider = object : EmbeddingProvider {
                override val identity = EmbeddingIdentity("automatic-rounds", 2)
                override val tokenizer = Provider().tokenizer
                override suspend fun embed(text: String): List<Float> {
                    val number = Regex("sample (\\d)").find(text)!!.groupValues[1].toDouble()
                    val angle = Math.toRadians(number * 3)
                    return listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                }
            }
            repeat(6) { i ->
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts("n$i", "Forest ecosystems",
                    listOf("Forest ecosystems support wildlife through diverse habitats and food sources; sample $i.")), null, Instant.EPOCH))
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = stored, topicCheckpointStore = FileTopicStore(dir.resolve("checkpoint")),
                formationWorkBudget = 1)
            index.setPaused(false)
            var rounds = 0
            // Observe the controller's real wake signal, as its foreground owner does.
            // No Resume, refresh, source edit, or repeated test-driven reconcile call supplies another wake.
            val owner = launch {
                controller.revision.collect {
                    rounds++
                    controller.reconcile(emptyList(), true)
                    stored.value?.takeIf { it.complete && it.connectionPassComplete }?.let { complete.complete(it) }
                }
            }
            try {
                val result = try { withTimeout(10_000) { complete.await() } }
                    catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("Automatic discovery stalled after $rounds rounds: ${controller.state.value.topicStatus}; ${controller.state.value.message}; indexed=${controller.state.value.indexedCount}; generation=${stored.value?.generation}", timeout)
                    }
                assertTrue("Fixture must require automatic continuation across multiple slices", rounds > 1)
                assertTrue(result.complete)
                assertTrue(result.connectionPassComplete)
                assertTrue(result.topics.isNotEmpty())
                assertEquals(6, result.topics.flatMap { it.members }.map { it.pageId }.distinct().size)
                assertTrue("Connections must finish too", result.connections.isNotEmpty())
            } finally { owner.cancelAndJoin() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun theActiveModelPolicyControlsFormationAndMembershipRatherThanLegacyDefaults() = runBlocking {
        val dir = Files.createTempDirectory("model-topic-policy").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val tokenizer = Provider().tokenizer
            val provider = object : EmbeddingProvider {
                override val identity = EmbeddingIdentity("distinct-space", 2)
                override val tokenizer = tokenizer
                override suspend fun embed(text: String) = if ("woodland" in text) listOf(.8f, .6f) else listOf(1f, 0f)
            }
            for ((id, text) in listOf("a" to "Forest ecology protects animal habitats and supports diverse wildlife populations throughout changing seasons.",
                "b" to "Wildlife management protects woodland habitats and sustains animal populations through careful conservation planning.")) {
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, if (id == "a") "Forest ecology" else "Wildlife management", listOf(text)), null, Instant.EPOCH))
            }
            val store = Store()
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                topicStore = store, topicPolicy = TopicPolicy(formationDistance = .1f, membership = .9f))
            index.setPaused(false)
            controller.reconcile(emptyList(), true)
            controller.refreshTopics()
            val candidates = controller.state.value.topicSnapshot.let { it.topics + it.provisionalTopics }
            assertTrue(candidates.isNotEmpty())
            assertTrue("Looser legacy clustering must not merge model-separated evidence", candidates.all { it.members.size == 1 })
            assertEquals(setOf("a", "b"), candidates.flatMap { it.members }.map { it.pageId }.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aPageRequestedAgainstAnOlderCacheRevisionCannotJoinANewerWindow() = runBlocking {
        val dir = Files.createTempDirectory("catalog-page-revision").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val documents = listOf("a", "b").map { id -> ReaderDocument.fromChunkTexts(id, id,
                listOf("Forest ecosystems protect woodland habitat and sustain diverse wildlife populations.")) }
            documents.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
            fun member(doc: ReaderDocument): TopicMembership {
                val text = doc.sections.flatMap { it.chunks }.single().text
                val p = SemanticPassage("${doc.pageId}:p", doc.pageId, "s", "Ecology", "b", "c", 0, text.length, text, text, 0)
                return TopicMembership(doc.pageId, SemanticMetadata(doc.title), p, .9f, p, semanticRevision(doc))
            }
            val pair = NoteConnection("pair", member(documents[0]), member(documents[1]), .9f)
            val controller = DiscoveryController(cache, FileSemanticIndexStore(dir.resolve("index")),
                FileDiscoveryInterestStore(dir.resolve("interests")), Provider())
            val oldRevision = cache.revision.value
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("other", "Other", listOf("Unrelated material.")), null, Instant.EPOCH))
            val page = controller.connectionWindow(TopicSnapshot(connections = listOf(pair)), expectedCacheRevision = oldRevision)
            assertFalse(page.available)
            assertTrue(page.connections.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun theControllerKeepsAPreviewAndLoadsHiddenConnectionsThroughTheirPinnedReference() = runBlocking {
        val dir = Files.createTempDirectory("controller-paged-catalog").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider()
            val pairs = (0 until 70).map { n ->
                suspend fun member(id: String): TopicMembership {
                    val text = "Forest ecosystems protect woodland habitat and sustain diverse wildlife populations."
                    val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                    cache.put(CachedDocument(doc, null, Instant.EPOCH))
                    val p = SemanticPassage("$id:p", id, "$id:s", "Ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                    val revision = semanticRevision(doc)
                    index.write(SemanticRecord(id, revision, provider.identity, SemanticMetadata(id),
                        listOf(EmbeddedPassage(p, listOf(1f, 0f)))))
                    return TopicMembership(id, SemanticMetadata(id), p, .9f, p, revision)
                }
                NoteConnection("pair${n.toString().padStart(3, '0')}", member("a$n"), member("b$n"), .9f)
            }
            val store = FileTopicStore(dir.resolve("topics"))
            val sources = pairs.flatMap { listOf(it.left, it.right) }.associate { it.pageId to it.revision }
            assertTrue(store.write(TopicSnapshot(connections = pairs, identity = provider.identity, acceptedRevisions = sources, generation = 1)))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider, topicStore = store)
            controller.reconcile(emptyList(), false)
            val inspected = controller.state.value.topicSnapshot
            assertEquals(2, inspected.connections.size)
            assertEquals(70, inspected.catalogReference!!.count)
            assertEquals(pairs.subList(60, 70), controller.connectionWindow(inspected, 60, 20).connections)
            assertEquals(listOf(pairs.last()), controller.connectionWindow(inspected, selectedId = "pair069").connections)
            val removingStore = object : TopicStore by store {
                override suspend fun readMetadata(): TopicSnapshot? {
                    val result = store.readMetadata()
                    // Removal arrives after the new controller enumerated sources; it is hidden beyond both previews.
                    cache.remove("a69")
                    return result
                }
            }
            val restarted = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = removingStore)
            restarted.reconcile(emptyList(), false)
            assertEquals(69, restarted.state.value.topicSnapshot.catalogReference!!.count)
            assertTrue(store.findConnection(inspected, "pair069").connections.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun anAcknowledgedButUnreadableGenerationCannotReplaceTheInspectedSnapshot() = runBlocking {
        val dir = Files.createTempDirectory("unreadable-generation").toFile()
        try {
            val model = EmbeddingIdentity("unreadable", 2)
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val text = "Forest ecosystems protect woodland habitat and sustain diverse wildlife populations."
            for (id in listOf("a", "b")) {
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c", 0, text.length, text, text, 0)
                index.write(SemanticRecord(id, "r", model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f)))))
            }
            index.setPaused(false)
            val prior = TopicSnapshot(identity = model, generation = 1)
            var reads = 0
            val store = object : TopicStore {
                override suspend fun read() = if (reads++ == 0) prior else null
                override suspend fun write(snapshot: TopicSnapshot) = true
                override suspend fun clear() = Unit
                override suspend fun bytes() = 0L
            }
            var shown = TopicView()
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, { shown = it }, formationWorkBudget = 25_000)
            val sources = mapOf("a" to "r", "b" to "r")
            worker.invalidate(sources); worker.build(sources, 0, cache.revision.value)
            assertEquals(prior, shown.snapshot)
            assertTrue(shown.status.orEmpty().contains("could not be reloaded"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aLargeConnectionScanCheckpointsInsideAPassageAndResumesAfterRestart() = runBlocking {
        val dir = Files.createTempDirectory("target-cursor").toFile()
        try {
            val model = EmbeddingIdentity("target-cursor", 2)
            val records = (0 until 270).associate { n ->
                val id = "p${n.toString().padStart(3, '0')}"
                val text = "Forest ecosystems protect woodland soil and provide habitat for diverse wildlife."
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c", 0, text.length, text, text, 0)
                id to SemanticRecord(id, "r", model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
            }
            var reads = 0
            val index = object : SemanticIndexStore {
                override suspend fun pageIds() = records.keys
                override suspend fun read(pageId: String): SemanticRecord? { reads++; return records[pageId] }
                override suspend fun write(record: SemanticRecord) = error("No embedding rewrite is permitted")
                override suspend fun remove(pageId: String) = error("No removal expected")
                override suspend fun clear() = error("No clear expected")
                override suspend fun bytes() = 0L
                override suspend fun isPaused() = false
                override suspend fun setPaused(paused: Boolean) = Unit
            }
            val cache = FileDocumentCache(dir.resolve("cache"))
            val store = Store()
            val sources = records.mapValues { "r" }
            fun worker() = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 25_000)
            var coordinator = worker()
            coordinator.invalidate(sources); coordinator.build(sources, 0, cache.revision.value)
            assertTrue("One quantum must not scan the entire library for 256 seed passages; observed $reads reads", reads <= 527)
            val first = store.value!!
            assertEquals(256, first.connections.size)
            assertEquals(0, first.connectionCursor)
            assertEquals(0, first.connectionPassageCursor)
            assertTrue(coordinator.needsMoreWork)
            reads = 0
            coordinator = worker()
            coordinator.invalidate(sources); coordinator.build(sources, 0, cache.revision.value)
            val resumed = store.value!!
            assertTrue("Resumed work also has a bounded target quantum", reads <= 258)
            assertTrue(resumed.connections.map { it.id }.containsAll(first.connections.map { it.id }))
            assertTrue(resumed.connections.any { it.left.pageId == "p000" && it.right.pageId == "p269" })
            assertEquals(1, resumed.connectionCursor)
        } finally { dir.deleteRecursively() }
    }

    @Test fun referenceOnlyPassagesResumeBeyondTheConnectionWorkQuantum() = runBlocking {
        val dir = Files.createTempDirectory("reference-resume").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("reference-resume", 2)
            fun passage(id: String, n: Int, text: String) = SemanticPassage("$id:p${n.toString().padStart(3, '0')}",
                id, "$id:s", null, "$id:b$n", "$id:c$n", 0, text.length, text, text, n)
            index.write(SemanticRecord("a", "r", model, SemanticMetadata("Source"), (0..269).map { n ->
                EmbeddedPassage(passage("a", n, if (n == 269) "See Target." else "Short instruction."), listOf(1f, 0f))
            }))
            index.write(SemanticRecord("b", "r", model, SemanticMetadata("Target"),
                listOf(EmbeddedPassage(passage("b", 0, "Literal target context."), listOf(0f, 1f)))))
            index.setPaused(false)
            val sources = mapOf("a" to "r", "b" to "r")
            val store = Store()
            fun coordinator() = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 250_000)
            val initial = coordinator()
            initial.invalidate(sources)
            initial.build(sources, 0, cache.revision.value)
            assertFalse(store.value!!.connectionPassComplete)
            assertTrue("Late references must await their durable candidate opportunity", store.value!!.connections.isEmpty())
            val restarted = coordinator()
            restarted.invalidate(sources)
            restarted.build(sources, 0, cache.revision.value)
            val pair = store.value!!.connections.single()
            assertTrue(pair.independentNamedReference)
            assertEquals("See Target.", pair.left.passage.excerpt)
            assertTrue(store.value!!.connectionPassComplete)
        } finally { dir.deleteRecursively() }
    }

    @Test fun referenceCatalogRetainsEveryValidatedPairBeyondPreviewExposureLimits() = runBlocking {
        val dir = Files.createTempDirectory("reference-exposure").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("references", 6)
            val sources = (0..5).associate { n ->
                val id = "p$n"
                val text = if (n == 0) (1..5).joinToString(" ") { "See Target $it." } else "Target context."
                val p = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                index.write(SemanticRecord(id, "r", model, SemanticMetadata(if (n == 0) "Source" else "Target $n"),
                    listOf(EmbeddedPassage(p, List(6) { if (it == n) 1f else 0f }))))
                id to "r"
            }
            index.setPaused(false)
            val store = Store()
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 25_000)
            worker.invalidate(sources)
            worker.build(sources, 0, cache.revision.value)
            assertEquals("Every validated reference must remain available", 5, store.value!!.connections.size)
            assertTrue(store.value!!.connections.all { it.independentNamedReference })
            assertEquals(2, selectDiscoveryConnections(store.value!!.connections).size)
            // A completed generation saved by the old engine must also be repaired on load.
            val uncapped = discoverExplicitNoteReferences(sources.keys.map { index.read(it)!! })
            store.value = store.value!!.copy(connections = uncapped)
            val restarted = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 25_000)
            restarted.invalidate(sources)
            assertEquals("Loading a catalog must not apply display caps", 5, store.value!!.connections.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun semanticCatalogIncludesEverySameTopicPairBeyondPerPassageAndPreviewLimits() = runBlocking {
        val dir = Files.createTempDirectory("complete-semantic-catalog").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("catalog", 2)
            val sources = (0..7).associate { n ->
                val id = "p$n"
                val text = "Forest ecosystems protect woodland soil and provide habitats for diverse wildlife."
                val passage = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems",
                    "$id:b", "$id:c", 0, text.length, text, text, 0)
                index.write(SemanticRecord(id, "r", model, SemanticMetadata("Forest $n"),
                    listOf(EmbeddedPassage(passage, listOf(1f, 0f)))))
                id to "r"
            }
            index.setPaused(false)
            val store = Store()
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 25_000)
            worker.invalidate(sources)
            worker.build(sources, 0, cache.revision.value)
            assertEquals(8, store.value!!.topics.single().members.size)
            assertEquals("All 8 choose 2 supported pairs belong to the catalog", 28, store.value!!.connections.size)
            assertEquals(28, store.value!!.connections.map { it.id }.distinct().size)
            assertTrue(store.value!!.connectionPassComplete)
            assertTrue(selectDiscoveryConnections(store.value!!.connections).size < 28)
        } finally { dir.deleteRecursively() }
    }

    @Test fun formationChecksPlaybackEligibilityInsideItsCpuWork() = pausedFormationRemainsResumable(false)

    @Test fun referenceAndSimilarityEvidenceForTheSamePairRemainIndependent() = runBlocking {
        val dir = Files.createTempDirectory("catalog-evidence-kinds").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("catalog-kinds", 2)
            for (id in listOf("a", "b")) {
                val text = if (id == "a") "See Forest B. Forest ecosystems protect woodland soil and provide wildlife habitats."
                    else "Forest ecosystems protect woodland soil and provide habitats for woodland wildlife."
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c", 0, text.length, text, text, 0)
                index.write(SemanticRecord(id, "r", model, SemanticMetadata(if (id == "a") "Forest A" else "Forest B"),
                    listOf(EmbeddedPassage(p, listOf(1f, 0f)))))
            }
            index.setPaused(false)
            val store = Store()
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 25_000)
            worker.invalidate(mapOf("a" to "r", "b" to "r"))
            worker.build(mapOf("a" to "r", "b" to "r"), 0, cache.revision.value)
            assertEquals(2, store.value!!.connections.size)
            assertEquals(setOf(true, false), store.value!!.connections.map { it.independentNamedReference }.toSet())
            assertEquals(2, store.value!!.connections.map { it.id }.distinct().size)
            assertEquals(setOf(ConnectionEvidenceKind.NamedReference, ConnectionEvidenceKind.SimilarPassages),
                store.value!!.connections.map { classifyConnectionEvidence(it, mapOf("a" to "Forest A", "b" to "Forest B")).kind }.toSet())
        } finally { dir.deleteRecursively() }
    }
    @Test fun formationYieldsToAWaitingForegroundQueryInsideItsCpuWork() = pausedFormationRemainsResumable(true)

    private fun pausedFormationRemainsResumable(query: Boolean) = runBlocking {
        val dir = Files.createTempDirectory("formation-pause").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("pause", 2)
            val text = "Forest roots absorb rainfall and protect woodland soil from erosion."
            val passages = (0..128).map { n ->
                val p = SemanticPassage("p:$n", "p", "s$n", "Ecology", "b$n", "c$n", 0, text.length, text, text, n)
                EmbeddedPassage(p, listOf(1f, 0f))
            }
            backing.write(SemanticRecord("p", "r", model, SemanticMetadata("Forest"), passages))
            backing.setPaused(false)
            var inFormation = false
            var pauseRequested = true
            var observedEligibilityChecks = 0
            val store = Store()
            val worker = TopicCoordinator(cache, backing, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), {
                    if (!query && inFormation && pauseRequested) { observedEligibilityChecks++; false } else true
                }, {}, { if (it.status == "Finding topics in all indexed cached Notes…") inFormation = true },
                formationWorkBudget = 25_000, shouldYield = {
                    if (query && inFormation && pauseRequested) { observedEligibilityChecks++; true } else false
                })
            worker.invalidate(mapOf("p" to "r"))
            worker.build(mapOf("p" to "r"), 0, cache.revision.value)
            assertTrue("CPU formation must observe playback/background eligibility", observedEligibilityChecks > 0)
            assertNull("Paused formation must preserve the prior published generation", store.value)
            assertTrue("Paused work must remain resumable", worker.needsMoreWork)
            pauseRequested = false
            worker.build(mapOf("p" to "r"), 0, cache.revision.value)
            assertTrue("The same worker must finish when playback/background pressure clears", store.value!!.complete)
            assertTrue(store.value!!.connectionPassComplete)
        } finally { dir.deleteRecursively() }
    }
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
                kotlinx.coroutines.sync.Mutex(), { true }, {}, { shown = it }, formationWorkBudget = 25_000)
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
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 1)
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

    @Test fun anExpiredProcessingWindowPersistsFormationWorkForAFreshCoordinator() = runBlocking {
        val dir = Files.createTempDirectory("formation-window-progress").toFile()
        try {
            val model = EmbeddingIdentity("formation-window", 2)
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store()
            val checkpoint = FileTopicStore(dir.resolve("checkpoint"))
            val sources = (0..24).associate { "n$it" to "r1" }
            for ((position, page) in sources.keys.withIndex()) {
                val text = "Forest ecosystems support wildlife through diverse habitats and food sources."
                val passage = SemanticPassage("$page:p", page, "$page:s", "Forest Ecosystems", "$page:b", "$page:c",
                    0, text.length, text, text, 0)
                val angle = Math.toRadians(position * 2.0)
                index.write(SemanticRecord(page, "r1", model, SemanticMetadata(page),
                    listOf(EmbeddedPassage(passage, listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())))))
            }
            index.setPaused(false)
            var eligible = true
            fun coordinator() = TopicCoordinator(cache, index, store, checkpoint, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { eligible }, {}, {}, shouldYield = { eligible = false; true })
            val first = coordinator()
            first.invalidate(sources)
            first.build(sources, 0, cache.revision.value)
            assertTrue(first.needsMoreWork)
            assertNull("Partial formation must not replace the published generation", store.value)
            val saved = checkpoint.read()?.formationProgress
            assertNotNull("Completed comparisons survive the expired window", saved)
            assertEquals(64L, saved!!.comparisonsCompleted)
            eligible = true
            val restarted = coordinator()
            restarted.invalidate(sources)
            restarted.build(sources, 0, cache.revision.value)
            assertEquals(128L, checkpoint.read()!!.formationProgress!!.comparisonsCompleted)
        } finally { dir.deleteRecursively() }
    }

    @Test fun completedFormationSurvivesAnInterruptedConnectionHandoff() = runBlocking {
        val dir = Files.createTempDirectory("formation-handoff").toFile()
        try {
            val model = EmbeddingIdentity("formation-handoff", 2)
            val cache = FileDocumentCache(dir.resolve("cache"))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store()
            val checkpoint = FileTopicStore(dir.resolve("checkpoint"))
            val sources = listOf("a", "b").associateWith { "r1" }
            for (page in sources.keys) {
                val text = "Forest ecosystems support wildlife through diverse habitats and food sources."
                val passage = SemanticPassage("$page:p", page, "$page:s", "Forest Ecosystems", "$page:b", "$page:c",
                    0, text.length, text, text, 0)
                backing.write(SemanticRecord(page, "r1", model, SemanticMetadata(page),
                    listOf(EmbeddedPassage(passage, listOf(1f, 0f)))))
            }
            backing.setPaused(false)
            var eligible = true
            var reads = 0
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? {
                    if (++reads > sources.size) eligible = false
                    return backing.read(pageId)
                }
            }
            val first = TopicCoordinator(cache, index, store, checkpoint, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { eligible }, {}, {})
            first.invalidate(sources)
            first.build(sources, 0, cache.revision.value)
            val completed = checkpoint.read()
            assertNotNull("Completed formation must survive a pause before connections finish", completed)
            assertTrue(completed!!.complete)
            assertNull(completed.formationProgress)
            eligible = true
            val restarted = TopicCoordinator(cache, backing, store, checkpoint, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { eligible }, {}, {})
            restarted.invalidate(sources)
            restarted.build(sources, 0, cache.revision.value)
            assertEquals("A completed formation must be reused rather than regenerated", completed.topics, store.value!!.topics)
            assertTrue(store.value!!.connectionPassComplete)
            assertNull(checkpoint.read())
        } finally { dir.deleteRecursively() }
    }

    @Test fun sameGenerationFormationSlicesReadEachIndexedNoteOnlyOnce() = runBlocking {
        val dir = Files.createTempDirectory("formation-workspace").toFile()
        try {
            val model = EmbeddingIdentity("formation-workspace", 2)
            val cache = FileDocumentCache(dir.resolve("cache"))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val sources = (0..5).associate { "n$it" to "r1" }
            for ((position, page) in sources.keys.withIndex()) {
                val text = "Forest ecosystems support wildlife through diverse habitats and food sources."
                val passage = SemanticPassage("$page:p", page, "$page:s", "Forest Ecosystems", "$page:b", "$page:c",
                    0, text.length, text, text, 0)
                val angle = Math.toRadians(position * 2.0)
                backing.write(SemanticRecord(page, "r1", model, SemanticMetadata(page), listOf(EmbeddedPassage(passage,
                    listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())))))
            }
            backing.setPaused(false)
            var reads = 0
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? { reads++; return backing.read(pageId) }
            }
            val coordinator = TopicCoordinator(cache, index, Store(), FileTopicStore(dir.resolve("checkpoint")),
                { model }, TopicPolicy(), kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 1)
            coordinator.invalidate(sources)
            coordinator.build(sources, 0, cache.revision.value)
            coordinator.build(sources, 0, cache.revision.value)
            assertEquals("A quantum must not reread the whole unchanged library", sources.size, reads)
            coordinator.releaseMemory()
            coordinator.build(sources, 0, cache.revision.value)
            assertEquals("Resource release must discard retained indexed records", sources.size * 2, reads)
            val changed = backing.read("n0")!!.copy(revision = "r2")
            backing.write(changed)
            coordinator.build(sources + ("n0" to "r2"), 0, cache.revision.value)
            assertEquals("A changed source signature must prepare the new complete library", sources.size * 3, reads)
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
            assertEquals(1L, store.value!!.generation)
            controller.reconcile(entries.reversed(), true)
            assertEquals("Browse order is not a semantic input", 1L, store.value!!.generation)
            controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = store, topicCheckpointStore = Store())
            controller.reconcile(entries + com.example.notionreader.domain.ReaderIndexEntry("uncached", "Other", null), true)
            assertEquals("A new coordinator resumes unchanged cached inputs despite uncached changes", 1L, store.value!!.generation)
            val updated = entries.map { if (it.pageId == "p0") it.copy(title = "Updated forest title") else it }
            controller.reconcile(updated, true)
            assertEquals("Changed cached metadata must still start a fresh reconciliation", 2L, store.value!!.generation)
            assertEquals("Updated forest title", index.read("p0")!!.metadata.title)
        } finally { dir.deleteRecursively() }
    }
    @Test fun pausingAConnectionQuantumCannotPublishAnEmptyReplacement() = runBlocking {
        val dir = Files.createTempDirectory("connection-pause").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("connection-pause", 2)
            val text = "It is what it is and we are who we are."
            fun record(id: String): SemanticRecord {
                val p = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                return SemanticRecord(id, "r", model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
            }
            val a = record("a"); val b = record("b")
            backing.write(a); backing.write(b); backing.setPaused(false)
            fun member(r: SemanticRecord) = TopicMembership(r.pageId, r.metadata, r.passages.single().passage, 1f,
                r.passages.single().passage, "r")
            val prior = NoteConnection("prior", member(a), member(b), 1f)
            val store = Store().apply { value = TopicSnapshot(identity = model, complete = true,
                connections = listOf(prior), acceptedRevisions = mapOf("a" to "r", "b" to "r")) }
            var reads = 0
            var pauseOnce = false
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? {
                    if (pageId == "a" && ++reads == 2) pauseOnce = true
                    return backing.read(pageId)
                }
            }
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { if (pauseOnce) { pauseOnce = false; false } else true }, {}, {})
            val sources = mapOf("a" to "r", "b" to "r")
            worker.invalidate(sources); worker.build(sources, 0, cache.revision.value)
            assertEquals(listOf(prior), store.value!!.connections)
            assertTrue(worker.needsMoreWork)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRestartResumesConnectionsBeyondTheFirstThreeSectionsInsideANote() = runBlocking {
        val dir = Files.createTempDirectory("late-connection-resume").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("late-connection", 2)
            val text = "It is what it is and we are who we are."
            fun evidence(page: String, n: Int, vector: List<Float>): EmbeddedPassage {
                val id = "$page:${n.toString().padStart(4, '0')}"
                return EmbeddedPassage(SemanticPassage(id, page, "$id:s", null, "$id:b", "$id:c",
                    0, text.length, text, text, n), vector)
            }
            index.write(SemanticRecord("a", "r", model, SemanticMetadata("A"),
                (0 until 256).map { evidence("a", it, listOf(1f, 0f)) } + evidence("a", 256, listOf(0f, 1f))))
            index.write(SemanticRecord("b", "r", model, SemanticMetadata("B"), listOf(evidence("b", 0, listOf(0f, 1f)))))
            index.setPaused(false)
            val store = Store()
            fun worker() = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            val sources = mapOf("a" to "r", "b" to "r")
            val first = worker(); first.invalidate(sources); first.build(sources, 0, cache.revision.value)
            assertTrue(store.value!!.connections.isEmpty())
            assertEquals(0, store.value!!.connectionCursor)
            assertEquals(256, store.value!!.connectionPassageCursor)
            assertFalse(store.value!!.connectionPassComplete)
            val restarted = worker(); restarted.invalidate(sources); restarted.build(sources, 0, cache.revision.value)
            assertEquals(1, store.value!!.connections.size)
            assertEquals("a:0256", store.value!!.connections.single().left.passage.id)
            assertTrue(store.value!!.connectionPassComplete)
        } finally { dir.deleteRecursively() }
    }

    @Test fun supportedIndividualConnectionsDoNotRequireEitherNoteToHaveATopic() = runBlocking {
        val dir = Files.createTempDirectory("ungrouped-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            for (id in listOf("a", "b")) cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, "Forest $id",
                // Controlled vectors with no publishable subject phrase, not a human quality corpus.
                listOf("It is what it is and we are who we are.")), null, Instant.EPOCH))
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
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, { view = it }, formationWorkBudget = 1)
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
            val established = store.value!!.topics.single { it.members.size == sources.size }
            assertEquals(sources.keys, established.members.map { it.pageId }.toSet())
            assertTrue(established.members.all { it.passage.order == 4 })
            assertFalse(coordinator.needsMoreWork)
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
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH)) }
            index.setPaused(false); controller.reconcile(emptyList(), true); controller.refreshTopics()
            val original = controller.state.value.topicSnapshot
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p3", "Forest 3", listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH))
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
            repeat(4) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH)) }
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
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH)) }
            val ungrouped = ReaderDocument.fromChunkTexts("q", "Other", listOf("old private evidence"))
            cache.put(CachedDocument(ungrouped, null, Instant.EPOCH))
            index.setPaused(false); initial.reconcile(emptyList(), true)
            val left = store.value!!.topics.first().members.first()
            val record = index.read("q")!!
            val right = TopicMembership("q", record.metadata, record.passages.first().passage, .8f, left.passage, record.revision)
            store.value = store.value!!.copy(connections = listOf(NoteConnection("pair", left, right, .8f)),
                provisionalTopics = emptyList())
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
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = store, topicCheckpointStore = gated,
                formationWorkBudget = 1)
            val build = async { controller.reconcile(emptyList(), true) }
            withTimeout(5_000) { entered.await() }
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
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH)) }
            index.setPaused(false)
            DiscoveryController(cache, index, interests, Provider(), topicStore = store).reconcile(emptyList(), true)
            // A legacy completed generation has no persisted build signature; it must still be safe.
            store.value = store.value!!.copy(sourceSignature = null)
            val removingStore = object : TopicStore by store {
                override suspend fun readMetadata(): TopicSnapshot? = read()
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
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH)) }
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
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Forest", listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH))
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


}
