package com.example.notionreader.data.notion

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class NotionRepositorySnapshotTest {

    private class FakeApi(
        private val page: NotionPage,
        private val rootBlocks: List<NotionBlock>,
        private val childBlocks: Map<String, List<NotionBlock>> = emptyMap(),
        private val searchPages: List<NotionPage> = emptyList()
    ) : NotionApi {
        override suspend fun retrievePage(pageId: String) = page
        override suspend fun retrieveBlockChildren(blockId: String) =
            if (blockId == page.id) rootBlocks else childBlocks[blockId].orEmpty()
        override suspend fun searchMostRecentPage() = searchPages.firstOrNull()
        override suspend fun searchPages() = searchPages
        override fun close() = Unit
    }

    private val validPageId = "11111111-1111-1111-1111-111111111111"

    private fun pageJson(id: String, title: String) = """
        {
          "object": "page",
          "id": "$id",
          "properties": {
            "title": {
              "id": "title",
              "type": "title",
              "title": [
                { "type": "text", "plain_text": "$title", "text": { "content": "$title" } }
              ]
            }
          }
        }
    """.trimIndent()

    private fun blockJson(id: String, text: String) = """
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

    @Test
    fun documentSnapshotCallbackReceivesPageRootAndChildBlocks() = runTest {
        val page = notionJson.decodeFromString<NotionPage>(pageJson(validPageId, "Snapshot Test"))
        val block1 = notionJson.decodeFromString<NotionBlock>(blockJson("block-1", "Paragraph content"))
        val api = FakeApi(page = page, rootBlocks = listOf(block1))

        var capturedPage: NotionPage? = null
        var capturedRoot: List<NotionBlock>? = null
        var capturedChildren: Map<String, List<NotionBlock>>? = null

        val repository = NotionRepository(
            api = api,
            onDocumentSnapshot = { p, root, children ->
                capturedPage = p
                capturedRoot = root
                capturedChildren = children
            }
        )

        val doc = repository.getDocument(validPageId)
        assertEquals("Snapshot Test", doc.title)
        assertNotNull(capturedPage)
        assertEquals(validPageId, capturedPage!!.id)
        assertEquals(1, capturedRoot?.size)
        assertEquals("block-1", capturedRoot?.first()?.id)
        assertNotNull(capturedChildren)
    }

    @Test
    fun documentSnapshotCallbackExceptionDoesNotFailDocumentLoad() = runTest {
        val page = notionJson.decodeFromString<NotionPage>(pageJson(validPageId, "Robust Test"))
        val block1 = notionJson.decodeFromString<NotionBlock>(blockJson("block-1", "Content"))
        val api = FakeApi(page = page, rootBlocks = listOf(block1))

        val repository = NotionRepository(
            api = api,
            onDocumentSnapshot = { _, _, _ ->
                throw IllegalStateException("Snapshot listener exploded")
            }
        )

        val doc = repository.getDocument(validPageId)
        assertEquals("Robust Test", doc.title)
    }

    @Test
    fun browseDiscoveredPagesIncludesPagesWithBlankTitles() = runTest {
        val p1 = notionJson.decodeFromString<NotionPage>(pageJson("11111111-1111-1111-1111-111111111111", "Titled Page"))
        val p2 = notionJson.decodeFromString<NotionPage>(pageJson("22222222-2222-2222-2222-222222222222", "   "))
        val p3 = notionJson.decodeFromString<NotionPage>(pageJson("33333333-3333-3333-3333-333333333333", ""))
        val api = FakeApi(page = p1, rootBlocks = emptyList(), searchPages = listOf(p1, p2, p3))

        val discovered = mutableListOf<String>()
        val repository = NotionRepository(
            api = api,
            onDiscoveredPages = { ids -> discovered.addAll(ids) }
        )

        val entries = repository.getBrowseIndex()
        // Browse index UI entries drops blank/whitespace titles
        assertEquals(1, entries.size)
        assertEquals("Titled Page", entries.first().title)

        // But structural discovery receives ALL discovered IDs including blank titles
        assertEquals(3, discovered.size)
        assertTrue(discovered.contains("11111111-1111-1111-1111-111111111111"))
        assertTrue(discovered.contains("22222222-2222-2222-2222-222222222222"))
        assertTrue(discovered.contains("33333333-3333-3333-3333-333333333333"))
    }
}
