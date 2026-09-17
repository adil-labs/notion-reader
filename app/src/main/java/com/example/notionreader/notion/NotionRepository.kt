package com.example.notionreader.notion

import com.example.notionreader.reader.ReaderDocument

/**
 * Turns Notion content into the provider-agnostic [ReaderDocument].
 *
 * This is the only place that knows about Notion block types; the reader engine never sees them.
 */
class NotionRepository(private val api: NotionApi) {

    /**
     * Loads a page as a readable document.
     *
     * [pageReference] may be a page UUID (dashed or not) or a Notion page URL. When it is blank, the
     * most recently edited page the integration can see is used, which is what makes a token-only setup
     * (a `.env` file holding nothing but the integration token) usable without extra configuration.
     *
     * The referenced value is never echoed back in an error message, because a mistyped token would
     * otherwise be shown and logged.
     */
    suspend fun getDocument(pageReference: String = ""): ReaderDocument {
        val page = resolvePage(pageReference)

        if (page.isTrashed) {
            throw NotionApiException("That Notion page is in the trash.")
        }

        val paragraphs = collectParagraphs(page.id)

        return ReaderDocument(
            id = page.id.ifBlank { pageReference },
            title = page.title().ifBlank { "Untitled" },
            paragraphs = paragraphs
        )
    }

    fun close() = api.close()

    private suspend fun resolvePage(pageReference: String): NotionPage {
        if (pageReference.isBlank()) {
            return api.searchMostRecentPage() ?: throw NotionApiException(
                "The integration cannot see any Notion pages. Share a page with the integration in Notion."
            )
        }

        val pageId = normalizeNotionPageId(pageReference) ?: throw NotionApiException(
            "The configured Notion page reference is neither a page ID nor a page URL."
        )

        return api.retrievePage(pageId)
    }

    /**
     * Reads blocks in document order, descending into child blocks where a block has them.
     *
     * Nested content is the norm in real Notion pages (nested list items, toggles, columns), and its
     * children must be read where they sit in the document rather than skipped or appended at the end.
     * Recursion is bounded by [MAX_BLOCK_DEPTH] so a pathological page cannot spin through requests.
     */
    private suspend fun collectParagraphs(blockId: String, depth: Int = 0): List<String> {
        val blocks = api.retrieveBlockChildren(blockId)
        val paragraphs = mutableListOf<String>()

        for (block in blocks) {
            block.toParagraphText()?.let(paragraphs::add)

            // `child_page`/`child_database` are separate documents, and a synced block repeats content
            // that already exists elsewhere on the page, so none of them are descended into.
            val descend = block.hasChildren &&
                block.type !in NON_RECURSIVE_BLOCK_TYPES &&
                depth < MAX_BLOCK_DEPTH

            if (descend) paragraphs += collectParagraphs(block.id, depth + 1)
        }

        return paragraphs
    }
}

/** Deepest level of nested blocks that is read, counting the page itself as level 0. */
private const val MAX_BLOCK_DEPTH = 5

/** Block types whose children do not belong to the current document's reading order. */
private val NON_RECURSIVE_BLOCK_TYPES = setOf("child_page", "child_database", "synced_block")

/** Block types whose `rich_text` is turned into a paragraph. Everything else is skipped safely. */
private val SUPPORTED_TEXT_BLOCKS = setOf(
    "paragraph",
    "heading_1",
    "heading_2",
    "heading_3",
    "bulleted_list_item",
    "numbered_list_item",
    "quote",
    "callout",
    "toggle"
)

/** The 32 hex characters of a Notion UUID, with or without dashes. */
private val NOTION_ID_PATTERN = Regex(
    "[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}"
)

/**
 * Extracts a canonical, dashed page UUID from whatever a user pasted into configuration.
 *
 * Accepts a plain UUID, an undashed ID and the shapes Notion produces when copying a page link, such as
 * `https://www.notion.so/Workspace/Architecture-1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d?pvs=4`. The query
 * string and fragment are dropped first, so a database view ID in `?v=` is never mistaken for the page.
 * Returns null when no page ID can be found.
 */
internal fun normalizeNotionPageId(raw: String): String? {
    val withoutQuery = raw.trim()
        .trim('"', '\'')
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore('&')

    val hex = NOTION_ID_PATTERN.findAll(withoutQuery).lastOrNull()?.value ?: return null
    val digits = hex.replace("-", "")

    return buildString(36) {
        append(digits, 0, 8).append('-')
        append(digits, 8, 12).append('-')
        append(digits, 12, 16).append('-')
        append(digits, 16, 20).append('-')
        append(digits, 20, 32)
    }
}

/** Title of a page, taken from whichever property is of type `title`. */
fun NotionPage.title(): String =
    properties.values
        .firstOrNull { it.type == "title" }
        ?.title
        ?.joinToString(separator = "") { it.plainText }
        .orEmpty()
        .trim()

/**
 * Converts blocks into plain paragraph strings, in document order.
 *
 * Unsupported blocks (images, tables, embeds, ...) and empty blocks are skipped so a page with some
 * unsupported content still reads from top to bottom instead of failing.
 */
fun List<NotionBlock>.toReaderParagraphs(): List<String> = mapNotNull { it.toParagraphText() }

internal fun NotionBlock.toParagraphText(): String? {
    if (type !in SUPPORTED_TEXT_BLOCKS) return null

    val richText = when (type) {
        "paragraph" -> paragraph
        "heading_1" -> heading1
        "heading_2" -> heading2
        "heading_3" -> heading3
        "bulleted_list_item" -> bulletedListItem
        "numbered_list_item" -> numberedListItem
        "quote" -> quote
        "callout" -> callout
        "toggle" -> toggle
        else -> null
    } ?: return null

    return richText.richText
        .joinToString(separator = "") { it.plainText }
        .trim()
        .takeIf { it.isNotEmpty() }
}
