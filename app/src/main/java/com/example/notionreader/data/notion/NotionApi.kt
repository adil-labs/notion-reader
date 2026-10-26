package com.example.notionreader.data.notion

import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.SerializationException

/** JSON configuration shared by the API, the repository and the tests. */
internal val notionJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/*
 * Every failure this layer produces is a [com.example.notionreader.domain.ReaderLoadException] carrying
 * one of the nine categories the integration spec requires. The spec names this component
 * (`NotionApiClient`) as the place responsible for error mapping, so nothing above this file has to
 * know about HTTP status codes or Notion error codes.
 */

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

    /**
     * `POST /v1/search` — every page this integration can see, following pagination.
     *
     * The same endpoint as [searchMostRecentPage] with the sort kept and the page size raised. That is
     * the whole of Browse's data source: the integration sees exactly the pages that have been shared
     * with it, so "All Notes" is a fact about permissions rather than a query the app invents.
     *
     * Deliberately returns pages rather than a `ReaderDocument` per result: a result needs a title and a
     * date, both of which are already on [NotionPage], and fetching block children for every result would
     * turn one request into hundreds before the user has looked at anything.
     */
    suspend fun searchPages(): List<NotionPage>

    /** `GET /v1/databases/{database_id}` — database metadata. */
    suspend fun retrieveDatabase(databaseId: String): NotionDatabase? = throw UnsupportedOperationException("Database metadata is unavailable")

    /** `GET /v1/data_sources/{data_source_id}` — data source metadata. */
    suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? = throw UnsupportedOperationException("Data-source metadata is unavailable")

    /**
     * `GET /v1/pages/{page_id}/properties/{property_id}` — paginates reference-bearing property values
     * such as relations that may be truncated on the main page object.
     */
    suspend fun retrievePropertyItems(
        pageId: String,
        propertyId: String,
        pageSize: Int = 100,
        startCursor: String? = null
    ): NotionPropertyItemList = throw UnsupportedOperationException("Property pagination is unavailable")

    /**
     * Paginates all property items for a given property until exhaustion.
     */
    suspend fun retrieveAllPropertyItems(
        pageId: String,
        propertyId: String
    ): List<NotionPropertyItem> = throw UnsupportedOperationException("Property pagination is unavailable")

    /** `POST /v1/search` with object=data_source filter. */
    suspend fun searchDataSources(): List<NotionDataSource> = throw UnsupportedOperationException("Data-source search is unavailable")

    /** `POST /v1/data_sources/{data_source_id}/query` — query data source members. */
    suspend fun queryDataSource(
        dataSourceId: String,
        pageSize: Int = 100,
        startCursor: String? = null
    ): NotionPageList = throw UnsupportedOperationException("Data-source query is unavailable")

    /** Queries all member pages of a data source following pagination. */
    suspend fun queryDataSourceMembers(dataSourceId: String): List<NotionPage> = throw UnsupportedOperationException("Data-source query is unavailable")

    fun close()
}

