package com.example.notionreader.data.discovery

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class GemmaEmbeddingOutputTest {
    @Test fun sentenceEmbeddingsAreFiniteUnitVectorsInTheDeclaredSpace() {
        val vector = normalizeGemmaOutput(FloatArray(768) { (it + 1).toFloat() })
        assertEquals(768, vector.size)
        assertEquals(1.0, sqrt(vector.sumOf { it.toDouble() * it }), 1e-6)
        assertTrue(vector.last() > vector.first())
    }
    @Test fun invalidOrWrongSpaceOutputsCannotEnterTheIndex() {
        for (invalid in listOf(FloatArray(384) { 1f }, FloatArray(768), FloatArray(768) { Float.NaN }, FloatArray(768) { Float.POSITIVE_INFINITY })) {
            assertThrows(IllegalArgumentException::class.java) { normalizeGemmaOutput(invalid) }
        }
    }
}
