package com.example.notionreader.data.notion

import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Only the parts of the Notion public API this app needs (see
 * https://developers.notion.com/reference).
 *
 * Every field has a default so a response that omits or adds fields cannot break reads; unknown keys
 * are ignored by the shared [notionJson] configuration.
 *
 * Version robustness: API version 2026-03-11 renamed `archived` to `in_trash`, so both are decoded and
 * either being true means the object is in the trash.
 */
@Serializable
data class NotionPage(
    val id: String = "",
    val url: String? = null,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("last_edited_time") val lastEditedTime: String? = null,
    @SerialName("in_trash") val inTrash: Boolean = false,
    @Deprecated("Replaced by in_trash in API version 2026-03-11.")
    val archived: Boolean = false,
    val parent: NotionParent? = null,
    val properties: Map<String, NotionProperty> = emptyMap()
) {
    /** True when this page has been moved to the trash, under either field name. */
    @Suppress("DEPRECATION")
    val isTrashed: Boolean
        get() = inTrash || archived
}

@Serializable
data class NotionParent(
    val type: String = "",
    @SerialName("page_id") val pageId: String? = null,
    @SerialName("database_id") val databaseId: String? = null,
    @SerialName("data_source_id") val dataSourceId: String? = null,
    @SerialName("block_id") val blockId: String? = null,
    val workspace: Boolean = false
)

@Serializable
data class NotionSelectOption(
    val id: String? = null,
    val name: String = "",
    val color: String? = null
)

@Serializable
data class NotionRelationTarget(
    val id: String = ""
)

@Serializable
data class NotionProperty(
    val id: String? = null,
    val type: String? = null,
    val title: List<NotionRichText> = emptyList(),
    val select: NotionSelectOption? = null,
    val status: NotionSelectOption? = null,
    @SerialName("multi_select") val multiSelect: List<NotionSelectOption> = emptyList(),
    @SerialName("rich_text") val richText: List<NotionRichText> = emptyList(),
    val relation: List<NotionRelationTarget> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val url: String? = null
)

@Serializable
data class NotionReference(
    val id: String = ""
)

@Serializable
data class NotionLinkPreview(
    val url: String = ""
)

@Serializable
data class NotionMention(
    val type: String = "",
    val page: NotionReference? = null,
    val database: NotionReference? = null,
    @SerialName("data_source") val dataSource: NotionReference? = null,
    @SerialName("link_preview") val linkPreview: NotionLinkPreview? = null
)

@Serializable
data class NotionRichText(
    @SerialName("plain_text") val plainText: String = "",
    val type: String? = null,
    val href: String? = null,
    /** Styling. Only `code` matters to the reader, as a content-type hint. */
    val annotations: NotionAnnotations? = null,
    val mention: NotionMention? = null
)

/** Rich-text styling. The reader ignores purely visual styling but keeps inline code as a hint. */
@Serializable
data class NotionAnnotations(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val underline: Boolean = false,
    val code: Boolean = false
)

@Serializable
data class NotionTextBlock(
    @SerialName("rich_text") val richText: List<NotionRichText> = emptyList()
)

/** `to_do` carries its state, which the reader keeps as metadata rather than spoken text. */
@Serializable
data class NotionTodoBlock(
    @SerialName("rich_text") val richText: List<NotionRichText> = emptyList(),
    val checked: Boolean = false
)

/** `code` carries the language, which the reader keeps as metadata. */
@Serializable
data class NotionCodeBlock(
    @SerialName("rich_text") val richText: List<NotionRichText> = emptyList(),
    val language: String? = null,
    val caption: List<NotionRichText> = emptyList()
)

@Serializable
data class NotionChildPageBlock(
    val title: String = ""
)

@Serializable
data class NotionChildDatabaseBlock(
    val title: String = ""
)

@Serializable
data class NotionSyncedFrom(
    @SerialName("block_id") val blockId: String? = null
)

@Serializable
data class NotionSyncedBlock(
    @SerialName("synced_from") val syncedFrom: NotionSyncedFrom? = null
)

@Serializable
data class NotionTableBlock(
    @SerialName("table_width") val tableWidth: Int = 0,
    @SerialName("has_column_header") val hasColumnHeader: Boolean = false,
    @SerialName("has_row_header") val hasRowHeader: Boolean = false
)

@Serializable
data class NotionTableRowBlock(
    val cells: List<List<NotionRichText>> = emptyList()
)

@Serializable
data class NotionBookmarkBlock(
    val url: String = "",
    val caption: List<NotionRichText> = emptyList()
)

@Serializable
data class NotionLinkToPageBlock(
    val type: String = "",
    @SerialName("page_id") val pageId: String? = null,
    @SerialName("database_id") val databaseId: String? = null
)

@Serializable
data class NotionDividerBlock(
    val dummy: String? = null
)

@Serializable
data class NotionFile(val url: String = "")

@Serializable
data class NotionExternal(val url: String = "")

@Serializable
data class NotionMediaBlock(
    val caption: List<NotionRichText> = emptyList(),
    val type: String = "",
    val file: NotionFile? = null,
    val external: NotionExternal? = null
)

@Serializable
data class NotionEmbedBlock(
    val url: String = "",
    val caption: List<NotionRichText> = emptyList()
)

@Serializable
data class NotionBlock(
    val id: String = "",
    val type: String = "",
    /** True when this block has child blocks that need a separate request to retrieve. */
    @SerialName("has_children") val hasChildren: Boolean = false,
    @SerialName("in_trash") val inTrash: Boolean = false,
    val archived: Boolean = false,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("last_edited_time") val lastEditedTime: String? = null,
    val parent: NotionParent? = null,
    val paragraph: NotionTextBlock? = null,
    @SerialName("heading_1") val heading1: NotionTextBlock? = null,
    @SerialName("heading_2") val heading2: NotionTextBlock? = null,
    @SerialName("heading_3") val heading3: NotionTextBlock? = null,
    @SerialName("heading_4") val heading4: NotionTextBlock? = null,
    @SerialName("bulleted_list_item") val bulletedListItem: NotionTextBlock? = null,
    @SerialName("numbered_list_item") val numberedListItem: NotionTextBlock? = null,
    @SerialName("to_do") val toDo: NotionTodoBlock? = null,
    val quote: NotionTextBlock? = null,
    val code: NotionCodeBlock? = null,
    val callout: NotionTextBlock? = null,
    val toggle: NotionTextBlock? = null,
    @SerialName("child_page") val childPage: NotionChildPageBlock? = null,
    @SerialName("child_database") val childDatabase: NotionChildDatabaseBlock? = null,
    @SerialName("synced_block") val syncedBlock: NotionSyncedBlock? = null,
    val table: NotionTableBlock? = null,
    @SerialName("table_row") val tableRow: NotionTableRowBlock? = null,
    val bookmark: NotionBookmarkBlock? = null,
    @SerialName("link_preview") val linkPreview: NotionBookmarkBlock? = null,
    @SerialName("link_to_page") val linkToPage: NotionLinkToPageBlock? = null,
    val divider: NotionDividerBlock? = null,
    val image: NotionMediaBlock? = null,
    val video: NotionMediaBlock? = null,
    val file: NotionMediaBlock? = null,
    val pdf: NotionMediaBlock? = null,
    val audio: NotionMediaBlock? = null,
    val embed: NotionEmbedBlock? = null
)

/** `GET /v1/blocks/{block_id}/children` response. */
@Serializable
data class NotionBlockList(
    @SerialName("object") val objectType: String? = null,
    @SerialName("results") private val _results: List<NotionBlock>? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") private val _hasMore: Boolean? = null
) {
    constructor(
        results: List<NotionBlock>,
        nextCursor: String? = null,
        hasMore: Boolean = false
    ) : this(
        objectType = "list",
        _results = results,
        nextCursor = nextCursor,
        _hasMore = hasMore
    )

    val results: List<NotionBlock>
        get() = _results ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'results' field in block list envelope"
        )

    val hasMore: Boolean
        get() = _hasMore ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'has_more' field in block list envelope"
        )

    fun validateEnvelope() {
        if (_results == null || _hasMore == null) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Missing required block list envelope fields (results or has_more)"
            )
        }
    }
}

