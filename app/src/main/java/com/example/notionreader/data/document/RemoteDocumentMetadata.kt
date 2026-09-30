package com.example.notionreader.data.document

import java.time.Instant

/**
 * Lightweight metadata returned by remote source for cache validation without downloading blocks.
 */
data class RemoteDocumentMetadata(
    val pageId: String,
    val title: String,
    val lastEditedAt: Instant?,
    val isTrashed: Boolean
)
