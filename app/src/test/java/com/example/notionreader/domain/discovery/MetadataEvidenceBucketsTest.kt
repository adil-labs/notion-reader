package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MetadataEvidenceBucketsTest {
    private fun evidence(id: Int, angle: Double, area: String?, topics: Set<String> = emptySet()): FormationEvidence {
        val text = "Detailed source evidence supports a meaningful subject across independent Notes."
        val p = SemanticPassage("p$id", "n$id", "s$id", "Subject", "b$id", "c$id", 0, text.length, text, text, id)
        val vector = floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())
        val embedded = EmbeddedPassage(p, vector.toList())
        return FormationEvidence(SemanticRecord("n$id", "r", EmbeddingIdentity("test", 2),
            SemanticMetadata("Note", area = area, topics = topics), listOf(embedded)), embedded, vector)
    }
    @Test fun unrelatedAreaBucketsSkipWorkWithoutExcludingCrossAreaMatches() {
        val evidence = listOf(evidence(0, 0.0, "Engineering"), evidence(1, 0.0, "Ecology"),
            evidence(2, Math.PI, "History"), evidence(3, Math.PI, null))
        val selected = MetadataEvidenceBuckets(evidence).eligible(floatArrayOf(1f, 0f), .9f)
        assertArrayEquals(booleanArrayOf(true, true, false, false), selected)
    }
    @Test fun existingTopicsProvideUsefulBucketsWhenAreaIsAbsent() {
        val evidence = listOf(evidence(0, 0.0, null, setOf("Databases")),
            evidence(1, Math.PI, null, setOf("Birds")))
        val buckets = MetadataEvidenceBuckets(evidence)
        assertEquals(2, buckets.bucketCount)
        assertArrayEquals(booleanArrayOf(true, false), buckets.eligible(floatArrayOf(1f, 0f), .9f))
    }
    @Test fun conservativeBoundsNeverDiscardAnExhaustiveThresholdMatch() {
        val evidence = (0..255).map { evidence(it, it * .071, "area${it % 7}", setOf("topic${it % 11}")) }
        val buckets = MetadataEvidenceBuckets(evidence)
        for (q in 0..63) {
            val center = floatArrayOf(cos(q * .103).toFloat(), sin(q * .103).toFloat())
            for (threshold in listOf(0f, .6f, .9f, 1f)) {
                val selected = buckets.eligible(center, threshold)
                evidence.forEachIndexed { i, e ->
                    if (projectionDot(center, e.vector).coerceIn(-1f, 1f) >= threshold)
                        assertTrue("A numerical bound cannot remove matching evidence", selected[i])
                }
            }
        }
    }
}
