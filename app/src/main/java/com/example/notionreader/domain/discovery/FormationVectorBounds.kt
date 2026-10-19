package com.example.notionreader.domain.discovery

import kotlin.math.sqrt

/** Conservative exclusion only; every uncertain pair keeps the original sequential Float dot product. */
internal class FormationVectorBounds(private val vector: FloatArray) {
    private val blockSize = 64
    private val suffixNorms = DoubleArray((vector.size + blockSize - 1) / blockSize + 1)
    private val operations = 2.0 * vector.size
    private val epsilon = Math.scalb(1.0, -24)
    private val gamma = operations * epsilon / (1.0 - operations * epsilon)
    private val underflowAllowance = 4.0 * vector.size * Float.MIN_VALUE.toDouble()
    private var norm = 0.0
    private var finite = true
    var lastAvoidedComponents: Int = 0
        private set
    var lastRejected: Boolean = false
        private set

    init { refresh() }

    /** Sums may change after a merge or the canonical resumed-step rebuild. */
    fun refresh() {
        finite = true
        var square = 0.0
        for (block in suffixNorms.lastIndex - 1 downTo 0) {
            for (index in minOf(vector.size, (block + 1) * blockSize) - 1 downTo block * blockSize) {
                val value = vector[index].toDouble()
                if (!value.isFinite()) finite = false
                val next = square + value * value
                // Float components have exact Double products; round each nonzero sum upward.
                square = if (next == 0.0) 0.0 else Math.nextUp(next)
            }
            suffixNorms[block] = if (square == 0.0) 0.0 else Math.nextUp(sqrt(square))
        }
        norm = suffixNorms[0]
    }

    fun dotOrReject(other: FormationVectorBounds, denominator: Float, distanceThreshold: Float): Float {
        lastAvoidedComponents = 0
        lastRejected = false
        require(vector.size == other.vector.size)
        val product = norm * other.norm
        val normProduct = if (product == 0.0) 0.0 else Math.nextUp(product)
        // Overflow/nonfinite arithmetic cannot use the ordinary rounding-error theorem. The
        // distance clamp also admits every pair at threshold 2, regardless of the raw ratio.
        if (!finite || !other.finite || !denominator.isFinite() || denominator <= 0f ||
            distanceThreshold >= 2f || !gamma.isFinite() || gamma < 0.0 || gamma >= .25 ||
            !normProduct.isFinite() || normProduct / denominator > 2.0 ||
            normProduct * (1.0 + 2.0 * gamma) >= Float.MAX_VALUE.toDouble())
            return projectionDot(vector, other.vector)

        // Float full dot = real full dot + errorFull; the preserved Float prefix has
        // errorPrefix. Cauchy bounds the remaining real dot, and BOTH error magnitudes
        // are at most gamma_(2d)*||a||||b|| (plus gradual-underflow allowance).
        val margin = Math.nextUp((2.0 * gamma + 1e-6) * normProduct + underflowAllowance)
        // Keep equality and nearby Float division/subtraction ties. The denominator is
        // the exact Float size product used by the original engine, converted to Double.
        val required = Math.nextDown((1.0 - distanceThreshold.toDouble() -
            2.0 * Math.ulp(1f).toDouble()) * denominator.toDouble())
        var sum = 0f
        var index = 0
        var block = 0
        while (index < vector.size) {
            val end = minOf(vector.size, (block + 1) * blockSize)
            while (index < end) {
                sum += vector[index] * other.vector[index]
                index++
            }
            if (index < vector.size && sum.isFinite()) {
                // Each cached suffix norm is already rounded upward; multiplying them
                // upward keeps Cauchy's bound without a square root for every pair/block.
                val tailProduct = suffixNorms[block + 1] * other.suffixNorms[block + 1]
                val tail = if (tailProduct == 0.0) 0.0 else Math.nextUp(tailProduct)
                val upper = Math.nextUp(Math.nextUp(sum.toDouble() + tail) + margin)
                if (upper.isFinite() && upper < required) {
                    lastRejected = true
                    lastAvoidedComponents = vector.size - index
                    return sum
                }
            }
            block++
        }
        return sum
    }
}
