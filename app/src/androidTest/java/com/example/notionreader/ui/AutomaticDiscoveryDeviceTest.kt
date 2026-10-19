package com.example.notionreader.ui

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.example.notionreader.ReaderApplication
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.data.local.DataStoreSettingsRepository
import com.example.notionreader.data.local.FileTopicStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.CachedDocument
import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.settings.DiscoveryEffort
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** Uses the real application cache/pipeline and normal scheduler, on an owned empty emulator only. */
class AutomaticDiscoveryDeviceTest {
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }

    @Test fun anEffortChangeBeforeSchedulerRestartReplacesThePersistedConstraint() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Battery simulation requires the owned emulator" }
        val application = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val name = "discovery-effort-restart-probe"
        val work = WorkManager.getInstance(application)
        val original = com.example.notionreader.DiscoveryScheduler(application, name, "$name-recovery")
        try {
            shell("dumpsys battery unplug"); shell("dumpsys battery set level 80")
            original.request(DiscoveryEffort.CHARGING_ONLY, true)
            withTimeout(10_000) {
                while (withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(name).get() }.none {
                    it.state == WorkInfo.State.ENQUEUED && "discovery-effort:CHARGING_ONLY" in it.tags
                }) delay(100)
            }
            val restarted = com.example.notionreader.DiscoveryScheduler(application, name, "$name-recovery")
            restarted.request(DiscoveryEffort.BALANCED, true)
            withTimeout(10_000) {
                while (withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(name).get() }.none {
                    it.state != WorkInfo.State.CANCELLED && "discovery-effort:BALANCED" in it.tags && !it.constraints.requiresCharging()
                }) delay(100)
            }
        } finally { original.cancel(); shell("dumpsys battery reset") }
    }

    @Test fun setupFinishesRealGemmaTopicsAndConnectionsWithTheScreenOffWithoutContinue() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Fixture staging is restricted to the owned emulator" }
        val application = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val app = application.host.app
        val controller = app.discovery!!
        val field = ReaderApp::class.java.getDeclaredField("documentCache").apply { isAccessible = true }
        val cache = field.get(app) as DocumentCache
        assertEquals("Never replace an existing library for this test", 0, cache.count())
        assertFalse("Never interrupt playback", app.session.state.value.isPlaying)
        withTimeout(30_000) { while (app.discoveryModel?.state?.value?.installed != true) delay(100) }
        val settings = app.settingsRepo as DataStoreSettingsRepository
        val before = settings.readCurrentSettings()
        val wasPaused = controller.state.value.paused
        val nonce = android.os.SystemClock.elapsedRealtime().toString()
        val examples = listOf(
            Triple("automaticqueuea", "RabbitMQ delivery notes", "RabbitMQ acknowledgments allow consumers to confirm successful message processing. Manual acknowledgment keeps a delivery unacknowledged until the consumer finishes its work. If a consumer disconnects before acknowledging, the broker requeues the message for another attempt. Applications must make repeated processing safe because a crash can cause redelivery. Read RabbitMQ recovery notes."),
            Triple("automaticqueueb", "RabbitMQ recovery notes", "RabbitMQ acknowledgments confirm that a consumer has processed a delivered message successfully. With manual acknowledgment the broker waits until processing finishes before removing the message. A disconnected consumer leaves unfinished deliveries available for requeueing and redelivery. Consumers should handle duplicate processing safely because failures can trigger another delivery attempt."),
            Triple("automaticsqla", "PostgreSQL snapshots", "PostgreSQL transaction isolation determines which concurrent changes a query can observe. Repeatable read uses a stable database snapshot throughout the transaction. Concurrent updates can cause serialization failures, requiring the application to retry the transaction. Choosing an isolation level balances consistency guarantees with contention and retry cost."),
            Triple("automaticsqlb", "PostgreSQL consistency", "PostgreSQL transaction isolation controls visibility of changes made by other transactions. Under repeatable read each query observes the same transaction snapshot. Conflicting concurrent writes may require a transaction retry after a serialization failure. Applications must choose isolation guarantees and implement retry behavior appropriate to their consistency requirements.")
        ).map { (id, title, body) -> Triple(id + nonce, title, body) }
        try {
            settings.updateSettings { it.copy(discoveryEffort = DiscoveryEffort.CHARGING_ONLY) }
            assertEquals(DiscoveryEffort.CHARGING_ONLY, settings.readCurrentSettings().discoveryEffort)
            settings.updateSettings { it.copy(discoveryEffort = DiscoveryEffort.BALANCED) }
            assertEquals(DiscoveryEffort.BALANCED, settings.readCurrentSettings().discoveryEffort)
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            delay(500)
            val started = android.os.SystemClock.elapsedRealtime()
            examples.forEachIndexed { i, (id, title, body) ->
                val doc = ReaderDocument.fromChunkTexts(id, title, listOf(body))
                cache.put(CachedDocument(doc.copy(sections = doc.sections.map {
                    it.copy(title = if (i < 2) "RabbitMQ acknowledgments" else "PostgreSQL transaction isolation")
                }), null, Instant.now()))
            }
            controller.resume() // Setup activation once; no further requests drive processing.
            val work = WorkManager.getInstance(application)
            val topics = FileTopicStore(File(application.noBackupFilesDir, "discovery-topics"))
            withTimeout(180_000) {
                while (true) {
                    val snapshot = controller.state.value
                    val completed = withContext(Dispatchers.IO) {
                        work.getWorkInfosForUniqueWork("discovery-pending").get()
                    }.any { it.state == WorkInfo.State.SUCCEEDED &&
                        it.outputData.getString("windowResult") == "COMPLETE" &&
                        it.outputData.getInt("processId", -1) == android.os.Process.myPid() &&
                        !it.outputData.getBoolean("startedInteractive", true) }
                    val latest = topics.readMetadata()
                    if (completed && snapshot.indexedCount == 4 && latest?.complete == true &&
                        latest.connectionPassComplete && latest.topics.flatMap { it.members }.map { it.pageId }.toSet()
                            .containsAll(examples.map { it.first })) break
                    delay(200)
                }
            }
            val snapshot = topics.readMetadata()!!
            assertTrue("Supported topics must actually be published", snapshot.topics.isNotEmpty())
            val connections = controller.connectionWindow(snapshot, 0, 40).connections
            assertTrue("Real Gemma semantic relationships must complete", connections.any { !it.independentNamedReference })
            assertTrue("Named references must complete too", connections.any { it.independentNamedReference })
            File(application.getExternalFilesDir(null), "automatic-discovery-device.txt").writeText(
                "api=${Build.VERSION.SDK_INT}\nindexed=4\ntopics=${snapshot.topics.size}\nconnections=${connections.size}\nnormalScheduler=true\nscreenOff=true\nelapsedMs=${android.os.SystemClock.elapsedRealtime() - started}\n")
        } finally {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            examples.forEach { cache.remove(it.first) }
            settings.updateSettings { it.copy(discoveryEffort = before.discoveryEffort) }
            if (wasPaused) controller.pause()
        }
    }
}
