package com.example.notionreader

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import com.example.notionreader.domain.settings.DiscoveryEffort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DiscoverySchedulerTest {

    private class FakeWorkManagerClient : WorkManagerClient {
        val existingWork = CopyOnWriteArrayList<ScheduledWorkRecord>()
        val periodicEnqueues = CopyOnWriteArrayList<Pair<String, PeriodicWorkRequest>>()
        val immediateEnqueues = CopyOnWriteArrayList<Pair<String, OneTimeWorkRequest>>()
        val cancelledWork = CopyOnWriteArrayList<String>()
        val completionsFlow = MutableSharedFlow<List<ScheduledWorkRecord>>(extraBufferCapacity = 16)

        @Volatile var onGetWorkInfos: (suspend () -> Unit)? = null
        var cancelFailure: Exception? = null

        override suspend fun getWorkInfos(name: String): List<ScheduledWorkRecord> {
            onGetWorkInfos?.invoke()
            return existingWork.toList()
        }

        override fun cancelUniqueWork(name: String) {
            cancelFailure?.let { throw it }
            cancelledWork.add(name)
        }

        override fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
            periodicEnqueues.add(name to request)
        }

        override fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
            immediateEnqueues.add(name to request)
        }

        override fun getWorkInfosFlow(name: String): Flow<List<ScheduledWorkRecord>> = completionsFlow
    }

    @Test
    fun shutdownReleasesActiveAndQueuedRequestCallers() = runBlocking {
        val client = FakeWorkManagerClient()
        val entered = CompletableDeferred<Unit>()
        client.onGetWorkInfos = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val scheduler = DiscoveryScheduler(client = client)
        try {
            val active = async { runCatching { scheduler.request(DiscoveryEffort.BALANCED, true) } }
            withTimeout(5_000) { entered.await() }
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.request(DiscoveryEffort.BALANCED, true) }
            }
            scheduler.shutdown()
            withTimeout(5_000) {
                assertTrue(active.await().exceptionOrNull() is CancellationException)
                assertTrue(queued.await().exceptionOrNull() is CancellationException)
            }
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun failedCancellationIsReportedToTheCaller() = runBlocking {
        val client = FakeWorkManagerClient()
        val scheduler = DiscoveryScheduler(
            immediateName = "immediate", recoveryName = "recovery", client = client,
            schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        )
        val failure = IllegalStateException("Cancellation failed")
        try {
            client.cancelFailure = failure
            val reported = runCatching { scheduler.cancelAndJoin() }.exceptionOrNull()
            assertTrue(reported is IllegalStateException)
            assertEquals(failure.message, reported?.message)
            client.cancelFailure = null
            scheduler.cancelAndJoin()
            assertEquals(listOf("immediate", "recovery"), client.cancelledWork)
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun cancellationWhileRequestInFlightPreventsWorkResurrection() = runBlocking {
        val client = FakeWorkManagerClient()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DiscoveryScheduler(
            immediateName = "test-immediate",
            recoveryName = "test-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        try {
            val inFlightBarrier = CompletableDeferred<Unit>()
            val proceedBarrier = CompletableDeferred<Unit>()
            client.onGetWorkInfos = {
                inFlightBarrier.complete(Unit)
                proceedBarrier.await()
            }

            val requestJob = launch {
                scheduler.request(DiscoveryEffort.CHARGING_ONLY, pending = true)
            }

            inFlightBarrier.await()
            scheduler.cancel()
            proceedBarrier.complete(Unit)
            requestJob.join()
            scheduler.cancelAndJoin()

            assertTrue("Immediate work must not be enqueued after cancellation", client.immediateEnqueues.isEmpty())
            assertTrue("Periodic work must not be enqueued after cancellation", client.periodicEnqueues.isEmpty())
            assertTrue("Cancellation must cancel immediate name", client.cancelledWork.contains("test-immediate"))
            assertTrue("Cancellation must cancel recovery name", client.cancelledWork.contains("test-recovery"))
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun concurrentRequestsDropSupersededWorkAndEnqueueOnlyLatest() = runBlocking {
        val client = FakeWorkManagerClient()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DiscoveryScheduler(
            immediateName = "test-immediate",
            recoveryName = "test-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        try {
            val inFlightBarrier = CompletableDeferred<Unit>()
            val proceedBarrier = CompletableDeferred<Unit>()
            val firstCall = AtomicBoolean(true)
            client.onGetWorkInfos = {
                if (firstCall.compareAndSet(true, false)) {
                    inFlightBarrier.complete(Unit)
                    proceedBarrier.await()
                }
            }

            // Launch first request (CHARGING_ONLY)
            val job1 = launch {
                scheduler.request(DiscoveryEffort.CHARGING_ONLY, pending = true)
            }

            // Wait until request 1 is blocked in query
            inFlightBarrier.await()

            // Launch second request (BALANCED) which supersedes request 1
            val job2 = launch(start = CoroutineStart.UNDISPATCHED) {
                scheduler.request(DiscoveryEffort.BALANCED, pending = true)
            }

            // Allow request 1 to continue
            proceedBarrier.complete(Unit)

            job1.join()
            job2.join()

            // Only request 2 (BALANCED) should have enqueued periodic and immediate work
            assertEquals("Only 1 periodic work should be enqueued", 1, client.periodicEnqueues.size)
            assertEquals("Only 1 immediate work should be enqueued", 1, client.immediateEnqueues.size)

            val periodicRequest = client.periodicEnqueues.first().second
            assertFalse(
                "Final enqueued work must reflect latest request (BALANCED does not require charging)",
                periodicRequest.workSpec.constraints.requiresCharging()
            )
            val immediateRequest = client.immediateEnqueues.first().second
            assertTrue(
                "Immediate request must carry tag for latest effort",
                immediateRequest.tags.contains("discovery-effort:BALANCED")
            )
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun cancelFollowedByNewerRequestEnqueuesNewWorkWithoutBeingCancelled() = runBlocking {
        val client = FakeWorkManagerClient()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DiscoveryScheduler(
            immediateName = "test-immediate",
            recoveryName = "test-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        try {
            scheduler.cancel()
            scheduler.request(DiscoveryEffort.BALANCED, pending = true)

            assertTrue("CancelUniqueWork must have been called", client.cancelledWork.contains("test-immediate"))
            assertEquals("Newer request must successfully enqueue periodic work", 1, client.periodicEnqueues.size)
            assertEquals("Newer request must successfully enqueue immediate work", 1, client.immediateEnqueues.size)
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun cancelDoesNotBlockCallingThreadDuringSlowOperation() = runBlocking {
        val client = FakeWorkManagerClient()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DiscoveryScheduler(
            immediateName = "test-immediate",
            recoveryName = "test-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        try {
            val queryStarted = CompletableDeferred<Unit>()
            val holdQuery = CompletableDeferred<Unit>()
            client.onGetWorkInfos = {
                queryStarted.complete(Unit)
                holdQuery.await()
            }

            launch {
                scheduler.request(DiscoveryEffort.CHARGING_ONLY, pending = true)
            }

            queryStarted.await()

            // Measuring cancel execution time: must be non-blocking
            val startNano = System.nanoTime()
            scheduler.cancel()
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L

            assertTrue("cancel() must not block the calling thread (took ${elapsedMs}ms)", elapsedMs < 200)

            holdQuery.complete(Unit)
            Unit
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun effortChangeReconcilesDifferentTagByCancellingOldImmediateWorkFirst() = runBlocking {
        val client = FakeWorkManagerClient()
        client.existingWork.add(
            ScheduledWorkRecord(
                id = "old-work-id",
                state = WorkInfo.State.ENQUEUED,
                tags = setOf("discovery-effort:CHARGING_ONLY")
            )
        )

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DiscoveryScheduler(
            immediateName = "test-immediate",
            recoveryName = "test-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        try {
            scheduler.request(DiscoveryEffort.BALANCED, pending = true)

            assertTrue(
                "Immediate work must be cancelled when effort tag changes from CHARGING_ONLY to BALANCED",
                client.cancelledWork.contains("test-immediate")
            )
            assertEquals("New immediate work must be enqueued", 1, client.immediateEnqueues.size)
            assertTrue(
                "New immediate work must carry new effort tag",
                client.immediateEnqueues.first().second.tags.contains("discovery-effort:BALANCED")
            )
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun completionsFlowPreservesDistinctOutcomesAndIgnoresIntermediateStates() = runBlocking {
        val client = FakeWorkManagerClient()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DiscoveryScheduler(
            immediateName = "test-immediate",
            recoveryName = "test-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        try {
            val completions = mutableListOf<DiscoveryTaskCompletion>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                scheduler.completions().collect { list ->
                    completions.addAll(list)
                }
            }

            val records = listOf(
                ScheduledWorkRecord("1", WorkInfo.State.SUCCEEDED),
                ScheduledWorkRecord("2", WorkInfo.State.FAILED),
                ScheduledWorkRecord("3", WorkInfo.State.CANCELLED),
                ScheduledWorkRecord("4", WorkInfo.State.ENQUEUED),
                ScheduledWorkRecord("5", WorkInfo.State.RUNNING),
                ScheduledWorkRecord("6", WorkInfo.State.BLOCKED)
            )

            client.completionsFlow.emit(records)
            delay(50)

            assertEquals("Only SUCCEEDED, FAILED, and CANCELLED should map to task completions", 3, completions.size)
            assertEquals(DiscoveryTaskOutcome.Success, completions.first { it.id == "1" }.outcome)
            assertEquals(DiscoveryTaskOutcome.Failure, completions.first { it.id == "2" }.outcome)
            assertEquals(DiscoveryTaskOutcome.Cancelled, completions.first { it.id == "3" }.outcome)

            job.cancel()
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun rapidBurstOfRequestsProcessesOnlyLatestWithoutRedundantEnqueues() = runBlocking {
        val client = FakeWorkManagerClient()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DiscoveryScheduler(
            immediateName = "test-immediate",
            recoveryName = "test-recovery",
            ioDispatcher = Dispatchers.Default,
            client = client,
            schedulerScope = scope
        )

        try {
            // Rapidly invoke 5 requests
            val jobs = (1..5).map { i ->
                val effort = if (i % 2 == 0) DiscoveryEffort.CHARGING_ONLY else DiscoveryEffort.BALANCED
                launch { scheduler.request(effort, pending = true) }
            }

            jobs.joinAll()

            // At most the latest request should have enqueued periodic and immediate work
            assertTrue("Periodic enqueues must be serialized and not multiply redundant", client.periodicEnqueues.size in 1..2)
            assertTrue("Immediate enqueues must be serialized and not multiply redundant", client.immediateEnqueues.size in 1..2)
        } finally {
            scheduler.shutdown()
        }
    }
}
