package com.example.notionreader.domain

import java.time.Instant

/**
 * Internal, provider-agnostic representation of a readable document.
 *
 * The reader engine and the UI only ever see this type; Notion JSON (or any future provider) is
 * converted into it by [ReaderDocumentMapper]. This keeps navigation logic independent of where the
 * content came from.
 *
 * Shape follows the integration spec's Reader domain model: a flat list of [ReaderSection]s whose
 * hierarchy is carried by [ReaderSection.level] rather than by nesting, each holding an ordered list
 * of [ReaderChunk]s.
 *
 * Two deliberate exclusions, both settled in docs/decisions.md:
 * - There is no `TITLE` chunk. [title] carries the page title, and the Player announces it on session
 *   start and on note transitions (D9).
 * - There is no `UNSUPPORTED` chunk. Unsupported blocks are represented in the normalized block tree
 *   with diagnostics and emit no chunk at all, because empty chunks would be useless navigation
 *   targets (D6).
 */
data class ReaderDocument(
    val pageId: String,
    val title: String,
    val sections: List<ReaderSection> = emptyList(),
    val lastEditedAt: Instant? = null
) {
    /** Every chunk in document order, across all sections. */
    val chunks: List<ReaderChunk>
        get() = sections.flatMap { it.chunks }

    /**
     * Chunk text in document order.
     *
     * This is the projection the playback layer currently consumes. Playback is still a flat,
     * chunk-at-a-time walk; section-aware navigation arrives with the Player phase. Keeping the
     * projection here means the reader engine never has to know about sections to keep working.
     */
    val chunkTexts: List<String>
        get() = chunks.map { it.text }

    companion object {

        val Empty = ReaderDocument(
            pageId = "",
            title = "",
            sections = emptyList()
        )

        /** Hard-coded document used to prove the reader independently of networking. */
        val Demo = fromChunkTexts(
            pageId = "demo",
            title = "Demo document",
            texts = listOf(
                "Welcome to Notion Reader.",
                "This application is testing sequential document playback.",
                "You can move backward or forward through paragraphs.",
                "Later these paragraphs will come directly from Notion."
            )
        )

        /** Builds a single-section document from plain text. Used by [Demo] and by tests. */
        fun fromChunkTexts(pageId: String, title: String, texts: List<String>): ReaderDocument =
            ReaderDocument(
                pageId = pageId,
                title = title,
                sections = listOf(
                    ReaderSection(
                        id = "$pageId:intro",
                        title = null,
                        level = 0,
                        chunks = texts.mapIndexed { index, text ->
                            ReaderChunk(
                                id = "$pageId:text:$index",
                                sourceBlockId = "",
                                kind = ReaderChunkKind.PARAGRAPH,
                                text = text
                            )
                        }
                    )
                )
            )
    }
}

/**
 * One navigable section of a document.
 *
 * [level] is the source heading level (1..3), and 0 for the leading section. Sections are flat: the
 * caller reconstructs hierarchy from [level] if it needs to, which keeps "previous section" and
 * "next section" unambiguous.
 */
data class ReaderSection(
    val id: String,
    val title: String?,
    val level: Int,
    val chunks: List<ReaderChunk>
)

/**
 * The smallest unit playback can resume and navigate to.
 *
 * [id] is derived from the Notion block identity rather than a list index, so it survives edits
 * elsewhere in the page. See [ChunkMetadata] for everything that must not be spoken.
 */
data class ReaderChunk(
    val id: String,
    val sourceBlockId: String,
    val kind: ReaderChunkKind,
    val text: String,
    val metadata: ChunkMetadata = ChunkMetadata()
)

enum class ReaderChunkKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TODO,
    QUOTE,
    CODE
}

/**
 * Non-spoken detail about a chunk.
 *
 * These values exist so the Note screen and later speech policy can consult them; none of them are
 * concatenated into [ReaderChunk.text]. In particular [linkTargets] holds hrefs that are deliberately
 * not spoken, while the link's display text stays in the chunk text.
 */
data class ChunkMetadata(
    /** `to_do` only. Whether it is announced is a Player/accessibility policy, not a mapping choice. */
    val checked: Boolean? = null,
    /** `code` only, as reported by Notion. */
    val language: String? = null,
    /** Link hrefs found in the block, in source order. Never spoken. */
    val linkTargets: List<String> = emptyList(),
    /** True when the block contained inline code, so a later speech policy can treat it differently. */
    val hasInlineCode: Boolean = false,
    /**
     * `heading_1`..`heading_3` only, as 1..3.
     *
     * A heading chunk is also its section's title (D3), so without this the Note screen could only
     * render every heading at one visual level, or guess a level back out of the section — and would
     * risk drawing the same heading twice. Carrying the level on the chunk itself makes the hierarchy
     * a fact about the chunk rather than something the UI reconstructs.
     */
    val headingLevel: Int? = null
)
