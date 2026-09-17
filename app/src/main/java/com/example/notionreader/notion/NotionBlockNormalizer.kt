package com.example.notionreader.notion

/** Supplies a block's children. Keeps the normalizer free of HTTP and trivially fakeable in tests. */
fun interface BlockChildrenSource {
    suspend fun childrenOf(blockId: String): List<NotionBlock>
}

/**
 * Converts raw Notion DTOs into [NormalizedBlock]s.
 *
 * This is the only place that knows Notion block type names. Everything downstream sees
 * [NormalizedBlock], and the reader domain never sees either (architectural invariant 1).
 *
 * Unsupported and non-readable blocks are kept as [NormalizedKind.UNSUPPORTED] with a diagnostic
 * rather than dropped, so a page that contains them still reads top to bottom while the omission
 * stays visible (invariant 5, and docs/decisions.md Q5).
 */
class NotionBlockNormalizer(
    private val maxDepth: Int = DEFAULT_MAX_BLOCK_DEPTH
) {

    suspend fun normalizeAll(
        blocks: List<NotionBlock>,
        source: BlockChildrenSource,
        depth: Int = 0
    ): List<NormalizedBlock> = blocks.map { normalize(it, source, depth) }

    suspend fun normalize(
        block: NotionBlock,
        source: BlockChildrenSource,
        depth: Int = 0
    ): NormalizedBlock {
        if (block.type in NON_DESCENDED_TYPES) {
            // Q4: child pages and databases are separate Notes reached by explicit navigation, and a
            // synced block repeats content that also exists on its source page. Descending would
            // either break the frozen queue snapshot or duplicate content, so none are entered.
            return unsupported(
                block,
                reason = "'${block.type}' is not part of this document's reading order"
            )
        }

        if (depth >= maxDepth && block.hasChildren) {
            // Q5: the guard must not silently discard content. Exceeding the limit is recorded on the
            // block so the loss is visible in the mapped document instead of invisible.
            return unsupported(
                block,
                reason = "children not read: nesting exceeded the depth limit of $maxDepth"
            )
        }

        val children = if (block.hasChildren) {
            normalizeAll(source.childrenOf(block.id), source, depth + 1)
        } else {
            emptyList()
        }

        return when (block.type) {
            "heading_1" -> textBlock(block, NormalizedKind.HEADING, block.heading1, children, level = 1)
            "heading_2" -> textBlock(block, NormalizedKind.HEADING, block.heading2, children, level = 2)
            "heading_3" -> textBlock(block, NormalizedKind.HEADING, block.heading3, children, level = 3)
            "paragraph" -> textBlock(block, NormalizedKind.PARAGRAPH, block.paragraph, children)
            "bulleted_list_item" -> textBlock(block, NormalizedKind.LIST_ITEM, block.bulletedListItem, children)
            "numbered_list_item" -> textBlock(block, NormalizedKind.LIST_ITEM, block.numberedListItem, children)
            "quote" -> textBlock(block, NormalizedKind.QUOTE, block.quote, children)
            // toggle and callout are containers whose own text is readable, so they map like a
            // paragraph and their children are read in place.
            "toggle" -> textBlock(block, NormalizedKind.PARAGRAPH, block.toggle, children)
            "callout" -> textBlock(block, NormalizedKind.PARAGRAPH, block.callout, children)

            "to_do" -> NormalizedBlock(
                id = block.id,
                kind = NormalizedKind.TODO,
                text = block.toDo.joinPlainText(),
                checked = block.toDo?.checked ?: false,
                linkTargets = block.toDo.linkTargets(),
                hasInlineCode = block.toDo.hasInlineCode(),
                notionType = block.type,
                children = children
            )

            "code" -> NormalizedBlock(
                id = block.id,
                kind = NormalizedKind.CODE,
                text = block.code.joinPlainText(),
                language = block.code?.language,
                notionType = block.type,
                children = children
            )

            // The divider's text-free structure is the whole point: it is preserved here and emits
            // no chunk downstream (D5).
            "divider" -> NormalizedBlock(
                id = block.id,
                kind = NormalizedKind.DIVIDER,
                notionType = block.type,
                children = children
            )

            else -> unsupported(block, reason = "unsupported block type '${block.type}'")
        }
    }

    private fun textBlock(
        block: NotionBlock,
        kind: NormalizedKind,
        payload: NotionTextBlock?,
        children: List<NormalizedBlock>,
        level: Int = 0
    ) = NormalizedBlock(
        id = block.id,
        kind = kind,
        text = payload.joinPlainText(),
        level = level,
        linkTargets = payload.linkTargets(),
        hasInlineCode = payload.hasInlineCode(),
        notionType = block.type,
        children = children
    )

    private fun unsupported(block: NotionBlock, reason: String) = NormalizedBlock(
        id = block.id,
        kind = NormalizedKind.UNSUPPORTED,
        notionType = block.type,
        diagnostic = reason
    )
}

/**
 * Deepest nesting level that is read, counting the page itself as level 0.
 *
 * Not a specification value: no governing document supplies a number (docs/decisions.md Q5). What is
 * settled is the behaviour at the limit, which is to record a diagnostic rather than discard content.
 */
internal const val DEFAULT_MAX_BLOCK_DEPTH = 5

/** Block types whose children are deliberately not read. See Q4 in docs/decisions.md. */
private val NON_DESCENDED_TYPES = setOf("child_page", "child_database", "synced_block")

/**
 * D12: fragment text is concatenated without inserting separator characters, because each fragment
 * already carries the spacing its author typed. Adding a space would corrupt "Fragment one, " +
 * "fragment two".
 */
private fun NotionTextBlock?.joinPlainText(): String =
    this?.richText?.joinToString(separator = "") { it.plainText }.orEmpty().trim()

private fun NotionTodoBlock?.joinPlainText(): String =
    this?.richText?.joinToString(separator = "") { it.plainText }.orEmpty().trim()

private fun NotionCodeBlock?.joinPlainText(): String =
    this?.richText?.joinToString(separator = "") { it.plainText }.orEmpty().trim()

/** D12: hrefs are kept as metadata so they can be inspected without being spoken. */
private fun NotionTextBlock?.linkTargets(): List<String> =
    this?.richText?.mapNotNull { it.href }?.filter { it.isNotBlank() }.orEmpty()

private fun NotionTodoBlock?.linkTargets(): List<String> =
    this?.richText?.mapNotNull { it.href }?.filter { it.isNotBlank() }.orEmpty()

/** Records that the block contained inline code, for a later speech policy. */
private fun NotionTextBlock?.hasInlineCode(): Boolean =
    this?.richText?.any { it.annotations?.code == true } ?: false

private fun NotionTodoBlock?.hasInlineCode(): Boolean =
    this?.richText?.any { it.annotations?.code == true } ?: false
