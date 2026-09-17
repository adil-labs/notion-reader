package com.example.notionreader.data.content

import com.example.notionreader.data.content.NormalizedBlock
import com.example.notionreader.data.content.NormalizedKind
import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ChunkMetadata
import com.example.notionreader.domain.ReaderChunk
import com.example.notionreader.domain.ReaderChunkKind
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderSection
import java.time.Instant

/**
 * Converts a normalized block tree into a [ReaderDocument].
 *
 * All mapping policy lives here, so the rules are testable against the Phase 0 fixture without a
 * network, an Android device, or a speech engine. The rules themselves are settled in
 * docs/decisions.md; the relevant section or decision is cited at each one below.
 */
class ReaderDocumentMapper(
    private val chunker: TextChunker = TextChunker()
) {

    fun map(
        pageId: String,
        title: String,
        lastEditedAt: Instant?,
        blocks: List<NormalizedBlock>
    ): ReaderDocument {
        val sections = mutableListOf<ReaderSection>()
        var current: SectionBuilder? = null

        // D2: the leading section is created only when there is content before the first heading,
        // so a page that opens with a heading has no leading section at all.
        fun ensureSection(): SectionBuilder = current ?: SectionBuilder(
            id = "$pageId:intro",
            title = null,
            level = 0
        ).also { current = it }

        // D8: a section id is the heading block's id, never an index.
        fun closeSection() {
            current?.takeIf { it.chunks.isNotEmpty() }?.let { sections += it.build() }
            current = null
        }

        fun walk(nodes: List<NormalizedBlock>) {
            for (node in nodes) {
                when (node.kind) {
                    NormalizedKind.HEADING -> {
                        val headingText = node.text.trim()
                        if (headingText.isEmpty()) {
                            // D4: an empty heading produces no section and no chunk, because a
                            // titleless section would be an unusable navigation target. Its children
                            // are still read, into the enclosing section, so nothing is lost.
                            walk(node.children)
                        } else {
                            // D3: a heading is both a section boundary and a spoken chunk.
                            closeSection()
                            val builder = SectionBuilder(node.id, headingText, node.level)
                            builder.chunks += chunkFrom(node, ReaderChunkKind.HEADING, headingText)
                            current = builder
                            walk(node.children)
                        }
                    }

                    // D5: a divider is preserved in the normalized tree and produces no chunk and no
                    // section. It has children only in malformed data, but read them if present.
                    NormalizedKind.DIVIDER -> walk(node.children)

                    // D6: an unsupported block produces no chunk, not even an empty one. Its children
                    // may still be readable, so descend into them.
                    NormalizedKind.UNSUPPORTED -> walk(node.children)

                    else -> {
                        val produced = chunksFrom(node)
                        // Empty blocks must not generate useless chunks, so a block with no text
                        // neither emits a chunk nor forces a leading section into existence.
                        if (produced.isNotEmpty()) ensureSection().chunks += produced
                        walk(node.children)
                    }
                }
            }
        }

        walk(blocks)
        closeSection()

        return ReaderDocument(
            pageId = pageId,
            title = title,
            sections = sections,
            lastEditedAt = lastEditedAt
        )
    }

    /**
     * D7: the id is `<sourceBlockId>:<part>`, with the part index always present, so an unsplit block
     * is `:0`. Uniformity matters — if a block that was `A:0` became `A` when its text shortened, or
     * a saved position of `A` stopped resolving once the block grew, resume would silently break.
     */
    private fun chunkFrom(block: NormalizedBlock, kind: ReaderChunkKind, text: String): ReaderChunk =
        ReaderChunk(
            id = "${block.id}:0",
            sourceBlockId = block.id,
            kind = kind,
            text = text,
            metadata = block.toMetadata()
        )

    private fun chunksFrom(block: NormalizedBlock): List<ReaderChunk> {
        val text = block.text.trim()
        if (text.isEmpty()) return emptyList()

        val kind = when (block.kind) {
            NormalizedKind.PARAGRAPH -> ReaderChunkKind.PARAGRAPH
            NormalizedKind.LIST_ITEM -> ReaderChunkKind.LIST_ITEM
            NormalizedKind.TODO -> ReaderChunkKind.TODO
            NormalizedKind.QUOTE -> ReaderChunkKind.QUOTE
            NormalizedKind.CODE -> ReaderChunkKind.CODE
            else -> ReaderChunkKind.PARAGRAPH
        }

        // Code stays whole: the spec says "code = one chunk initially, with a later code-reading
        // policy", and splitting source at a sentence boundary would be meaningless.
        val parts = if (kind == ReaderChunkKind.CODE) listOf(text) else chunker.split(text)

        val metadata = block.toMetadata()
        return parts.mapIndexed { index, part ->
            ReaderChunk(
                id = "${block.id}:$index",
                sourceBlockId = block.id,
                kind = kind,
                text = part,
                metadata = metadata
            )
        }
    }

    /**
     * D10 and D12: metadata is carried alongside the text and never folded into it, so link hrefs,
     * checked state, code language and inline-code hints are available to later policy without ever
     * being spoken during ordinary playback.
     */
    private fun NormalizedBlock.toMetadata() = ChunkMetadata(
        checked = checked,
        language = language,
        linkTargets = linkTargets,
        hasInlineCode = hasInlineCode,
        headingLevel = level.takeIf { it > 0 }
    )

    private class SectionBuilder(
        private val id: String,
        private val title: String?,
        private val level: Int
    ) {
        val chunks = mutableListOf<ReaderChunk>()

        fun build() = ReaderSection(id = id, title = title, level = level, chunks = chunks.toList())
    }
}
