package com.example.notionreader.data.notion

import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTTP layer the integration spec's *Repository tests* section asks for, driven by a fake HTTP
 * engine rather than a fake API.
 *
 * A fake `NotionApi` would prove nothing about this milestone: headers, retry policy, pagination and
 * error mapping all live *below* that interface. Driving [KtorNotionApi] — and, where the test is
 * about the whole slice, [NotionRepository] on top of it — over `MockEngine` exercises the real client
 * configuration ([configureForNotion]), the real serialization and the real mapping onto
 * [ReaderErrorCategory].
 *
 * Retry delays are virtual under `runTest`, so exhausting four attempts costs no wall-clock time.
 */
class KtorNotionApiTest {

    private val pageId = "11111111-1111-1111-1111-111111111111"
    private val blockId = "22222222-2222-2222-2222-222222222222"

    // ------------------------------------------------------------------ one page of children

    @Test
    fun readsOnePageOfChildrenAndSendsTheRequiredHeaders() = runTest {
        val mock = MockNotion(listOf(okChildren(block("b1", "First."))))

        val blocks = mock.api().retrieveBlockChildren(blockId)

        assertEquals(listOf("b1"), blocks.map { it.id })
        assertEquals(1, mock.requests.size)
        assertEquals("Bearer ntn_test", mock.requests[0].headers[HttpHeaders.Authorization])
        assertEquals(KtorNotionApi.NOTION_VERSION, mock.requests[0].headers["Notion-Version"])
        assertEquals("100", mock.requests[0].url.parameters["page_size"])
        assertTrue(
            mock.requests[0].url.toString().endsWith("/blocks/$blockId/children?page_size=100")
        )
    }

    // ------------------------------------------------------------------ pagination

    @Test
    fun followsTheCursorUntilTheLastPage() = runTest {
        val mock = MockNotion(
            listOf(
                okChildren(block("b1", "First."), nextCursor = "cursor-2"),
                okChildren(block("b2", "Second."))
            )
        )

        val blocks = mock.api().retrieveBlockChildren(blockId)

        assertEquals(listOf("b1", "b2"), blocks.map { it.id })
        assertEquals(2, mock.requests.size)
        assertEquals("cursor-2", mock.requests[1].url.parameters["start_cursor"])
    }

    /** `has_more` without a cursor would loop forever, so it must terminate. */
    @Test
    fun stopsWhenHasMoreArrivesWithoutACursor() = runTest {
        val mock = MockNotion(
            listOf(okChildren(block("b1", "First."), nextCursor = null, hasMore = true))
        )

        val blocks = mock.api().retrieveBlockChildren(blockId)

        assertEquals(listOf("b1"), blocks.map { it.id })
        assertEquals(1, mock.requests.size)
    }

    // ------------------------------------------------------------------ nested children

    @Test
    fun readsNestedChildrenThroughTheRealHttpStack() = runTest {
        val parent = block("b1", "Parent").replace("\"has_children\": false", "\"has_children\": true")
        val mock = MockNotion(
            listOf(
                okPage(),
                okChildren(parent),
                okChildren(block("b1a", "Nested child"))
            )
        )

        val document = NotionRepository(mock.api()).getDocument(pageId)

        assertEquals(listOf("Parent", "Nested child"), document.chunkTexts)
        assertEquals(3, mock.requests.size)
        assertTrue(mock.requests[2].url.toString().contains("/blocks/b1/children"))
    }

    // ------------------------------------------------------------------ error mapping

    @Test
    fun mapsUnauthorizedToAuthorizationRequiredAndDoesNotRetry() = runTest {
        val mock = MockNotion(listOf(error(HttpStatusCode.Unauthorized, "unauthorized")))

        val failure = mock.api().failureOfPageRequest()

        assertEquals(ReaderErrorCategory.AUTHORIZATION_REQUIRED, failure.category)
        assertEquals("authentication failures are never retried", 1, mock.requests.size)
    }

    @Test
    fun mapsRestrictedResourceToPermissionDenied() = runTest {
        val mock = MockNotion(listOf(error(HttpStatusCode.Forbidden, "restricted_resource")))

        val failure = mock.api().failureOfPageRequest()

        assertEquals(ReaderErrorCategory.PERMISSION_DENIED, failure.category)
        assertEquals(1, mock.requests.size)
    }

