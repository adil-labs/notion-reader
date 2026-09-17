package com.example.notionreader.notion

import com.example.notionreader.reader.ReaderDocument
import com.example.notionreader.reader.ReaderDocumentMapper
import java.time.Instant

/**
 * Turns Notion content into the provider-agnostic [ReaderDocument].
 *
 * Wiring only: fetching, then [NotionBlockNormalizer], then [ReaderDocumentMapper]. All mapping
 * policy lives in those two collaborators so it can be tested without HTTP.
 *
 * The former implementation flattened every block to a `List<String>`, which lost heading levels,
 * link targets, to-do state, code language and block identity. This pipeline preserves them, which is
 * what makes stable chunk ids and non-spoken metadata possible at all.
 */
class NotionRepository(
    private val api: NotionApi,
    private val normalizer: NotionBlockNormalizer = NotionBlockNormalizer(),
    private val mapper: ReaderDocumentMapper = ReaderDocumentMapper()
) {

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

        val children = BlockChildrenSource { api.retrieveBlockChildren(it) }
        val blocks = normalizer.normalizeAll(
            blocks = api.retrieveBlockChildren(page.id),
            source = children
        )

        return mapper.map(
            pageId = page.id.ifBlank { pageReference },
            title = page.title().ifBlank { "Untitled" },
            lastEditedAt = page.lastEditedTime.toInstantOrNull(),
            blocks = blocks
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
}

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
 * Notion reports ISO-8601 instants. A page whose timestamp is missing or unparseable is still
 * readable, so this degrades to null rather than failing the load.
 */
internal fun String?.toInstantOrNull(): Instant? =
    this?.let { runCatching { Instant.parse(it) }.getOrNull() }
