package com.example.notionreader.data.document

import com.example.notionreader.domain.ReaderDocument

/**
 * Result of loading a document, carrying provenance and freshness metadata.
 */
data class DocumentLoad(
    val document: ReaderDocument,
    val source: DocumentSource,
    val freshness: DocumentFreshness
)

enum class DocumentSource {
    CACHE,
    REMOTE
}

enum class DocumentFreshness {
    FRESH,
    STALE_OFFLINE,
    UNVALIDATED
}
