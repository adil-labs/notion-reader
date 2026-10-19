package com.example.notionreader.discovery

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.data.local.FileTopicStore
import com.example.notionreader.domain.discovery.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Historical MiniLM-loaded synthetic formation baseline; not a Gemma resource or human quality gate. */
class TopicFormationScalabilityDeviceTest {
    private fun stableJavaHeapBytes(total: () -> Long, free: () -> Long): Long? {
        val before = total()
        val unused = free()
        val after = total()
        return if (before == after && unused in 0..before) before - unused else null
    }
    @Test fun heapSamplingRejectsAHeapResizeInsteadOfReportingAnInflatedPeak() {
        val totals = ArrayDeque(listOf(400L, 100L))
        assertNull(stableJavaHeapBytes({ totals.removeFirst() }, { 20L }))
        assertEquals(80L, stableJavaHeapBytes({ 100L }, { 20L }))
        assertNull(stableJavaHeapBytes({ 100L }, { 101L }))
    }
    @Test fun tenThousandPassagesAcrossUnevenSectionsCompleteWithoutTruncation() = formation(8)
    @Test fun fortySubjectsAcrossUnevenSectionsCompleteWithoutTruncation() = formation(40)

    private fun formation(subjectCount: Int) {
        val provider = ShippingDiscoveryInferenceTest().provider()
        runBlocking { provider.embed("Model-loaded idle baseline for public topic formation.") }
        val identity = provider.identity
        fun allocated(): Long? = stableJavaHeapBytes({ Runtime.getRuntime().totalMemory() }, { Runtime.getRuntime().freeMemory() })
            ?.let { it + Debug.getNativeHeapAllocatedSize() }
        Runtime.getRuntime().gc()
        Thread.sleep(50)
        // Include retained corpus allocation in the added-memory measurement.
        val baseline = requireNotNull(allocated()) { "Baseline heap changed while sampling; retry the benchmark" }
        Log.i("TopicFormationBenchmark", "baselineJava=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} baselineNative=${Debug.getNativeHeapAllocatedSize()}")
        val baseNames = listOf("Forest ecology", "Ocean currents", "Database recovery", "Stellar evolution",
            "Mountain geology", "Music harmony", "Plant physiology", "River erosion")
        val names = (0 until subjectCount).map { baseNames.getOrNull(it) ?: "Public subject $it" }
        val records = (0 until 100).map { note ->
            SemanticRecord("note-$note", "r1", identity, SemanticMetadata("Public synthetic Note $note"),
                (0 until 25).flatMap { section ->
                    val sectionNumber = note * 25 + section
                    val subject = sectionNumber % names.size
                    val count = if (sectionNumber % 2 == 0) 1 else 7
                    (0 until count).map { part ->
                        val excerpt = "${names[subject]} describes a distinct public subject with independently supported explanatory body evidence."
                        val passage = SemanticPassage("$note:$section:$part", "note-$note", "section-$sectionNumber",
                            names[subject], "body-$sectionNumber-$part", "chunk-$sectionNumber-$part",
                            0, excerpt.length, excerpt, excerpt, section * 10 + part)
                        // Match FileSemanticIndexStore's primitive representation instead of retaining boxed Floats.
                        val varied = FloatArray(384) { if (it == subject) 1f else 0f }
                        varied[subjectCount + sectionNumber % (384 - subjectCount)] = .04f + (sectionNumber % 17) * .001f
                        EmbeddedPassage(passage, normalizeEmbedding(varied.toList(), 384))
                    }
                })
        }
        assertEquals(10_000, records.sumOf { it.passages.size })
        assertEquals(2_500, records.flatMap { it.passages }.map { it.passage.sectionId }.distinct().size)
        val peak = AtomicLong(maxOf(baseline, allocated() ?: baseline))
        val sampling = AtomicBoolean(true)
        val samples = AtomicLong()
        val monitor = Thread {
            while (sampling.get()) {
                allocated()?.let { sample ->
                    val old = peak.getAndAccumulate(sample, ::maxOf)
                    if (sample > old) Log.i("TopicFormationMemory", "sampleBytes=$sample javaTotal=${Runtime.getRuntime().totalMemory()} javaFree=${Runtime.getRuntime().freeMemory()} nativeBytes=${Debug.getNativeHeapAllocatedSize()}")
                    samples.incrementAndGet()
                }
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; start() }
        val started = System.nanoTime()
        val cpuStarted = Debug.threadCpuTimeNanos()
        val workDirectory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "public-formation-${System.nanoTime()}")
        var snapshot: TopicSnapshot? = null
        var steps = 0
        var comparisons = 0L
        var stepNanos = 0L
        var persistenceNanos = 0L
        try {
            do {
                val stepStarted = System.nanoTime()
                snapshot = SectionHierarchicalEngine(identity).step(records, snapshot, TopicPolicy(), workBudget = 250_000)
                stepNanos += System.nanoTime() - stepStarted
                snapshot.formationProgress?.let {
                    assertEquals(2_500, it.totalSections)
                    assertTrue(it.comparisonsCompleted >= comparisons)
                    comparisons = it.comparisonsCompleted
                }
                // A fresh store and engine must resume the actual persisted checkpoint.
                val persistenceStarted = System.nanoTime()
                snapshot = runBlocking {
                    val store = FileTopicStore(workDirectory)
                    assertTrue("Checkpoint persistence must succeed", store.write(snapshot!!))
                    FileTopicStore(workDirectory).read().also { assertNotNull(it) }
                }
                persistenceNanos += System.nanoTime() - persistenceStarted
                steps++
                Log.i("TopicFormationBenchmark", "steps=$steps javaAllocated=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} nativeAllocated=${Debug.getNativeHeapAllocatedSize()}")
                if (steps % 16 == 0) Log.i("TopicFormationBenchmark", "steps=$steps comparisons=$comparisons stepMs=${stepNanos / 1e6} persistenceMs=${persistenceNanos / 1e6}")
                check(System.nanoTime() - started < 120_000_000_000L) { "Formation exceeded the 120-second active-processing gate" }
            } while (!snapshot!!.complete)
        } finally {
            Log.i("TopicFormationBenchmark", "final steps=$steps comparisons=$comparisons complete=${snapshot?.complete} elapsedMs=${(System.nanoTime() - started) / 1e6} stepMs=${stepNanos / 1e6} persistenceMs=${persistenceNanos / 1e6} sampledAddedBytes=${peak.get() - baseline}")
            provider.release()
            sampling.set(false)
            monitor.join(1_000)
            runBlocking { FileTopicStore(workDirectory).clear() }
            assertTrue(workDirectory.delete())
        }
        val elapsedMs = (System.nanoTime() - started) / 1e6
        val additionalPeak = peak.get() - baseline
        assertTrue(snapshot!!.complete)
        assertEquals(100, snapshot.acceptedRevisions.size)
        assertTrue("Late sections must retain evidence", snapshot.topics.flatMap { it.members }
            .any { it.pageId == "note-99" })
        assertEquals("Every orthogonal public subject survives", names.toSet(), snapshot.topics.map { it.label }.toSet())
        assertTrue("Memory sampler must observe the run", samples.get() > 1)
        assertTrue("Sampled additional Java/native memory exceeds 128 MiB", additionalPeak <= 128L * 1024 * 1024)
        val reachedCpuWork = java.util.concurrent.CountDownLatch(1)
        val cancelRequested = AtomicBoolean(false)
        val cancellation = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val cancellable = Thread {
            try {
                SectionHierarchicalEngine(identity).step(records, null, TopicPolicy(), workBudget = Int.MAX_VALUE) {
                    if (checks.incrementAndGet() >= 3000) reachedCpuWork.countDown()
                    if (cancelRequested.get()) throw java.util.concurrent.CancellationException("Public benchmark cancellation")
                }
            } catch (failure: Throwable) { cancellation.set(failure) }
        }.apply { start() }
        try {
            assertTrue("Cancellation must be exercised during active CPU work", reachedCpuWork.await(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally { cancelRequested.set(true) }
        val cancelledAt = System.nanoTime()
        cancellable.join(1000)
        assertFalse("Formation must stop within one second after cancellation", cancellable.isAlive)
        assertTrue("The worker must observe cancellation rather than fail for another reason", cancellation.get() is java.util.concurrent.CancellationException)
        Log.i("TopicFormationBenchmark", "cancellationMs=${(System.nanoTime() - cancelledAt) / 1e6} checks=${checks.get()}")
        Log.i("TopicFormationBenchmark", "passages=10000 sections=2500 subjects=$subjectCount steps=$steps activeMs=$elapsedMs threadCpuMs=${(Debug.threadCpuTimeNanos() - cpuStarted) / 1e6} sampledAddedBytes=$additionalPeak samples=${samples.get()}")
    }
}
