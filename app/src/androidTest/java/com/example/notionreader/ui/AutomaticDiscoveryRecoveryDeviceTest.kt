package com.example.notionreader.ui

import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.example.notionreader.ReaderApplication
import com.example.notionreader.application.ReaderApp
import com.example.notionreader.data.local.*
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.document.*
import com.example.notionreader.domain.settings.DiscoveryEffort
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** Two wrapper invocations bracket a real host-observed process death; use the repository probe. */
class AutomaticDiscoveryRecoveryDeviceTest {
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }

    @Test fun interruptedPassagesRecoverInANewBackgroundProcess() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Recovery probe requires the owned emulator" }
        val application = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val app = application.host.app
        val controller = app.discovery!!
        val cacheField = ReaderApp::class.java.getDeclaredField("documentCache").apply { isAccessible = true }
        val cache = cacheField.get(app) as DocumentCache
        val marker = File(application.filesDir, "automatic-recovery-state.json")
        val preparing = File(application.filesDir, "automatic-recovery-prepare").exists()
        if (preparing) {
            assertEquals("Never replace a library to prepare the recovery probe", 0, cache.count())
            assertFalse(app.session.state.value.isPlaying)
            val settings = (app.settingsRepo as DataStoreSettingsRepository).readCurrentSettings()
            val paused = controller.state.value.paused
            val nonce = android.os.SystemClock.elapsedRealtime().toString()
            val ids = listOf("recoverya$nonce", "recoveryb$nonce")
            controller.pause()
            app.settingsRepo.updateSettings { it.copy(discoveryEffort = DiscoveryEffort.BALANCED) }
            val body = "RabbitMQ acknowledgments allow consumers to confirm successful message processing. Manual acknowledgment keeps a delivery unacknowledged until the consumer finishes its work. A disconnected consumer leaves unfinished deliveries available for requeueing and redelivery. Consumers should handle duplicate processing safely because failures can trigger another delivery attempt."
            for ((i, id) in ids.withIndex()) {
                val doc = ReaderDocument.fromChunkTexts(id, "RabbitMQ recovery study $i",
                    (1..40).map { section -> "$body Reliability example $section explains acknowledgments and broker recovery." })
                cache.put(CachedDocument(doc.copy(sections = doc.sections.map { it.copy(title = "RabbitMQ acknowledgments") }), null, Instant.now()))
            }
            marker.writeText(buildJsonObject {
                put("oldProcessId", Process.myPid()); put("effort", settings.discoveryEffort.name); put("paused", paused)
                put("ids", JsonArray(ids.map(::JsonPrimitive)))
            }.toString())
            shell("dumpsys battery set level 100"); shell("dumpsys battery set usb 1")
            shell("input keyevent KEYCODE_HOME"); shell("input keyevent KEYCODE_SLEEP")
            val progress = FileSemanticIndexStore(File(application.noBackupFilesDir, "semantic-passage-progress"))
            val index = FileSemanticIndexStore(File(application.noBackupFilesDir, "semantic-index"))
            controller.resume()
            withTimeout(60_000) {
                while (ids.none { progress.read(it)?.passages?.isNotEmpty() == true && index.read(it) == null }) delay(50)
            }
            shell("dumpsys battery unplug"); shell("dumpsys battery set level 15")
            withTimeout(15_000) { while (controller.state.value.indexing) delay(50) }
            val staged = ids.sumOf { progress.read(it)?.passages?.size ?: 0 }
            assertTrue("The probe must interrupt actual durable passage work", staged > 0)
            assertTrue("A full completed index would not prove interruption", ids.any { index.read(it) == null })
            File(application.getExternalFilesDir(null), "automatic-recovery-prepared.txt")
                .writeText("oldProcessId=${Process.myPid()}\nstagedPassages=$staged\n")
        } else {
            check(marker.isFile) { "Run tools/probe-automatic-discovery-recovery.ps1 to prepare a real process-death boundary" }
            val state = Json.parseToJsonElement(marker.readText()).jsonObject
            val ids = state.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
            try {
                val oldPid = state.getValue("oldProcessId").jsonPrimitive.int
                val receipt = Json.parseToJsonElement(File(application.filesDir, "automatic-recovery-receipt.json").readText()).jsonObject
                val headlessPid = receipt.getValue("processId").jsonPrimitive.int
                assertNotEquals("The original process must actually have died", oldPid, headlessPid)
                assertEquals("COMPLETE", receipt.getValue("result").jsonPrimitive.content)
                assertFalse(receipt.getValue("startedInteractive").jsonPrimitive.boolean)
                val topics = FileTopicStore(File(application.noBackupFilesDir, "discovery-topics"))
                val snapshot = topics.readMetadata()!!
                assertTrue(snapshot.complete && snapshot.connectionPassComplete)
                assertTrue(snapshot.topics.flatMap { it.members }.map { it.pageId }.containsAll(ids))
                val catalog = topics.connectionPage(snapshot, 0, 40)
                assertTrue("The recovered catalog must be available", catalog.available)
                assertTrue("The recovered catalog must retain semantic evidence", catalog.connections.any { !it.independentNamedReference })
                val progress = FileSemanticIndexStore(File(application.noBackupFilesDir, "semantic-passage-progress"))
                assertTrue(ids.all { progress.read(it) == null })
                File(application.getExternalFilesDir(null), "automatic-recovery-verified.txt").writeText(
                    "oldProcessId=$oldPid\nheadlessProcessId=$headlessPid\nnormalScheduler=true\nscreenOff=true\nindexed=${ids.size}\ntopics=${snapshot.topics.size}\npassageProgressRetired=true\n")
            } finally {
                ids.forEach { cache.remove(it) }
                app.settingsRepo.updateSettings { it.copy(discoveryEffort = DiscoveryEffort.valueOf(state.getValue("effort").jsonPrimitive.content)) }
                if (state.getValue("paused").jsonPrimitive.boolean) controller.pause()
                shell("dumpsys battery reset"); shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
                marker.delete(); File(application.filesDir, "automatic-recovery-receipt.json").delete()
            }
        }
    }
}
