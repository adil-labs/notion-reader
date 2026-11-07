package com.example.notionreader.ui

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.notionreader.application.*
import com.example.notionreader.data.discovery.*
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.discovery.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real Gemma evidence-first run on the installed topic model. The model is large, so
 * this test skips when the artifact is absent (emulator/CI) instead of substituting a fake.
 */
class SupportFirstDeviceTest {
    @Test fun realGemmaAdmitsATopicAndPersistsBoundedSupport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val models = File(context.noBackupFilesDir, "discovery-model")
        val topicBundle = File(models, "generative/current/${GenerativeModelPack.bundleFile}")
        val embeddingBundle = File(models, "current/${GemmaModelPack.bundleFile}")
        assumeTrue("Requires the installed Gemma topic model", topicBundle.isFile)
        assumeTrue("Requires the installed embedding model", embeddingBundle.isFile)
        check(Build.VERSION.SDK_INT >= 31) { "This probe expects the current device runtime" }

        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "support-first-").toFile()
        val embedding = GemmaEmbeddingProvider(File(models, "current"))
        val modelScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.SupervisorJob())
        val manager = GenerativeModelManager(models, modelScope, unmetered = { true })
        var proposalAttempts = 0L; var supportAttempts = 0L; var supportNanos = 0L
        val labeler = GemmaTopicLabeler.create(manager, File(directory, "runtime"), { true },
            backend = TopicLabelBackend.GPU,
            inferenceDiagnostics = { name, value ->
                when (name) {
                    "proposalAttempts" -> proposalAttempts += value
                    "supportAttempts" -> supportAttempts += value
                    "supportNanos" -> supportNanos += value
                }
            })
        var earlyStops = 0L; var checked = 0L; var reused = 0L
        try {
            manager.checkInstalled()
            assumeTrue("Requires a verified installed Gemma topic bundle", manager.verifiedBundle() != null)
            val texts = listOf(
                "RabbitMQ acknowledgments allow consumers to confirm successful message processing. " +
                    "Manual acknowledgment keeps a delivery unacknowledged until the consumer finishes its work. " +
                    "If a consumer disconnects before acknowledging, the broker requeues the message for another attempt. " +
                    "Applications must make repeated processing safe because a crash can cause redelivery.",
                "RabbitMQ acknowledgments confirm that a consumer processed a delivered message successfully. " +
                    "With manual acknowledgment the broker waits until processing finishes before removing the message. " +
                    "A disconnected consumer leaves unfinished deliveries available for requeueing and redelivery. " +
                    "Consumers should handle duplicate processing safely because failures can trigger another delivery attempt."
            )
            val documents = texts.mapIndexed { index, text ->
                val split = text.length / 2
                ReaderDocument.fromChunkTexts("support${index + 1}", "RabbitMQ delivery $index",
                    listOf(text.substring(0, split), text.substring(split)))
            }
            val cache = FileDocumentCache(File(directory, "cache"))
            val index = FileSemanticIndexStore(File(directory, "index"))
            documents.forEach { cache.put(CachedDocument(it, null, Instant.EPOCH)) }
            val controller = DiscoveryController(cache, index, FileDiscoveryInterestStore(File(directory, "interests")),
                embedding, modelAvailable = { true }, topicPolicy = GemmaModelPack.topicPolicy,
                recommendationThreshold = GemmaModelPack.recommendationThreshold,
                topicStore = FileTopicStore(File(directory, "topics"), allowFreshInitialization = true),
                topicLabelRuntime = labeler, topicLabelModelAvailable = { true },
                topicRetrievalStore = FileTopicRetrievalStore(File(directory, "matches")),
                proposalProgressStore = FileProposalProgressStore(File(directory, "progress")),
                topicSupportIdentity = { GenerativeModelPack.supportIdentity(TopicLabelBackend.GPU.provenance) },
                topicProposalIdentity = { GenerativeModelPack.proposalIdentity(TopicLabelBackend.GPU.provenance) },
                mayContinueIndexing = { true },
                workDiagnostics = { values ->
                    earlyStops += values["conceptScanEarlyStopped"] ?: 0
                    checked += values["supportCandidatesChecked"] ?: 0
                    reused += values["supportReused"] ?: 0
                })
            controller.resume()
            val entries = documents.map { ReaderIndexEntry(it.pageId, it.title) }
            withTimeout(12 * 60_000) {
                do {
                    controller.reconcile(entries, true)
                    controller.refreshTopics()
                } while (controller.state.value.publishedTopics.isEmpty())
            }
            // Admission is allowed before corpus membership scanning; let the membership
            // scheduler run a bounded tail so the early-support checkpoint is observable.
            repeat(20) {
                controller.reconcile(entries, true)
                controller.refreshTopics()
            }
            val published = controller.state.value.publishedTopics
            assertTrue("Real Gemma did not publish an independently supported topic", published.isNotEmpty())
            assertTrue("The probe expected a verified Note membership", published.any { it.topic.matchingMembers.isNotEmpty() })
            assertTrue("The real proposal runtime was not invoked", proposalAttempts > 0)
            assertTrue("The real support runtime was not invoked", supportAttempts > 0)
            assertTrue("The expected bounded check did not run", checked > 0)
            assertTrue("No verified support decision was observed", earlyStops + reused > 0)
            assertTrue("Support latency was not sampled", supportNanos > 0)
            val earlyCheckpoint = File(directory, "progress").listFiles().orEmpty()
                .filter { it.name.startsWith("proposal-pending-note-scan-") }
                .mapNotNull { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                .any { raw ->
                    raw.contains("\"completion\":\"SUPPORTED_EARLY\"") &&
                        Regex("\"retrievedCandidates\":(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let { retrieved ->
                                Regex("\"examinedCandidates\":(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                                    ?.let { examined -> retrieved > examined }
                            } == true
                }
            assertTrue("A real early-support checkpoint with an unchecked tail was not persisted", earlyCheckpoint)
            val concept = checkNotNull(controller.state.value.topicSnapshot.topics.firstNotNullOfOrNull { it.canonicalConcept }) {
                "A published topic must have canonical evidence"
            }
            // A tiny two-Note fixture can finish coverage. The invariant that must hold is
            // that an unchecked tail is never reported as complete passage coverage.
            assertTrue("Unchecked coverage was presented as a completed scan",
                published.all { topic ->
                    val evidence = topic.topic.canonicalConcept
                    evidence == null || evidence.uncheckedRevisions.isEmpty() || !evidence.passageCoverageComplete
                })
            android.util.Log.i("SupportFirstDevice", "proposalAttempts=$proposalAttempts supportAttempts=$supportAttempts " +
                "supportMeanMs=${supportNanos / supportAttempts / 1_000_000} earlyStops=$earlyStops checked=$checked " +
                "unchecked=${concept.uncheckedRevisions.size} supported=${concept.supported.size} " +
                "matchingComplete=${concept.matchingComplete} passageComplete=${concept.passageCoverageComplete} " +
                "members=${published.sumOf { it.topic.matchingMembers.size }} " +
                "device=${Build.MODEL} api=${Build.VERSION.SDK_INT}")
            controller.pause()
            controller.closeLabels()
            embedding.release()
        } finally {
            modelScope.cancel()
            directory.deleteRecursively()
        }
    }
}
