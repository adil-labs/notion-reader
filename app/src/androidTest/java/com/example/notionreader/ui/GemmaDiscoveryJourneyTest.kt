package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.application.*
import com.example.notionreader.data.discovery.*
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.*
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.player.*
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** All stores are isolated from the installed app library; model inference and pipeline are real. */
class GemmaDiscoveryJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedNotesProduceSubjectsConnectionsAndSearchThroughAccessibleNavigation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = File(context.noBackupFilesDir, "discovery-model/current")
        val model = if (File(installed, GemmaModelPack.graph).isFile) installed
            else File(context.cacheDir, "gemma-benchmark-q4")
        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "gemma-journey-").toFile()
        val provider = GemmaEmbeddingProvider(model)
        try {
            val examples = listOf(
                Triple("queuea", "RabbitMQ delivery notes", "RabbitMQ acknowledgments allow consumers to confirm successful message processing. Manual acknowledgment keeps a delivery unacknowledged until the consumer finishes its work. If a consumer disconnects before acknowledging, the broker requeues the message for another attempt. Applications must make repeated processing safe because a crash can cause redelivery. Read RabbitMQ recovery notes."),
                Triple("queueb", "RabbitMQ recovery notes", "RabbitMQ acknowledgments confirm that a consumer has processed a delivered message successfully. With manual acknowledgment the broker waits until processing finishes before removing the message. A disconnected consumer leaves unfinished deliveries available for requeueing and redelivery. Consumers should handle duplicate processing safely because failures can trigger another delivery attempt."),
                Triple("sqla", "PostgreSQL snapshots", "PostgreSQL transaction isolation determines which concurrent changes a query can observe. Repeatable read uses a stable database snapshot throughout the transaction. Concurrent updates can cause serialization failures, requiring the application to retry the transaction. Choosing an isolation level balances consistency guarantees with contention and retry cost."),
                Triple("sqlb", "PostgreSQL consistency", "PostgreSQL transaction isolation controls visibility of changes made by other transactions. Under repeatable read each query observes the same transaction snapshot. Conflicting concurrent writes may require a transaction retry after a serialization failure. Applications must choose isolation guarantees and implement retry behavior appropriate to their consistency requirements."),
                Triple("rivera", "River habitat notes", "Riparian ecology studies habitats along rivers and streams. Vegetation shades the water and keeps summer temperatures lower for aquatic wildlife. Roots stabilize stream banks while floodplains absorb seasonal flows. Restoring native plants protects water quality and creates shelter for fish, insects and birds."),
                Triple("riverb", "River restoration notes", "Riparian ecology explains how riverbank vegetation supports stream habitats. Native trees shade waterways and regulate temperatures needed by aquatic species. Plant roots reduce bank erosion and floodplain habitats store seasonal floodwater. Restoration improves water quality while providing habitat for river fish, birds and insects.")
            )
            val documents = examples.mapIndexed { i, (id, title, text) ->
                ReaderDocument.fromChunkTexts(id, title, listOf(text)).let { doc -> doc.copy(sections = doc.sections.map {
                    it.copy(title = when (i / 2) { 0 -> "RabbitMQ acknowledgments"; 1 -> "PostgreSQL transaction isolation"; else -> "Riparian ecology" })
                }) }
            }
            val cache = FileDocumentCache(File(directory, "cache"))
            val index = FileSemanticIndexStore(File(directory, "index"))
            val topics = FileTopicStore(File(directory, "topics"))
            documents.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(File(directory, "interests")), provider,
                topicStore = topics, topicCheckpointStore = FileTopicStore(File(directory, "checkpoint")),
                topicPolicy = GemmaModelPack.topicPolicy, recommendationThreshold = GemmaModelPack.recommendationThreshold)
            controller.resume()
            var measuredEstimate: ProcessingTimeRange? = null
            val estimates = launch(Dispatchers.Unconfined) {
                controller.state.collect { s -> s.embeddingWork?.takeIf { it.remainingPassages > 0 }?.estimate?.let { measuredEstimate = it } }
            }
            val entries = documents.map { ReaderIndexEntry(it.pageId, it.title) }
            val start = android.os.SystemClock.elapsedRealtime()
            var passes = 0
            do {
                controller.reconcile(entries, true)
                controller.refreshTopics()
                passes++
                check(passes <= 64) { "Discovery did not finish its finite public fixture" }
            } while (!controller.state.value.topicSnapshot.let { it.complete && it.connectionPassComplete })
            estimates.cancelAndJoin()
            assertNotNull("Actual device work never produced an embedding estimate", measuredEstimate)
            val state = controller.state.value
            assertNull(state.message)
            assertEquals(6, state.indexedCount)
            val published = publishableDiscoveryTopics(state.topicSnapshot.topics)
            assertTrue("No source-supported subject was published", published.isNotEmpty())
            val queueTopic = published.firstOrNull { t -> t.members.map { it.pageId }.containsAll(listOf("queuea", "queueb")) }
            assertNotNull("Related RabbitMQ Notes were not discovered together", queueTopic)
            assertTrue("Unrelated ecology must not join the messaging subject", queueTopic!!.members.none { it.pageId.startsWith("river") })
            val connections = controller.connectionWindow(state.topicSnapshot, 0, 60).connections
            assertTrue("No paired connection discovered", connections.isNotEmpty())
            assertTrue("Named reference lost", connections.any { it.independentNamedReference })
            assertTrue("No semantic passage connection discovered", connections.any { !it.independentNamedReference })
            android.util.Log.i("GemmaDiscoveryPipeline", "indexed=${state.indexedCount}, subjects=${published.size}, connections=${connections.size}, semantic=${connections.count { !it.independentNamedReference }}")
            controller.search("RabbitMQ consumer acknowledgment and message redelivery")
            assertTrue(controller.state.value.results.first().pageId.startsWith("queue"))
            controller.search("")
            val speech = object : SpeechEngine {
                override fun speak(text: String, onComplete: (() -> Unit)?) = Unit
                override fun stop() = Unit
                override fun shutdown() = Unit
            }
            val app = ReaderApp(ReaderSession(ReaderEngineImpl(speech, documents.first())), NoteViewer(documents.first()),
                ReaderNavigator(ReaderNavigation(top = ReaderDestination.Discover)),
                DefaultLoadReaderDocument { id -> documents.first { it.pageId == id } }, discovery = controller)
            app.loadBrowseIndex { entries }
            val sessionBefore = app.session.state.value
            compose.setContent { ReaderShell(app, null, { runBlocking { app.openNote(it) } }, onRetryNoteLoad = {},
                onRetryBrowseIndex = {}, onContinueSession = {}, onReplayCompleted = {}, onCreateSavedQueue = {}) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("See all topics").fetchSemanticsNodes().any { it.boundsInRoot.width > 0f } }
            compose.onNodeWithText("See all topics").performScrollTo().assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { app.navigator.state.value.current == ReaderDestination.DiscoveryTopics &&
                compose.onAllNodesWithText(queueTopic.label).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(queueTopic.label).performScrollTo().assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Why these Notes?").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Why these Notes?").performScrollTo().assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Open label source Note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Open label source Note").performScrollTo().assertIsReachable()
            compose.runOnIdle { app.back() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(queueTopic.label) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(queueTopic.label).assertIsFocused()
            compose.runOnIdle { app.back() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("See all connections").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("See all connections").performScrollTo().assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Compare passages").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Compare passages").onFirst().assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { app.navigator.state.value.current == ReaderDestination.DiscoveryConnection &&
                compose.onAllNodesWithText("Preview listening").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Compare passages").assertIsReadable()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "gemma-connection-device.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithText("Preview listening").performScrollTo().assertIsReachable()
            compose.runOnIdle { app.back() }
            assertEquals(sessionBefore, app.session.state.value)
            val report = buildJsonObject {
                put("device", android.os.Build.MODEL); put("api", android.os.Build.VERSION.SDK_INT)
                put("indexedNotes", state.indexedCount); put("passes", passes)
                put("subjects", JsonArray(published.map { JsonPrimitive(it.label) }))
                put("connections", connections.size); put("semanticConnections", connections.count { !it.independentNamedReference })
                put("elapsedMilliseconds", android.os.SystemClock.elapsedRealtime() - start)
                put("observedEstimateMinimumSeconds", measuredEstimate!!.minimumSeconds)
                put("observedEstimateMaximumSeconds", measuredEstimate!!.maximumSeconds)
            }.toString()
            android.util.Log.i("GemmaDiscoveryJourney", report)
            File(context.getExternalFilesDir(null), "gemma-device-journey.json").writeText(report)
        } finally { provider.release(); directory.deleteRecursively() }
    }
}
