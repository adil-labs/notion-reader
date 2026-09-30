package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadCancelledException
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.document.DocumentLoadPurpose
import kotlin.coroutines.cancellation.CancellationException

/**
 * The single high-level use case the integration spec asks the first vertical slice to expose.
 *
 * "Everything below this interface is replaceable implementation detail" — the UI depends on this and
 * not on the Notion layer, so swapping the provider, or replacing HTTP with a cache, changes nothing
 * that the screens can see.
 *
 * A `Result` rather than a thrown exception because failure is an ordinary outcome of loading remote
 * content: the Note screen has to render "could not load, Retry" as a first-class state, and the
 * category it needs is the one carried by [ReaderLoadException].
 *
 * Cancellation is the exception to that: it is thrown, never returned, and typed as
 * [ReaderLoadCancelledException] so that leaving a screen is not reported as a failure.
 */
interface LoadReaderDocument {
    suspend operator fun invoke(
        pageId: String,
        knownRemoteLastEditedAt: java.time.Instant? = null,
        forceRefresh: Boolean = false,
        purpose: DocumentLoadPurpose = DocumentLoadPurpose.PLAYBACK
    ): Result<ReaderDocument>
}

/**
 * [LoadReaderDocument] over an arbitrary `suspend` loader.
 *
 * Taking a lambda keeps this class out of the `notion` package: the same use case works for the
 * Notion API, for a fixture, or for a future cache, and it is testable with no HTTP at all.
 */
class DefaultLoadReaderDocument(
    private val load: suspend (String, java.time.Instant?, Boolean, DocumentLoadPurpose) -> ReaderDocument
) : LoadReaderDocument {
    constructor(loadWithoutPurpose: suspend (String, java.time.Instant?, Boolean) -> ReaderDocument) :
        this({ pageId, ts, refresh, _ -> loadWithoutPurpose(pageId, ts, refresh) })
    constructor(loadWithTimestamp: suspend (String, java.time.Instant?) -> ReaderDocument) :
        this({ pageId, ts, _, _ -> loadWithTimestamp(pageId, ts) })
    constructor(simpleLoad: suspend (String) -> ReaderDocument) :
        this({ pageId, _, _, _ -> simpleLoad(pageId) })

    override suspend operator fun invoke(
        pageId: String,
        knownRemoteLastEditedAt: java.time.Instant?,
        forceRefresh: Boolean,
        purpose: DocumentLoadPurpose
    ): Result<ReaderDocument> = try {
        Result.success(load(pageId, knownRemoteLastEditedAt, forceRefresh, purpose))
    } catch (e: CancellationException) {
        // `runCatching` would have swallowed this and the UI would have shown a network failure for a
        // screen the user simply left. Rethrowing keeps structured concurrency intact; the typed
        // wrapper keeps the taxonomy's CANCELLED category observable at the boundary.
        throw if (e is ReaderLoadCancelledException) e else ReaderLoadCancelledException(cause = e)
    } catch (e: ReaderLoadException) {
        Result.failure(e)
    } catch (e: Exception) {
        // A total `Result`: an unexpected exception must not be able to crash the caller. The cause is
        // preserved for logs, and the category says what is actually true from the outside — the
        // exchange could not be turned into a document.
        Result.failure(
            ReaderLoadException(
                category = ReaderErrorCategory.MALFORMED_RESPONSE,
                diagnostic = "unexpected ${e::class.simpleName}: ${e.message}",
                cause = e
            )
        )
    }
}

/** The category of a failed load. Anything unmapped is treated as an unreadable response. */
val Throwable.readerErrorCategory: ReaderErrorCategory
    get() = (this as? ReaderLoadException)?.category ?: ReaderErrorCategory.MALFORMED_RESPONSE

/** Message to show and to read aloud for a failed load. */
val Throwable.readerErrorMessage: String
    get() = (this as? ReaderLoadException)?.message ?: readerErrorCategory.message

/**
 * The failure as a [ReaderLoadException], so anything that can fail can be rendered.
 *
 * [DefaultLoadReaderDocument] already guarantees this shape for loads that go through it, but a queue
 * failure is reported to the session as a [ReaderLoadException] and nothing should be able to reach it
 * holding a bare `RuntimeException`. Unmapped failures become `MALFORMED_RESPONSE` via
 * [readerErrorCategory], which is the same total fallback the loader itself uses.
 */
fun Throwable.asReaderLoadException(): ReaderLoadException = this as? ReaderLoadException
    ?: ReaderLoadException(
        category = readerErrorCategory,
        message = readerErrorMessage,
        diagnostic = "unexpected ${this::class.simpleName}: $message",
        cause = this
    )

/** Whether offering Retry for this failure is meaningful. */
val Throwable.isReaderErrorRetryable: Boolean
    get() = readerErrorCategory.canRetry
