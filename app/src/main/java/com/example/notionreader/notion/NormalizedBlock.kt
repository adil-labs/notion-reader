package com.example.notionreader.notion

/**
 * Small, provider-shaped-free block model sitting between Notion DTOs and the reader domain.
 *
 * This layer exists because two decisions need somewhere for structure that produces no chunk to
 * live (docs/decisions.md):
 * - a divider is preserved here and nowhere else (D5);
 * - an unsupported block is preserved here with diagnostics and emits no chunk, because an empty
 *   chunk would be a useless navigation target (D6).
 *
 * It is also where the traversal-depth diagnostic lands when a page nests deeper than the guard
 * allows (Q5).
 */
data class NormalizedBlock(
    val id: String,
    val kind: NormalizedKind,
    /** Speakable text, already concatenated from rich-text fragments. Empty for non-text blocks. */
    val text: String = "",
    /** Heading level 1..3. Meaningless for other kinds. */
    val level: Int = 0,
    /** `to_do` only. */
    val checked: Boolean? = null,
    /** `code` only. */
    val language: String? = null,
    /** Link hrefs found in the block, in source order. Never spoken. */
    val linkTargets: List<String> = emptyList(),
    /** True when the block contained inline code. */
    val hasInlineCode: Boolean = false,
    /** The original Notion block type, kept for diagnostics on [NormalizedKind.UNSUPPORTED]. */
    val notionType: String = "",
    /** Why a block was not mapped, for diagnostics. Null when nothing went wrong. */
    val diagnostic: String? = null,
    /** Child blocks, in source order. */
    val children: List<NormalizedBlock> = emptyList()
)

enum class NormalizedKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TODO,
    QUOTE,
    CODE,
    /** No spoken chunk; preserved for structure (D5). */
    DIVIDER,
    /** No spoken chunk; preserved for diagnostics (D6). */
    UNSUPPORTED
}
