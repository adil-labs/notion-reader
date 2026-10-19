package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FormationVectorBoundsTest {
    @Test fun unrelated768DimensionalVectorsAvoidMostComponentsWithoutChangingAdmission() {
        val a = FloatArray(768).also { it[0] = 1f }
        val b = FloatArray(768).also { it[128] = 1f }
        val bounds = FormationVectorBounds(a)
        bounds.dotOrReject(FormationVectorBounds(b), 1f, .1f)
        assertTrue("An unrelated pair must be rejected by a conservative proof", bounds.lastRejected)
        assertTrue("The proof must save actual feature multiplication work", bounds.lastAvoidedComponents >= 640)
        assertTrue(1f - projectionDot(a, b) > .1f)
    }

    @Test fun randomNegativeComponentsAndFloatBoundaryTiesNeverLoseAnAdmissiblePair() {
        val random = Random(7459)
        repeat(80) {
            val sizeA = random.nextInt(1, 200)
            val sizeB = random.nextInt(1, 200)
            fun vector(size: Int) = FloatArray(768) { random.nextDouble(-1.0, 1.0).toFloat() }.also { values ->
                normalizeProjectionVector(values)
                values.indices.forEach { index -> values[index] *= size }
            }
            val a = vector(sizeA); val b = vector(sizeB)
            val denominator = sizeA.toFloat() * sizeB
            val exact = projectionDot(a, b)
            val distance = (1f - exact / denominator).coerceIn(0f, 2f)
            for (threshold in listOf(0f, .1f, .35f, 1f, 1.9f, 2f,
                distance, Math.nextDown(distance).coerceIn(0f, 2f), Math.nextUp(distance).coerceIn(0f, 2f))) {
                val bounds = FormationVectorBounds(a)
                val score = bounds.dotOrReject(FormationVectorBounds(b), denominator, threshold)
                if (bounds.lastRejected) assertTrue("A bound may reject only an inadmissible exact distance", distance > threshold)
                else assertEquals("Uncertain pairs keep the original float accumulation", exact.toBits(), score.toBits())
            }
        }
    }

    @Test fun refreshingAMergedVectorCannotReuseItsOldSuffixNorms() {
        val a = FloatArray(768).also { it[0] = 1f }
        val b = FloatArray(768).also { it[128] = 1f }
        val left = FormationVectorBounds(a)
        val right = FormationVectorBounds(b)
        left.dotOrReject(right, 1f, .1f)
        a[0] = 0f; a[128] = 1f
        left.refresh()
        assertEquals(1f, left.dotOrReject(right, 1f, .1f), 0f)
        assertFalse(left.lastRejected)
    }

    @Test fun overflowNonfiniteAndMaximumDistanceCasesKeepTheOriginalFloatArithmetic() {
        val cases = listOf(
            FloatArray(768) { Float.MAX_VALUE },
            FloatArray(768) { if (it % 2 == 0) Float.MAX_VALUE else -Float.MAX_VALUE },
            FloatArray(768) { Float.NaN },
            FloatArray(768) { Float.MIN_VALUE },
            FloatArray(768)
        )
        for (a in cases) {
            val b = FloatArray(768) { 1f }
            val bounds = FormationVectorBounds(a)
            val value = bounds.dotOrReject(FormationVectorBounds(b), 1f, 2f)
            assertFalse("The maximum clamped distance admits every pair", bounds.lastRejected)
            assertEquals(projectionDot(a, b).toBits(), value.toBits())
        }
    }
}
