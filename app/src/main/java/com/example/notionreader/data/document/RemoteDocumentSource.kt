package com.example.notionreader.data.document

import com.example.notionreader.domain.ReaderDocument

/**
 * Remote provider of reader documents and metadata.
 */
interface RemoteDocumentSource : AutoCloseable {
    /** Retrieves lightweight metadata to determine freshness without downloading document blocks. */
    suspend fun getMetadata(pageReference: String = ""): RemoteDocumentMetadata

    /** Retrieves the full document with all speakable chunks. */
    suspend fun getDocument(pageReference: String = ""): ReaderDocument

    override fun close() {}
}
