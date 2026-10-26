package com.example.notionreader.data.notion

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotionPropertyPaginationTest {
    @Test fun propertyIdsRepeatAcrossDistinctOrderedValuesAndPages() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            val first = requests++ == 0
            if (!first) assertEquals("opaque", request.url.parameters["start_cursor"])
            val content = if (first) "First" else "Second"
            respond("""{"object":"list","results":[{"object":"property_item","id":"title","type":"title",
                "title":{"type":"text","plain_text":"$content"}}],"has_more":$first,
                "next_cursor":${if (first) "\"opaque\"" else "null"}}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = KtorNotionApi("fixture-token", HttpClient(engine) { configureForNotion() })
        try {
            val items = api.retrieveAllPropertyItems("11111111-1111-1111-1111-111111111111", "title")
            assertEquals(listOf("First", "Second"), items.map { it.title!!.plainText })
            assertEquals(2, requests)
        } finally { api.close() }
    }
}
