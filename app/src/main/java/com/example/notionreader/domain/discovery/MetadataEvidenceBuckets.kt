package com.example.notionreader.domain.discovery

import java.util.Locale
import kotlin.math.sqrt

/** Authored labels organize optional acceleration; a label can never exclude evidence.
 * Each vector ball bounds the original float dot product, including rounding error.
 */
internal class MetadataEvidenceBuckets(private val evidence: List<FormationEvidence>, checkActive: () -> Unit = {}) {
    private data class Key(val area: String?, val topics: List<String>)
    private data class Bucket(val indices: List<Int>, val center: DoubleArray, val radius: Double, val maximumNorm: Double)
    private val buckets: List<Bucket>
    val bucketCount: Int get() = buckets.size
    init {
        val groups = evidence.indices.groupBy { i ->
            if (i % 64 == 0) checkActive()
            val metadata = evidence[i].record.metadata
            fun key(value: String) = value.trim().lowercase(Locale.ROOT)
            Key(metadata.area?.let(::key)?.takeIf { it.isNotEmpty() }, metadata.topics.map(::key).filter { it.isNotEmpty() }.distinct().sorted())
        }
        buckets = groups.values.map { indices ->
            checkActive()
            val dimensions = evidence[indices.first()].vector.size
            val center = DoubleArray(dimensions)
            indices.forEachIndexed { n, i ->
                if (n % 64 == 0) checkActive()
                evidence[i].vector.forEachIndexed { d, v -> center[d] += v.toDouble() / indices.size }
            }
            var radius = 0.0
            var maximumNorm = 0.0
            indices.forEachIndexed { n, i ->
                if (n % 64 == 0) checkActive()
                var distance = 0.0; var norm = 0.0
                evidence[i].vector.forEachIndexed { d, v ->
                    val delta = v.toDouble() - center[d]
                    distance += delta * delta; norm += v.toDouble() * v
                }
                radius = maxOf(radius, sqrt(distance)); maximumNorm = maxOf(maximumNorm, sqrt(norm))
            }
            Bucket(indices, center, radius, maximumNorm)
        }
    }
    fun eligible(center: FloatArray, threshold: Float, checkActive: () -> Unit = {}): BooleanArray {
        val result = BooleanArray(evidence.size)
        val queryNorm = sqrt(center.sumOf { it.toDouble() * it })
        // Standard gamma_n bound for float multiply/add accumulation; generous double margin.
        val epsilon = Math.scalb(1.0, -24)
        val gamma = (2 * center.size * epsilon) / (1 - 2 * center.size * epsilon)
        buckets.forEach { bucket ->
            checkActive()
            var dot = 0.0
            for (d in center.indices) dot += center[d].toDouble() * bucket.center[d]
            val upper = dot + queryNorm * bucket.radius + gamma * queryNorm * bucket.maximumNorm + 1e-6
            // Equality is retained; only a strict, finite proof can skip an entire bucket.
            if (!upper.isFinite() || upper >= threshold) bucket.indices.forEach { result[it] = true }
        }
        return result
    }
}
