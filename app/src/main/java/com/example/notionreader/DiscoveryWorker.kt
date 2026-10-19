package com.example.notionreader

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.withContext
import com.example.notionreader.application.DiscoveryWindowResult

/** No pipeline of its own: scheduling enters the same serialized coordinator as foreground work. */
class DiscoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val started = android.os.SystemClock.elapsedRealtime()
        val interactive = (applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        val result = (applicationContext as ReaderApplication).runScheduledDiscovery()
        if (BuildConfig.DEBUG && result == DiscoveryWindowResult.COMPLETE) withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Aggregate private developer evidence; no source IDs, text, queries or labels.
            try { java.io.File(applicationContext.cacheDir, "discovery-window-receipt.json").writeText(
                "{\"processId\":${android.os.Process.myPid()},\"startedInteractive\":$interactive,\"result\":\"COMPLETE\",\"finishedAtElapsedMillis\":${android.os.SystemClock.elapsedRealtime()}}")
            } catch (_: java.io.IOException) { /* Optional evidence never changes pipeline success. */ }
        }
        return when (result) {
            DiscoveryWindowResult.PENDING, DiscoveryWindowResult.DEFERRED -> Result.retry()
            else -> Result.success(workDataOf("windowResult" to result.name, "startedInteractive" to interactive,
                "startedAtElapsedMillis" to started, "processId" to android.os.Process.myPid()))
        }
    }
}
