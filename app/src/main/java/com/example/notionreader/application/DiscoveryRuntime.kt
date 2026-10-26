package com.example.notionreader.application

import com.example.notionreader.domain.document.DocumentCache
import com.example.notionreader.domain.discovery.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared execution resources; collaborators never create competing locks or inference caches. */
internal class DiscoveryRuntime(cache: DocumentCache, private val provider: EmbeddingProvider,
    private val labeler: TopicLabelRuntime? = null,
    private val labelModelAvailable: () -> Boolean = { labeler != null }) {
    val validity = DiscoveryValidity(cache)
    val generation get() = validity.generation
    val embeddingMemo = ExactEmbeddingMemo()
    val foregroundQueries = AtomicInteger()
    val inference = Mutex()
    val bulkGate = Mutex()
    val commits = Mutex()
    private val activeLabel = AtomicReference<Job?>(null)

    /** Nonblocking signal; the label job retains native ownership through its cleanup. */
    fun interruptLabeling() { activeLabel.get()?.cancel() }

    suspend fun labelTopic(request: TopicLabelRequest, mayWork: () -> Boolean): TopicLabelRun {
        val generator = labeler ?: return TopicLabelRun.Unavailable
        if (!labelModelAvailable()) return TopicLabelRun.Unavailable
        val token = validity.capture()
        return bulkStep {
            inference.withLock {
                if (!mayWork() || !validity.isCurrent(token)) return@withLock TopicLabelRun.Cancelled
                if (!labelModelAvailable()) return@withLock TopicLabelRun.Unavailable
                provider.release()
                val result = coroutineScope {
                    val task = async(start = CoroutineStart.LAZY) { generator.label(request) }
                    activeLabel.set(task)
                    val watcher = launch {
                        while (!task.isCompleted) {
                            if (!mayWork() || !validity.isCurrent(token) || !labelModelAvailable() || foregroundQueries.get() > 0)
                                task.cancel()
                            delay(50)
                        }
                    }
                    try {
                        // Cover a query/policy change that raced registration of the lazy job.
                        if (foregroundQueries.get() > 0 || !mayWork() || !validity.isCurrent(token) || !labelModelAvailable()) task.cancel()
                        else task.start()
                        try { task.await() }
                        catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            TopicLabelRun.Cancelled
                        }
                    } finally {
                        withContext(NonCancellable) {
                            // await() can throw before a cancelled child's native cleanup finishes.
                            task.join()
                            watcher.cancelAndJoin()
                            activeLabel.compareAndSet(task, null)
                        }
                    }
                }
                if (!validity.isCurrent(token) || !mayWork()) TopicLabelRun.Cancelled
                else if (!labelModelAvailable()) TopicLabelRun.Unavailable else result
            }
        }
    }

    suspend fun <T> foregroundStep(action: suspend () -> T): T {
        foregroundQueries.incrementAndGet()
        interruptLabeling()
        try { return bulkGate.withLock { action() } }
        finally { foregroundQueries.decrementAndGet() }
    }

    /** Called while inference is owned; memo hits need no native engine switch. */
    suspend fun embedExactInput(text: String): List<Float> {
        embeddingMemo.get(provider.identity, text)?.let { return it }
        val token = embeddingMemo.token()
        return embedInput(text).also { embeddingMemo.put(provider.identity, text, it, token) }
    }
    suspend fun embedInput(text: String): List<Float> {
        labeler?.close()
        return provider.embed(text)
    }

    /** End of a bulk window: never retain an idle generative engine across OS/power waits. */
    suspend fun closeLabels() {
        if (labeler == null) return
        interruptLabeling()
        withContext(NonCancellable) { inference.withLock { labeler.close() } }
    }
    suspend fun releaseEngines() {
        interruptLabeling()
        withContext(NonCancellable) { inference.withLock { labeler?.close(); provider.release() } }
    }

    suspend fun <T> bulkStep(action: suspend () -> T): T {
        while (true) {
            while (foregroundQueries.get() > 0) delay(10)
            bulkGate.lock()
            if (foregroundQueries.get() == 0) {
                try { return action() } finally { bulkGate.unlock() }
            }
            bulkGate.unlock()
        }
    }
}
