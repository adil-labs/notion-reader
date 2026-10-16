package com.example.notionreader.domain.discovery

/** Immutable primitive storage; the List port remains compatible with test/provider implementations. */
internal class PackedNormalizedVector private constructor(private val components: FloatArray, private val squaredNorm: Double) :
    AbstractList<Float>(), java.util.RandomAccess {
    override val size: Int get() = components.size
    override fun get(index: Int): Float = components[index]

    fun cosine(other: PackedNormalizedVector): Float {
        require(size == other.size)
        var dot = 0.0
        for (i in components.indices) dot += components[i].toDouble() * other.components[i]
        return (dot / kotlin.math.sqrt(squaredNorm * other.squaredNorm)).toFloat().coerceIn(-1f, 1f)
    }

    companion object {
        fun fromNormalized(values: FloatArray): PackedNormalizedVector {
            require(values.isNotEmpty())
            var squared = 0.0
            for (value in values) { require(value.isFinite()); squared += value.toDouble() * value }
            require(kotlin.math.abs(squared - 1.0) < .001)
            return PackedNormalizedVector(values.copyOf(), squared)
        }
    }
}
