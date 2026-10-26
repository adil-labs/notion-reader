package com.example.generativediscoveryprobe

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Public probe process only. PSS and Android 17 anonymous+swap accounting remain distinct. */
internal object ProbeMemory {
    fun snapshot(context: Context): JSONObject {
        val manager = context.getSystemService(ActivityManager::class.java)
        val system = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val heap = Runtime.getRuntime()
        val process = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val status = File("/proc/self/status").readLines().mapNotNull {
            val parts = it.split(':', limit = 2)
            if (parts.size == 2) parts[0] to parts[1].trim().substringBefore(' ').toLongOrNull() else null
        }.toMap()
        val anon = status["RssAnon"]
        val swap = status["VmSwap"]
        return JSONObject().put("javaAllocatedBytes", heap.totalMemory() - heap.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("processPssKiB", process.totalPss)
            .put("anonRssKiB", anon ?: JSONObject.NULL).put("swapKiB", swap ?: JSONObject.NULL)
            .put("anonPlusSwapKiB", if (anon != null && swap != null) anon + swap else JSONObject.NULL)
            .put("deviceAvailableBytes", system.availMem).put("deviceTotalBytes", system.totalMem)
            .put("deviceThresholdBytes", system.threshold).put("deviceLowMemory", system.lowMemory)
    }

    fun recentExits(context: Context): JSONArray {
        val result = JSONArray()
        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 10).forEach { exit ->
                    result.put(JSONObject().put("timestamp", exit.timestamp).put("reason", exit.reason)
                        .put("status", exit.status).put("importance", exit.importance)
                        .put("description", exit.description?.take(300))
                        .put("memoryLimiter", exit.reason == ApplicationExitInfo.REASON_OTHER &&
                            exit.description?.contains("MemoryLimiter:AnonSwap") == true))
                }
        }
        return result
    }
}
