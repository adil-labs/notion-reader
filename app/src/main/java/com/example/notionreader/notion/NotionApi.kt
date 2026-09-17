package com.example.notionreader.notion

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.random.Random

/** JSON configuration shared by the API, the repository and the tests. */
internal val notionJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Raised for every Notion failure. [message] is safe to show in the UI and to read out by TalkBack. */
class NotionApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The Notion API surface this app needs (https://developers.notion.com/reference).
 *
 * An interface rather than a concrete class so the repository can be unit-tested on the JVM.
 */
interface NotionApi {

    /** `GET /v1/pages/{page_id}` — page metadata, including the title property. */
    suspend fun retrievePage(pageId: String): NotionPage

    /** `GET /v1/blocks/{block_id}/children`, following pagination until every block is read. */
    suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock>

    /** `POST /v1/search` — the most recently edited page this integration can see, if any. */
    suspend fun searchMostRecentPage(): NotionPage?

    fun close()
}

/**
 * Ktor-backed [NotionApi].
 *
 * Behaviour follows the published Notion request limits
 * (https://developers.notion.com/reference/request-limits):
 * - `Authorization: Bearer <token>` and `Notion-Version` are sent on every request.
 * - HTTP 429 (`rate_limited`) and 529 (`service_overload`) are retried, honouring `Retry-After`.
 * - 500/502/503/504 are retried the same way, because every call this class makes is a read.
 * - Other 4xx responses are never retried; they are reported with the Notion error code and message.
 * - Retries are limited, and delays back off exponentially with jitter.
 */
class KtorNotionApi(
    private val token: String,
    private val httpClient: HttpClient = createHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) : NotionApi {

    override suspend fun retrievePage(pageId: String): NotionPage =
        call(path = "/pages/$pageId")

    override suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock> {
        val blocks = mutableListOf<NotionBlock>()
        var cursor: String? = null

        while (true) {
            val page: NotionBlockList = call(
                path = "/blocks/$blockId/children",
                parameters = buildMap {
                    put("page_size", PAGE_SIZE.toString())
                    cursor?.let { put("start_cursor", it) }
                }
            )

            blocks += page.results

            cursor = page.nextCursor
            if (!page.hasMore || cursor == null) return blocks
        }
    }

    override suspend fun searchMostRecentPage(): NotionPage? {
        val body = buildJsonObject {
            put("filter", buildJsonObject {
                put("property", "object")
                put("value", "page")
            })
            // Notion only accepts a sort together with a filter.
            put("sort", buildJsonObject {
                put("direction", "descending")
                put("timestamp", "last_edited_time")
            })
            put("page_size", 1)
        }

        val results: NotionPageList = call(path = "/search", body = body)
        return results.results.firstOrNull()
    }

    override fun close() {
        httpClient.close()
    }

    /**
     * Performs one API call, retrying rate limits, overloads and server errors.
     *
     * Every request this class makes reads data, so retrying is always safe here.
     */
    private suspend inline fun <reified T> call(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        body: JsonObject? = null
    ): T {
        var attempt = 0

        while (true) {
            attempt += 1

            val response = try {
                httpClient.request(baseUrl + path) {
                    method = if (body == null) HttpMethod.Get else HttpMethod.Post
                    header("Authorization", "Bearer $token")
                    header("Notion-Version", NOTION_VERSION)
                    parameters.forEach { (name, value) -> parameter(name, value) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            } catch (e: IOException) {
                if (attempt < MAX_ATTEMPTS) {
                    delay(backoffMillis(attempt))
                    continue
                }
                throw NotionApiException(
                    "Could not reach Notion. Check the network connection and try again.",
                    e
                )
            }

            val text = response.bodyAsText()
            if (response.status.isSuccess()) return decodeNotionResponse(text)

            if (attempt < MAX_ATTEMPTS && response.isRetryable()) {
                delay(response.retryDelayMillis(attempt))
                continue
            }

            throw NotionApiException(describeFailure(response.status.value, text))
        }
    }

    private fun describeFailure(status: Int, body: String): String {
        val error = runCatching { notionJson.decodeFromString<NotionErrorBody>(body) }.getOrNull()

        val explanation = when (error?.code) {
            "unauthorized" ->
                "Notion rejected the integration token. Check that the token is correct and still active."

            "restricted_resource" ->
                "The integration does not have access to this page. Share the page with the integration in Notion."

            "object_not_found" ->
                "Notion could not find that page. Check the page reference and share the page with the integration."

            "validation_error" ->
                "Notion rejected the request as invalid."

            "rate_limited" ->
                "Notion is rate limiting this integration and the retries were used up. Try again shortly."

            "service_overload" ->
                "Notion is temporarily overloaded. Try again shortly."

            else -> when (status) {
                401 -> "Notion rejected the integration token (HTTP 401)."
                404 -> "Notion could not find that page (HTTP 404)."
                else -> "Notion request failed (HTTP $status)."
            }
        }

        val detail = error?.message?.trim().orEmpty()

        return listOf(explanation, detail)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    companion object {

        const val DEFAULT_BASE_URL = "https://api.notion.com/v1"

        /** Current data API version (https://developers.notion.com/reference/versioning). */
        const val NOTION_VERSION = "2026-03-11"

        /** Notion caps `page_size` at 100. */
        private const val PAGE_SIZE = 100

        private const val MAX_ATTEMPTS = 4

        private const val MAX_RETRY_DELAY_MILLIS = 30_000L

        fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false

            install(ContentNegotiation) {
                json(notionJson)
            }

            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }

        /** 429, 529 and the retryable server errors. See the request limits documentation. */
        internal fun HttpResponse.isRetryable(): Boolean = when (status.value) {
            429, 529, 500, 502, 503, 504 -> true
            else -> false
        }

        /** Honours the `Retry-After` header (integer seconds), otherwise backs off with jitter. */
        internal fun HttpResponse.retryDelayMillis(attempt: Int): Long {
            val retryAfterSeconds = headers["Retry-After"]?.trim()?.toLongOrNull()
            if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
                return (retryAfterSeconds * 1_000).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
            return backoffMillis(attempt)
        }

        internal fun backoffMillis(attempt: Int): Long {
            val exponential = 250L shl (attempt - 1).coerceIn(0, 6)
            return (exponential + Random.nextLong(0, 250)).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        }
    }
}

/** Decodes a Notion payload, turning a schema surprise into a user-readable error. */
internal inline fun <reified T> decodeNotionResponse(text: String): T = try {
    notionJson.decodeFromString(text)
} catch (e: SerializationException) {
    throw NotionApiException("Notion returned a response this app could not read.", e)
}