    @Test
    fun mapsObjectNotFoundToPageNotFound() = runTest {
        val mock = MockNotion(listOf(error(HttpStatusCode.NotFound, "object_not_found")))

        val failure = mock.api().failureOfPageRequest()

        assertEquals(ReaderErrorCategory.PAGE_NOT_FOUND, failure.category)
        assertEquals(1, mock.requests.size)
    }

    /** A 403 with no Notion error code still has to land in the right category. */
    @Test
    fun mapsAnUncoded403ToPermissionDenied() = runTest {
        val mock = MockNotion(listOf(plain(HttpStatusCode.Forbidden, "nope")))

        assertEquals(
            ReaderErrorCategory.PERMISSION_DENIED,
            mock.api().failureOfPageRequest().category
        )
    }

    @Test
    fun retriesRateLimitsAndReportsThemWhenTheRetriesRunOut() = runTest {
        val mock = MockNotion(List(4) { error(HttpStatusCode.TooManyRequests, "rate_limited") })

        val failure = mock.api().failureOfPageRequest()

        assertEquals(ReaderErrorCategory.RATE_LIMITED, failure.category)
        assertEquals(4, mock.requests.size)
        assertTrue("a rate limit is worth offering Retry for", failure.category.canRetry)
    }

    @Test
    fun succeedsWhenARateLimitIsFollowedByASuccess() = runTest {
        val mock = MockNotion(
            listOf(error(HttpStatusCode.TooManyRequests, "rate_limited"), okPage())
        )

        val page = mock.api().retrievePage(pageId)

        assertEquals(pageId, page.id)
        assertEquals(2, mock.requests.size)
    }

    @Test
    fun retriesServerErrorsAndReportsThemAsServerErrors() = runTest {
        val mock = MockNotion(List(4) { plain(HttpStatusCode.InternalServerError, "boom") })

        val failure = mock.api().failureOfPageRequest()

        assertEquals(ReaderErrorCategory.SERVER_ERROR, failure.category)
        assertEquals(4, mock.requests.size)
    }

    @Test
    fun mapsServiceOverloadToServerError() = runTest {
        val overloaded = HttpStatusCode.fromValue(SERVICE_OVERLOAD)
        val mock = MockNotion(List(4) { error(overloaded, "service_overload") })

        assertEquals(
            ReaderErrorCategory.SERVER_ERROR,
            mock.api().failureOfPageRequest().category
        )
    }

    @Test
    fun mapsAnUnreachableNetworkToNetworkUnavailable() = runTest {
        val mock = MockNotion(List(4) { Canned.Failure(IOException("no route to host")) })

        val failure = mock.api().failureOfPageRequest()

        assertEquals(ReaderErrorCategory.NETWORK_UNAVAILABLE, failure.category)
        assertEquals(4, mock.requests.size)
        assertTrue("connectivity is worth offering Retry for", failure.category.canRetry)
    }

    @Test
    fun mapsAnUnreadablePayloadToMalformedResponse() = runTest {
        val mock = MockNotion(
            listOf(plain(HttpStatusCode.OK, """{ "object": "list", "results": "not a list" }"""))
        )

        assertEquals(
            ReaderErrorCategory.MALFORMED_RESPONSE,
            mock.api().failureOfChildrenRequest().category
        )
    }