/** `POST /v1/search` response. */
@Serializable
data class NotionPageList(
    @SerialName("object") val objectType: String? = null,
    @SerialName("results") private val _results: List<NotionPage>? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") private val _hasMore: Boolean? = null
) {
    constructor(
        results: List<NotionPage>,
        nextCursor: String? = null,
        hasMore: Boolean = false
    ) : this(
        objectType = "list",
        _results = results,
        nextCursor = nextCursor,
        _hasMore = hasMore
    )

    val results: List<NotionPage>
        get() = _results ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'results' field in page list envelope"
        )

    val hasMore: Boolean
        get() = _hasMore ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'has_more' field in page list envelope"
        )

    fun validateEnvelope() {
        if (_results == null || _hasMore == null) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Missing required page list envelope fields (results or has_more)"
            )
        }
    }
}

@Serializable
data class NotionDatabase(
    val id: String = "",
    val title: List<NotionRichText> = emptyList(),
    val parent: NotionParent? = null,
    @SerialName("in_trash") val inTrash: Boolean = false,
    @SerialName("is_inline") val isInline: Boolean = false,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("last_edited_time") val lastEditedTime: String? = null
)

@Serializable
data class NotionDataSource(
    val id: String = "",
    val title: List<NotionRichText> = emptyList(),
    val parent: NotionParent? = null,
    @SerialName("in_trash") val inTrash: Boolean = false,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("last_edited_time") val lastEditedTime: String? = null
) {
    val databaseId: String?
        get() = parent?.databaseId

    val name: String
        get() = title.joinToString("") { it.plainText }
}

