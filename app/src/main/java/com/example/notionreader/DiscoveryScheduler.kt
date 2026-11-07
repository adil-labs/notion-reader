package com.example.notionreader

import android.content.Context
import androidx.work.*
import com.example.notionreader.domain.settings.DiscoveryEffort
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class DiscoveryTaskOutcome {
    Success,
    Failure,
    Cancelled
}

data class DiscoveryTaskCompletion(
    val id: String,
    val outcome: DiscoveryTaskOutcome
)

interface DiscoveryTaskScheduler {
    suspend fun request(nextEffort: DiscoveryEffort, pending: Boolean)
    fun cancel()
    fun completions(): Flow<List<DiscoveryTaskCompletion>>
}

internal data class ScheduledWorkRecord(
    val id: String,
    val state: WorkInfo.State,
    val tags: Set<String> = emptySet()
)

internal interface WorkManagerClient {
    suspend fun getWorkInfos(name: String): List<ScheduledWorkRecord>
    fun cancelUniqueWork(name: String)
    fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest)
    fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
    fun getWorkInfosFlow(name: String): Flow<List<ScheduledWorkRecord>>
}

internal class DefaultWorkManagerClient(private val work: WorkManager) : WorkManagerClient {
    override suspend fun getWorkInfos(name: String): List<ScheduledWorkRecord> {
        val infos = work.getWorkInfosForUniqueWork(name).get()
        return infos.map { ScheduledWorkRecord(it.id.toString(), it.state, it.tags) }
    }

    override fun cancelUniqueWork(name: String) {
        work.cancelUniqueWork(name).result.get()
    }

    override fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
        work.enqueueUniquePeriodicWork(name, policy, request).result.get()
    }

    override fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        work.enqueueUniqueWork(name, policy, request).result.get()
    }

    override fun getWorkInfosFlow(name: String): Flow<List<ScheduledWorkRecord>> =
        work.getWorkInfosForUniqueWorkFlow(name).map { infos ->
            infos.map { ScheduledWorkRecord(it.id.toString(), it.state, it.tags) }
        }
}

/**
 * Persistent grants of execution time for the single application-owned pipeline.
 *
 * Serializes all scheduling and cancellation mutations through an unbounded FIFO channel
 * processed on [ioDispatcher]. Monotonic mutation sequences prevent older requests from
 * resurrecting canceled or superseded work while keeping [cancel] non-blocking on the main thread.
 */
internal class DiscoveryScheduler(
    context: Context? = null,
    private val immediateName: String = IMMEDIATE,
    private val recoveryName: String = RECOVERY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    client: WorkManagerClient? = null,
    schedulerScope: CoroutineScope? = null
) : DiscoveryTaskScheduler {

    private val client: WorkManagerClient = client ?: run {
        val ctx = checkNotNull(context) { "Either context or WorkManagerClient must be provided" }
        DefaultWorkManagerClient(WorkManager.getInstance(ctx))
    }

    private val scope: CoroutineScope = schedulerScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)

    private val submissionLock = Any()
    private val sequence = AtomicLong(0)
    private val latestSubmittedId = AtomicLong(0)
    private val latestCancelId = AtomicLong(0)

    private sealed interface ScheduledMutation {
        val ack: CompletableDeferred<Unit>?

        data class Request(
            val id: Long,
            val effort: DiscoveryEffort,
            val pending: Boolean,
            override val ack: CompletableDeferred<Unit>
        ) : ScheduledMutation

        data class Cancel(
            val id: Long,
            override val ack: CompletableDeferred<Unit>? = null
        ) : ScheduledMutation
    }

    private val mutationChannel = Channel<ScheduledMutation>(Channel.UNLIMITED) {
        it.ack?.cancel(CancellationException("Discovery scheduler stopped"))
    }

    init {
        scope.launch(ioDispatcher) {
            for (mutation in mutationChannel) {
                try {
                    when (mutation) {
                        is ScheduledMutation.Request -> processRequest(mutation)
                        is ScheduledMutation.Cancel -> processCancel(mutation)
                    }
                    mutation.ack?.complete(Unit)
                } catch (c: CancellationException) {
                    mutation.ack?.cancel(c)
                    throw c
                } catch (failure: Exception) {
                    mutation.ack?.completeExceptionally(failure)
                }
            }
        }.invokeOnCompletion { mutationChannel.cancel() }
    }

    override suspend fun request(nextEffort: DiscoveryEffort, pending: Boolean) {
        val ack = CompletableDeferred<Unit>()
        synchronized(submissionLock) {
            val id = sequence.incrementAndGet()
            latestSubmittedId.set(id)
            check(mutationChannel.trySend(ScheduledMutation.Request(id, nextEffort, pending, ack)).isSuccess)
        }
        ack.await()
    }

    override fun cancel() {
        synchronized(submissionLock) {
            val id = sequence.incrementAndGet()
            latestSubmittedId.set(id)
            latestCancelId.set(id)
            check(mutationChannel.trySend(ScheduledMutation.Cancel(id)).isSuccess)
        }
    }

    internal suspend fun cancelAndJoin() {
        val ack = CompletableDeferred<Unit>()
        synchronized(submissionLock) {
            val id = sequence.incrementAndGet()
            latestSubmittedId.set(id)
            latestCancelId.set(id)
            check(mutationChannel.trySend(ScheduledMutation.Cancel(id, ack)).isSuccess)
        }
        ack.await()
    }

    private suspend fun processRequest(mutation: ScheduledMutation.Request) {
        if (isSupersededOrCancelled(mutation.id)) return

        val tag = "discovery-effort:${mutation.effort.name}"
        val existing = client.getWorkInfos(immediateName)

        if (isSupersededOrCancelled(mutation.id)) return

        if (existing.any { !it.state.isFinished && tag !in it.tags }) {
            client.cancelUniqueWork(immediateName)
        }

        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(mutation.effort == DiscoveryEffort.CHARGING_ONLY)
            .build()

        if (isSupersededOrCancelled(mutation.id)) return

        client.enqueueUniquePeriodicWork(
            recoveryName,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DiscoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )

        if (isSupersededOrCancelled(mutation.id)) return

        if (mutation.pending) {
            client.enqueueUniqueWork(
                immediateName,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DiscoveryWorker>()
                    .setConstraints(constraints)
                    .addTag(tag)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    private fun processCancel(mutation: ScheduledMutation.Cancel) {
        if (mutation.id < latestCancelId.get()) return
        client.cancelUniqueWork(immediateName)
        client.cancelUniqueWork(recoveryName)
    }

    private fun isSupersededOrCancelled(mutationId: Long): Boolean =
        mutationId < latestSubmittedId.get() || mutationId <= latestCancelId.get()

    override fun completions(): Flow<List<DiscoveryTaskCompletion>> =
        client.getWorkInfosFlow(immediateName).map { records ->
            records.mapNotNull { record ->
                val outcome = when (record.state) {
                    WorkInfo.State.SUCCEEDED -> DiscoveryTaskOutcome.Success
                    WorkInfo.State.FAILED -> DiscoveryTaskOutcome.Failure
                    WorkInfo.State.CANCELLED -> DiscoveryTaskOutcome.Cancelled
                    else -> null
                }
                outcome?.let { DiscoveryTaskCompletion(record.id, it) }
            }
        }

    internal fun shutdown() {
        mutationChannel.cancel()
        scope.cancel()
    }

    companion object {
        const val IMMEDIATE = "discovery-pending"
        const val RECOVERY = "discovery-recovery"
    }
}
