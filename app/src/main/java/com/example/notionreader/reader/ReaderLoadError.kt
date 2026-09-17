package com.example.notionreader.reader

import kotlin.coroutines.cancellation.CancellationException

/**
 * The categories the integration spec requires every failure to be mapped onto.
 *
 * The list is fixed by the spec's *Error handling* section, which requires that "cancellation must
 * remain cancellation and must not be translated into a network error" and that permission problems
 * are not collapsed into generic network failures. A category — rather than a message string — is
 * what lets the UI decide whether offering Retry is meaningful, which the UI contract makes a
 * requirement of the Note screen's error state.
 *
 * [message] is written to be read aloud by TalkBack: one sentence, no identifiers, no tokens.
 */
enum class ReaderErrorCategory(
    /** Plain-language, spoken-safe explanation. Never contains a token, URL or page body. */
    val message: String,
    /** True only for failures that a later attempt can plausibly fix. */
    val canRetry: Boolean
) {
    /** No usable credential: missing, malformed, revoked, or rejected with HTTP 401. */
    AUTHORIZATION_REQUIRED(
        message = "Notion authorization is required. Check that the integration token is still active.",
        canRetry = false
    ),

    /** The credential is fine, but the integration was never given access to this content. */
    PERMISSION_DENIED(
        message = "The Notion integration does not have access to this page. " +
            "Share the page with the integration in Notion.",
        canRetry = false
    ),

    /** The page reference does not resolve: unknown, deleted, or in the trash. */
    PAGE_NOT_FOUND(
        message = "Notion could not find that page. Check the page reference in the configuration.",
        canRetry = false
    ),

    /** Notion rate limited the integration (HTTP 429) and the retries were used up. */
    RATE_LIMITED(
        message = "Notion is rate limiting this integration. Try again shortly.",
        canRetry = true
    ),

    /** The request never reached Notion, or the reply never arrived (timeouts, no connectivity). */
    NETWORK_UNAVAILABLE(
        message = "Could not reach Notion. Check the network connection and try again.",
        canRetry = true
    ),

    /** Notion reported a server-side failure (HTTP 5xx) and the retries were used up. */
    SERVER_ERROR(
        message = "Notion is temporarily overloaded or unavailable. Try again shortly.",
        canRetry = true
    ),

    /** A reply arrived but could not be understood: unexpected JSON, or a missing required field. */
    MALFORMED_RESPONSE(
        message = "Notion returned a response this app could not read.",
        canRetry = false
    ),

    /**
     * The page was read but held nothing the reader can speak.
     *
     * Deliberately *not* raised because a page contains some unsupported blocks: the spec requires
     * that "a single unsupported block should not invalidate an otherwise readable page". It is raised
     * only when the whole page yields no readable content at all.
     */
    UNSUPPORTED_DOCUMENT_STRUCTURE(
        message = "This page has no content the reader can speak.",
        canRetry = false
    ),

    /**
     * The load was abandoned, normally because the screen was left.
     *
     * Carried by [ReaderLoadCancelledException], which is still a cancellation, so structured
     * concurrency keeps working and nothing reports a network error that never happened.
     */
    CANCELLED(
        message = "The page load was cancelled.",
        canRetry = false
    )
}

/**
 * A failure that has been mapped onto [ReaderErrorCategory].
 *
 * [message] and [category] are for people. [diagnostic] carries the detail that belongs in a log —
 * the HTTP status and Notion's own error message — and is deliberately kept out of [message] so that
 * reading the screen aloud never recites a page ID or a payload. Access tokens cannot appear in
 * either: the token travels in an `Authorization` header, never in a URL, path or body.
 */
class ReaderLoadException(
    val category: ReaderErrorCategory,
    message: String = category.message,
    val diagnostic: String = message,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Cancellation, still recognisable as cancellation.
 *
 * `Result`-based APIs are tempting to implement with `runCatching`, which also swallows
 * `CancellationException` and would turn "the user left the screen" into a failure the UI reports.
 * [LoadReaderDocument] therefore rethrows cancellation — as this typed subtype, so the taxonomy's
 * `CANCELLED` category is real rather than decorative, while coroutine machinery still sees an
 * ordinary cancellation and does not log it as a crash.
 */
class ReaderLoadCancelledException(
    val category: ReaderErrorCategory = ReaderErrorCategory.CANCELLED,
    cause: Throwable? = null
) : CancellationException(category.message) {
    // `initCause` rather than the (String, Throwable) constructor: that overload only exists from
    // Java 9 / Android API 33, and this app supports API 26.
    init {
        cause?.let { initCause(it) }
    }
}
