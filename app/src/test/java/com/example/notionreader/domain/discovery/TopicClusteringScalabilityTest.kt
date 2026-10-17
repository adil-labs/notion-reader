package com.example.notionreader.domain.discovery

import org.junit.Assert.*
import org.junit.Test

/** Host coverage checks. Device resource acceptance belongs to the Android benchmark. */
class TopicClusteringScalabilityTest {
    private val identity = EmbeddingIdentity("scale-384", 384)
    private fun records(passages: Int, sections: Int): List<SemanticRecord> {
        require(passages >= sections)
        return (0 until 50).map { note ->
            val body = (0 until passages).filter { index -> (if (index < sections) index else index % 7) % 50 == note }.map { index ->
                // Every section is populated; remaining passages create deliberately uneven sections.
                val section = if (index < sections) index else index % 7
                val subject = section % 10
                val heading = "Research subject $subject"
                val p = SemanticPassage("p$index", "n$note", "s$section", heading, "b$index", "c$index", 0, 80,
                    "$heading contains independent explanatory evidence in this synthetic fixture.", heading, index)
                EmbeddedPassage(p, List(384) { if (it == subject) 1f else 0f })
            }
            SemanticRecord("n$note", "r1", identity, SemanticMetadata("Note $note"), body)
        }
    }
    private fun checkCoverage(passages: Int, sections: Int) {
        // Section identity is scoped to its Note, so fixture sections must keep a stable Note owner.
        val input = records(passages, sections)
        val actualPassages = input.sumOf { it.passages.size }
        val actualSections = input.flatMap { r -> r.passages.map { r.pageId to it.passage.sectionId } }.toSet().size
        assertEquals(passages, actualPassages)
        assertEquals(sections, actualSections)
        val started = System.nanoTime()
        val snapshot = SectionHierarchicalEngine(identity).discover(input, null, TopicPolicy())
        println("Host coverage: $actualPassages passages/$actualSections sections, ${(System.nanoTime() - started) / 1_000_000} ms; not Android acceptance")
        assertTrue(snapshot.complete)
        assertNull(snapshot.formationProgress)
        assertEquals(input.map { it.pageId }.toSet(), snapshot.acceptedRevisions.keys)
        assertEquals("The section after the former truncation boundary contributes evidence", 50,
            (snapshot.topics + snapshot.provisionalTopics).flatMap { it.members }.map { it.pageId }.toSet().size)
    }
    @Test fun completeCoverageAtRealLibraryScale() = checkCoverage(2437, 422)
    @Test fun completeCoverageBeyondFormerSectionLimit() = checkCoverage(10000, 2101)
    @Test fun completeCoverageAtStressScale() = checkCoverage(20000, 3000)
}
