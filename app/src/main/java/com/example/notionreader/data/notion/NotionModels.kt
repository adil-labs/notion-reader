package com.example.notionreader.data.notion

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
    @SerialName("last_edited_time") val lastEditedTime: String? = null,
    @SerialName("in_trash") val inTrash: Boolean = false,
    @Deprecated("Replaced by in_trash in API version 2026-03-11.")
    val archived: Boolean = false,
    val properties: Map<String, NotionProperty> = emptyMap()
) {
    /** True when this page has been moved to the trash, under either field name. */
    @Suppress("DEPRECATION")
    val isTrashed: Boolean
        get() = inTrash || archived
}

@Serializable
data class NotionProperty(
    val id: String? = null,
    val type: String? = null,
    val title: List<NotionRichText> = emptyList()
)

@Serializable
data class NotionRichText(
    @SerialName("plain_text") val plainText: String = "",
    val type: String? = null,
    val href: String? = null,
    /** Styling. Only `code` matters to the reader, as a content-type hint. */
    val annotations: NotionAnnotations? = null
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
    val language: String? = null
)

@Serializable
data class NotionBlock(
    val id: String = "",
    val type: String = "",
    /** True when this block has child blocks that need a separate request to retrieve. */
    @SerialName("has_children") val hasChildren: Boolean = false,
    @SerialName("in_trash") val inTrash: Boolean = false,
    val archived: Boolean = false,
    val paragraph: NotionTextBlock? = null,
    @SerialName("heading_1") val heading1: NotionTextBlock? = null,
    @SerialName("heading_2") val heading2: NotionTextBlock? = null,
    @SerialName("heading_3") val heading3: NotionTextBlock? = null,
    @SerialName("bulleted_list_item") val bulletedListItem: NotionTextBlock? = null,
    @SerialName("numbered_list_item") val numberedListItem: NotionTextBlock? = null,
    @SerialName("to_do") val toDo: NotionTodoBlock? = null,
    val quote: NotionTextBlock? = null,
    val code: NotionCodeBlock? = null,
    val callout: NotionTextBlock? = null,
    val toggle: NotionTextBlock? = null
)

/** `GET /v1/blocks/{block_id}/children` response. */
@Serializable
data class NotionBlockList(
    val results: List<NotionBlock> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false
)

/** `POST /v1/search` response. */
@Serializable
data class NotionPageList(
    val results: List<NotionPage> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false
)

/** Error body: `{"object":"error","status":404,"code":"object_not_found","message":"..."}`. */
@Serializable
internal data class NotionErrorBody(
    val code: String? = null,
    val status: Int? = null,
    val message: String? = null
)