@Serializable
data class NotionPropertyItem(
    val id: String = "",
    val type: String = "",
    val relation: NotionRelationTarget? = null,
    val title: NotionRichText? = null,
    @SerialName("rich_text") val richText: NotionRichText? = null
)

@Serializable
data class NotionPropertyItemList(
    @SerialName("object") val objectType: String? = null,
    @SerialName("results") private val _results: List<NotionPropertyItem>? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") private val _hasMore: Boolean? = null
) {
    constructor(
        results: List<NotionPropertyItem>,
        nextCursor: String? = null,
        hasMore: Boolean = false
    ) : this(
        objectType = "list",
        _results = results,
        nextCursor = nextCursor,
        _hasMore = hasMore
    )

    val results: List<NotionPropertyItem>
        get() = _results ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'results' field in property item list envelope"
        )

    val hasMore: Boolean
        get() = _hasMore ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'has_more' field in property item list envelope"
        )

    fun validateEnvelope() {
        if (_results == null || _hasMore == null) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Missing required property item list envelope fields (results or has_more)"
            )
        }
    }
}

@Serializable
data class NotionDatabaseList(
    @SerialName("object") val objectType: String? = null,
    @SerialName("results") private val _results: List<NotionDatabase>? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") private val _hasMore: Boolean? = null
) {
    constructor(
        results: List<NotionDatabase>,
        nextCursor: String? = null,
        hasMore: Boolean = false
    ) : this(
        objectType = "list",
        _results = results,
        nextCursor = nextCursor,
        _hasMore = hasMore
    )

    val results: List<NotionDatabase>
        get() = _results ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'results' field in database list envelope"
        )

    val hasMore: Boolean
        get() = _hasMore ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'has_more' field in database list envelope"
        )

    fun validateEnvelope() {
        if (_results == null || _hasMore == null) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Missing required database list envelope fields (results or has_more)"
            )
        }
    }
}

@Serializable
data class NotionDataSourceList(
    @SerialName("object") val objectType: String? = null,
    @SerialName("results") private val _results: List<NotionDataSource>? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") private val _hasMore: Boolean? = null
) {
    constructor(
        results: List<NotionDataSource>,
        nextCursor: String? = null,
        hasMore: Boolean = false
    ) : this(
        objectType = "list",
        _results = results,
        nextCursor = nextCursor,
        _hasMore = hasMore
    )

    val results: List<NotionDataSource>
        get() = _results ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'results' field in data source list envelope"
        )

    val hasMore: Boolean
        get() = _hasMore ?: throw ReaderLoadException(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            "Missing required 'has_more' field in data source list envelope"
        )

    fun validateEnvelope() {
        if (_results == null || _hasMore == null) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Missing required data source list envelope fields (results or has_more)"
            )
        }
    }
}

/** Error body: `{"object":"error","status":404,"code":"object_not_found","message":"..."}`. */
@Serializable
internal data class NotionErrorBody(
    val code: String? = null,
    val status: Int? = null,
    val message: String? = null
)
