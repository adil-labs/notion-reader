package com.example.notionreader.data.notion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Serves a captured fixture snapshot offline.
 *
 * The snapshot is keyed by block id exactly as the real API is addressed, so the fetch path under test
 * — page children, then one call per block with children — is reproduced faithfully. Several test
 * classes need this, which is why it lives here rather than inside one of them: sharing it is what
 * makes "the contract test and the identity-stability test read the same real data" true rather than
 * merely intended.
 */
internal class FixtureNotionApi private constructor(
    private val pageElement: JsonElement,
    private val childrenByBlock: Map<String, JsonElement>
) : NotionApi {

    override suspend fun retrievePage(pageId: String): NotionPage =
        notionJson.decodeFromJsonElement(NotionPage.serializer(), pageElement)

    override suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock> {
        val array = childrenByBlock[blockId]?.jsonArray ?: return emptyList()
        return array.map { notionJson.decodeFromJsonElement(NotionBlock.serializer(), it) }
    }

    override suspend fun searchMostRecentPage(): NotionPage? = retrievePage("")

    /**
     * The fixture has one page, so a page listing returns that one.
     *
     * Deliberately not an empty list: Browse's default state is "All Notes", and a fixture that reports
     * seeing nothing would make every Browse test exercise the empty state instead of the result set.
     */
    override suspend fun searchPages(): List<NotionPage> = listOf(retrievePage(""))

    override fun close() = Unit

    companion object {
        fun fromResource(path: String): FixtureNotionApi {
            val text = requireNotNull(FixtureNotionApi::class.java.getResourceAsStream(path)) {
                "fixture snapshot not found on the test classpath: $path"
            }.bufferedReader().use { it.readText() }

            val root = Json.parseToJsonElement(text).jsonObject
            val page = requireNotNull(root["page"]) { "snapshot has no 'page'" }
            val children = requireNotNull(root["children"]) { "snapshot has no 'children'" }.jsonObject

            return FixtureNotionApi(page, children)
        }
    }
}
