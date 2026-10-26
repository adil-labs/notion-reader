package com.example.notionreader.data.discovery

import android.content.Context
import android.os.SystemClock
import com.example.generativediscoveryprobe.ProbeMemory
import com.example.notionreader.application.*
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.document.StorageCategory
import com.example.notionreader.domain.document.StorageFailure
import com.example.notionreader.domain.document.StorageRefused
import com.example.notionreader.domain.document.StorageWriteGate
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.settings.DiscoveryBackend
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Shipping controller/stores and both real engines, in a separate app with public inputs only.
 * This deliberately exercises the adapter before Reader activation; it does not change its gate. */
internal object ProductionWorkerProbe {
    private fun verify(file: File, artifact: ModelArtifact) {
        require(file.length() == artifact.bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == artifact.sha256)
    }
    fun run(context: Context, backendName: String, queryAfterMillis: Int, saveForRestart: Boolean = false, lifecycleAction: String? = null, refuseLabelStorage: Boolean = false, deleteManagedModel: Boolean = false, queryDuringInitialization: Boolean = false): JSONObject = runBlocking {
        require(lifecycleAction == null || lifecycleAction in setOf("withdraw", "reset", "model-delete"))
        require(lifecycleAction != "model-delete" || deleteManagedModel)
        val models = requireNotNull(context.getExternalFilesDir(null))
        verify(File(models, GenerativeModelPack.bundleFile), GenerativeModelPack.artifact)
        verify(File(models, GemmaModelPack.bundleFile), GemmaModelPack.artifacts.single())
        // Only this probe-owned fixture directory is replaced. Never access Reader's package data.
        val directory = File(context.filesDir, "public-worker-fixture")
        check(!directory.exists() || directory.deleteRecursively()); check(directory.mkdirs())
        val cache = FileDocumentCache(File(directory, "cache"))
        val index = FileSemanticIndexStore(File(directory, "index"))
        val progress = FileSemanticIndexStore(File(directory, "passages"))
        val graph = FileTopicStore(File(directory, "topics"))
        val checkpoint = FileTopicStore(File(directory, "checkpoint"))
        val matches = FileTopicRetrievalStore(File(directory, "matches"))
        var refuseWrites = refuseLabelStorage
        val refusedWrites = AtomicInteger()
        val labels = FileTopicLabelStore(File(directory, "labels"), object : StorageWriteGate {
            override suspend fun <T> write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T {
                check(category == StorageCategory.DISCOVERY && additionalPeakBytes > 0)
                if (refuseWrites) { refusedWrites.incrementAndGet(); throw StorageRefused(StorageFailure.DEVICE_SPACE) }
                return block()
            }
        })
        val interests = FileDiscoveryInterestStore(File(directory, "interests"))
        val text = "Seed germination requires suitable moisture and temperature. A dry seed absorbs water before sprouting. Unsuitable temperatures prevent successful germination."
        repeat(3) { i ->
            cache.put(CachedDocument(ReaderDocument.fromChunkTexts("public-seed-$i", "Seed evidence $i", listOf(text)), null, Instant.EPOCH))
        }
        val realEmbedding = GemmaEmbeddingProvider(models)
        realEmbedding.selectBackend(DiscoveryBackend.CPU)
        val embeddingCalls = AtomicInteger(); val labelCalls = AtomicInteger()
        val nativeActive = AtomicInteger(); val nativeCancellations = AtomicInteger()
        val events = JSONArray(); val samples = JSONArray(); val idle = JSONArray()
        val eventsLock = Any()
        fun event(name: String) = synchronized(eventsLock) {
            events.put(JSONObject().put("event", name).put("elapsedMillis", SystemClock.elapsedRealtime()))
        }
        val originalBundle = File(models, GenerativeModelPack.bundleFile)
        var modelBundle = originalBundle
        var releaseController: DiscoveryController? = null
        val manager = if (deleteManagedModel) GenerativeModelManager(File(models, "public-managed-model"), this,
            unmetered = { true }, onDeleted = {
                check(modelBundle.isFile) { "Model files disappeared before resource release" }
                event("managedModelDeletionCallback")
                requireNotNull(releaseController).releaseTopicLabelModel()
                check(nativeActive.get() == 0 && modelBundle.isFile) { "Deletion callback did not await native release" }
                event("managedModelNativeResourcesReleased")
            }).also {
                check(it.modelDirectory.mkdirs() || it.modelDirectory.isDirectory)
                modelBundle = File(it.modelDirectory, GenerativeModelPack.bundleFile)
                check(!modelBundle.exists() && originalBundle.renameTo(modelBundle))
                File(it.modelDirectory, "installed").writeText(GenerativeModelPack.revision)
                it.checkInstalled()
                check(it.state.value.installed && it.state.value.storageBytes!! >= GenerativeModelPack.artifact.bytes)
            } else null
        val embedding = object : EmbeddingProvider by realEmbedding, ObservableEmbeddingRuntime by realEmbedding {
            override suspend fun embed(text: String): List<Float> {
                embeddingCalls.incrementAndGet(); event("embeddingStart")
                return realEmbedding.embed(text).also { event("embeddingDone") }
            }
            override fun release() { event("embeddingRelease"); realEmbedding.release() }
        }
        val backend = if (backendName == "GPU") TopicLabelBackend.GPU else TopicLabelBackend.CPU
        val identity = GenerativeModelPack.identity(backend.provenance)
        val nativeStarted = CompletableDeferred<Unit>()
        val initializationStarted = CompletableDeferred<Unit>()
        val initializationCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        var installed = true
        fun modelAvailable() = installed && (manager?.state?.value?.installed != false)
        var permitted = true
        val memory = DiscoveryMemoryGate({
            val facts = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(facts)
            DiscoveryDeviceMemory(facts.availMem, facts.totalMem, facts.threshold, facts.lowMemory)
        }, SystemClock::elapsedRealtime)
        val labeler = GemmaTopicLabeler(identity, ::modelAvailable, {
            event("labelEngineOpening")
            initializationStarted.complete(Unit)
            val engine = LiteRtTopicLabelEngine(modelBundle, context.cacheDir, backend)
            initializationCompleted.set(true)
            synchronized(eventsLock) { idle.put(ProbeMemory.snapshot(context).put("elapsedMillis", SystemClock.elapsedRealtime())) }
            event("labelEngineOpened")
            object : NativeTopicLabelEngine {
                override fun conversation(): NativeTopicLabelSession {
                    val session = engine.conversation()
                    return object : NativeTopicLabelSession {
                        override fun countPromptTokens(prompt: String) = session.countPromptTokens(prompt)
                        override fun start(prompt: String, chunk: (String) -> Unit, done: (Throwable?) -> Unit) {
                            labelCalls.incrementAndGet(); event("nativeGenerationStart")
                            nativeActive.incrementAndGet()
                            session.start(prompt, chunk) { failure ->
                                nativeActive.decrementAndGet()
                                if (failure is CancellationException) nativeCancellations.incrementAndGet()
                                event(if (failure is CancellationException) "nativeGenerationCancelled" else "nativeGenerationTerminal")
                                done(failure)
                            }
                            nativeStarted.complete(Unit)
                        }
                        override fun cancel() { event("nativeCancelRequested"); session.cancel() }
                        override fun close() { session.close(); event("conversationClosed") }
                    }
                }
                override fun close() { engine.close(); event("labelEngineClosed") }
            }
        }, { permitted && memory.allows(force = true) }, Dispatchers.IO, { stage, failure ->
            event("diagnostic:$stage:${failure.javaClass.simpleName}")
        })
        fun controller() = DiscoveryController(cache, index, interests, embedding, topicStore = graph,
            topicCheckpointStore = checkpoint, passageProgressStore = progress, topicPolicy = GemmaModelPack.topicPolicy,
            recommendationThreshold = GemmaModelPack.recommendationThreshold, topicLabelRuntime = labeler,
            topicLabelModelAvailable = ::modelAvailable, topicLabelIdentity = { identity }, topicLabelStore = labels,
            topicRetrievalStore = matches,
            mayContinueIndexing = { permitted && memory.allows() })
        val report = JSONObject().put("scope", "public fixture; shipping controller/stores and actual CPU embedding/Gemma labeling; not representative scale or process death")
            .put("labelIdentity", identity.toString()).put("sourceNotes", 3).put("distinctSourceBodies", 1)
            .put("readerActivationGateChanged", false).put("queryAfterNativeStartMillis", queryAfterMillis)
            .put("before", ProbeMemory.snapshot(context)).put("priorExits", ProbeMemory.recentExits(context))
        val progressFile = File(models, "production-worker-progress-$backendName-query$queryAfterMillis.json")
        fun saveProgress(telemetryOnly: Boolean = false) = synchronized(eventsLock) {
            val value = if (telemetryOnly) JSONObject().put("stage", "sampling").put("pid", android.os.Process.myPid()) else report
            value.put("events", events).put("modelLoadedIdleSamples", idle).put("samples", samples)
            val target = if (telemetryOnly) File(models, progressFile.name.removeSuffix(".json") + ".telemetry.json") else progressFile
            val temporary = File(models, target.name + ".tmp")
            temporary.writeText(value.toString(2))
            check(temporary.renameTo(target))
        }
        report.put("stage", "starting").put("pid", android.os.Process.myPid())
        saveProgress()
        val sampler = launch(Dispatchers.IO) {
            while (isActive && samples.length() < 1800) {
                val sample = ProbeMemory.snapshot(context).put("elapsedMillis", SystemClock.elapsedRealtime())
                synchronized(eventsLock) { samples.put(sample) }
                if (samples.length() % 10 == 0) saveProgress(telemetryOnly = true)
                delay(500)
            }
        }
        try {
            var active = controller(); releaseController = active; active.resume()
            val started = SystemClock.elapsedRealtime()
            val first = async(Dispatchers.Default) { active.reconcile(emptyList(), true) }
            if (queryDuringInitialization) {
                withTimeout(180_000) { initializationStarted.await() }
                delay(50)
                check(!initializationCompleted.get() && labelCalls.get() == 0) { "Query missed engine initialization" }
                event("foregroundQueryDuringInitializationRequested")
                val queryStarted = SystemClock.elapsedRealtime()
                active.search("Seed germination requires suitable moisture and temperature")
                event("foregroundQueryDuringInitializationCompleted")
                check(active.state.value.searchFailure == null && nativeActive.get() == 0)
                check(labelCalls.get() == 0) { "Cancelled initialization started native generation" }
                report.put("initializationContentionQueryMillis", SystemClock.elapsedRealtime() - queryStarted)
                    .put("nativeGenerationCallsBeforeInitializationQueryReturned", labelCalls.get())
            }
            if (lifecycleAction != null) {
                withTimeout(180_000) { nativeStarted.await() }
                delay(500)
                check(nativeActive.get() > 0) { "Generation finished before lifecycle action" }
                event(when (lifecycleAction) {
                    "withdraw" -> "sourceWithdrawalRequested"
                    "reset" -> "resetRequested"
                    else -> "managedModelDeletionRequested"
                })
                when (lifecycleAction) {
                    "withdraw" -> cache.remove("public-seed-2")
                    "reset" -> withTimeout(30_000) { active.clearIndex() }
                    else -> {
                        withTimeout(30_000) { requireNotNull(manager).delete() }
                        event("managedModelFilesDeleted")
                        check(!modelBundle.exists() && !originalBundle.exists() && !manager!!.state.value.installed)
                        check(manager.state.value.storageBytes == 0L)
                    }
                }
                withTimeout(30_000) { first.await() }
                installed = false // Disable replacement only after observing the source-triggered terminal.
                active.reconcile(emptyList(), false); active.refreshTopics()
                check(nativeActive.get() == 0 && nativeCancellations.get() > 0)
                val current = active.state.value.topicSnapshot.topics
                if (lifecycleAction == "withdraw") check(current.flatMap { it.members + it.matchingMembers }
                    .none { it.pageId == canonicalSemanticId("public-seed-2") })
                else if (lifecycleAction == "reset") check(current.isEmpty() && index.pageIds().isEmpty())
                else check(index.pageIds().size == 3 && File(models, GemmaModelPack.bundleFile).isFile)
                check(current.none { it.currentGeneratedLabel != null })
                check(labels.bytes() == 0L) { "Cancelled generation wrote a label or cursor" }
                check(cache.count() == if (lifecycleAction == "withdraw") 2 else 3)
                report.put("lifecycleActionDuringNativeGeneration", lifecycleAction)
                    .put("nativeCancellationTerminals", nativeCancellations.get())
                    .put("staleSavedLabelBytes", labels.bytes()).put("stage", "completed")
                return@runBlocking report
            }
            if (queryAfterMillis > 0) {
                withTimeout(180_000) { nativeStarted.await() }
                delay(queryAfterMillis.toLong())
                check(nativeActive.get() > 0) { "Native generation completed before the contention query" }
                val queryStarted = SystemClock.elapsedRealtime(); event("foregroundQueryRequested")
                active.search("Seed germination requires suitable moisture and temperature")
                event("foregroundQueryCompleted")
                report.put("foregroundContentionQueryMillis", SystemClock.elapsedRealtime() - queryStarted)
                    .put("foregroundQueryFailure", active.state.value.searchFailure ?: JSONObject.NULL)
                check(active.state.value.searchFailure == null)
                check(nativeCancellations.get() > 0) { "No native cancellation terminal was observed" }
            }
            first.await()
            if (refuseLabelStorage) {
                check(refusedWrites.get() > 0 && labelCalls.get() > 0) { "Storage refusal was not exercised after native generation" }
                check(active.state.value.processingFailure?.contains("storage", ignoreCase = true) == true) {
                    "Missing actionable storage failure: ${active.state.value.processingFailure}"
                }
                check(labels.bytes() == 0L && active.state.value.topicSnapshot.topics.none { it.currentGeneratedLabel != null })
                check(index.pageIds().size == 3 && cache.count() == 3)
                report.put("labelStorageAdmissionRefusalPreservedSourcesAndIndex", true)
                    .put("refusedLabelWrites", refusedWrites.get()).put("storageFailureMessage", active.state.value.processingFailure)
                refuseWrites = false
                active.reconcile(emptyList(), true)
            }
            report.put("stage", "initial-reconcile-returned"); saveProgress()
            repeat(12) {
                if (active.hasPendingWork && active.state.value.processingFailure == null) active.reconcile(emptyList(), true)
            }
            report.put("initialWorkMillis", SystemClock.elapsedRealtime() - started)
                .put("initialEmbeddingCalls", embeddingCalls.get()).put("initialLabelCalls", labelCalls.get())
            saveProgress()
            check(!active.hasPendingWork) { "Unfinished worker: ${active.state.value.processingFailure}" }
            val initial = active.state.value.topicSnapshot
            check(initial.topics.isNotEmpty() && initial.topics.all { it.currentGeneratedLabel != null }) { "Missing current names: ${active.state.value.topicStatus}" }
            check(initial.topics.all { it.currentTopicMatches != null && it.matchingMembers.isNotEmpty() }) {
                "Missing complete matching Notes: ${active.state.value.topicStatus}"
            }
            val names = JSONArray()
            initial.topics.forEach { topic -> names.put(JSONObject().put("id", topic.id).put("label", topic.effectiveLabel)
                .put("definition", topic.currentGeneratedLabel!!.definition)
                .put("seedMembers", JSONArray(topic.members.map { it.pageId }))
                .put("members", JSONArray(topic.matchingMembers.map { it.pageId })).put("citations", JSONArray(topic.currentGeneratedLabel!!.evidenceIds))) }
            report.put("initialTopics", names)
            val firstId = initial.topics.first().id
            active.followTopic(firstId, "My seed notes", "seed germination")
            val labelsBefore = labelCalls.get(); val embeddingsBefore = embeddingCalls.get()
            active.reconcile(emptyList(), true)
            check(labelCalls.get() == labelsBefore && embeddingCalls.get() == embeddingsBefore)
            report.put("unchangedReconcileAdditionalNativeCalls", 0)
            if (saveForRestart) {
                val receipt = JSONObject().put("savedPid", android.os.Process.myPid()).put("topics", names)
                    .put("labelIdentity", identity.toString())
                File(directory, "restart-receipt.json").writeText(receipt.toString(2))
                report.put("stage", "saved-for-process-restart").put("savedPid", android.os.Process.myPid())
                return@runBlocking report
            }
            if (manager != null) {
                check(modelBundle.isFile && modelAvailable())
                event("managedModelDeletionRequested")
                withTimeout(30_000) { manager.delete() }
                event("managedModelFilesDeleted")
                check(!modelBundle.exists() && !originalBundle.exists() && !manager.state.value.installed)
                check(manager.state.value.storageBytes == 0L && nativeActive.get() == 0)
                check(index.pageIds().size == 3 && cache.count() == 3)
                check(File(models, GemmaModelPack.bundleFile).length() == GemmaModelPack.artifacts.single().bytes)
                report.put("actualManagerDeletedModelFilesAndReleasedNativeResources", true)
                    .put("deletedModelOwnedBytes", manager.state.value.storageBytes)
            } else { installed = false; active.releaseTopicLabelModel() }
            // Recreate the actual application controller over the existing files; this is not process-death evidence.
            active = controller(); active.reconcile(emptyList(), false); active.refreshTopics()
            check(active.state.value.topicSnapshot.topics.map { it.effectiveLabel } == initial.topics.map { it.effectiveLabel })
            check(active.state.value.topicSnapshot.topics.map { it.matchingMembers.map { member -> member.pageId } } ==
                initial.topics.map { it.matchingMembers.map { member -> member.pageId } })
            check(active.state.value.topicSnapshot.topics.all { it.currentTopicMatches != null })
            check(active.state.value.interests.single().displayName == "My seed notes")
            check(labelCalls.get() == labelsBefore)
            check(embeddingCalls.get() == embeddingsBefore)
            report.put("modelAvailabilityWithdrawalAndControllerRecreationRetainedNamesAndAlias", true)
                .put("controllerRecreationRestoredMatchesWithoutNativeWork", true)
            val queryTimes = JSONArray()
            repeat(10) { i ->
                val begin = SystemClock.elapsedRealtime()
                active.search(if (i % 2 == 0) "Seed germination requires suitable moisture and temperature" else "Water absorption before seed sprouting")
                check(active.state.value.searchFailure == null)
                queryTimes.put(SystemClock.elapsedRealtime() - begin)
            }
            report.put("smallFixtureQueryMillis", queryTimes)
            cache.remove("public-seed-2"); active.reconcile(emptyList(), false); active.refreshTopics()
            val removed = canonicalSemanticId("public-seed-2")
            check(initial.topics.any { group -> group.members.any { it.pageId == removed } })
            check(active.state.value.topicSnapshot.topics.flatMap { it.members + it.matchingMembers }.none { it.pageId == removed })
            for (topic in initial.topics.filter { group -> group.members.any { it.pageId == removed } }) {
                check(labels.read(topic.id) == null) { "Withdrawn evidence retained a saved label" }
                check(matches.read(topic.id) == null) { "Withdrawn concept retained its query or matches" }
            }
            report.put("sourceWithdrawalExcludedRemovedNote", true)
                .put("sourceWithdrawalRemovedAffectedSavedNames", true)
            active.clearIndex()
            check(labels.bytes() == 0L && matches.bytes() == 0L && active.state.value.topicSnapshot.topics.isEmpty())
            check(interests.read().single().displayName == "My seed notes" && cache.count() == 2)
            report.put("resetClearedNamesAndProgressButRetainedAliasAndCachedNotes", true)
                .put("events", events).put("modelLoadedIdleSamples", idle)
            report.put("stage", "completed")
        } catch (failure: Throwable) {
            report.put("stage", "failed").put("error", failure.javaClass.name).put("message", failure.message)
            throw failure
        } finally {
            withContext(NonCancellable) { sampler.cancelAndJoin(); labeler.close(); realEmbedding.release() }
            // Recover the staged probe artifact on failure before deletion; a successful deletion
            // intentionally consumes this probe's copy and must be explicitly re-staged by the host.
            if (manager != null && modelBundle.exists()) check(!originalBundle.exists() && modelBundle.renameTo(originalBundle))
            report.put("samples", samples).put("afterClose", ProbeMemory.snapshot(context))
            saveProgress()
        }
        report
    }

