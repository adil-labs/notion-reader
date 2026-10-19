package com.example.notionreader

import android.content.Context
import androidx.work.*
import com.example.notionreader.domain.settings.DiscoveryEffort
import java.util.concurrent.TimeUnit

/** Persistent grants of execution time for the single application-owned pipeline. */
internal class DiscoveryScheduler(context: Context,
    private val immediateName: String = IMMEDIATE, private val recoveryName: String = RECOVERY) {
    private val work = WorkManager.getInstance(context)
    @Synchronized fun request(nextEffort: DiscoveryEffort, pending: Boolean) {
        val tag = "discovery-effort:${nextEffort.name}"
        // Reconcile persisted requests, not a process-local recollection of the prior setting.
        val existing = work.getWorkInfosForUniqueWork(immediateName).get()
        if (existing.any { !it.state.isFinished && tag !in it.tags }) work.cancelUniqueWork(immediateName)
        val constraints = Constraints.Builder()
            // BatteryNotLow cannot express "30% OR external power" and could strand a
            // persisted job on low-battery charging after process death. Check exact power in-app.
            .setRequiresCharging(nextEffort == DiscoveryEffort.CHARGING_ONLY).build()
        work.enqueueUniquePeriodicWork(recoveryName, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DiscoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build())
        if (pending) work.enqueueUniqueWork(immediateName, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DiscoveryWorker>().setConstraints(constraints)
                    .addTag(tag)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build())
    }
    fun completions() = work.getWorkInfosForUniqueWorkFlow(immediateName)
    @Synchronized fun cancel() {
        work.cancelUniqueWork(immediateName); work.cancelUniqueWork(recoveryName)
    }
    companion object {
        const val IMMEDIATE = "discovery-pending"
        const val RECOVERY = "discovery-recovery"
    }
}
