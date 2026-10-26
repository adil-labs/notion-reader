package com.example.notionreader.data.notion

import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotionStructuralApiTest {

    @Test
    fun decodesPageWithParentAndCreatedTime() {
        val json = """
            {
                "object": "page",
                "id": "11111111-1111-1111-1111-111111111111",
                "created_time": "2026-10-26T08:00:00.000Z",
                "last_edited_time": "2026-10-26T09:30:00.000Z",
                "parent": {
                    "type": "data_source_id",
                    "data_source_id": "22222222-2222-2222-2222-222222222222"
                },
                "properties": {
                    "Related": {
                        "id": "rel-prop",
                        "type": "relation",
                        "has_more": false,
                        "relation": [
                            { "id": "33333333-3333-3333-3333-333333333333" }
                        ]
                    }
                }
            }
        """.trimIndent()

        val page = notionJson.decodeFromString<NotionPage>(json)
        assertEquals("11111111-1111-1111-1111-111111111111", page.id)
        assertEquals("2026-10-26T08:00:00.000Z", page.createdTime)
        assertNotNull(page.parent)
        assertEquals("data_source_id", page.parent!!.type)
        assertEquals("22222222-2222-2222-2222-222222222222", page.parent!!.dataSourceId)

        val relProp = page.properties["Related"]
        assertNotNull(relProp)
        assertEquals(1, relProp!!.relation.size)
        assertEquals("33333333-3333-3333-3333-333333333333", relProp.relation.single().id)
    }

    @Test
    fun decodesMentionRichText() {
        val json = """
            {
                "type": "mention",
                "plain_text": "Project Tracker",
                "mention": {
                    "type": "database",
                    "database": {
                        "id": "44444444-4444-4444-4444-444444444444"
                    }
                }
            }
        """.trimIndent()

        val rt = notionJson.decodeFromString<NotionRichText>(json)
        assertEquals("mention", rt.type)
        assertEquals("Project Tracker", rt.plainText)
        assertNotNull(rt.mention)
        assertEquals("database", rt.mention!!.type)
        assertEquals("44444444-4444-4444-4444-444444444444", rt.mention!!.database?.id)
    }

    @Test
    fun decodesStructuralBlocks() {
        val json = """
            {
                "object": "block",
                "id": "55555555-5555-5555-5555-555555555555",
                "type": "synced_block",
                "has_children": false,
                "synced_block": {
                    "synced_from": {
                        "block_id": "66666666-6666-6666-6666-666666666666"
                    }
                }
            }
        """.trimIndent()

        val block = notionJson.decodeFromString<NotionBlock>(json)
        assertEquals("synced_block", block.type)
        assertNotNull(block.syncedBlock)
        assertEquals("66666666-6666-6666-6666-666666666666", block.syncedBlock!!.syncedFrom?.blockId)
    }

    @Test
    fun decodesCaptionBearingMediaAndEmbedBlocks() {
        val json = """
            {
                "object": "block",
                "id": "55555555-5555-5555-5555-555555555556",
                "type": "image",
                "has_children": false,
                "image": {
                    "type": "external",
                    "external": { "url": "https://example.com/pic.png" },
                    "caption": [
                        { "type": "text", "plain_text": "Figure 1", "href": "https://notion.so/target-page-id" }
                    ]
                }
            }
        """.trimIndent()

        val block = notionJson.decodeFromString<NotionBlock>(json)
        assertEquals("image", block.type)
        assertNotNull(block.image)
        assertEquals("https://example.com/pic.png", block.image!!.external?.url)
        assertEquals("Figure 1", block.image!!.caption.single().plainText)
        assertEquals("https://notion.so/target-page-id", block.image!!.caption.single().href)
    }

    @Test
    fun decodesReal2026DataSourceWithRichTextTitleAndParentDatabaseId() {
        val dsJson = """
            {
                "object": "data_source",
                "id": "77777777-7777-7777-7777-777777777777",
                "parent": {
                    "type": "database_id",
                    "database_id": "88888888-8888-8888-8888-888888888888"
                },
                "title": [
                    { "type": "text", "plain_text": "Real 2026 Data Source" }
                ],
                "in_trash": false
            }
        """.trimIndent()

        val ds = notionJson.decodeFromString<NotionDataSource>(dsJson)
        assertEquals("77777777-7777-7777-7777-777777777777", ds.id)
        assertEquals("88888888-8888-8888-8888-888888888888", ds.databaseId)
        assertEquals("Real 2026 Data Source", ds.name)
        assertEquals("88888888-8888-8888-8888-888888888888", ds.parent?.databaseId)
    }

    @Test
    fun unsupportedFieldsDoNotInventADataSourceTitleOrParent() {
        val legacyJson = """
            {
                "object": "data_source",
                "id": "77777777-7777-7777-7777-777777777777",
                "database_id": "88888888-8888-8888-8888-888888888888",
                "name": "Legacy Primary Source"
            }
        """.trimIndent()

        val ds = notionJson.decodeFromString<NotionDataSource>(legacyJson)
        assertEquals("77777777-7777-7777-7777-777777777777", ds.id)
        org.junit.Assert.assertNull(ds.databaseId)
        assertEquals("", ds.name)
    }

    @Test
    fun decodesPropertyItemsListWithRelationAndRichText() {
        val json = """
            {
                "object": "list",
                "results": [
                    {
                        "object": "property_item",
                        "id": "rel-item-1",
                        "type": "relation",
                        "relation": {
                            "id": "99999999-9999-9999-9999-999999999999"
                        }
                    },
                    {
                        "object": "property_item",
                        "id": "text-item-2",
                        "type": "rich_text",
                        "rich_text": {
                            "type": "mention",
                            "plain_text": "Linked Spec",
                            "mention": {
                                "type": "page",
                                "page": { "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" }
                            }
                        }
                    }
                ],
                "next_cursor": null,
                "has_more": false
            }
        """.trimIndent()

        val list = notionJson.decodeFromString<NotionPropertyItemList>(json)
        assertEquals(2, list.results.size)
        assertEquals("99999999-9999-9999-9999-999999999999", list.results[0].relation?.id)
        assertEquals("Linked Spec", list.results[1].richText?.plainText)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", list.results[1].richText?.mention?.page?.id)
    }

    @Test
    fun emptyJsonEnvelopeThrowsMalformedResponseRatherThanEmptyComplete() {
        val emptyJson = "{}"

        val blockList = notionJson.decodeFromString<NotionBlockList>(emptyJson)
        try {
            blockList.validateEnvelope()
            fail("Expected validateEnvelope to fail on empty json")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
        }

        val pageList = notionJson.decodeFromString<NotionPageList>(emptyJson)
        try {
            pageList.validateEnvelope()
            fail("Expected validateEnvelope to fail on empty json")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
        }

        val propList = notionJson.decodeFromString<NotionPropertyItemList>(emptyJson)
        try {
            propList.validateEnvelope()
            fail("Expected validateEnvelope to fail on empty json")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
        }
    }

    @Test
    fun cursorLoopFailsWhenHasMoreIsTrueButNextCursorIsMissing() = runTest {
        val json = """
            {
                "object": "list",
                "results": [
                    { "object": "block", "id": "11111111-1111-1111-1111-111111111111", "type": "paragraph" }
                ],
                "next_cursor": null,
                "has_more": true
            }
        """.trimIndent()

        val engine = MockEngine { respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val api = KtorNotionApi("token", HttpClient(engine) { configureForNotion() }, "https://api.notion.test/v1")

        try {
            api.retrieveBlockChildren("22222222-2222-2222-2222-222222222222")
            fail("Expected malformed response for missing next_cursor")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
            assertTrue(e.diagnostic.contains("has_more is true but next_cursor"))
        }
    }

    @Test
    fun cursorLoopFailsWhenCursorRepeats() = runTest {
        val json1 = """
            {
                "object": "list",
                "results": [
                    { "object": "block", "id": "11111111-1111-1111-1111-111111111111", "type": "paragraph" }
                ],
                "next_cursor": "cursor_loop",
                "has_more": true
            }
        """.trimIndent()
        val json2 = """
            {
                "object": "list",
                "results": [
                    { "object": "block", "id": "22222222-2222-2222-2222-222222222222", "type": "paragraph" }
                ],
                "next_cursor": "cursor_loop",
                "has_more": true
            }
        """.trimIndent()

        var callCount = 0
        val engine = MockEngine {
            val body = if (callCount++ == 0) json1 else json2
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = KtorNotionApi("token", HttpClient(engine) { configureForNotion() }, "https://api.notion.test/v1")

        try {
            api.retrieveBlockChildren("33333333-3333-3333-3333-333333333333")
            fail("Expected malformed response for repeating cursor")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
            assertTrue(e.diagnostic.contains("Pagination cursor did not advance"))
            assertTrue(!e.diagnostic.contains("cursor_loop"))
        }
    }

    @Test
    fun cursorLoopFailsOnDuplicateItemIds() = runTest {
        val json = """
            {
                "object": "list",
                "results": [
                    { "object": "block", "id": "11111111-1111-1111-1111-111111111111", "type": "paragraph" },
                    { "object": "block", "id": "11111111-1111-1111-1111-111111111111", "type": "paragraph" }
                ],
                "next_cursor": null,
                "has_more": false
            }
        """.trimIndent()

        val engine = MockEngine { respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val api = KtorNotionApi("token", HttpClient(engine) { configureForNotion() }, "https://api.notion.test/v1")

        try {
            api.retrieveBlockChildren("22222222-2222-2222-2222-222222222222")
            fail("Expected malformed response on duplicate IDs")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
            assertTrue(e.diagnostic.contains("Duplicate identity"))
            assertTrue(!e.diagnostic.contains("11111111-1111-1111-1111-111111111111"))
        }
    }

    @Test
    fun cursorLoopFailsOnMalformedBlankId() = runTest {
        val json = """
            {
                "object": "list",
                "results": [
                    { "object": "block", "id": "   ", "type": "paragraph" }
                ],
                "next_cursor": null,
                "has_more": false
            }
        """.trimIndent()

        val engine = MockEngine { respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val api = KtorNotionApi("token", HttpClient(engine) { configureForNotion() }, "https://api.notion.test/v1")

        try {
            api.retrieveBlockChildren("22222222-2222-2222-2222-222222222222")
            fail("Expected malformed response on blank ID")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
            assertTrue(e.diagnostic.contains("Malformed identity"))
        }
    }

    @Test
    fun queryDataSourcePaginatesMembersOverMockEngine() = runTest {
        val page1 = """
            {
                "object": "list",
                "results": [
                    { "object": "page", "id": "11111111-1111-1111-1111-111111111111" }
                ],
                "next_cursor": "c2",
                "has_more": true
            }
        """.trimIndent()
        val page2 = """
            {
                "object": "list",
                "results": [
                    { "object": "page", "id": "22222222-2222-2222-2222-222222222222" }
                ],
                "next_cursor": null,
                "has_more": false
            }
        """.trimIndent()

        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val body = if (requests.size == 1) page1 else page2
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = KtorNotionApi("token", HttpClient(engine) { configureForNotion() }, "https://api.notion.test/v1")

        val members = api.queryDataSourceMembers("ds-123")
        assertEquals(2, members.size)
        assertEquals("11111111-1111-1111-1111-111111111111", members[0].id)
        assertEquals("22222222-2222-2222-2222-222222222222", members[1].id)
        assertEquals(2, requests.size)
        assertEquals("/v1/data_sources/ds-123/query", requests[0].url.encodedPath)
    }

    @Test
    fun retrieveAllPropertyItemsPaginatesOverMockEngine() = runTest {
        val p1 = """
            {
                "object": "list",
                "results": [
                    { "object": "property_item", "id": "item1", "type": "relation", "relation": { "id": "11111111-1111-1111-1111-111111111111" } }
                ],
                "next_cursor": "c_prop",
                "has_more": true
            }
        """.trimIndent()
        val p2 = """
            {
                "object": "list",
                "results": [
                    { "object": "property_item", "id": "item2", "type": "relation", "relation": { "id": "22222222-2222-2222-2222-222222222222" } }
                ],
                "next_cursor": null,
                "has_more": false
            }
        """.trimIndent()

        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val body = if (requests.size == 1) p1 else p2
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = KtorNotionApi("token", HttpClient(engine) { configureForNotion() }, "https://api.notion.test/v1")

        val items = api.retrieveAllPropertyItems("page-abc", "prop-xyz")
        assertEquals(2, items.size)
        assertEquals("11111111-1111-1111-1111-111111111111", items[0].relation?.id)
        assertEquals("22222222-2222-2222-2222-222222222222", items[1].relation?.id)
        assertEquals(2, requests.size)
    }
}
