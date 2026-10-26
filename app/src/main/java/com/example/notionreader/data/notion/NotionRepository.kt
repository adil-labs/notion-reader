package com.example.notionreader.data.notion

import com.example.notionreader.data.content.BlockChildrenSource
import com.example.notionreader.data.content.NotionBlockNormalizer
import com.example.notionreader.data.content.ReaderDocumentMapper
import com.example.notionreader.data.document.RemoteDocumentMetadata
import com.example.notionreader.data.document.RemoteDocumentSource
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.ReaderLoadException
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
 *
 * Every failure leaves here as a [ReaderLoadException] carrying a category, so callers never have to
 * read a message string to decide whether retrying is meaningful.
 */
class NotionRepository(
    private val api: NotionApi,
    private val normalizer: NotionBlockNormalizer = NotionBlockNormalizer(),
    private val mapper: ReaderDocumentMapper = ReaderDocumentMapper(),
    private val onDocumentSnapshot: ((page: NotionPage, rootBlocks: List<NotionBlock>, childBlocks: Map<String, List<NotionBlock>>) -> Unit)? = null,
    private val onDiscoveredPages: ((List<String>) -> Unit)? = null,
    private val onDiscoveredMetadata: ((List<NotionPage>) -> Unit)? = null
) : RemoteDocumentSource {

    /**
     * Resolves lightweight page metadata without fetching child blocks.
     */
    override suspend fun getMetadata(pageReference: String): RemoteDocumentMetadata {
        val page = resolvePage(pageReference)
        return RemoteDocumentMetadata(
            pageId = page.id.ifBlank { pageReference },
            title = page.title().ifBlank { "Untitled" },
            lastEditedAt = page.lastEditedTime.toInstantOrNull(),
            isTrashed = page.isTrashed
        )
    }

    /**
     * Loads a page as a readable document.
     *
     * [pageReference] may be a page UUID (dashed or not) or a Notion page URL. When it is blank, the
     * most recently edited page the integration can see is used, which is what makes a token-only setup
     * (a `.env` file holding nothing but the integration token) usable without extra configuration.
     *
     * The referenced value is never echoed back in an error message or diagnostic, because a mistyped
     * token would otherwise be shown and logged.
     */
    override suspend fun getDocument(pageReference: String): ReaderDocument {
        val page = resolvePage(pageReference)

        if (page.isTrashed) {
            throw ReaderLoadException(
                category = ReaderErrorCategory.PAGE_NOT_FOUND,
                message = "That Notion page is in the trash.",
                diagnostic = "page is in the trash"
            )
        }

        val childMap = mutableMapOf<String, List<NotionBlock>>()
        val children = BlockChildrenSource { blockId ->
            val fetched = api.retrieveBlockChildren(blockId)
            childMap[blockId] = fetched
            fetched
        }
        val rootBlocks = api.retrieveBlockChildren(page.id)
        val blocks = normalizer.normalizeAll(
            blocks = rootBlocks,
            source = children
        )

        val document = mapper.map(
            pageId = page.id.ifBlank { pageReference },
            title = page.title().ifBlank { "Untitled" },
            lastEditedAt = page.lastEditedTime.toInstantOrNull(),
            blocks = blocks
        )

        // The page has content, but none of it is speakable. That is a failure worth reporting, not
        // an empty reader: the user asked for a page and would otherwise be shown nothing at all with
        // no explanation. A page with no blocks is simply empty, and stays a valid empty document.
        //
        // This is deliberately the *only* place the category is raised. The spec requires that "a
        // single unsupported block should not invalidate an otherwise readable page", so partial
        // losses stay silent here and are recorded as diagnostics on the normalized nodes instead.
        if (blocks.isNotEmpty() && document.chunks.isEmpty()) {
            throw ReaderLoadException(
                category = ReaderErrorCategory.UNSUPPORTED_DOCUMENT_STRUCTURE,
                diagnostic = "${blocks.size} top-level blocks produced no chunks; " +
                    "types=${blocks.map { it.notionType }.distinct().take(20)}"
            )
        }

        try {
            onDocumentSnapshot?.invoke(page, rootBlocks, childMap)
        } catch (_: Exception) {
            // Snapshot callback failure must not fail document loading
        }

        return document
    }

    override fun close() = api.close()

    /**
     * Every page the integration can see, as Browse entries.
     *
     * `POST /v1/search` with no query returns exactly the pages that have been shared with the
     * integration, so this is Browse's "All Notes": the set is a fact about permissions rather than a
     * filter the app chooses, which is what lets the default result set exist without the missing
     * Content Metadata & Workspace Schema Adapters specification.
     *
     * **A page with a blank title is dropped.** It cannot be named in a result row, in a queue
     * ("Note 3 of 8"), or in the between-Notes announcement, so including it would put an unlabelled row
     * in front of the user and an unnamed Note into a queue. Dropping it is the smaller wrong: the
     * alternative is inventing a placeholder name and presenting that as the page's name. The count of
     * dropped pages is not reported anywhere yet, which is recorded in `docs/decisions.md` §15.
     *
     * Date parsing is deliberately tolerant and cannot fail the call: a page whose `last_edited_time`
     * cannot be read is still a readable Note, and the only thing lost is its position in "Recently
     * updated" — which [ReaderIndexEntry] already handles by sorting unknown dates last.
     */
    suspend fun getBrowseIndex(): List<ReaderIndexEntry> {
        val pages = api.searchPages()
        try {
            onDiscoveredPages?.invoke(pages.map { it.id })
            onDiscoveredMetadata?.invoke(pages)
        } catch (_: Exception) {
            // Discovery callback failure must not fail browse indexing
        }
        return pages.mapNotNull { page ->
            val title = page.title().trim()
            if (title.isEmpty()) return@mapNotNull null

            val kind = page.properties["Type"]?.select?.name
                ?: page.properties["Kind"]?.select?.name
                ?: page.properties.values.firstOrNull { it.type == "select" && (it.id?.contains("type", ignoreCase = true) == true) }?.select?.name

            val area = page.properties["Area"]?.select?.name
                ?: page.properties.values.firstOrNull { it.type == "select" && (it.id?.contains("area", ignoreCase = true) == true) }?.select?.name

            val topics = (page.properties["Topics"]?.multiSelect?.map { it.name }
                ?: page.properties["Tags"]?.multiSelect?.map { it.name }
                ?: emptyList()).filter { it.isNotBlank() }.toSet()

            val project = page.properties["Project"]?.richText?.joinToString("") { it.plainText }?.trim()?.ifEmpty { null }
                ?: page.properties["Project"]?.select?.name

            val status = page.properties["Status"]?.status?.name
                ?: page.properties["Status"]?.select?.name

            ReaderIndexEntry(
                pageId = page.id,
                title = title,
                lastEditedAt = page.lastEditedTime.toInstantOrNull(),
                kind = kind,
                area = area,
                topics = topics,
                project = project,
                status = status
            )
        }

    }

    private suspend fun resolvePage(pageReference: String): NotionPage {
        if (pageReference.isBlank()) {
            return api.searchMostRecentPage() ?: throw ReaderLoadException(
                category = ReaderErrorCategory.PERMISSION_DENIED,
                message = "The integration cannot see any Notion pages. " +
                    "Share a page with the integration in Notion.",
                diagnostic = "POST /v1/search returned no pages"
            )
        }

        val pageId = normalizeNotionPageId(pageReference) ?: throw ReaderLoadException(
            category = ReaderErrorCategory.PAGE_NOT_FOUND,
            message = "The configured Notion page reference is neither a page ID nor a page URL.",
            // Length only: the reference may be a mistyped token, which must never be logged.
            diagnostic = "unparseable page reference (length ${pageReference.length})"
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
