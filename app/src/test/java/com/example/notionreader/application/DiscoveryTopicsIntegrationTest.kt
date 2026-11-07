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
        provider: EmbeddingProvider, diagnostic: (String, String) -> Unit = { _, _ -> }): DiscoveryController {
        val identity = TopicLabelIdentity("fixture", "tokens", "template", "prompt", "schema", "policy", "fixture")
        val generator = object : TopicLabelRuntime, TopicSupportRuntime, DiscoveryProposalRuntime {
            override suspend fun label(request: TopicLabelRequest) = TopicLabelRun.Completed(TopicLabelCandidate(
                "Forest ecosystems", request.evidence.map { it.passageId }, "Ecological relationships within forests."))
            override suspend fun verifySupport(request: TopicSupportRequest) = TopicSupportRun.Completed(SupportDecision.SUPPORTED)
            override suspend fun propose(request: DiscoveryProposalRequest) = DiscoveryProposalRun.Completed(
                DiscoveryProposalResponse.Concept("Forest ecosystems", "Ecological relationships within forests.",
                    "Forest ecosystems", request.evidence.map { it.passageId }))
            override suspend fun close() {}
        }
        return DiscoveryController(cache, index, FileDiscoveryInterestStore(directory.resolve("interests")), provider,
            topicStore = Store(TopicSnapshot(identity = provider.identity, canonicalCatalogReady = true)),
            topicLabelRuntime = generator, topicLabelModelAvailable = { true },
            topicLabelIdentity = { identity }, topicSupportIdentity = { identity }, topicProposalIdentity = { identity },
            topicLabelStore = FileTopicLabelStore(directory.resolve("labels")),
            topicRetrievalStore = FileTopicRetrievalStore(directory.resolve("matches")),
            proposalProgressStore = FileProposalProgressStore(directory.resolve("progress")), diagnosticLog = diagnostic)
    }
    @Test fun aHundredNoteConnectionSweepResumesAndReconcilesMovesAndRemovalsExactly() = runBlocking {
        val dir = Files.createTempDirectory("hundred-note-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = FileTopicStore(dir.resolve("topics"))
            val model = EmbeddingIdentity("hundred-note-connections", 10)
            val groups = (0 until 100).associate { "p$it" to it / 10 }.toMutableMap()
            val sources = linkedMapOf<String, String>()
            suspend fun record(id: String, edited: Boolean = false) {
                val text = "Forest ecosystems support wildlife habitats and woodland biodiversity."
                val document = ReaderDocument.fromChunkTexts(id, id, if (edited) listOf(text, "Edited metadata evidence.") else listOf(text))
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val revision = semanticRevision(document); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c",
                    0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id),
                    listOf(EmbeddedPassage(p, List(10) { if (it == groups.getValue(id)) 1f else 0f })))))
            }
            for (id in groups.keys) record(id)
            index.setPaused(false)
            val policy = TopicPolicy()
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true,
                acceptedRevisions = sources.toMap(), policyVersion = policy.version)))
            fun worker(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("matches")),
                    { model }, TopicRetrievalPolicy(.85f), { List(10) { if (it == 0) 1f else 0f } }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null, { model }, policy,
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            }
            suspend fun complete() {
                var coordinator = worker()
                repeat(100) { round ->
                    if (round % 7 == 0) coordinator = worker()
                    coordinator.invalidate(sources)
                    coordinator.build(sources, 0, cache.revision.value)
                    val ticket = coordinator.captureCatalogTicket()
                    if (ticket != null && ticket.snapshot.acceptedRevisions == sources) {
                        coordinator.advanceIncrementalConnections(ticket, sources)
                        if (store.readMetadata()!!.connectionPassComplete) return
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
            cache.remove("p99"); sources.remove("p99"); groups.remove("p99"); index.remove("p99")
            groups["p0"] = 1; record("p0", edited = true)
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
            val sources = linkedMapOf<String, String>()
            var reads = 0
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? { reads++; return backing.read(pageId) }
            }
            suspend fun add(id: String) {
                val text = "Forest ecology sustains diverse woodland habitats and wildlife."
                val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val passage = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                assertTrue(backing.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
            }
            repeat(8) { add("p$it") }; backing.setPaused(false)
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources.toMap())))
            fun coordinator(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("matches")),
                    { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null,
                    { model }, TopicPolicy(), kotlinx.coroutines.sync.Mutex(), { true }, {}, {},
                    retrievals = retrievals)
            }
            val first = coordinator(); first.invalidate(sources)
            first.advanceIncrementalConnections(first.captureCatalogTicket()!!, sources)
            val original = store.read()!!
            assertEquals(28, original.connections.size)
            add("p8")
            val next = coordinator(); next.invalidate(sources); reads = 0
            next.build(sources, 0, cache.revision.value)
            next.advanceIncrementalConnections(next.captureCatalogTicket()!!, sources)
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
            val sources = linkedMapOf<String, String>()
            for ((id, title) in listOf("a" to "Source", "b" to "Alpha", "c" to " alpha ")) {
                val text = if (id == "a") "Open Alpha." else "A short target context."
                val document = ReaderDocument.fromChunkTexts(id, title, listOf(text))
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val revision = semanticRevision(document); sources[id] = revision
                val passage = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(title), listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
            }
            index.setPaused(false)
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources.toMap())))
            fun coordinator(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("matches")),
                    { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null, { model }, TopicPolicy(),
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            }
            val first = coordinator(); first.invalidate(sources)
            first.advanceIncrementalConnections(first.captureCatalogTicket()!!, sources)
            assertTrue(store.read()!!.connections.none { it.independentNamedReference })
            cache.remove("c"); sources.remove("c"); index.remove("c")
            val restarted = coordinator(); restarted.invalidate(sources)
            restarted.advanceIncrementalConnections(restarted.captureCatalogTicket()!!, sources)
            assertTrue("Removing a case/whitespace title collision must revisit the surviving target",
                store.read()!!.connections.any { it.independentNamedReference && setOf(it.left.pageId, it.right.pageId) == setOf("a", "b") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun addingANoteDuringAPartialConnectionSweepDoesNotRestartCompletedSeeds() = runBlocking {
        val dir = Files.createTempDirectory("incremental-partial-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("incremental-partial-connections", 2)
            val store = FileTopicStore(dir.resolve("topics"))
            val sources = linkedMapOf<String, String>()
            suspend fun add(id: String) {
                val text = "Forest ecology sustains wildlife habitats in woodland ecosystems."
                val document = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val revision = semanticRevision(document); sources[id] = revision
                val passages = (0..1).map { n ->
                    val p = SemanticPassage("$id:p$n", id, "$id:s$n", "Forest ecology", "$id:b$n", "$id:c$n", 0, text.length, text, text, n)
                    EmbeddedPassage(p, listOf(1f, 0f))
                }
                assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id), passages)))
            }
            repeat(12) { add("p" + it.toString().padStart(2, '0')) }; index.setPaused(false)
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources.toMap())))
            fun coordinator(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("matches")),
                    { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null,
                    { model }, TopicPolicy(), kotlinx.coroutines.sync.Mutex(), { true }, {}, {},
                    retrievals = retrievals)
            }
            val first = coordinator(); first.invalidate(sources)
            first.advanceIncrementalConnections(first.captureCatalogTicket()!!, sources)
            assertFalse("The fixture must cross the 256-candidate window", store.read()!!.connectionPassComplete)
            add("zzz")
            val restarted = coordinator(); restarted.invalidate(sources)
            restarted.build(sources, 0, cache.revision.value)
            restarted.advanceIncrementalConnections(restarted.captureCatalogTicket()!!, sources)
            val result = store.read()!!
            assertTrue("A small delta and unfinished seeds must fit in one window without replaying completed seeds", result.connectionPassComplete)
            val expected = sources.keys.flatMap { left -> sources.keys.filter { it > left }.map { right -> setOf(left, right) } }.toSet()
            assertEquals(expected, result.connections.map { setOf(it.left.pageId, it.right.pageId) }.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun addingANoteDuringAPartialConnectionSweepRetainsUnfinishedPairs() = runBlocking {
        val dir = Files.createTempDirectory("incremental-partial-connections").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("incremental-partial-connections", 2)
            val store = FileTopicStore(dir.resolve("topics"))
            val sources = linkedMapOf<String, String>()
            suspend fun add(id: String) {
                val text = "Forest ecology sustains wildlife habitats in woodland ecosystems."
                val document = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val revision = semanticRevision(document); sources[id] = revision
                val passages = (0..1).map { n ->
                    val p = SemanticPassage("$id:p$n", id, "$id:s$n", "Forest ecology", "$id:b$n", "$id:c$n", 0, text.length, text, text, n)
                    EmbeddedPassage(p, listOf(1f, 0f))
                }
                assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(id), passages)))
            }
            repeat(20) { add("p" + it.toString().padStart(2, '0')) }; index.setPaused(false)
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources.toMap())))
            fun coordinator(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("matches")),
                    { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null,
                    { model }, TopicPolicy(), kotlinx.coroutines.sync.Mutex(), { true }, {}, {},
                    retrievals = retrievals)
            }
            val first = coordinator(); first.invalidate(sources)
            first.advanceIncrementalConnections(first.captureCatalogTicket()!!, sources)
            assertFalse("The fixture must cross the 256-candidate window", store.read()!!.connectionPassComplete)
            add("zzz")
            val restarted = coordinator(); restarted.invalidate(sources)
            restarted.build(sources, 0, cache.revision.value)
            repeat(20) {
                if (!store.readMetadata()!!.connectionPassComplete) {
                    restarted.advanceIncrementalConnections(restarted.captureCatalogTicket()!!, sources)
                }
            }
            val result = store.read()!!
            assertTrue("The resumed sweep must finish", result.connectionPassComplete)
            val expected = sources.keys.flatMap { left -> sources.keys.filter { it > left }.map { right -> setOf(left, right) } }.toSet()
            assertEquals(expected, result.connections.map { setOf(it.left.pageId, it.right.pageId) }.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun metadataUpdatesReplaceAffectedConnectionEvidenceEvenWhenItsScoreIsUnchanged() = runBlocking {
        val dir = Files.createTempDirectory("incremental-connection-metadata").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("incremental-connection-metadata", 2)
            val store = FileTopicStore(dir.resolve("topics"))
            val sources = linkedMapOf<String, String>()
            val metadata = linkedMapOf<String, SemanticMetadata>()
            for (id in listOf("a", "b")) {
                val text = "Forest ecology sustains wildlife habitats and protects woodland ecosystems."
                val document = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val revision = semanticRevision(document); sources[id] = revision; metadata[id] = SemanticMetadata(id)
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, revision, model, metadata.getValue(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
            }
            index.setPaused(false)
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources.toMap())))
            fun coordinator(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("matches")),
                    { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null, { model }, TopicPolicy(),
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            }
            val first = coordinator(); first.invalidate(sources); first.build(sources, 0, cache.revision.value, metadata = metadata)
            first.advanceIncrementalConnections(first.captureCatalogTicket()!!, sources)
            assertEquals(1, store.read()!!.connections.size)
            metadata["b"] = SemanticMetadata("Renamed target")
            index.write(index.read("b")!!.copy(metadata = metadata.getValue("b")))
            val restarted = coordinator(); restarted.invalidate(sources); restarted.build(sources, 1, cache.revision.value, metadata = metadata)
            restarted.advanceIncrementalConnections(restarted.captureCatalogTicket()!!, sources)
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
            val backing = FileTopicStore(dir.resolve("topics"))
            assertTrue(backing.write(TopicSnapshot(identity = Provider().identity, canonicalCatalogReady = true)))
            val store = object : TopicStore by backing {
                override suspend fun inspectMetadata(): TopicMetadataState { entered.complete(Unit); release.await(); return backing.inspectMetadata() }
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
            val backing = FileTopicStore(dir.resolve("topics"))
            assertTrue(backing.write(TopicSnapshot(identity = Provider().identity, canonicalCatalogReady = true)))
            val store = object : TopicStore by backing {
                override suspend fun inspectMetadata(): TopicMetadataState { entered.complete(Unit); release.await(); return backing.inspectMetadata() }
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
            val backing = FileTopicStore(dir.resolve("topics"))
            assertTrue(backing.write(TopicSnapshot(identity = Provider().identity, canonicalCatalogReady = true)))
            val store = object : TopicStore by backing {
                override suspend fun inspectMetadata(): TopicMetadataState { entered.complete(Unit); release.await(); return backing.inspectMetadata() }
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
            repeat(3) { n -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$n", "Forest $n", listOf(
                "Forest ecology sustains diverse wildlife habitats and woodland ecosystems.")), null, Instant.EPOCH)) }
            val diagnostics = ArrayDeque<String>()
            val controller = conceptController(dir, cache, index, Provider()) { tag, message ->
                val outcome = message.substringBefore(" elapsedMs=")
                if (tag in setOf("EvidenceScheduler", "CanonicalAdmission") && outcome !in diagnostics) {
                    diagnostics.addLast(outcome)
                }
            }
            controller.setTopicsInspected(true)
            controller.reconcile(emptyList(), true)
            assertTrue(controller.hasPendingWork)
            repeat(200) { if (controller.hasPendingWork) controller.reconcile(emptyList(), true) }
            assertFalse("Background work still pending: ${controller.state.value.topicFailure}; $diagnostics", controller.hasPendingWork)
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
            val backing = FileTopicStore(dir.resolve("topics"))
            assertTrue(backing.write(TopicSnapshot(identity = Provider().identity, canonicalCatalogReady = true)))
            var attempted = false
            val store = object : TopicStore by backing {
                override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                    attempted = true
                    return TopicGuardedWriteResult.Rejected(TopicWriteResult.Rejected(TopicWriteFailure.BudgetExceeded))
                }
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(), topicStore = store,
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("queries")),
                proposalProgressStore = FileProposalProgressStore(dir.resolve("progress")))
            repeat(8) { if (!attempted) controller.reconcile(emptyList(), true) }
            assertTrue("The guarded catalog write must be attempted", attempted)
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
            val backing = FileTopicStore(dir.resolve("topics"))
            assertTrue(backing.write(TopicSnapshot(identity = Provider().identity, canonicalCatalogReady = true)))
            val store = object : TopicStore by backing {
                override suspend fun inspectMetadata(): TopicMetadataState {
                    entered.complete(Unit); release.await(); return backing.inspectMetadata()
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
            val provider = Provider()
            val members = mutableListOf<TopicMembership>()
            val sources = linkedMapOf<String, String>()
            repeat(2) { i ->
                val id = "p$i"
                val body = "Forest ecology explains how trees shelter wildlife and support healthy woodland habitats."
                val document = ReaderDocument.fromChunkTexts(id, "Forest $i", listOf(body))
                cache.put(CachedDocument(document, null, Instant.EPOCH))
                val revision = semanticRevision(document); sources[id] = revision
                val passage = SemanticPassage("$id:p", id, "$id:s", "Forest", "$id:b", "$id:c", 0, body.length, body, body, 0)
                val metadata = SemanticMetadata(document.title)
                assertTrue(index.write(SemanticRecord(id, revision, provider.identity, metadata,
                    listOf(EmbeddedPassage(passage, listOf(1f, 0f))))))
                members += TopicMembership(id, metadata, passage, 1f, passage, revision)
            }
            index.setPaused(false)
            val backing = FileTopicStore(dir.resolve("topics"))
            val connection = NoteConnection("connection", members[0], members[1], 1f)
            assertTrue(backing.write(TopicSnapshot(identity = provider.identity, canonicalCatalogReady = true,
                acceptedRevisions = sources, connections = listOf(connection), connectionPassComplete = true)))
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
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = store, topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("matches")),
                )
            controller.reconcile(emptyList(), false)
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
                assertTrue("Reset must remove the stored connection", backing.read()!!.connections.isEmpty())
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
            repeat(80) { if (controller.hasPendingWork) controller.reconcile(emptyList(), true) }
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
            repeat(80) { if (controller.hasPendingWork) controller.reconcile(emptyList(), false) }
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
            val controller = conceptController(dir, cache, index, Provider())
            index.setPaused(false)
            repeat(80) { if (controller.hasPendingWork) controller.reconcile(emptyList(), true) }
            assertFalse("Initial analysis must be complete", controller.hasPendingWork)
            controller.reconcile(listOf(com.example.notionreader.domain.ReaderIndexEntry("a", "Forest ecosystems", kind = "Research")), false)
            assertTrue("Changed eligible metadata must arrange a future analysis window", controller.hasPendingWork)
            repeat(80) { if (controller.hasPendingWork) controller.reconcile(listOf(com.example.notionreader.domain.ReaderIndexEntry("a", "Forest ecosystems", kind = "Research")), true) }
            assertFalse(controller.hasPendingWork)
        } finally { dir.deleteRecursively() }
    }
    @Test fun unfinishedDiscoveryKeepsItsWindowBusyWithoutAContinueAction() = runBlocking {
        val dir = Files.createTempDirectory("automatic-discovery-rounds").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val complete = CompletableDeferred<TopicSnapshot>()
            val identity = TopicLabelIdentity("fixture", "tokens", "template", "prompt", "schema", "policy", "fixture")
            val generator = object : TopicLabelRuntime, TopicSupportRuntime, DiscoveryProposalRuntime {
                override suspend fun label(request: TopicLabelRequest): TopicLabelRun {
                    val name = if (request.evidence.any { it.pageId in listOf("n3", "n4", "n5") }) "Canopy wildlife" else "Forest ecosystems"
                    return TopicLabelRun.Completed(TopicLabelCandidate(
                        name, request.evidence.map { it.passageId }, "Ecological relationships."))
                }
                override suspend fun verifySupport(request: TopicSupportRequest) = TopicSupportRun.Completed(SupportDecision.SUPPORTED)
                override suspend fun propose(request: DiscoveryProposalRequest): DiscoveryProposalRun {
                    val name = if (request.evidence.any { it.pageId in listOf("n3", "n4", "n5") }) "Canopy wildlife" else "Forest ecosystems"
                    return DiscoveryProposalRun.Completed(
                        DiscoveryProposalResponse.Concept(name, "Ecological relationships.",
                            name, request.evidence.map { it.passageId }))
                }
                override suspend fun close() {}
            }
            val provider = object : EmbeddingProvider {
                override val identity = EmbeddingIdentity("automatic-rounds", 2)
                override val tokenizer = Provider().tokenizer
                override suspend fun embed(text: String): List<Float> {
                    val number = Regex("sample (\\d)").find(text)?.groupValues?.get(1)?.toDouble() ?: 0.0
                    val angle = Math.toRadians((number % 3) * 3.0 + (if (number >= 3) 60.0 else 0.0))
                    return listOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                }
            }
            val stored = Store(TopicSnapshot(identity = provider.identity, canonicalCatalogReady = true))
            repeat(6) { i ->
                val title = if (i >= 3) "Canopy wildlife" else "Forest ecosystems"
                val body = if (i >= 3) "Canopy wildlife flourishes in high tree branches; sample $i."
                    else "Forest ecosystems support wildlife through diverse habitats and food sources; sample $i."
                cache.put(CachedDocument(ReaderDocument.fromChunkTexts("n$i", title, listOf(body)), null, Instant.EPOCH))
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = stored, topicCheckpointStore = FileTopicStore(dir.resolve("checkpoint")),
                topicLabelRuntime = generator, topicLabelModelAvailable = { true },
                topicLabelIdentity = { identity }, topicSupportIdentity = { identity }, topicProposalIdentity = { identity },
                topicLabelStore = FileTopicLabelStore(dir.resolve("labels")),
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("matches")),
                proposalProgressStore = FileProposalProgressStore(dir.resolve("progress")))
            index.setPaused(false)
            var rounds = 0
            var clock = 0L
            val windowSteps = 300
            val history = mutableListOf<String>()
            // Fake time advances one second per bounded step, so the window budget is a step
            // budget: the outcome does not depend on how fast the machine happens to be.
            val coordinator = DiscoveryWorkCoordinator({ clock }) {
                clock += 1_000
                rounds++
                controller.reconcile(emptyList(), true)
                if (rounds % 10 == 0) {
                    val s = stored.value
                    history += "round=$rounds indexed=${controller.state.value.indexedCount} pending=${controller.hasPendingWork} gen=${s?.generation} conn=${s?.connections?.size} cc=${s?.connectionCursor} pass=${s?.connectionPassComplete} failure=${controller.state.value.topicFailure} topics=${s?.topics?.map { t -> "${t.id}:${t.canonicalConcept?.scannedRevisions?.size}/${t.canonicalConcept?.matchingComplete}" }}"
                }
                stored.value?.takeIf { it.connectionPassComplete }?.let {
                    if (!controller.hasPendingWork) complete.complete(it)
                }
                discoveryWindowResult(true, controller.state.value, true, controller.hasPendingWork)
            }
            // One window owns continuation: nothing outside the window supplies a wake.
            val windowResult = coordinator.runWindow(windowSteps * 1_000L)
            assertEquals("Bounded progress must not publish a wake; only external triggers may", 0L, controller.revision.value)
            val stalled = controller.state.value
            assertTrue("The window must keep itself busy until the work is finished, not stop with PENDING after $rounds rounds " +
                "(gen=${stored.value?.generation}, connections=${stored.value?.connections?.size}, " +
                "cursor=${stored.value?.connectionCursor}, complete=${stored.value?.connectionPassComplete}, " +
                "failure=${stalled.topicFailure}, block=${stalled.topicBlock}, pending=${controller.hasPendingWork}, " +
                "history=${history.takeLast(3)})", complete.isCompleted)
            val result = complete.await()
            assertNotEquals("A window that ran out of steps is not a finished discovery", DiscoveryWindowResult.PENDING, windowResult)
            assertTrue("Fixture must require automatic continuation across multiple slices", rounds > 1)
            assertTrue(result.connectionPassComplete)
            assertTrue("Connections must finish too", result.connections.isNotEmpty())
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
            val store = Store(TopicSnapshot(identity = provider.identity, canonicalCatalogReady = true))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                topicStore = store, topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("matches")),
                topicPolicy = TopicPolicy(connection = .95f, version = 42))
            index.setPaused(false)
            repeat(20) { if (controller.hasPendingWork) controller.reconcile(emptyList(), true) }
            val snapshot = controller.state.value.topicSnapshot
            assertEquals(42, snapshot.policyVersion)
            assertTrue("A strict connection policy (.95) must not connect notes with similarity .80", snapshot.connections.isEmpty())
            val permissiveController = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                topicStore = Store(TopicSnapshot(identity = provider.identity, canonicalCatalogReady = true)),
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("permissive-matches")),
                topicPolicy = TopicPolicy(connection = .5f, version = 43))
            repeat(20) { if (permissiveController.hasPendingWork) permissiveController.reconcile(emptyList(), true) }
            val permissiveSnapshot = permissiveController.state.value.topicSnapshot
            assertEquals(43, permissiveSnapshot.policyVersion)
            assertTrue("A permissive connection policy (.50) must connect notes with similarity .80", permissiveSnapshot.connections.isNotEmpty())
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
            assertTrue(store.write(TopicSnapshot(connections = pairs, identity = provider.identity, acceptedRevisions = sources, generation = 1, canonicalCatalogReady = true)))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider, topicStore = store)
            controller.reconcile(emptyList(), false)
            val inspected = controller.state.value.topicSnapshot
            assertEquals(2, inspected.connections.size)
            assertEquals(70, inspected.catalogReference!!.count)
            assertEquals(pairs.subList(60, 70), controller.connectionWindow(inspected, 60, 20).connections)
            assertEquals(listOf(pairs.last()), controller.connectionWindow(inspected, selectedId = "pair069").connections)
            val removingStore = object : TopicStore by store {
                var inject = true
                override suspend fun inspectMetadata(): TopicMetadataState {
                    val result = store.inspectMetadata()
                    // Removal arrives after the new controller enumerated sources; it is hidden beyond both previews.
                    if (inject) { inject = false; cache.remove("a69") }
                    return result
                }
            }
            val restarted = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = removingStore)
            restarted.reconcile(emptyList(), false)
            restarted.reconcile(emptyList(), false)
            assertEquals(69, restarted.state.value.topicSnapshot.catalogReference!!.count)
            assertTrue(store.findConnection(inspected, "pair069").connections.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aLargeConnectionScanCheckpointsInsideAPassageAndResumesAfterRestart() = runBlocking {
        val dir = Files.createTempDirectory("target-cursor").toFile()
        try {
            val model = EmbeddingIdentity("target-cursor", 2)
            val cache = FileDocumentCache(dir.resolve("cache"), maxEntries = 300)
            val backing = FileSemanticIndexStore(dir.resolve("index"))
            val sources = linkedMapOf<String, String>()
            repeat(270) { n ->
                val id = "p${n.toString().padStart(3, '0')}"
                val text = "Forest ecosystems protect woodland soil and provide habitat for diverse wildlife."
                val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text))
                cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c", 0, text.length, text, text, 0)
                assertTrue(backing.write(SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
            }
            backing.setPaused(false)
            var reads = 0
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? { reads++; return backing.read(pageId) }
            }
            val store = FileTopicStore(dir.resolve("topics")); val policy = TopicPolicy()
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true,
                acceptedRevisions = sources, policyVersion = policy.version)))
            fun worker(): TopicCoordinator {
                val queries = FileTopicRetrievalStore(dir.resolve("queries"))
                val retrievals = TopicRetrievalCoordinator(index, queries, { model }, TopicRetrievalPolicy(.85f),
                    { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null, { model }, policy,
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            }
            var coordinator = worker(); coordinator.invalidate(sources); reads = 0
            assertEquals(IncrementalConnectionsResult.Pending,
                coordinator.advanceIncrementalConnections(coordinator.captureCatalogTicket()!!, sources))
            assertTrue("A quantum must bound indexed Note reads; observed $reads", reads <= 527)
            val first = store.read()!!
            assertEquals(256, first.connections.size)
            assertEquals(0, first.connectionCursor)
            assertEquals(0, first.connectionPassageCursor)
            assertTrue(first.connectionTargetCursor > 0)
            coordinator = worker(); coordinator.invalidate(sources); reads = 0
            coordinator.advanceIncrementalConnections(coordinator.captureCatalogTicket()!!, sources)
            val resumed = store.read()!!
            assertTrue("Resumed work also has a bounded target quantum: $reads", reads <= 258)
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
            val sourceTexts = (0..269).map { n -> if (n == 269) "See Target." else "Short instruction." }
            val source = ReaderDocument.fromChunkTexts("a", "Source", sourceTexts)
            val target = ReaderDocument.fromChunkTexts("b", "Target", listOf("Literal target context."))
            cache.put(CachedDocument(source, null, Instant.EPOCH)); cache.put(CachedDocument(target, null, Instant.EPOCH))
            val sources = mapOf("a" to semanticRevision(source), "b" to semanticRevision(target))
            fun passage(id: String, n: Int, text: String) = SemanticPassage("$id:p${n.toString().padStart(3, '0')}",
                id, "$id:s", null, "$id:b$n", "$id:c$n", 0, text.length, text, text, n)
            assertTrue(index.write(SemanticRecord("a", sources.getValue("a"), model, SemanticMetadata("Source"),
                sourceTexts.mapIndexed { n, text -> EmbeddedPassage(passage("a", n, text), listOf(1f, 0f)) })))
            assertTrue(index.write(SemanticRecord("b", sources.getValue("b"), model, SemanticMetadata("Target"),
                listOf(EmbeddedPassage(passage("b", 0, "Literal target context."), listOf(0f, 1f))))))
            index.setPaused(false)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources)))
            fun coordinator(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("queries")),
                    { model }, TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null, { model }, TopicPolicy(),
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            }
            val initial = coordinator(); initial.invalidate(sources)
            assertEquals(IncrementalConnectionsResult.Pending, initial.advanceIncrementalConnections(initial.captureCatalogTicket()!!, sources))
            assertFalse(store.read()!!.connectionPassComplete)
            assertTrue("Late references must await their durable candidate opportunity", store.read()!!.connections.isEmpty())
            val restarted = coordinator(); restarted.invalidate(sources)
            restarted.advanceIncrementalConnections(restarted.captureCatalogTicket()!!, sources)
            val result = store.read()!!; val pair = result.connections.single()
            assertTrue(pair.independentNamedReference)
            assertEquals("See Target.", pair.left.passage.excerpt)
            assertTrue(result.connectionPassComplete)
        } finally { dir.deleteRecursively() }
    }

    @Test fun referenceCatalogRetainsEveryValidatedPairBeyondPreviewExposureLimits() = runBlocking {
        val dir = Files.createTempDirectory("reference-exposure").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("references", 6); val sources = linkedMapOf<String, String>()
            for (n in 0..5) {
                val id = "p$n"; val title = if (n == 0) "Source" else "Target $n"
                val text = if (n == 0) (1..5).joinToString(" ") { "See Target $it." } else "Target context."
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(title),
                    listOf(EmbeddedPassage(p, List(6) { if (it == n) 1f else 0f })))))
            }
            index.setPaused(false)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources)))
            fun coordinator(): TopicCoordinator {
                val queries = FileTopicRetrievalStore(dir.resolve("queries"))
                val retrievals = TopicRetrievalCoordinator(index, queries, { model }, TopicRetrievalPolicy(.85f),
                    { List(6) { if (it == 0) 1f else 0f } }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null, { model }, TopicPolicy(),
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            }
            val worker = coordinator(); worker.invalidate(sources)
            worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources)
            val connections = store.read()!!.connections
            assertEquals("Every validated reference must remain available", 5, connections.size)
            assertTrue(connections.all { it.independentNamedReference })
            assertEquals(2, selectDiscoveryConnections(connections).size)
            val restarted = coordinator(); restarted.invalidate(sources)
            assertEquals("Loading a catalog must not apply display caps", 5, store.read()!!.connections.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun semanticCatalogIncludesEverySameTopicPairBeyondPerPassageAndPreviewLimits() = runBlocking {
        val dir = Files.createTempDirectory("complete-semantic-catalog").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("catalog", 2); val sources = linkedMapOf<String, String>()
            val records = mutableListOf<SemanticRecord>()
            for (n in 0..7) {
                val id = "p$n"; val title = "Forest $n"
                val text = "Forest ecosystems protect woodland soil and provide habitats for diverse wildlife."
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c", 0, text.length, text, text, 0)
                val record = SemanticRecord(id, revision, model, SemanticMetadata(title), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
                assertTrue(index.write(record)); records += record
            }
            index.setPaused(false)
            val generator = TopicLabelIdentity("a", "t", "c", "proposal", "s", "v", "CPU")
            val verifier = generator.copy(prompt = "support"); val seed = records.first(); val seedPassage = seed.passages.single()
            val offered = TopicLabelEvidence(seedPassage.passage.id, seed.pageId, seed.revision, seedPassage.passage.excerpt)
            val request = DiscoveryProposalRequest(semanticHash("forest"), 0, listOf(offered), generator)
            val response = DiscoveryProposalResponse.Concept("Forest ecosystems", "Woodland habitats support wildlife.",
                "Listen about woodland habitats.", listOf(offered.passageId))
            val key = (validateDiscoveryProposal(request, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
            val progress = FileProposalProgressStore(dir.resolve("progress")); val assessments = records.mapIndexed { n, record ->
                val p = record.passages.single().passage
                val evidence = TopicLabelEvidence(p.id, record.pageId, record.revision, p.excerpt)
                val support = TopicSupportRequest(if (n == 0) "pending:$key" else "topic", key, response.name,
                    response.definition, evidence, verifier, 0)
                val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
                assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true }); assessment
            }
            val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
                generator, verifier, listOf(assessments.first().evidence), listOf(assessments.first()),
                assessments.map { it.evidence }, sources, true, request.proposalKey)
            val member = TopicMembership(seed.pageId, seed.metadata, seedPassage.passage, 1f, seedPassage.passage, seed.revision)
            val topic = DiscoveredTopic("topic", "Forest ecosystems", seedPassage.passage, seedPassage, listOf(member), 1, canonicalConcept = concept)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(topics = listOf(topic), identity = model, canonicalCatalogReady = true, acceptedRevisions = sources)))
            val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("queries")), { model },
                TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals, proposalProgress = progress)
            worker.invalidate(sources); worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources)
            worker.invalidate(sources)
            assertEquals(8, worker.captureCatalogTicket()!!.snapshot.topics.single().matchingMembers.size)
            val snapshot = store.read()!!
            assertEquals("All 8 choose 2 supported pairs belong to the catalog", 28, snapshot.connections.size)
            assertEquals(28, snapshot.connections.map { it.id }.distinct().size)
            assertTrue(snapshot.connectionPassComplete)
            assertTrue(selectDiscoveryConnections(snapshot.connections).size < 28)
        } finally { dir.deleteRecursively() }
    }

    @Test fun referenceAndSimilarityEvidenceForTheSamePairRemainIndependent() = runBlocking {
        val dir = Files.createTempDirectory("catalog-evidence-kinds").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("catalog-kinds", 2); val sources = linkedMapOf<String, String>()
            for (id in listOf("a", "b")) {
                val title = if (id == "a") "Forest A" else "Forest B"
                val text = if (id == "a") "See Forest B. Forest ecosystems protect woodland soil and provide wildlife habitats."
                    else "Forest ecosystems protect woodland soil and provide habitats for woodland wildlife."
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", "Forest ecosystems", "$id:b", "$id:c", 0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, revision, model, SemanticMetadata(title),
                    listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
            }
            index.setPaused(false)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources)))
            val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("queries")), { model },
                TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            worker.invalidate(sources); worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources)
            val connections = store.read()!!.connections
            assertEquals(2, connections.size)
            assertEquals(setOf(true, false), connections.map { it.independentNamedReference }.toSet())
            assertEquals(2, connections.map { it.id }.distinct().size)
            assertEquals(setOf(ConnectionEvidenceKind.NamedReference, ConnectionEvidenceKind.SimilarPassages),
                connections.map { classifyConnectionEvidence(it, mapOf("a" to "Forest A", "b" to "Forest B")).kind }.toSet())
        } finally { dir.deleteRecursively() }
    }
    @Test fun aDuplicateCurrentTitleWithdrawsAnIndependentReferenceFromTheInspectedGeneration() = runBlocking {
        val dir = Files.createTempDirectory("reference-title-invalidation").toFile()
        try {
            val model = EmbeddingIdentity("references", 2); val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index")); val sources = linkedMapOf<String, String>()
            suspend fun add(id: String, title: String, text: String): SemanticRecord {
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                return SemanticRecord(id, revision, model, SemanticMetadata(title), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
                    .also { assertTrue(index.write(it)) }
            }
            val a = add("a", "Reading Guide", "Read Target Note.")
            val b = add("b", "Target Note", "A literal target context.")
            index.setPaused(false)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources.toMap(),
                connections = discoverExplicitNoteReferences(listOf(a, b)))))
            assertEquals(1, store.read()!!.connections.size)
            var shown = TopicView()
            val coordinator = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { true }, {}, { shown = it })
            coordinator.invalidate(sources); coordinator.setInspected(true)
            assertEquals(1, shown.snapshot.connections.size)
            add("c", "Target Note", "An unrelated duplicate title.")
            coordinator.invalidate(sources)
            assertTrue(store.read()!!.connections.isEmpty())
            assertTrue(shown.snapshot.connections.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun browseOrderingAndUncachedMetadataDoNotRestartTheCandidateSweep() = runBlocking {
        val dir = Files.createTempDirectory("topic-metadata-order").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
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
            val store = Store(TopicSnapshot(identity = model, canonicalCatalogReady = true,
                acceptedRevisions = entries.associate { it.pageId to semanticRevision(cache.get(it.pageId)!!.document) }))
            val provider = object : EmbeddingProvider by Provider() {
                override val identity = model
                override suspend fun embed(text: String): List<Float> = error("Unchanged embeddings must be reused")
            }
            var controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = store, topicCheckpointStore = Store(),
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("matches")))
            controller.reconcile(entries, true)
            assertEquals(1L, store.value!!.generation)
            controller.reconcile(entries.reversed(), true)
            assertEquals("Browse order is not a semantic input", 1L, store.value!!.generation)
            controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")),
                provider, topicStore = store, topicCheckpointStore = Store(),
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("matches")))
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
            val cache = FileDocumentCache(dir.resolve("cache")); val backing = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("connection-pause", 2); val sources = linkedMapOf<String, String>()
            val records = mutableListOf<SemanticRecord>(); val text = "It is what it is and we are who we are."
            for (id in listOf("a", "b")) {
                val doc = ReaderDocument.fromChunkTexts(id, id, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", null, "$id:b", "$id:c", 0, text.length, text, text, 0)
                val record = SemanticRecord(id, revision, model, SemanticMetadata(id), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
                assertTrue(backing.write(record)); records += record
            }
            backing.setPaused(false)
            fun member(r: SemanticRecord) = TopicMembership(r.pageId, r.metadata, r.passages.single().passage, 1f,
                r.passages.single().passage, r.revision)
            val prior = NoteConnection("prior", member(records[0]), member(records[1]), 1f)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true,
                connections = listOf(prior), acceptedRevisions = sources)))
            var arm = false; var paused = false; var reads = 0
            val index = object : SemanticIndexStore by backing {
                override suspend fun read(pageId: String): SemanticRecord? {
                    if (arm) { reads++; paused = true }
                    return backing.read(pageId)
                }
            }
            val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("queries")), { model },
                TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { !paused }, {}, kotlinx.coroutines.sync.Mutex())
            val worker = TopicCoordinator(cache, index, store, null, { model }, TopicPolicy(),
                kotlinx.coroutines.sync.Mutex(), { !paused }, {}, {}, retrievals = retrievals)
            worker.invalidate(sources)
            worker.build(sources, 0, cache.revision.value, metadata = records.associate { it.pageId to it.metadata })
            arm = true
            assertEquals(IncrementalConnectionsResult.Deferred, worker.advanceIncrementalConnections(worker.captureCatalogTicket()!!, sources))
            assertTrue("The pause must occur inside connection work", reads > 0)
            assertEquals(listOf(prior), store.read()!!.connections)
            assertTrue(worker.needsMoreWork)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRestartResumesConnectionsBeyondTheFirstThreeSectionsInsideANote() = runBlocking {
        val dir = Files.createTempDirectory("late-connection-resume").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("late-connection", 2)
            val text = "It is what it is and we are who we are."
            val docA = ReaderDocument.fromChunkTexts("a", "A", List(257) { text })
            val docB = ReaderDocument.fromChunkTexts("b", "B", listOf(text))
            cache.put(CachedDocument(docA, null, Instant.EPOCH)); cache.put(CachedDocument(docB, null, Instant.EPOCH))
            val sources = mapOf("a" to semanticRevision(docA), "b" to semanticRevision(docB))
            fun evidence(page: String, n: Int, vector: List<Float>): EmbeddedPassage {
                val id = "$page:${n.toString().padStart(4, '0')}"
                return EmbeddedPassage(SemanticPassage(id, page, "$id:s", null, "$id:b", "$id:c",
                    0, text.length, text, text, n), vector)
            }
            assertTrue(index.write(SemanticRecord("a", sources.getValue("a"), model, SemanticMetadata("A"),
                (0 until 256).map { evidence("a", it, listOf(1f, 0f)) } + evidence("a", 256, listOf(0f, 1f)))))
            assertTrue(index.write(SemanticRecord("b", sources.getValue("b"), model, SemanticMetadata("B"), listOf(evidence("b", 0, listOf(0f, 1f))))))
            index.setPaused(false)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = model, canonicalCatalogReady = true, acceptedRevisions = sources)))
            fun worker(): TopicCoordinator {
                val retrievals = TopicRetrievalCoordinator(index, FileTopicRetrievalStore(dir.resolve("queries")), { model },
                    TopicRetrievalPolicy(.85f), { listOf(1f, 0f) }, { true }, {}, kotlinx.coroutines.sync.Mutex())
                return TopicCoordinator(cache, index, FileTopicStore(dir.resolve("topics")), null, { model }, TopicPolicy(),
                    kotlinx.coroutines.sync.Mutex(), { true }, {}, {}, retrievals = retrievals)
            }
            val first = worker(); first.invalidate(sources)
            assertEquals(IncrementalConnectionsResult.Pending, first.advanceIncrementalConnections(first.captureCatalogTicket()!!, sources))
            val partial = store.read()!!
            assertTrue(partial.connections.isEmpty())
            assertEquals(0, partial.connectionCursor)
            assertEquals(256, partial.connectionPassageCursor)
            assertFalse(partial.connectionPassComplete)
            val restarted = worker(); restarted.invalidate(sources)
            restarted.advanceIncrementalConnections(restarted.captureCatalogTicket()!!, sources)
            val completed = store.read()!!
            assertEquals(1, completed.connections.size)
            assertEquals("a:0256", completed.connections.single().left.passage.id)
            assertTrue(completed.connectionPassComplete)
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
                topicStore = FileTopicStore(dir.resolve("topics"), allowFreshInitialization = true),
                topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("queries")),
                proposalProgressStore = FileProposalProgressStore(dir.resolve("progress")))
            repeat(30) { controller.reconcile(emptyList(), true) }
            assertTrue(controller.state.value.topicSnapshot.topics.isEmpty())
            assertEquals(1, controller.state.value.topicSnapshot.connections.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun aSupportedThemeAfterFourPrivatePassagesStillGetsDiscovered() = runBlocking {
        val dir = Files.createTempDirectory("topic-late-evidence").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val model = EmbeddingIdentity("late", 13)
            val sources = (0..2).associate { "p$it" to "r" }
            val records = linkedMapOf<String, SemanticRecord>()
            for ((n, id) in sources.keys.withIndex()) {
                val passages = ((0..3).map { n * 4 + it } + 12).mapIndexed { position, axis ->
                    val text = "Body evidence $axis"
                    EmbeddedPassage(SemanticPassage("$id:$axis", id, "$id:s$axis", null, "$id:b$axis",
                        "$id:c$axis", 0, text.length, text, text, position), List(13) { if (it == axis) 1f else 0f })
                }
                val record = SemanticRecord(id, "r", model, SemanticMetadata(id), passages)
                records[id] = record
                index.write(record)
            }
            index.setPaused(false)
            val window = proposeDiscoveryWindow(
                changed = records.getValue("p2"),
                changedPassageIds = records.getValue("p2").passages.map { it.passage.id }.toSet(),
                explainedPassageIds = emptySet(),
                olderRecords = sequenceOf(records.getValue("p0"), records.getValue("p1")),
                currentRevisions = sources,
                explicitPageIds = emptySet(),
                structureComplete = true,
                scanComplete = true
            )
            assertFalse("Later body evidence must receive a candidate opportunity", window.proposals.isEmpty())
            val shared = window.proposals.single { it.evidence.size == sources.size }
            assertEquals(sources.keys, shared.evidence.map { it.pageId }.toSet())
            assertTrue(shared.evidence.all { it.passageId.endsWith(":12") })
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
    private class Store(var value: TopicSnapshot? = null) : TopicStore {
        override suspend fun read() = value
        override suspend fun readMetadata() = value
        override suspend fun inspectMetadata(): TopicMetadataState =
            TopicMetadataState.Available(value ?: TopicSnapshot(canonicalCatalogReady = true))
        override suspend fun write(snapshot: TopicSnapshot): Boolean { value = snapshot; return true }
        override suspend fun writeDetailed(snapshot: TopicSnapshot): TopicWriteResult {
            value = snapshot
            return TopicWriteResult.Written
        }
        override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
            if (!current()) return TopicGuardedWriteResult.Stale
            value = snapshot
            return TopicGuardedWriteResult.Written
        }
        override suspend fun initializeFresh(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
            if (!current()) return TopicGuardedWriteResult.Stale
            value = snapshot
            return TopicGuardedWriteResult.Written
        }
        override suspend fun beginReset(): Boolean = true
        override suspend fun finishReset(snapshot: TopicSnapshot): TopicWriteResult {
            value = snapshot
            return TopicWriteResult.Written
        }
        override suspend fun clear() { value = null }
        override suspend fun bytes() = 0L
    }
    @Test fun changingAnUngroupedConnectionEndpointWithdrawsItsOldPassageWhilePaused() = runBlocking {
        val dir = Files.createTempDirectory("topics-connection-revision").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider(); val sources = linkedMapOf<String, String>()
            val members = mutableListOf<TopicMembership>()
            for ((id, title, text) in listOf(Triple("p", "Forest", "forest ecology protects wildlife habitats."),
                    Triple("q", "Other", "old private evidence"))) {
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", "Context", "$id:b", "$id:c", 0, text.length, text, text, 0)
                assertTrue(index.write(SemanticRecord(id, revision, provider.identity, SemanticMetadata(title), listOf(EmbeddedPassage(p, listOf(1f, 0f))))))
                members += TopicMembership(id, SemanticMetadata(title), p, .8f, p, revision)
            }
            index.setPaused(true)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(identity = provider.identity, canonicalCatalogReady = true, acceptedRevisions = sources,
                connections = listOf(NoteConnection("pair", members[0], members[1], .8f)))))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider, topicStore = store)
            controller.reconcile(emptyList(), false)
            assertEquals(1, controller.state.value.topicSnapshot.connections.size)
            assertTrue(controller.state.value.topicSnapshot.topics.isEmpty())
            val ungrouped = cache.snapshot().single { it.document.pageId == "q" }.document
            cache.put(CachedDocument(ungrouped.copy(title = "Changed title"), null, Instant.EPOCH))
            controller.reconcile(emptyList(), false)
            assertTrue(controller.state.value.paused)
            assertTrue(controller.state.value.topicSnapshot.connections.isEmpty())
            assertTrue(store.read()!!.connections.isEmpty())
            assertTrue(dir.resolve("topics").walkTopDown().filter { it.isFile }.none { it.readText().contains("old private evidence") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun aCheckpointFinishingDuringResetCannotRecreateSourceEvidence() = runBlocking {
        val dir = Files.createTempDirectory("topics-reset-checkpoint").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache"))
            val index = FileSemanticIndexStore(dir.resolve("index"))
            val store = Store(TopicSnapshot(identity = Provider().identity, canonicalCatalogReady = true))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gated = object : TopicStore by store {
                override suspend fun writeGuarded(snapshot: TopicSnapshot, current: () -> Boolean): TopicGuardedWriteResult {
                    entered.complete(Unit)
                    release.await()
                    return store.writeGuarded(snapshot, current)
                }
            }
            repeat(3) { i -> cache.put(CachedDocument(ReaderDocument.fromChunkTexts("p$i", "Forest $i", List(32) { "forest" }), null, Instant.EPOCH)) }
            index.setPaused(false)
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), Provider(),
                topicStore = gated, topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("matches")))
            val build = async { controller.reconcile(emptyList(), true) }
            withTimeout(5_000) { entered.await() }
            val clear = async { controller.clearIndex() }
            yield(); release.complete(Unit); clear.await(); build.await()
            assertTrue(controller.state.value.topicSnapshot.topics.isEmpty())
            assertTrue(store.value?.topics?.isEmpty() != false)
            assertTrue(store.value?.connections?.isEmpty() != false)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aRemovalObservedAfterEnumerationCannotBeRepublishedFromTheOldSourceMap() = runBlocking {
        val dir = Files.createTempDirectory("topics-enumeration-race").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider(); val sources = linkedMapOf<String, String>(); val records = mutableListOf<SemanticRecord>()
            for (i in 0..2) {
                val id = "p$i"; val title = "Forest $i"; val text = "forest ecology protects wildlife and supports healthy woodland habitats."
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", "Ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                val record = SemanticRecord(id, revision, provider.identity, SemanticMetadata(title), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
                assertTrue(index.write(record)); records += record
            }
            index.setPaused(false)
            val generator = TopicLabelIdentity("a", "t", "c", "proposal", "s", "v", "CPU"); val verifier = generator.copy(prompt = "support")
            val seed = records.first(); val p = seed.passages.single(); val evidence = TopicLabelEvidence(p.passage.id, seed.pageId, seed.revision, p.passage.excerpt)
            val proposal = DiscoveryProposalRequest(semanticHash("forest-race"), 0, listOf(evidence), generator)
            val response = DiscoveryProposalResponse.Concept("Forest ecology", "Woodland habitats support wildlife.", "Listen about woodland habitats.", listOf(evidence.passageId))
            val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
            val support = TopicSupportRequest("pending:$key", key, response.name, response.definition, evidence, verifier, 0)
            val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
            val progress = FileProposalProgressStore(dir.resolve("progress")); assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true })
            val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
                generator, verifier, listOf(assessment.evidence), listOf(assessment), listOf(assessment.evidence), sources, true, proposal.proposalKey)
            val member = TopicMembership(seed.pageId, seed.metadata, p.passage, 1f, p.passage, seed.revision)
            val topic = DiscoveredTopic("topic", "Forest ecology", p.passage, p, listOf(member), 1, canonicalConcept = concept)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(topics = listOf(topic), identity = provider.identity, canonicalCatalogReady = true, acceptedRevisions = sources)))
            var injected = false
            val removingStore = object : TopicStore by store {
                override suspend fun inspectMetadata(): TopicMetadataState {
                    val result = store.inspectMetadata()
                    if (!injected) { injected = true; cache.remove("p0") }
                    return result
                }
            }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                topicStore = removingStore, proposalProgressStore = progress, topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("queries")))
            controller.reconcile(emptyList(), false)
            assertTrue("The removal must occur during catalog restoration", injected)
            assertTrue("The raced restoration must not publish withdrawn source evidence",
                controller.state.value.topicSnapshot.topics.flatMap { it.members }.none { it.pageId == "p0" })
            controller.reconcile(emptyList(), false); controller.refreshTopics()
            assertTrue(controller.state.value.topicSnapshot.topics.flatMap { it.members }.none { it.pageId == "p0" })
            assertTrue(store.read()!!.topics.flatMap { it.members }.none { it.pageId == "p0" })
            assertEquals(2, cache.count())
        } finally { dir.deleteRecursively() }
    }

    @Test fun evictionPreservesPreparedOrderButRemovesPrivateEvidenceAndRequiresReview() = runBlocking {
        val dir = Files.createTempDirectory("topic-preview-retention").toFile()
        try {
            val cache = FileDocumentCache(dir.resolve("cache")); val index = FileSemanticIndexStore(dir.resolve("index"))
            val provider = Provider(); val sources = linkedMapOf<String, String>(); val records = mutableListOf<SemanticRecord>()
            for (i in 0..2) {
                val id = "p$i"; val title = "Forest $i"; val text = "forest ecology protects wildlife and supports healthy woodland habitats."
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(text)); cache.put(CachedDocument(doc, null, Instant.EPOCH))
                val revision = semanticRevision(doc); sources[id] = revision
                val p = SemanticPassage("$id:p", id, "$id:s", "Ecology", "$id:b", "$id:c", 0, text.length, text, text, 0)
                val record = SemanticRecord(id, revision, provider.identity, SemanticMetadata(title), listOf(EmbeddedPassage(p, listOf(1f, 0f))))
                assertTrue(index.write(record)); records += record
            }
            index.setPaused(false)
            val generator = TopicLabelIdentity("a", "t", "c", "proposal", "s", "v", "CPU"); val verifier = generator.copy(prompt = "support")
            val seed = records.first(); val p = seed.passages.single(); val evidence = TopicLabelEvidence(p.passage.id, seed.pageId, seed.revision, p.passage.excerpt)
            val proposal = DiscoveryProposalRequest(semanticHash("forest-preview"), 0, listOf(evidence), generator)
            val response = DiscoveryProposalResponse.Concept("Forest ecology", "Woodland habitats support wildlife.", "Listen about woodland habitats.", listOf(evidence.passageId))
            val key = (validateDiscoveryProposal(proposal, response, 0, sources) as DiscoveryProposalValidation.Accepted).workKey
            val progress = FileProposalProgressStore(dir.resolve("progress"))
            val assessments = records.mapIndexed { n, record ->
                val passage = record.passages.single().passage
                val offered = TopicLabelEvidence(passage.id, record.pageId, record.revision, passage.excerpt)
                val support = TopicSupportRequest(if (n == 0) "pending:$key" else "topic", key, response.name, response.definition, offered, verifier, 0)
                val assessment = (validateTopicSupport(support, SupportDecision.SUPPORTED, 0, key, sources) as TopicSupportValidation.Accepted).assessment
                assertEquals(ProposalProgressWriteResult.Written, progress.writeSupport(assessment) { true }); assessment
            }
            val concept = CanonicalConceptEvidence(key, response.name, response.definition, response.listeningDescription,
                generator, verifier, listOf(assessments.first().evidence), listOf(assessments.first()), assessments.map { it.evidence }, sources, true, proposal.proposalKey)
            val member = TopicMembership(seed.pageId, seed.metadata, p.passage, 1f, p.passage, seed.revision)
            val topic = DiscoveredTopic("topic", "Forest ecology", p.passage, p, listOf(member), 1, canonicalConcept = concept)
            val store = FileTopicStore(dir.resolve("topics"))
            assertTrue(store.write(TopicSnapshot(topics = listOf(topic), identity = provider.identity, canonicalCatalogReady = true, acceptedRevisions = sources)))
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(dir.resolve("interests")), provider,
                topicStore = store, proposalProgressStore = progress, topicRetrievalStore = FileTopicRetrievalStore(dir.resolve("queries")))
            controller.reconcile(emptyList(), false); controller.refreshTopics()
            controller.preparePreview(controller.topicPreview("topic"))
            val before = controller.state.value.preview.map { it.pageId }
            assertEquals(3, before.size)
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
