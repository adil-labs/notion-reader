package com.example.notionreader.application

import com.example.notionreader.data.notion.title
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadCancelledException
import com.example.notionreader.domain.ReaderLoadException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one high-level use case the spec puts above the whole integration stack.
 *
 * The interesting behaviour is not that a document comes back — it is what happens on the way out: a
 * failure has to arrive as a categorised value rather than an exception, and cancellation has to stay
 * cancellation. `runCatching` is the obvious way to write this class and the wrong one, so the
 * cancellation case is asserted rather than assumed.
 */
class LoadReaderDocumentTest {

    private val document = ReaderDocument.fromChunkTexts(
        pageId = "page-1",
        title = "Test",
        texts = listOf("One.")
    )

    @Test
    fun returnsTheLoadedDocument() = runTest {
        val load = DefaultLoadReaderDocument { document }

        assertEquals(document, load("page-1").getOrNull())
    }

    @Test
    fun passesTheRequestedPageReferenceThrough() = runTest {
        var requested: String? = null
        val load = DefaultLoadReaderDocument { reference ->
            requested = reference
            document
        }

        load("page-1")

        assertEquals("page-1", requested)
    }

    @Test
    fun returnsACategorisedFailureInsteadOfThrowing() = runTest {
        val load = DefaultLoadReaderDocument {
            throw ReaderLoadException(ReaderErrorCategory.PAGE_NOT_FOUND)
        }

        val error = load("page-1").exceptionOrNull()

        assertTrue(error is ReaderLoadException)
        assertEquals(ReaderErrorCategory.PAGE_NOT_FOUND, (error as ReaderLoadException).category)
    }

    /** `Result` has to be total: an unanticipated exception must not be able to reach the caller. */
    @Test
    fun wrapsAnUnexpectedExceptionRatherThanLettingItEscape() = runTest {
        val load = DefaultLoadReaderDocument { throw IllegalStateException("boom") }

        val error = load("page-1").exceptionOrNull()

        assertTrue(error is ReaderLoadException)
        assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, (error as ReaderLoadException).category)
        assertTrue("the cause is kept for logs", error.cause is IllegalStateException)
    }

    @Test
    fun cancellationIsThrownRatherThanReturnedAsAFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val load = DefaultLoadReaderDocument {
            started.complete(Unit)
            release.await()
            document
        }

        var thrown: Throwable? = null
        val job = launch {
            try {
                load("page-1")
            } catch (e: Throwable) {
                thrown = e
            }
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(
            "an abandoned load must not surface as a failure: $thrown",
            thrown is ReaderLoadCancelledException
        )
        assertTrue("it is still cancellation", thrown is CancellationException)
        assertEquals(ReaderErrorCategory.CANCELLED, (thrown as ReaderLoadCancelledException).category)
    }

    // ---------------------------------------------------------------- taxonomy

    /**
     * The taxonomy is fixed by the spec's error-handling list. Asserting it verbatim is the point: an
     * added category, a rename or a dropped one is a change to a contract, not a refactor.
     */
    @Test
    fun taxonomyMatchesTheSpecList() {
        assertEquals(
            listOf(
                "AUTHORIZATION_REQUIRED",
                "PERMISSION_DENIED",
                "PAGE_NOT_FOUND",
                "RATE_LIMITED",
                "NETWORK_UNAVAILABLE",
                "SERVER_ERROR",
                "MALFORMED_RESPONSE",
                "UNSUPPORTED_DOCUMENT_STRUCTURE",
                "CANCELLED"
            ),
            ReaderErrorCategory.entries.map { it.name }
        )
    }

    /** Every message is read aloud by TalkBack, so each has to be usable as a sentence on its own. */
    @Test
    fun everyCategoryHasASpokenSafeMessage() {
        ReaderErrorCategory.entries.forEach { category ->
            assertTrue("${category.name} needs a message", category.message.isNotBlank())
            assertTrue(
                "${category.name} must not leak a payload detail",
                !category.message.contains("null", ignoreCase = true)
            )
        }
    }

    @Test
    fun onlyTransientCategoriesOfferRetry() {
        val retryable = ReaderErrorCategory.entries.filter { it.canRetry }.map { it.name }

        assertEquals(
            listOf("RATE_LIMITED", "NETWORK_UNAVAILABLE", "SERVER_ERROR"),
            retryable
        )
    }
}
