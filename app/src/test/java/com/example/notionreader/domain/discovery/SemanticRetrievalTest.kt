package com.example.notionreader.domain.discovery

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SemanticRetrievalTest {
    @Test fun packedNormalizedVectorsRejectCorruptionAndDoNotAliasTheirInput() {
        val source = floatArrayOf(1f, 0f)
        val vector = PackedNormalizedVector.fromNormalized(source)
        source[0] = 0f
        assertEquals(listOf(1f, 0f), vector)
        assertEquals(0f, cosineSimilarity(vector, PackedNormalizedVector.fromNormalized(floatArrayOf(0f, 1f))), .0001f)
        assertThrows(IllegalArgumentException::class.java) { PackedNormalizedVector.fromNormalized(floatArrayOf(Float.NaN, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { PackedNormalizedVector.fromNormalized(floatArrayOf(0f, 0f)) }
        assertThrows(IllegalArgumentException::class.java) { PackedNormalizedVector.fromNormalized(floatArrayOf(2f, 0f)) }
    }

    private val tokenizer = object : SemanticTokenizer {
        override fun tokens(text: String): List<SemanticToken> = Regex("\\S+").findAll(text)
            .map { SemanticToken(1, it.range.first, it.range.last + 1) }.toList()
    }

    @Test fun longDocumentsRetainTheirLastPassageWithinTheCompleteTokenBudget() {
        val text = (1..900).joinToString(" ") { "word$it" }
        val doc = ReaderDocument.fromChunkTexts("PAGE-1", "Long note", listOf(text))
        val chunks = SemanticChunker(tokenizer).chunk(doc)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { tokenizer.tokens(it.input).size + 2 <= 256 })
        assertTrue(chunks.last().excerpt.endsWith("word900"))
        assertTrue(chunks.all { text.substring(it.start, it.end) == it.excerpt })
        assertEquals(chunks.map { it.id }, SemanticChunker(tokenizer).chunk(doc).map { it.id })
    }

    @Test fun changingAnotherParagraphDoesNotRenameAnExistingPassage() {
        val doc = ReaderDocument.fromChunkTexts("p", "Title", listOf("first", "second"))
        val edited = doc.copy(sections = doc.sections.map { s -> s.copy(chunks = s.chunks.mapIndexed { i, c -> if (i == 0) c.copy(text = "changed") else c }) })
        assertEquals(SemanticChunker(tokenizer).chunk(doc)[1].id, SemanticChunker(tokenizer).chunk(edited)[1].id)
        assertNotEquals(semanticRevision(doc), semanticRevision(edited))
    }

    @Test fun cosineUsesNormalizedVectorsAndRejectsInvalidDimensions() {
        assertEquals(1f, cosineSimilarity(listOf(2f, 0f), listOf(3f, 0f)), .0001f)
        assertEquals(0f, cosineSimilarity(listOf(1f, 0f), listOf(0f, 1f)), .0001f)
        assertEquals(-1f, cosineSimilarity(listOf(1f, 0f), listOf(-1f, 0f)), .0001f)
        for (bad in listOf(listOf(0f, 0f), listOf(Float.NaN, 1f), listOf(1f))) {
            assertThrows(IllegalArgumentException::class.java) { cosineSimilarity(listOf(1f, 0f), bad) }
        }
    }

    @Test fun retrievalGroupsChunksFiltersMetadataExcludesSeedsAndBreaksTiesByIdentity() = runBlocking {
        val profile = EmbeddingIdentity("test", 2)
        fun record(id: String, area: String, vector: List<Float>) = SemanticRecord(
            pageId = id, revision = "r", identity = profile,
            metadata = SemanticMetadata.from(ReaderIndexEntry(id, id, area = area)),
            passages = listOf("a", "b").map { EmbeddedPassage(SemanticPassage(it, id, "s", "Heading", it, it, 0, 4, "text", "text", 0), vector) }
        )
        val records = listOf(record("z", "Chess", listOf(1f,0f)), record("b", "Economics", listOf(1f,0f)), record("a", "Economics", listOf(1f,0f)))
        val results = rankSemanticRecords(listOf(1f,0f), profile, records.asSequence(), BrowseQuery(selectedArea = "economics"), 20)
        assertEquals(listOf("a", "b"), results.map { it.pageId })
        assertEquals(1, rankSemanticRecords(listOf(1f,0f), profile, records.asSequence(), limit = 1).size)
        assertEquals(listOf("b"), rankSemanticRecords(listOf(1f,0f), profile, records.asSequence(), BrowseQuery(selectedArea = "Economics"), excludePageId = "A").map { it.pageId })
        assertTrue(rankSemanticRecords(listOf(1f,0f), profile.copy(model = "new"), records.asSequence()).isEmpty())
    }
}
