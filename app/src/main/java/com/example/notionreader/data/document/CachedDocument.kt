package com.example.notionreader.data.document

import com.example.notionreader.domain.ReaderDocument
import java.time.Instant

const val DOCUMENT_CACHE_SCHEMA_VERSION = 1

/**
 * A cached [ReaderDocument] snapshot with freshness metadata.
 */
data class CachedDocument(
    val document: ReaderDocument,
    val remoteLastEditedAt: Instant?,
    val cachedAt: Instant,
    val schemaVersion: Int = DOCUMENT_CACHE_SCHEMA_VERSION
)