    fun restoreAfterProcessDeath(context: Context, backendName: String): JSONObject = runBlocking {
        val directory = File(context.filesDir, "public-worker-fixture")
        val receipt = JSONObject(File(directory, "restart-receipt.json").readText())
        val oldPid = receipt.getInt("savedPid"); val newPid = android.os.Process.myPid()
        check(oldPid != newPid) { "Process replacement is not observed" }
        val cache = FileDocumentCache(File(directory, "cache"))
        val index = FileSemanticIndexStore(File(directory, "index"))
        val interests = FileDiscoveryInterestStore(File(directory, "interests"))
        val provider = GemmaEmbeddingProvider(requireNotNull(context.getExternalFilesDir(null)))
        var nativeCalls = 0
        val embedding = object : EmbeddingProvider by provider {
            override suspend fun embed(text: String): List<Float> { nativeCalls++; error("Restore cannot embed") }
        }
        val backend = if (backendName == "GPU") TopicLabelBackend.GPU else TopicLabelBackend.CPU
        val identity = GenerativeModelPack.identity(backend.provenance)
        check(identity.toString() == receipt.getString("labelIdentity"))
        val unavailable = object : TopicLabelRuntime {
            override suspend fun label(request: TopicLabelRequest): TopicLabelRun { nativeCalls++; error("Restore cannot generate") }
            override suspend fun close() {}
        }
        val started = SystemClock.elapsedRealtime()
        try {
            val controller = DiscoveryController(cache, index, interests, embedding,
                topicStore = FileTopicStore(File(directory, "topics")),
                topicCheckpointStore = FileTopicStore(File(directory, "checkpoint")),
                passageProgressStore = FileSemanticIndexStore(File(directory, "passages")),
                topicPolicy = GemmaModelPack.topicPolicy, topicLabelStore = FileTopicLabelStore(File(directory, "labels")),
                topicRetrievalStore = FileTopicRetrievalStore(File(directory, "matches")),
                topicLabelRuntime = unavailable, topicLabelModelAvailable = { false }, topicLabelIdentity = { identity })
            controller.reconcile(emptyList(), false); controller.refreshTopics()
            val expected = receipt.getJSONArray("topics")
            val actual = controller.state.value.topicSnapshot.topics
            check(actual.size == expected.length() && actual.isNotEmpty())
            for (i in 0 until expected.length()) {
                val entry = expected.getJSONObject(i)
                val restored = actual.single { it.id == entry.getString("id") }
                check(restored.currentGeneratedLabel != null && restored.effectiveLabel == entry.getString("label"))
                check(restored.currentTopicMatches != null)
                check(restored.matchingMembers.map { it.pageId } == (0 until entry.getJSONArray("members").length())
                    .map { entry.getJSONArray("members").getString(it) })
            }
            check(nativeCalls == 0 && cache.count() == 3)
            check(controller.state.value.interests.single().displayName == "My seed notes")
            JSONObject().put("scope", "actual probe-process replacement; shipping files/controller; model unavailable; public fixture only")
                .put("savedPid", oldPid).put("restoredPid", newPid).put("restoredTopics", actual.size)
                .put("nativeCalls", nativeCalls).put("restoredAlias", "My seed notes")
                .put("restoreMillis", SystemClock.elapsedRealtime() - started).put("afterRestore", ProbeMemory.snapshot(context))
        } finally { provider.release() }
    }
}
