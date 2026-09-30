package com.example.notionreader.data.local

import com.example.notionreader.data.document.CachedDocument
import com.example.notionreader.domain.ChunkMetadata
import com.example.notionreader.domain.ReaderChunk
import com.example.notionreader.domain.ReaderChunkKind
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderSection
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderDocumentCodecTest {

    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private val edited = Instant.parse("2026-09-30T09:30:00Z")

    @Test
    fun roundTripPreservesCompleteDocumentHierarchyAndMetadata() {
        val document = ReaderDocument(
            pageId = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
            title = "Architecture Decision Record",
            lastEditedAt = edited,
            sections = listOf(
                ReaderSection(
                    id = "sec-intro",
                    title = null,
                    level = 0,
                    chunks = listOf(
                        ReaderChunk(
                            id = "c1",
                            sourceBlockId = "b1",
                            kind = ReaderChunkKind.PARAGRAPH,
                            text = "Introduction paragraph.",
                            metadata = ChunkMetadata()
                        )
                    )
                ),
                ReaderSection(
                    id = "sec-h1",
                    title = "Primary Heading",
                    level = 1,
                    chunks = listOf(
                        ReaderChunk(
                            id = "c2",
                            sourceBlockId = "b2",
                            kind = ReaderChunkKind.HEADING,
                            text = "Primary Heading",
                            metadata = ChunkMetadata(headingLevel = 1)
                        ),
                        ReaderChunk(
                            id = "c3",
                            sourceBlockId = "b3",
                            kind = ReaderChunkKind.CODE,
                            text = "val x = 42",
                            metadata = ChunkMetadata(language = "kotlin", hasInlineCode = false)
                        ),
                        ReaderChunk(
                            id = "c4",
                            sourceBlockId = "b4",
                            kind = ReaderChunkKind.TODO,
                            text = "Complete testing",
                            metadata = ChunkMetadata(checked = true, linkTargets = listOf("https://example.com"))
                        )
                    )
                )
            )
        )

        val original = CachedDocument(
            document = document,
            remoteLastEditedAt = edited,
            cachedAt = now,
            schemaVersion = 1
        )

        val encoded = ReaderDocumentCodec.encode(original)
        val decoded = ReaderDocumentCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.schemaVersion, decoded!!.schemaVersion)
        assertEquals(original.cachedAt, decoded.cachedAt)
        assertEquals(original.remoteLastEditedAt, decoded.remoteLastEditedAt)
        assertEquals(original.document.pageId, decoded.document.pageId)
        assertEquals(original.document.title, decoded.document.title)
        assertEquals(original.document.lastEditedAt, decoded.document.lastEditedAt)
        assertEquals(2, decoded.document.sections.size)

        val decodedH1 = decoded.document.sections[1]
        assertEquals("Primary Heading", decodedH1.title)
        assertEquals(1, decodedH1.level)
        assertEquals(3, decodedH1.chunks.size)

        val decodedTodo = decodedH1.chunks[2]
        assertEquals(ReaderChunkKind.TODO, decodedTodo.kind)
        assertEquals(true, decodedTodo.metadata.checked)
        assertEquals(listOf("https://example.com"), decodedTodo.metadata.linkTargets)
    }

    @Test
    fun returnsNullOnCorruptedJson() {
        val decoded = ReaderDocumentCodec.decode("{ this is not valid json }")
        assertNull(decoded)
    }

    @Test
    fun returnsNullOnIncompatibleSchemaVersion() {
        val json = """
            {
                "schemaVersion": 999,
                "pageId": "some-id",
                "title": "Title",
                "cachedAtEpochMilli": 100000,
                "sections": []
            }
        """.trimIndent()
        val decoded = ReaderDocumentCodec.decode(json)
        assertNull(decoded)
    }

    @Test
    fun returnsNullWhenPageIdIsBlank() {
        val json = """
            {
                "schemaVersion": 1,
                "pageId": "",
                "title": "Title",
                "cachedAtEpochMilli": 100000,
                "sections": []
            }
        """.trimIndent()
        val decoded = ReaderDocumentCodec.decode(json)
        assertNull(decoded)
    }

    @Test
    fun returnsNullOnUnknownChunkKind() {
        val json = """
            {
                "schemaVersion": 1,
                "pageId": "some-id",
                "title": "Title",
                "cachedAtEpochMilli": 100000,
                "sections": [
                    {
                        "id": "sec-1",
                        "title": null,
                        "level": 0,
                        "chunks": [
                            {
                                "id": "c1",
                                "sourceBlockId": "b1",
                                "kind": "FUTURE_UNSUPPORTED_KIND_999",
                                "text": "Some text",
                                "metadata": {}
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()
        val decoded = ReaderDocumentCodec.decode(json)
        assertNull(decoded)
    }
}