    @Test
    fun mapsAnUnrecognisedClientErrorToMalformedResponseWithoutRetrying() = runTest {
        val mock = MockNotion(listOf(plain(HttpStatusCode.BadRequest, "bad cursor")))

        val failure = mock.api().failureOfChildrenRequest()

        assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, failure.category)
        assertEquals(
            "an unmapped client error is our bug, not something a retry can fix",
            1,
            mock.requests.size
        )
    }

    @Test
    fun keepsDiagnosticDetailOutOfTheSpokenMessage() = runTest {
        val mock = MockNotion(listOf(error(HttpStatusCode.NotFound, "object_not_found")))

        val failure = mock.api().failureOfPageRequest()

        assertTrue(failure.diagnostic.contains("404"))
        assertTrue(failure.diagnostic.contains("object_not_found"))
        assertTrue(
            "the spoken message must not recite payload detail",
            !failure.message!!.contains("object_not_found")
        )
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun cancellationDuringPaginationStaysCancellation() = runTest {
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (request.url.parameters["start_cursor"] == null) {
                respond(
                    childrenJson(block("b1", "First."), nextCursor = "cursor-2"),
                    HttpStatusCode.OK,
                    JSON_HEADERS
                )
            } else {
                inFlight.complete(Unit)
                release.await()
                respond(childrenJson(block("b2", "Second.")), HttpStatusCode.OK, JSON_HEADERS)
            }
        }

        val api = KtorNotionApi(
            token = "ntn_test",
            httpClient = HttpClient(engine) { configureForNotion() },
            baseUrl = BASE_URL
        )

        var failure: Throwable? = null
        val job = launch {
            try {
                api.retrieveBlockChildren(blockId)
            } catch (e: Throwable) {
                failure = e
            }
        }

        inFlight.await()
        job.cancelAndJoin()

        assertNotNull("the second page request should have been in flight", failure)
        assertTrue(
            "cancellation must remain cancellation, not become a Notion failure: $failure",
            failure is CancellationException
        )
        assertTrue("cancellation must not be reported as a load failure", failure !is ReaderLoadException)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun KtorNotionApi.failureOfPageRequest(): ReaderLoadException =
        failureOf { retrievePage(pageId) }

    private suspend fun KtorNotionApi.failureOfChildrenRequest(): ReaderLoadException =
        failureOf { retrieveBlockChildren(blockId) }

    private suspend fun failureOf(block: suspend () -> Unit): ReaderLoadException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a ReaderLoadException but got $error", error is ReaderLoadException)
        return error as ReaderLoadException
    }

    private fun okPage() = plain(HttpStatusCode.OK, PAGE_JSON)

    private fun okChildren(
        vararg blocks: String,
        nextCursor: String? = null,
        hasMore: Boolean = nextCursor != null
    ) = plain(HttpStatusCode.OK, childrenJson(*blocks, nextCursor = nextCursor, hasMore = hasMore))

    private fun childrenJson(
        vararg blocks: String,
        nextCursor: String? = null,
        hasMore: Boolean = nextCursor != null
    ) = """
        {
          "object": "list",
          "results": [${blocks.joinToString(",")}],
          "next_cursor": ${nextCursor?.let { "\"$it\"" } ?: "null"},
          "has_more": $hasMore
        }
    """.trimIndent()

    private fun error(status: HttpStatusCode, code: String) = plain(
        status,
        """{ "object": "error", "status": ${status.value}, "code": "$code", "message": "test failure" }"""
    )

    private fun plain(status: HttpStatusCode, body: String) = Canned.Response(status, body)

    private fun block(id: String, text: String) = """
        {
          "object": "block",
          "id": "$id",
          "type": "paragraph",
          "has_children": false,
          "paragraph": {
            "rich_text": [ { "type": "text", "plain_text": "$text", "text": { "content": "$text" } } ]
          }
        }
    """.trimIndent()

    /** One canned reply from the fake engine. */
    private sealed interface Canned {
        data class Response(val status: HttpStatusCode, val body: String) : Canned
        data class Failure(val error: IOException) : Canned
    }

    /** A fake HTTP engine that serves canned replies in order and records what was asked. */
    private inner class MockNotion(private val responses: List<Canned>) {

        val requests = mutableListOf<HttpRequestData>()

        val engine: MockEngine = MockEngine { request ->
            requests += request
            when (val response = responses.getOrNull(requests.size - 1)) {
                is Canned.Response -> respond(response.body, response.status, JSON_HEADERS)
                is Canned.Failure -> throw response.error

                null -> throw AssertionError("unexpected request #${requests.size} to ${request.url}")
            }
        }

        fun api() = KtorNotionApi(
            token = "ntn_test",
            httpClient = HttpClient(engine) { configureForNotion() },
            baseUrl = BASE_URL
        )
    }

    private companion object {
        const val BASE_URL = "https://api.notion.test/v1"

        /** 529 is what Notion returns for `service_overload`, and is not a named Ktor status. */
        const val SERVICE_OVERLOAD = 529

        val JSON_HEADERS: Headers = headersOf(HttpHeaders.ContentType, "application/json")

        val PAGE_JSON = """
            {
              "object": "page",
              "id": "11111111-1111-1111-1111-111111111111",
              "last_edited_time": "2026-01-01T00:00:00.000Z",
              "properties": {
                "title": {
                  "type": "title",
                  "title": [ { "type": "text", "plain_text": "Test page" } ]
                }
              }
            }
        """.trimIndent()
    }
}
