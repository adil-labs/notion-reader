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
    private fun conceptController(directory: java.io.File, cache: FileDocumentCache, index: FileSemanticIndexStore,
        provider: EmbeddingProvider): DiscoveryController {
        val identity = TopicLabelIdentity("fixture", "tokens", "template", "prompt", "schema", "policy", "fixture")
        val generator = object : TopicLabelRuntime {
            override suspend fun label(request: TopicLabelRequest) = TopicLabelRun.Completed(TopicLabelCandidate(
                "Forest ecosystems", request.evidence.map { it.passageId }, "Ecological relationships within forests."))
            override suspend fun close() {}
        }
        return DiscoveryController(cache, index, FileDiscoveryInterestStore(directory.resolve("interests")), provider,
            topicStore = Store(), topicLabelRuntime = generator, topicLabelModelAvailable = { true },
            topicLabelIdentity = { identity }, topicLabelStore = FileTopicLabelStore(directory.resolve("labels")),
            topicRetrievalStore = FileTopicRetrievalStore(directory.resolve("matches")))
    }
    @Test fun theCanonicalCatalogUsesTheSharedLabelRuntimeAndRetainsFollowsAcrossRestart() = runBlocking {
        val dir = Files.createTempDirectory("canonical-label-catalog").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val interests = FileDiscoveryInterestStore(dir.resolve("interests")); val graph = Store()
            val labelIdentity = TopicLabelIdentity("a", "t", "c", "p", "s", "v", "GPU")
            var calls = 0
            val native = object : TopicLabelRuntime {
                override suspend fun label(request: TopicLabelRequest): TopicLabelRun {
                    calls++
                    return TopicLabelRun.Completed(TopicLabelCandidate("Forest ecosystems", request.evidence.map { it.passageId }, "Ecological relationships within forests."))
                }
                override suspend fun close() {}
            }
            fun controller() = DiscoveryController(cache, index, interests, Provider(), topicStore = graph,
                topicLabelRuntime = native, topicLabelModelAvailable = { true },
                topicLabelStore = FileTopicLabelStore(dir.resolve("labels")), topicLabelIdentity = { labelIdentity },
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("matches")))
            repeat(2) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i",
                listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH)) }
            val first = controller(); first.resume(); first.reconcile(emptyList(), true)
            val topic = first.state.value.topicSnapshot.topics.single()
            assertEquals("Forest ecosystems", topic.effectiveLabel); assertEquals(1, calls)
            assertEquals(setOf("p0", "p1"), topic.matchingMembers.map { it.pageId }.toSet())
            assertEquals(topic.id, first.state.value.publishedTopics.single().topic.id)
            first.followTopic(topic.id, "My woodland", "forest wildlife")
            val restarted = controller(); restarted.reconcile(emptyList(), false); restarted.refreshTopics()
            assertEquals("Forest ecosystems", restarted.state.value.topicSnapshot.topics.single().effectiveLabel)
            assertEquals(setOf("p0", "p1"), restarted.state.value.topicSnapshot.topics.single().matchingMembers.map { it.pageId }.toSet())
            assertEquals("My woodland", restarted.state.value.interests.single().displayName)
            assertEquals(topic.id, restarted.state.value.interests.single().originTopicId)
            assertEquals(1, calls)
            restarted.clearIndex(); assertEquals(0L, FileTopicLabelStore(dir.resolve("labels")).bytes())
            assertEquals(0L, FileTopicRetrievalStore(dir.resolve("matches")).bytes())
            assertEquals("My woodland", interests.read().single().displayName)
        } finally { dir.deleteRecursively() }
    }
    @Test fun newlyCachedNotesPublishAutomaticallyBeforeAnyDiscoveryViewIsInspected() = runBlocking {
        val dir = Files.createTempDirectory("automatic-topic-publication").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), topicStore = Store())
            suspend fun add(id: String) { cache.put(CachedDocument(ReaderDocument.fromChunkTexts(id, "Forest $id",
                listOf("forest ecology protects wildlife and supports healthy woodland habitats.")), null, Instant.EPOCH)) }
            repeat(3) { add("p$it") }
            controller.resume(); controller.reconcile(emptyList(), true)
            assertEquals(3, controller.state.value.topicSnapshot.topics.single().members.size)
            add("p3")
            repeat(10) { if (controller.hasPendingWork) controller.reconcile(emptyList(), true) }
            assertFalse(controller.hasPendingWork)
            assertEquals(4, controller.state.value.topicSnapshot.topics.single().members.size)
            assertFalse(controller.state.value.topicsUpdated)
            controller.setTopicsInspected(true)
            add("p4")
            repeat(10) { if (controller.hasPendingWork) controller.reconcile(emptyList(), true) }
            assertFalse(controller.hasPendingWork)
            assertEquals(4, controller.state.value.topicSnapshot.topics.single().members.size)
            assertTrue(controller.state.value.topicsUpdated)
            controller.setTopicsInspected(false)
            assertEquals(5, controller.state.value.topicSnapshot.topics.single().members.size)
            assertFalse(controller.state.value.topicsUpdated)
        } finally { dir.deleteRecursively() }
    }
    @Test fun aHundredNoteConnectionSweepResumesAndReconcilesMovesAndRemovalsExactly() = runBlocking {
        val dir = Files.createTempDirectory("hundred-note-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = FileTopicStore(dir.resolve("topics"))
            val checkpoint = FileTopicStore(dir.resolve("checkpoint"))
            val model = EmbeddingIdentity("hundred-note-connections", 10)
            val groups = (0 until 100).associate { "p$it" to it / 10 }.toMutableMap()
            val sources = groups.keys.associateWith { "r1" }.toMutableMap()
            suspend fun record(id: String) {
                val text = "Forest ecosystems support wildlife habitats and woodland biodiversity."
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c",
                    0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, sources.getValue(id), model, SemanticMetadata(id),
                    listOf(EmbeddedPassage(p, List(10) { if (it == groups.getValue(id)) 1f else 0f })))))
            }
            for (id in groups.keys) record(id)
            index.setPaused(false)
            fun worker() = TopicCoordinator(cache, index, store, checkpoint, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            suspend fun complete() {
                var coordinator = worker()
                repeat(80) { round ->
                    if (round % 7 == 0) coordinator = worker()
                    coordinator.invalidate(sources)
                    coordinator.build(sources, 0, cache.revision.value)
                    if (!coordinator.needsMoreWork) {
                        assertTrue(store.read()!!.complete)
                        assertTrue(store.read()!!.connectionPassComplete)
                        return
                    }
                }
                fail("A bounded sweep must complete despite coordinator restarts")
            }
            fun expected() = groups.keys.flatMap { left -> groups.keys.filter { right ->
                left < right && groups[left] == groups[right]
            }.map { right -> setOf(left, right) } }.toSet()
            suspend fun actual() = store.read()!!.connections.map { setOf(it.left.pageId, it.right.pageId) }.toSet()
            complete()
            assertEquals(450, actual().size)
            assertEquals(expected(), actual())
            sources.remove("p99"); groups.remove("p99"); index.remove("p99")
            groups["p0"] = 1; sources["p0"] = "r2"; record("p0")
            val changed = worker(); changed.invalidate(sources)
            assertTrue(store.read()!!.connections.none { it.left.pageId in setOf("p0", "p99") || it.right.pageId in setOf("p0", "p99") })
            complete()
            assertEquals(expected(), actual())
            assertTrue(store.read()!!.connections.all { pair ->
                pair.left.revision == sources[pair.left.pageId] && pair.right.revision == sources[pair.right.pageId]
            })
        } finally { dir.deleteRecursively() }
    }
    @Test fun continuityMemoryLimitReportsIncompleteCoverageInsteadOfInvalidData() = runBlocking {
        val dir = Files.createTempDirectory("continuity-limit-message").toFile()
        try {
            val store = FileTopicStore(dir)
            val prior = TopicSnapshot(generation = 7)
            assertEquals(TopicWriteResult.Written, store.writeDetailed(prior))
            val oversized = prior.copy(continuity = listOf(TopicContinuity("opaque", 1,
                sourceIds = setOf("x".repeat(4 * 1024 * 1024)))))
            val rejected = store.writeDetailed(oversized) as TopicWriteResult.Rejected
            assertEquals(TopicValidationReason.ContinuityBound, rejected.validationReason)
            assertEquals(prior, store.read())
            for (checkpoint in listOf(false, true)) {
                val message = persistenceFailureMessage(rejected, checkpoint)
                assertTrue(message.contains("continuity memory limit"))
                assertTrue(message.contains("coverage is incomplete"))
                assertFalse(message.contains("validation failed"))
                assertFalse(message.contains("Retry processing"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun addingANoteRetainsUnchangedConnectionsWithoutRepeatingTheirPairReads() = runBlocking {
        val dir = Files.createTempDirectory("incremental-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("incremental-connections", 2)
            val store = FileTopicStore(dir.resolve("topics"))
            var reads = 0
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? { reads++; return backing.read(pageId) }
            }
            suspend fun add(id: String) {
                val text = "Forest ecology sustains diverse woodland habitats and wildlife."
                val passage = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                backing.write(SemanticRecord(id, "r1", model, SemanticMetadata(id), listOf(EmbeddedPassage(passage, listOf(1f, 0f)))))
            }
            repeat(8) { add("p$it") }; backing.setPaused(false)
            fun coordinator() = TopicCoordinator(cache, index, store, FileTopicStore(dir.resolve("checkpoint")),
                { model }, TopicPolicy(), kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            val oldSources = (0..7).associate { "p$it" to "r1" }
            val first = coordinator(); first.invalidate(oldSources); first.build(oldSources, 0, cache.revision.value)
            val original = store.read()!!
            assertEquals(28, original.connections.size)
            add("p8")
            val sources = oldSources + ("p8" to "r1")
            val next = coordinator(); next.invalidate(sources); reads = 0
            next.build(sources, 0, cache.revision.value)
            val result = store.read()!!
            assertTrue(result.connectionPassComplete)
            assertEquals(36, result.connections.size)
            assertTrue(result.connections.map { it.id }.containsAll(original.connections.map { it.id }))
            assertTrue("A one-Note delta must not reread all unchanged pairs: $reads reads", reads < 60)
        } finally { dir.deleteRecursively() }
    }

    @Test fun removingANormalizedTitleCollisionDiscoversTheNewlyUnambiguousReference() = runBlocking {
        val dir = Files.createTempDirectory("incremental-reference-titles").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("incremental-reference-titles", 2)
            val store = FileTopicStore(dir.resolve("topics"))
            for ((id, title) in listOf("a" to "Source", "b" to "Alpha", "c" to " alpha ")) {
                val text = if (id == "a") "Open Alpha." else "A short target context."
                val passage = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                index.write(SemanticRecord(id, "r1", model, SemanticMetadata(title), listOf(EmbeddedPassage(passage, listOf(1f, 0f)))))
            }
            index.setPaused(false)
            fun coordinator() = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            val original = listOf("a", "b", "c").associateWith { "r1" }
            val first = coordinator(); first.invalidate(original); first.build(original, 0, cache.revision.value)
            assertTrue(store.read()!!.connections.none { it.independentNamedReference })
            index.remove("c")
            val current = original - "c"
            val restarted = coordinator(); restarted.invalidate(current); restarted.build(current, 0, cache.revision.value)
            assertTrue("Removing a case/whitespace title collision must revisit the surviving target",
                store.read()!!.connections.any { it.independentNamedReference && setOf(it.left.pageId, it.right.pageId) == setOf("a", "b") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun topicReuseCertificatesSurviveTheShippingStoreAndCoordinatorRestart() = runBlocking {
        val dir = Files.createTempDirectory("durable-incremental-topics").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("durable-incremental-topics", 2)
            val policy = TopicPolicy(formationDistance = .1f, membership = .9f)
            suspend fun add(id: String, degrees: Double) {
                val text = "Forest ecology sustains wildlife habitats and protects woodland ecosystems."
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                val angle = Math.toRadians(degrees)
                index.write(SemanticRecord(id, "r1", model, SemanticMetadata(id), listOf(EmbeddedPassage(p,
                    listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())))))
            }
            repeat(6) { add("p$it", it.toDouble()) }; index.setPaused(false)
            val oldSources = (0..5).associate { "p$it" to "r1" }
            val store = FileTopicStore(dir.resolve("topics"))
            val first = TopicCoordinator(cache, index, store, null, { model }, policy,
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            first.invalidate(oldSources); first.build(oldSources, 0, cache.revision.value)
            assertTrue(store.read()!!.formationReuse!!.groups.any { it.size == 6 })
            add("new", 150.0)
            val sources = oldSources + ("new" to "r1")
            val checkpoint = FileTopicStore(dir.resolve("checkpoint"))
            val restarted = TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), checkpoint,
                { model }, policy, kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, formationWorkBudget = 1)
            restarted.invalidate(sources); restarted.build(sources, 0, cache.revision.value)
            val progress = FileTopicStore(dir.resolve("checkpoint")).read()!!.formationProgress!!
            assertTrue("Process recreation must preserve the unchanged topic group", progress.clusters.any { it.size == 6 })
        } finally { dir.deleteRecursively() }
    }

    @Test fun addingANoteDuringAPartialConnectionSweepDoesNotRestartCompletedSeeds() = runBlocking {
        val dir = Files.createTempDirectory("incremental-partial-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("incremental-partial-connections", 2)
            val store = FileTopicStore(dir.resolve("topics"))
            suspend fun add(id: String) {
                val passages = (0..1).map { n ->
                    val text = "Forest ecology sustains wildlife habitats in woodland ecosystem $n."
                    val p = SemanticPassage("$id:p$n", id, "$id:s$n", "Forest ecology", "$id:b$n", "$id:c$n", 0, text.length, text, text, n)
                    EmbeddedPassage(p, listOf(1f, 0f))
                }
                index.write(SemanticRecord(id, "r1", model, SemanticMetadata(id), passages))
            }
            repeat(12) { add("p" + it.toString().padStart(2, '0')) }; index.setPaused(false)
            fun coordinator() = TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null,
                { model }, TopicPolicy(), kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            val original = (0..11).associate { "p" + it.toString().padStart(2, '0') to "r1" }
            val first = coordinator(); first.invalidate(original); first.build(original, 0, cache.revision.value)
            assertFalse("The fixture must cross the 256-candidate window", store.read()!!.connectionPassComplete)
            add("new")
            val sources = original + ("new" to "r1")
            val restarted = coordinator(); restarted.invalidate(sources); restarted.build(sources, 0, cache.revision.value)
            val result = store.read()!!
            assertTrue("A small delta plus the unfinished seed fits one window without replaying completed seeds", result.connectionPassComplete)
            assertEquals(78, result.connections.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun metadataUpdatesReplaceAffectedConnectionEvidenceEvenWhenItsScoreIsUnchanged() = runBlocking {
        val dir = Files.createTempDirectory("incremental-connection-metadata").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("incremental-connection-metadata", 2)
            val store = FileTopicStore(dir.resolve("topics"))
            for (id in listOf("a", "b")) {
                val text = "Forest ecology sustains wildlife habitats and protects woodland ecosystems."
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                index.write(SemanticRecord(id, "r1", model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f)))))
            }
            index.setPaused(false)
            fun coordinator() = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {})
            val sources = mapOf("a" to "r1", "b" to "r1")
            val first = coordinator(); first.invalidate(sources); first.build(sources, 0, cache.revision.value)
            assertEquals(1, store.read()!!.connections.size)
            index.write(index.read("b")!!.copy(metadata = SemanticMetadata("Renamed target")))
            val restarted = coordinator(); restarted.invalidate(sources); restarted.build(sources, 1, cache.revision.value)
            val pair = store.read()!!.connections.single()
            assertEquals("Current metadata replaces equal-score stale evidence", "Renamed target",
                listOf(pair.left, pair.right).single { it.pageId == "b" }.metadata.title)
        } finally { dir.deleteRecursively() }
    }

    @Test fun verifiedIndexCoverageDoesNotWaitForSavedDiscoveryRestoration() = runBlocking {
        val dir = Files.createTempDirectory("discovery-coverage-before-restoration").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); index.setPaused(false)
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            val provider = Provider()
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Forest",
                listOf("Forest ecology supports diverse woodland habitats.")), null, Instant.EPOCH))
            DiscoveryController(cache, index, interests, provider).reconcile(emptyList(), true)
            val calls = provider.calls
            val entered = CompletableDeferred<Unit>()
            val store = object : TopicStore by Store() {
                override suspend fun readMetadata(): TopicSnapshot? { entered.complete(Unit); release.await(); return null }
            }
            val controller = DiscoveryController(cache, index, interests, provider, topicStore = store)
            val work = async { controller.reconcile(emptyList(), false) }
            try {
                withTimeout(5_000) { entered.await() }
                assertEquals("Verified existing records must be visible while saved discoveries load", 1, controller.state.value.indexedCount)
                assertEquals(DiscoveryCoverageStatus.Ready, controller.state.value.indexCoverage)
                assertEquals(DiscoveryProcessingStatus.LoadingSavedDiscoveries, controller.state.value.processingStatus)
                assertEquals(calls, provider.calls)
            } finally { release.complete(Unit); work.await() }
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }

    @Test fun savedIndexingPermissionIsVisibleBeforeSavedDiscoveriesFinishLoading() = runBlocking {
        val dir = Files.createTempDirectory("discovery-pause-readiness").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            index.setPaused(false)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Cached", listOf("Already opened content.")), null, Instant.EPOCH))
            val entered = CompletableDeferred<Unit>()
            val store = object : TopicStore by Store() {
                override suspend fun readMetadata(): TopicSnapshot? { entered.complete(Unit); release.await(); return null }
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = store)
            val work = async { controller.reconcile(emptyList(), false) }
            try {
                withTimeout(5_000) { entered.await() }
                assertEquals(DiscoveryCacheStatus.Ready, controller.state.value.cacheStatus)
                assertFalse("Known inventory must publish the saved indexing permission rather than the startup pause default", controller.state.value.paused)
                assertEquals("Saved discovery restoration is distinct from index verification",
                    DiscoveryProcessingStatus.LoadingSavedDiscoveries, controller.state.value.processingStatus)
            } finally { release.complete(Unit); work.await() }
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }

    @Test fun pausingWhileSavedDiscoveriesLoadCannotBeOverwrittenByReconciliation() = runBlocking {
        val dir = Files.createTempDirectory("discovery-pause-during-startup").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); index.setPaused(false)
            val entered = CompletableDeferred<Unit>()
            val store = object : TopicStore by Store() {
                override suspend fun readMetadata(): TopicSnapshot? { entered.complete(Unit); release.await(); return null }
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = store)
            val work = async { controller.reconcile(emptyList(), false) }
            try {
                withTimeout(5_000) { entered.await() }
                controller.pause(); release.complete(Unit); work.await()
                assertTrue(index.isPaused())
                assertTrue("Reconciliation cannot replace the user's newer pause command", controller.state.value.paused)
            } finally { release.complete(Unit); work.await() }
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }

    @Test fun finishedBackgroundWorkIsReadyWhileAnOlderPartialGenerationRemainsInspected() = runBlocking {
        val dir = Files.createTempDirectory("discovery-actual-work-status").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); index.setPaused(false)
            repeat(12) { n -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$n", "Forest $n", listOf(
                "Forest ecology sustains diverse wildlife habitats and woodland ecosystems.",
                "Forest ecology protects river habitats and supports diverse wildlife populations.")), null, Instant.EPOCH)) }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = Store())
            controller.setTopicsInspected(true)
            controller.reconcile(emptyList(), true)
            assertTrue(controller.hasPendingWork)
            repeat(10) { if (controller.hasPendingWork) controller.reconcile(emptyList(), true) }
            assertFalse(controller.hasPendingWork)
            assertTrue(controller.state.value.topicsUpdated)
            assertFalse("Inspected coverage stays pinned until Refresh", controller.state.value.topicSnapshot.connectionPassComplete)
            controller.updateWorkAvailability(com.example.notionreader.domain.settings.DiscoveryEffort.BALANCED,
                DiscoveryExecutionAvailability.WaitingForPlayback)
            assertEquals(DiscoveryProcessingStatus.Ready, controller.state.value.processingStatus)
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectedDiscoveryStorageDoesNotTurnIncompleteCoverageIntoReady() = runBlocking {
        val dir = Files.createTempDirectory("discovery-failed-work-status").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); index.setPaused(false)
            repeat(3) { n -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$n", "Forest $n", listOf(
                "Forest ecology sustains diverse wildlife habitats and woodland ecosystems.")), null, Instant.EPOCH)) }
            val store = object : TopicStore by Store() {
                override suspend fun writeDetailed(snapshot: TopicSnapshot) = TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded)
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = store)
            controller.reconcile(emptyList(), true)
            assertTrue(controller.state.value.topicStatus.orEmpty().contains("storage"))
            assertNotEquals("A stopped worker is not evidence of complete coverage", DiscoveryProcessingStatus.Ready,
                controller.state.value.processingStatus)
        } finally { dir.deleteRecursively() }
    }

    @Test fun explicitPauseDoesNotPreventValidatingExistingIndexedCoverage() = runBlocking {
        val dir = Files.createTempDirectory("discovery-paused-coverage").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); index.setPaused(false)
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Forest", listOf(
                "Forest ecology sustains diverse woodland habitats.")), null, Instant.EPOCH))
            val provider = Provider()
            val interests = FileDiscoveryInterestStore(dir.resolve("interests"))
            DiscoveryController(cache, index, interests, provider).reconcile(emptyList(), true)
            index.setPaused(true)
            val restarted = DiscoveryController(cache, index, interests, provider)
            restarted.reconcile(emptyList(), true)
            assertTrue(restarted.state.value.paused)
            assertEquals("Paused indexing still reports the validated cached index", 1, restarted.state.value.indexedCount)
            assertEquals(DiscoveryCoverageStatus.Ready, restarted.state.value.indexCoverage)
            assertEquals(1, provider.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cachedNotesAreVisibleBeforeSavedDiscoveriesFinishLoading() = runBlocking {
        val dir = Files.createTempDirectory("discovery-cache-inventory").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p", "Already opened", listOf("Cached content.")), null, Instant.EPOCH))
            val entered = CompletableDeferred<Unit>()
            val store = object : TopicStore by Store() {
                override suspend fun readMetadata(): TopicSnapshot? {
                    entered.complete(Unit); release.await(); return null
                }
            }
            val controller = DiscoveryController(cache, FileSemanticIndexStore(dir.resolve("index")),
                FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = store)
            val reconciliation = async { controller.reconcile(emptyList(), false) }
            try {
                withTimeout(5_000) { entered.await() }
                assertEquals("Cached content must not look absent while saved discoveries load", setOf("p"), controller.state.value.cachedPageIds)
                assertEquals(DiscoveryCacheStatus.Ready, controller.state.value.cacheStatus)
            } finally { release.complete(Unit); reconciliation.await() }
        } finally { release.complete(Unit); dir.deleteRecursively() }
    }

    @Test fun clearingDiscoveryRejectsConnectionPagesAlreadyInFlight() = assertResetRejectsRead("page")
    @Test fun clearingDiscoveryRejectsConnectionDetailsAlreadyInFlight() = assertResetRejectsRead("details")
    @Test fun clearingDiscoveryRejectsConnectionPreviewsAlreadyInFlight() = assertResetRejectsRead("preview")

    private fun assertResetRejectsRead(request: String) = runBlocking {
        val dir = Files.createTempDirectory("connection-reset-$request").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val backing = Store()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = object : TopicStore by backing {
                override suspend fun connectionPage(snapshot: TopicSnapshot, offset: Int, limit: Int): ConnectionCatalogPageResult {
                    val captured = backing.connectionPage(snapshot, offset, limit)
                    entered.complete(Unit); release.await()
                    return captured
                }
                override suspend fun findConnection(snapshot: TopicSnapshot, id: String): ConnectionCatalogPageResult {
                    val captured = backing.findConnection(snapshot, id)
                    entered.complete(Unit); release.await()
                    return captured
                }
            }
            repeat(3) { i ->
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf(
                    "Forest ecology explains how trees shelter wildlife and support healthy woodland habitats.")), null, Instant.EPOCH))
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                Provider(), topicStore = store)
            index.setPaused(false)
            controller.reconcile(emptyList(), true)
            val inspected = controller.state.value.topicSnapshot
            assertTrue("The reset proof needs real connection evidence", inspected.connections.isNotEmpty())
            val id = inspected.connections.first().id
            val revision = cache.revision.value
            val read = async {
                when (request) {
                    "page" -> controller.connectionWindow(inspected).connections.size
                    "details" -> controller.connectionWindow(inspected, selectedId = id).connections.size
                    else -> controller.connectionPreview(id, inspected, revision).size
                }
            }
            try {
                withTimeout(5_000) { entered.await() }
                controller.clearIndex()
                assertNull("The fake must preserve destructive clearing", backing.value)
                assertEquals("Index clearing must leave the source cache unchanged", revision, cache.revision.value)
            } finally { release.complete(Unit) }
            assertEquals("Cleared evidence must not return from an in-flight $request read", 0, read.await())
        } finally { dir.deleteRecursively() }
    }

    @Test fun completedDiscoveriesArePreparedBeforeInspectionWithoutMoreInference() = runBlocking {
        val dir = Files.createTempDirectory("prepared-discovery-inspection").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            var inferences = 0
            val provider = object : EmbeddingProvider by Provider() {
                override suspend fun embed(text: String): List<Float> { inferences++; return listOf(1f, 0f) }
            }
            repeat(3) { i ->
                val document = ReaderDocument.fromChunkTexts("p$i", "Forest $i", listOf(
                    "Forest ecology explains how trees shelter wildlife and support healthy woodland habitats."))
                cache.put(CachedDocument(document.copy(sections = document.sections.map {
                    it.copy(title = "Forest ecology") }), null, Instant.EPOCH))
            }
            val controller = conceptController(dir, cache, index, provider)
            index.setPaused(false)
            controller.reconcile(emptyList(), true)
            assertFalse(controller.hasPendingWork)
            val ready = controller.state.value
            assertTrue("Publication must finish before discoveries become inspectable", ready.publishedTopics.isNotEmpty())
            assertTrue("The inspection proof must include discovered connections", ready.topicSnapshot.connections.isNotEmpty())
            assertEquals(publishableDiscoveryTopics(ready.topicSnapshot.topics), ready.publishedTopics.map { it.topic })
            val calls = inferences
            repeat(3) {
                ready.publishedTopics.forEach { assertTrue(controller.topicPreview(it.topic.id).isNotEmpty()) }
                ready.topicSnapshot.connections.forEach { assertEquals(2, controller.connectionPreview(it.id).size) }
            }
            assertEquals("Reading discovered evidence must never invoke the model", calls, inferences)
            controller.reconcile(emptyList(), false)
            assertSame("An unchanged inspected generation reuses its prepared publication", ready.publishedTopics,
                controller.state.value.publishedTopics)
            var advertisedInference = false
            val observer = launch(kotlinx.coroutines.Dispatchers.Unconfined,
                start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                controller.state.collect { if (it.indexing) advertisedInference = true }
            }
            controller.reconcile(emptyList(), true)
            observer.cancelAndJoin()
            assertFalse("A completed generation must not advertise fresh inference during validation", advertisedInference)
            assertEquals(calls, inferences)
            cache.remove("p0")
            controller.reconcile(emptyList(), false)
            assertTrue(controller.state.value.publishedTopics.flatMap { it.topic.members }.none { it.pageId == "p0" })
        } finally { dir.deleteRecursively() }
    }
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
                    TopicWriteFailure.RecordMemoryLimit -> "record memory limit"
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
        var calls = 0
            private set
        override val identity = EmbeddingIdentity("topics-test", 2)
        override val tokenizer = object : SemanticTokenizer {
            override fun tokens(text: String) = Regex("\\S+").findAll(text).map {
                SemanticToken(1, it.range.first, it.range.last + 1)
            }.toList()
        }
        override suspend fun embed(text: String): List<Float> {
            calls++
            return if ("forest" in text) listOf(1f, 0f) else listOf(0f, 1f)
        }
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
            val controller = conceptController(dir, cache, index, Provider())
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
