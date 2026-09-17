package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory

/**
 * Load state of a document that has to be fetched (for example from the Notion API).
 *
 * This lives in the application layer rather than beside [ReaderDocument], where it was, because it is
 * the **outcome of a use case** and not a fact about a document. The distinction is not academic: its
 * `Error` arm carries a display message and a retryable flag, both of which are decisions the screen
 * makes, and keeping those in the domain is what put `domain → application` in the import graph when
 * this type was first moved - the domain would have had to know how the application reports failure.
 *
 * The document itself stays in `domain`. So does [ReaderErrorCategory], because "permission denied" and
 * "malformed response" are categories of the *content source*, and the loader that classifies a failure
 * has to name one whether or not anything is displaying it.
 */
sealed interface DocumentLoadState {

    data object Loading : DocumentLoadState

    data class Loaded(
        val document: ReaderDocument
    ) : DocumentLoadState

    /**
     * A failed load, carrying the category as well as the text.
     *
     * The UI contract requires that "permission problems must not be collapsed into generic network
     * errors" and that errors appear "where recovery is meaningful", so the screen needs to know
     * whether Retry would achieve anything — which is [ReaderErrorCategory.canRetry], not something
     * that can be recovered by pattern-matching an English sentence.
     */
    data class Error(
        val category: ReaderErrorCategory,
        val message: String
    ) : DocumentLoadState {
        val canRetry: Boolean get() = category.canRetry
    }
}