/**
 * Ktor-backed [NotionApi].
 *
 * Behaviour follows the published Notion request limits
 * (https://developers.notion.com/reference/request-limits):
 * - `Authorization: Bearer <token>` and `Notion-Version` are sent on every request.
 * - HTTP 429 (`rate_limited`, honouring `Retry-After`) and 529/5xx (`service_overload`) are retried,
 *   because every call this class makes is a read.
 * - Nothing else is retried. In particular an authentication or permission failure is reported on
 *   the first response: repeating it cannot change the answer.
 * - Retries are limited, and delays back off exponentially with jitter.
 * - Every failure is mapped onto the spec's error taxonomy via [failureFor], so callers deal in
 *   categories rather than in HTTP status codes.
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
        var loops = 0
        val seenCursors = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()

        while (true) {
            if (++loops > MAX_PAGINATION_LOOPS) {
                throw ReaderLoadException(
                    ReaderErrorCategory.MALFORMED_RESPONSE,
                    "Exceeded maximum pagination iterations ($MAX_PAGINATION_LOOPS)"
                )
            }
            val page: NotionBlockList = call(
                path = "/blocks/$blockId/children",
                parameters = buildMap {
                    put("page_size", PAGE_SIZE.toString())
                    cursor?.let { put("start_cursor", it) }
                }
            )

            page.validateEnvelope()
            validateCursorProgress(page.hasMore, page.nextCursor, seenCursors)
            for (b in page.results) {
                validateItemId(b.id, seenIds)
            }

            blocks += page.results

            cursor = page.nextCursor
            if (!page.hasMore || cursor == null) return blocks
        }
    }

    override suspend fun searchMostRecentPage(): NotionPage? {
        val body = searchBody(pageSize = 1)
        val results: NotionPageList = call(path = "/search", body = body)
        results.validateEnvelope()
        return results.results.firstOrNull()
    }

    override suspend fun searchPages(): List<NotionPage> {
        val pages = mutableListOf<NotionPage>()
        var cursor: String? = null
        var loops = 0
        val seenCursors = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()

        while (true) {
            if (++loops > MAX_PAGINATION_LOOPS) {
                throw ReaderLoadException(
                    ReaderErrorCategory.MALFORMED_RESPONSE,
                    "Exceeded maximum pagination iterations ($MAX_PAGINATION_LOOPS)"
                )
            }
            val results: NotionPageList = call(
                path = "/search",
                body = searchBody(pageSize = PAGE_SIZE, startCursor = cursor)
            )

            results.validateEnvelope()
            validateCursorProgress(results.hasMore, results.nextCursor, seenCursors)
            for (p in results.results) {
                validateItemId(p.id, seenIds)
            }

            pages += results.results

            cursor = results.nextCursor
            if (!results.hasMore || cursor == null) return pages
        }
    }

    override suspend fun retrieveDatabase(databaseId: String): NotionDatabase =
        call(path = "/databases/$databaseId")

    override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource =
        call(path = "/data_sources/$dataSourceId")

    override suspend fun retrievePropertyItems(
        pageId: String,
        propertyId: String,
        pageSize: Int,
        startCursor: String?
    ): NotionPropertyItemList {
        val list: NotionPropertyItemList = call(
            path = "/pages/$pageId/properties/$propertyId",
            parameters = buildMap {
                put("page_size", pageSize.toString())
                startCursor?.let { put("start_cursor", it) }
            }
        )
        list.validateEnvelope()
        return list
    }

    override suspend fun retrieveAllPropertyItems(pageId: String, propertyId: String): List<NotionPropertyItem> {
        val items = mutableListOf<NotionPropertyItem>()
        var cursor: String? = null
        var loops = 0
        val seenCursors = mutableSetOf<String>()

        while (true) {
            if (++loops > MAX_PAGINATION_LOOPS) {
                throw ReaderLoadException(
                    ReaderErrorCategory.MALFORMED_RESPONSE,
                    "Exceeded maximum pagination iterations ($MAX_PAGINATION_LOOPS)"
                )
            }
            val list = retrievePropertyItems(
                pageId = pageId,
                propertyId = propertyId,
                pageSize = PAGE_SIZE,
                startCursor = cursor
            )

            validateCursorProgress(list.hasMore, list.nextCursor, seenCursors)
            // Each item's id is the shared property id, not a unique value identity.

            items += list.results

            cursor = list.nextCursor
            if (!list.hasMore || cursor == null) return items
        }
    }

    override suspend fun queryDataSource(
        dataSourceId: String,
        pageSize: Int,
        startCursor: String?
    ): NotionPageList {
        val results: NotionPageList = call(
            path = "/data_sources/$dataSourceId/query",
            body = queryDataSourceBody(pageSize = pageSize, startCursor = startCursor)
        )
        results.validateEnvelope()
        return results
    }

    override suspend fun queryDataSourceMembers(dataSourceId: String): List<NotionPage> {
        val pages = mutableListOf<NotionPage>()
        var cursor: String? = null
        var loops = 0
        val seenCursors = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()

        while (true) {
            if (++loops > MAX_PAGINATION_LOOPS) {
                throw ReaderLoadException(
                    ReaderErrorCategory.MALFORMED_RESPONSE,
                    "Exceeded maximum pagination iterations ($MAX_PAGINATION_LOOPS)"
                )
            }
            val results = queryDataSource(
                dataSourceId = dataSourceId,
                pageSize = PAGE_SIZE,
                startCursor = cursor
            )

            validateCursorProgress(results.hasMore, results.nextCursor, seenCursors)
            for (p in results.results) {
                validateItemId(p.id, seenIds)
            }

            pages += results.results

            cursor = results.nextCursor
            if (!results.hasMore || cursor == null) return pages
        }
    }

    override suspend fun searchDataSources(): List<NotionDataSource> {
        val dataSources = mutableListOf<NotionDataSource>()
        var cursor: String? = null
        var loops = 0
        val seenCursors = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()

        while (true) {
            if (++loops > MAX_PAGINATION_LOOPS) {
                throw ReaderLoadException(
                    ReaderErrorCategory.MALFORMED_RESPONSE,
                    "Exceeded maximum pagination iterations ($MAX_PAGINATION_LOOPS)"
                )
            }
            val results: NotionDataSourceList = call(
                path = "/search",
                body = searchDataSourceBody(pageSize = PAGE_SIZE, startCursor = cursor)
            )

            results.validateEnvelope()
            validateCursorProgress(results.hasMore, results.nextCursor, seenCursors)
            for (ds in results.results) {
                validateItemId(ds.id, seenIds)
            }

            dataSources += results.results

            cursor = results.nextCursor
            if (!results.hasMore || cursor == null) return dataSources
        }
    }

    private fun validateCursorProgress(
        hasMore: Boolean,
        nextCursor: String?,
        seenCursors: MutableSet<String>
    ) {
        if (hasMore && nextCursor.isNullOrBlank()) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "has_more is true but next_cursor is null or blank"
            )
        }
        if (nextCursor != null && !seenCursors.add(nextCursor)) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Pagination cursor did not advance"
            )
        }
    }

    private fun validateItemId(id: String, seenIds: MutableSet<String>) {
        if (id.isBlank() || id != id.trim() || id.contains(" ")) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Malformed identity in a list response"
            )
        }
        if (!seenIds.add(id)) {
            throw ReaderLoadException(
                ReaderErrorCategory.MALFORMED_RESPONSE,
                "Duplicate identity in a list response"
            )
        }
    }

    /**
     * The `POST /v1/search` body for pages.
     *
     * Sorted by `last_edited_time` descending.
     */
    private fun searchBody(pageSize: Int, startCursor: String? = null): JsonObject = buildJsonObject {
        put("filter", buildJsonObject {
            put("property", "object")
            put("value", "page")
        })
        put("sort", buildJsonObject {
            put("direction", "descending")
            put("timestamp", "last_edited_time")
        })
        put("page_size", pageSize)
        startCursor?.let { put("start_cursor", it) }
    }

    private fun queryDataSourceBody(pageSize: Int, startCursor: String? = null): JsonObject = buildJsonObject {
        put("page_size", pageSize)
        startCursor?.let { put("start_cursor", it) }
    }

    private fun searchDataSourceBody(pageSize: Int, startCursor: String? = null): JsonObject = buildJsonObject {
        put("filter", buildJsonObject {
            put("property", "object")
            put("value", "data_source")
        })
        put("page_size", pageSize)
        startCursor?.let { put("start_cursor", it) }
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
                throw ReaderLoadException(
                    category = ReaderErrorCategory.NETWORK_UNAVAILABLE,
                    diagnostic = "${e::class.simpleName} after $attempt attempts: ${e.message}",
                    cause = e
                )
            }

            val text = response.bodyAsText()
            if (response.status.isSuccess()) return decodeNotionResponse(text)

            val failure = failureFor(response.status.value, text)

            // Retry gates on the category, not on the status code, so the spec's "retry only
            // transient failures" and "do not automatically retry permission/authentication
            // failures" are decided in exactly one place.
            if (attempt < MAX_ATTEMPTS && failure.category.canRetry) {
                delay(response.retryDelayMillis(attempt))
                continue
            }

            throw failure
        }
    }

    companion object {

        const val DEFAULT_BASE_URL = "https://api.notion.com/v1"

        /** Current data API version (https://developers.notion.com/reference/versioning). */
        const val NOTION_VERSION = "2026-03-11"

        /** Notion caps `page_size` at 100. */
        private const val PAGE_SIZE = 100

        private const val MAX_PAGINATION_LOOPS = 500

        private const val MAX_ATTEMPTS = 4

        private const val MAX_RETRY_DELAY_MILLIS = 30_000L

        fun createHttpClient(): HttpClient = HttpClient(OkHttp) { configureForNotion() }

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

/**
 * Client configuration shared by the real client and by the tests.
 *
 * Tests build an engine of their own (`MockEngine`) but must not build their *own* client: a client
 * configured differently would prove nothing about the one the app ships, and would not catch a
 * missing `expectSuccess = false` (which would make Ktor throw before this class can map the status).
 */
fun HttpClientConfig<*>.configureForNotion() {
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

/**
 * Maps one HTTP failure onto the spec's error taxonomy.
 *
 * Notion's own error `code` wins over the status, because the code is the more precise signal
 * (`restricted_resource` and `unauthorized` can both arrive as 403). Statuses with no code, or codes
 * this app has no specific category for, fall back to the status, and anything left over becomes
 * [ReaderErrorCategory.MALFORMED_RESPONSE] — the exchange could not be completed and it is not a
 * credential, access, rate or connectivity problem.
 *
 * The returned `diagnostic` keeps the status, code and Notion's message for a log; the user-facing
 * message stays the category's one-sentence explanation, so nothing large or private is read aloud.
 */
internal fun failureFor(status: Int, body: String): ReaderLoadException {
    val error = runCatching { notionJson.decodeFromString<NotionErrorBody>(body) }.getOrNull()

    val category = when (error?.code) {
        "unauthorized" -> ReaderErrorCategory.AUTHORIZATION_REQUIRED
        "restricted_resource" -> ReaderErrorCategory.PERMISSION_DENIED
        "object_not_found" -> ReaderErrorCategory.PAGE_NOT_FOUND
        "rate_limited" -> ReaderErrorCategory.RATE_LIMITED
        "service_overload" -> ReaderErrorCategory.SERVER_ERROR
        else -> when (status) {
            401 -> ReaderErrorCategory.AUTHORIZATION_REQUIRED
            403 -> ReaderErrorCategory.PERMISSION_DENIED
            404 -> ReaderErrorCategory.PAGE_NOT_FOUND
            429 -> ReaderErrorCategory.RATE_LIMITED
            in 500..599 -> ReaderErrorCategory.SERVER_ERROR
            else -> ReaderErrorCategory.MALFORMED_RESPONSE
        }
    }

    val diagnostic = buildString {
        append("HTTP ").append(status)
        error?.code?.let { append(" code=").append(it) }
        error?.message?.takeIf { it.isNotBlank() }?.let { append(" message=").append(it.take(300)) }
        // Only an error snippet is ever kept, and only briefly: a successful body is never logged, so
        // no note content can reach a log through this path.
        if (error == null && body.isNotBlank()) {
            append(" body=").append(body.trim().take(300))
        }
    }

    return ReaderLoadException(category = category, diagnostic = diagnostic)
}

/** Decodes a Notion payload, turning a schema surprise into a categorised, user-readable failure. */
internal inline fun <reified T> decodeNotionResponse(text: String): T = try {
    notionJson.decodeFromString(text)
} catch (e: SerializationException) {
    throw ReaderLoadException(
        category = ReaderErrorCategory.MALFORMED_RESPONSE,
        diagnostic = "${e::class.simpleName}: ${e.message}",
        cause = e
    )
}

