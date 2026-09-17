package com.example.notionreader.data.notion

import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionRepositoryTest {

    private class FakeNotionApi(
        private val pageJson: String,
        private val blockListJson: String,
        private val nestedBlockListJson: String = EMPTY_LIST_JSON,
        private val searchResultsJson: String = EMPTY_LIST_JSON
    ) : NotionApi {

        val requestedPageIds = mutableListOf<String>()
        val requestedBlockIds = mutableListOf<String>()
        var searchCount = 0

        override suspend fun retrievePage(pageId: String): NotionPage {
            requestedPageIds += pageId
            return notionJson.decodeFromString(pageJson)
        }

        override suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock> {
            requestedBlockIds += blockId
            val json = if (requestedBlockIds.size == 1) blockListJson else nestedBlockListJson
            return notionJson.decodeFromString<NotionBlockList>(json).results
        }

        override suspend fun searchMostRecentPage(): NotionPage? {
            searchCount++
            return notionJson.decodeFromString<NotionPageList>(searchResultsJson).results.firstOrNull()
        }

        /** The same results as [searchMostRecentPage], since the fixture has no cursor in it. */
        override suspend fun searchPages(): List<NotionPage> {
            searchCount++
            return notionJson.decodeFromString<NotionPageList>(searchResultsJson).results
        }

        override fun close() = Unit

        companion object {
            const val EMPTY_LIST_JSON = """{ "object": "list", "results": [], "has_more": false }"""
        }
    }

    private val pageId = "11111111-1111-1111-1111-111111111111"

    private val pageJson = """
        {
          "object": "page",
          "id": "$pageId",
          "url": "https://www.notion.so/Demo-11111111111111111111111111111111",
          "created_time": "2026-09-17T07:00:00.000Z",
          "properties": {
            "title": {
              "id": "title",
              "type": "title",
              "title": [
                { "type": "text", "plain_text": "Architecture", "text": { "content": "Architecture" } }
              ]
            }
          }
        }
    """.trimIndent()

    private fun blockListJson(vararg blockJson: String): String = """
        {
          "object": "list",
          "results": [${blockJson.joinToString(",")}],
          "next_cursor": null,
          "has_more": false
        }
    """.trimIndent()

    private fun searchResultsJson(vararg pageJson: String): String = """
        {
          "object": "list",
          "results": [${pageJson.joinToString(",")}],
          "next_cursor": null,
          "has_more": false
        }
    """.trimIndent()

    private fun block(id: String, type: String, text: String, textKey: String = type): String = """
        {
          "object": "block",
          "id": "$id",
          "type": "$type",
          "has_children": false,
          "$textKey": {
            "rich_text": [ { "type": "text", "plain_text": "$text", "text": { "content": "$text" } } ]
          }
        }
    """.trimIndent()

    private fun parentBlock(id: String, type: String, text: String, textKey: String = type): String = """
        {
          "object": "block",
          "id": "$id",
          "type": "$type",
          "has_children": true,
          "$textKey": {
            "rich_text": [ { "type": "text", "plain_text": "$text", "text": { "content": "$text" } } ]
          }
        }
    """.trimIndent()

    private fun repository(
        vararg blocks: String,
        nested: String = FakeNotionApi.EMPTY_LIST_JSON
    ) = NotionRepository(
        FakeNotionApi(
            pageJson = pageJson,
            blockListJson = blockListJson(*blocks),
            nestedBlockListJson = nested,
            searchResultsJson = searchResultsJson(pageJson)
        )
    )

    @Test
    fun buildsDocumentTitleFromThePageTitleProperty() = runTest {
        val document = repository().getDocument(pageId)

        assertEquals("Architecture", document.title)
        assertEquals(pageId, document.pageId)
    }

    @Test
    fun convertsSupportedBlocksIntoParagraphsInOrder() = runTest {
        val document = repository(
            block("1", "heading_1", "Architecture"),
            block("2", "paragraph", "The reader does not depend on Android TTS."),
            block("3", "heading_2", "Layers"),
            block("4", "heading_3", "Speech"),
            block("5", "bulleted_list_item", "Reader engine"),
            block("6", "numbered_list_item", "Speech engine"),
            block("7", "quote", "Keep the reader independent."),
            block("8", "callout", "TalkBack must remain usable.")
        ).getDocument(pageId)

        assertEquals(
            listOf(
                "Architecture",
                "The reader does not depend on Android TTS.",
                "Layers",
                "Speech",
                "Reader engine",
                "Speech engine",
                "Keep the reader independent.",
                "TalkBack must remain usable."
            ),
            document.chunkTexts
        )
    }

    @Test
    fun skipsUnsupportedAndEmptyBlocksInsteadOfFailing() = runTest {
        val unsupported = """
            { "object": "block", "id": "9", "type": "image", "image": { "type": "external" } }
        """.trimIndent()
        val empty = block("10", "paragraph", "")

        val document = repository(
            block("1", "paragraph", "First."),
            unsupported,
            empty,
            block("11", "paragraph", "Second.")
        ).getDocument(pageId)

        assertEquals(listOf("First.", "Second."), document.chunkTexts)
    }

    @Test
    fun joinsMultipleRichTextRunsIntoSingleParagraph() = runTest {
        val multiRun = """
            {
              "object": "block",
              "id": "1",
              "type": "paragraph",
              "paragraph": {
                "rich_text": [
                  { "type": "text", "plain_text": "Hello " },
                  { "type": "text", "plain_text": "world." }
                ]
              }
            }
        """.trimIndent()

        val document = repository(multiRun).getDocument(pageId)

        assertEquals(listOf("Hello world."), document.chunkTexts)
    }

    /** A genuinely empty page is empty, not broken, so it stays a valid empty document. */
    @Test
    fun toleratesPagesWithNoReadableBlocks() = runTest {
        val document = repository().getDocument(pageId)

        assertEquals(emptyList<String>(), document.chunkTexts)
        assertEquals(0, document.sections.size)
    }

    @Test
    fun acceptsAnUndashedIdAsThePageReference() = runTest {
        val api = FakeNotionApi(pageJson, blockListJson(block("1", "paragraph", "Body")))
        NotionRepository(api).getDocument("11111111111111111111111111111111")

        assertEquals(listOf(pageId), api.requestedPageIds)
    }

    @Test
    fun acceptsAPageUrlAsThePageReference() = runTest {
        val api = FakeNotionApi(pageJson, blockListJson(block("1", "paragraph", "Body")))
        val url = "https://www.notion.so/Workspace/Architecture-11111111-1111-1111-1111-111111111111?pvs=4"

        NotionRepository(api).getDocument(url)

        assertEquals(listOf(pageId), api.requestedPageIds)
    }

    @Test
    fun discoversTheMostRecentPageWhenNoReferenceIsConfigured() = runTest {
        val api = FakeNotionApi(
            pageJson = pageJson,
            blockListJson = blockListJson(block("1", "paragraph", "Body")),
            searchResultsJson = searchResultsJson(pageJson)
        )

        val document = NotionRepository(api).getDocument()

        assertEquals(1, api.searchCount)
        assertEquals(emptyList<String>(), api.requestedPageIds)
        assertEquals(listOf(pageId), api.requestedBlockIds)
        assertEquals("Architecture", document.title)
    }

    @Test
    fun reportsAConfigurationErrorWithoutEchoingTheValue() = runTest {
        val api = FakeNotionApi(pageJson, blockListJson())
        val secretLookingValue = "ntn_thisIsActuallyATokenNotAPageId"

        val error = runCatching { NotionRepository(api).getDocument(secretLookingValue) }.exceptionOrNull()

        assertTrue(error is ReaderLoadException)
        assertEquals(ReaderErrorCategory.PAGE_NOT_FOUND, (error as ReaderLoadException).category)
        assertTrue(error.message!!.contains("neither a page ID nor a page URL"))
        assertTrue(!error.message!!.contains("ntn_"))
        assertTrue("a mistyped token must not reach a log either", !error.diagnostic.contains("ntn_"))
        assertEquals(0, api.searchCount)
    }

    @Test
    fun reportsAPageThatIsInTheTrash() = runTest {
        val trashedPageJson = pageJson.replace("\"url\"", "\"in_trash\": true, \"url\"")
        val api = FakeNotionApi(trashedPageJson, blockListJson())

        val error = runCatching { NotionRepository(api).getDocument(pageId) }.exceptionOrNull()

        assertTrue(error is ReaderLoadException)
        assertEquals(ReaderErrorCategory.PAGE_NOT_FOUND, (error as ReaderLoadException).category)
        assertTrue(error.message!!.contains("trash"))
    }

    @Test
    fun reportsAnIntegrationThatCannotSeeAnyPage() = runTest {
        val api = FakeNotionApi(
            pageJson = pageJson,
            blockListJson = blockListJson(),
            searchResultsJson = searchResultsJson()
        )

        val error = runCatching { NotionRepository(api).getDocument() }.exceptionOrNull()

        assertTrue(error is ReaderLoadException)
        assertEquals(ReaderErrorCategory.PERMISSION_DENIED, (error as ReaderLoadException).category)
    }

    /**
     * A page with content that yields nothing speakable is a reportable failure, not an empty reader:
     * the user asked for a page and would otherwise be shown nothing, with no explanation.
     */
    @Test
    fun reportsAPageWhoseEntireContentIsUnsupported() = runTest {
        val image = """
            { "object": "block", "id": "9", "type": "image", "image": { "type": "external" } }
        """.trimIndent()

        val error = runCatching { repository(image).getDocument(pageId) }.exceptionOrNull()

        assertTrue(error is ReaderLoadException)
        assertEquals(
            ReaderErrorCategory.UNSUPPORTED_DOCUMENT_STRUCTURE,
            (error as ReaderLoadException).category
        )
        assertTrue("the diagnostic should say what was ignored", error.diagnostic.contains("image"))
    }

    @Test
    fun readsNestedChildBlocksInDocumentOrder() = runTest {
        val document = repository(
            block("1", "paragraph", "Before."),
            parentBlock("2", "bulleted_list_item", "Parent item"),
            block("3", "paragraph", "After."),
            nested = blockListJson(
                block("2a", "bulleted_list_item", "Nested item"),
                block("2b", "paragraph", "Nested paragraph")
            )
        ).getDocument(pageId)

        assertEquals(
            listOf("Before.", "Parent item", "Nested item", "Nested paragraph", "After."),
            document.chunkTexts
        )
    }

    @Test
    fun doesNotDescendIntoChildPages() = runTest {
        val childPage = """
            { "object": "block", "id": "9", "type": "child_page", "has_children": true,
              "child_page": { "title": "Sub page" } }
        """.trimIndent()

        val document = repository(
            block("1", "paragraph", "Body"),
            childPage,
            nested = blockListJson(block("9a", "paragraph", "Sub page content"))
        ).getDocument(pageId)

        assertEquals(listOf("Body"), document.chunkTexts)
    }

    @Test
    fun normalizeNotionPageIdHandlesTheShapesUsersPaste() {
        assertEquals(pageId, normalizeNotionPageId(pageId))
        assertEquals(pageId, normalizeNotionPageId("11111111111111111111111111111111"))
        assertEquals(pageId, normalizeNotionPageId("https://www.notion.so/$pageId"))
        assertEquals(
            pageId,
            normalizeNotionPageId("https://www.notion.so/Team/Notes-$pageId#comment-123")
        )
        assertEquals(
            pageId,
            normalizeNotionPageId("  https://www.notion.so/Notes-11111111111111111111111111111111?v=22222222222222222222222222222222  ")
        )
        assertEquals(pageId, normalizeNotionPageId("\"$pageId\""))

        assertNull(normalizeNotionPageId(""))
        assertNull(normalizeNotionPageId("not a page"))
        assertNull(normalizeNotionPageId("ntn_1234567890"))
    }
}
