package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JUnit 4 contract tests for [NotionStructuralDiscovery] and [StructuralDiscoveryContinuation].
 */
class NotionStructuralDiscoveryTest {

    @Test fun admittedContinuationsMakeProgressWhenTheSourceInventoryExceedsTheirCapacity() = runTest {
        val api = FakeNotionApi()
        api.dataSources = (1..201).map { dataSource("%08x-1111-1111-1111-111111111111".format(it)) }
        api.queryDataSourceHandler = { _, _, cursor ->
            NotionPageList(results = emptyList(), hasMore = cursor == null, nextCursor = if (cursor == null) "second-page" else null)
        }
        val discovery = NotionStructuralDiscovery(api, StructuralDiscoveryContinuation())
        var last: String? = null
        repeat(4) {
            discovery.collect("fixture", {}, {}, sourceEpoch = 1, startAfterSourceId = last,
                maxRequestsPerSource = 1, onSourceAttempt = { last = it })
        }
        assertTrue(api.queryCalls.any { it.startCursor == "second-page" })
        assertTrue(api.queryCalls.any { it.dataSourceId == api.dataSources.last().id && it.startCursor == "second-page" })
    }

    @Test fun disappearedSourcesReleaseOnlyTheirEphemeralContinuationSlots() = runTest {
        val api = FakeNotionApi()
        api.dataSources = (1..200).map { dataSource("%08x-1111-1111-1111-111111111111".format(it)) }
        api.queryDataSourceHandler = { _, _, _ -> NotionPageList(results = emptyList(), hasMore = true, nextCursor = "next") }
        val discovery = NotionStructuralDiscovery(api, StructuralDiscoveryContinuation())
        discovery.collect("fixture", {}, {}, maxRequestsPerSource = 1)
        api.dataSources = listOf(dataSource(source3Id))
        api.queryCalls.clear()
        discovery.collect("fixture", {}, {}, maxRequestsPerSource = 1)
        assertTrue(api.queryCalls.any { it.dataSourceId == source3Id })
    }

    @Test fun deniedMemberQueriesReleaseTheirEphemeralContinuationSlots() = runTest {
        val api = FakeNotionApi()
        api.dataSources = (1..201).map { dataSource("%08x-1111-1111-1111-111111111111".format(it)) }
        api.queryDataSourceHandler = { _, _, _ -> NotionPageList(results = emptyList(), hasMore = true, nextCursor = "next") }
        val discovery = NotionStructuralDiscovery(api, StructuralDiscoveryContinuation())
        var last: String? = null
        discovery.collect("fixture", {}, {}, maxRequestsPerSource = 1, onSourceAttempt = { last = it })
        api.queryDataSourceHandler = { _, _, _ -> throw ReaderLoadException(ReaderErrorCategory.PERMISSION_DENIED) }
        repeat(2) {
            discovery.collect("fixture", {}, {}, maxRequestsPerSource = 1, startAfterSourceId = last, onSourceAttempt = { last = it })
        }
        assertTrue(api.queryCalls.any { it.dataSourceId == api.dataSources.last().id })
    }

    private val source1Id = "11111111-1111-1111-1111-111111111111"
    private val source2Id = "22222222-2222-2222-2222-222222222222"
    private val source3Id = "33333333-3333-3333-3333-333333333333"
    private val database1Id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val page1Id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val page2Id = "cccccccc-cccc-cccc-cccc-cccccccccccc"

    private fun dataSource(
        id: String,
        name: String = "DS $id",
        databaseId: String? = null,
        parent: NotionParent? = null,
        inTrash: Boolean = false,
        createdTime: String? = "2026-10-26T10:00:00Z",
        lastEditedTime: String? = "2026-10-26T12:00:00Z"
    ) = NotionDataSource(
        id = id,
        title = listOf(NotionRichText(plainText = name)),
        parent = parent ?: databaseId?.let { NotionParent(type = "database_id", databaseId = it) },
        inTrash = inTrash,
        createdTime = createdTime,
        lastEditedTime = lastEditedTime
    )

    private fun notionPage(
        id: String,
        title: String = "Page $id",
        parent: NotionParent? = null,
        createdTime: String? = "2026-10-26T10:00:00Z",
        lastEditedTime: String? = "2026-10-26T12:00:00Z",
        inTrash: Boolean = false
    ) = NotionPage(
        id = id,
        parent = parent,
        createdTime = createdTime,
        lastEditedTime = lastEditedTime,
        inTrash = inTrash,
        properties = mapOf(
            "title" to NotionProperty(
                id = "title",
                type = "title",
                title = listOf(NotionRichText(plainText = title))
            )
        )
    )

    private class FakeNotionApi : NotionApi {
        var dataSources: List<NotionDataSource> = emptyList()
        var databases: Map<String, NotionDatabase> = emptyMap()
        val queryCalls = mutableListOf<QueryCall>()
        var queryDataSourceHandler: (suspend (String, Int, String?) -> NotionPageList)? = null

        data class QueryCall(val dataSourceId: String, val pageSize: Int, val startCursor: String?)

        override suspend fun searchDataSources(): List<NotionDataSource> = dataSources

        override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? = databases[databaseId]

        override suspend fun queryDataSource(
            dataSourceId: String,
            pageSize: Int,
            startCursor: String?
        ): NotionPageList {
            queryCalls.add(QueryCall(dataSourceId, pageSize, startCursor))
            queryDataSourceHandler?.let { return it(dataSourceId, pageSize, startCursor) }
            return NotionPageList(results = emptyList(), nextCursor = null, hasMore = false)
        }

        override suspend fun retrievePage(pageId: String): NotionPage = error("Not needed for discovery tests")
        override suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock> = error("Not needed for discovery tests")
        override suspend fun searchMostRecentPage(): NotionPage? = null
        override suspend fun searchPages(): List<NotionPage> = emptyList()
        override fun close() {}
    }

    @Test
    fun twoDataSourcesFirstPaginatedMaxRequestsPerSourceStillVisitsSecond() = runTest {
        val api = FakeNotionApi()
        api.dataSources = listOf(dataSource(source1Id, "Source 1"), dataSource(source2Id, "Source 2"))

        api.queryDataSourceHandler = { id, _, _ ->
            when (id) {
                source1Id -> NotionPageList(
                    results = listOf(notionPage(page1Id, "Page 1")),
                    nextCursor = "cursor-source1-p2",
                    hasMore = true
                )
                source2Id -> NotionPageList(
                    results = listOf(notionPage(page2Id, "Page 2")),
                    nextCursor = null,
                    hasMore = false
                )
                else -> NotionPageList(results = emptyList(), nextCursor = null, hasMore = false)
            }
        }

        val discovery = NotionStructuralDiscovery(api)
        val containers = mutableListOf<StructuralContainer>()
        val pages = mutableListOf<StructuralPage>()
        val attemptedSources = mutableListOf<String>()

        val result = discovery.collect(
            identity = "test-ws",
            onContainer = { containers += it },
            onPage = { pages += it },
            maxRequestsPerSource = 1,
            onSourceAttempt = { attemptedSources += it }
        )

        assertFalse("Slice should be incomplete due to pagination limit on first source", result.complete)
        assertEquals(listOf(source1Id, source2Id), attemptedSources)
        assertEquals(listOf(source1Id, source2Id), containers.map { it.id })
        assertEquals(listOf(page1Id, page2Id), pages.map { it.id })
        assertEquals(2, api.queryCalls.size)
        assertEquals(source1Id, api.queryCalls[0].dataSourceId)
        assertNull(api.queryCalls[0].startCursor)
        assertEquals(source2Id, api.queryCalls[1].dataSourceId)
        assertNull(api.queryCalls[1].startCursor)
    }

    @Test
    fun sameStructuralDiscoveryContinuationAcrossCollectCallsResumesOpaqueCursorInMemory() = runTest {
        val api = FakeNotionApi()
        api.dataSources = listOf(dataSource(source1Id))

        api.queryDataSourceHandler = { _, _, cursor ->
            when (cursor) {
                null -> NotionPageList(
                    results = listOf(notionPage(page1Id, "Page 1")),
                    nextCursor = "opaque-token-abc",
                    hasMore = true
                )
                "opaque-token-abc" -> NotionPageList(
                    results = listOf(notionPage(page2Id, "Page 2")),
                    nextCursor = null,
                    hasMore = false
                )
                else -> NotionPageList(results = emptyList(), nextCursor = null, hasMore = false)
            }
        }

        val continuation = StructuralDiscoveryContinuation()
        val discovery = NotionStructuralDiscovery(api, continuation)
        val pages = mutableListOf<StructuralPage>()

        val result1 = discovery.collect(
            identity = "test-ws",
            sourceEpoch = 1L,
            maxRequestsPerSource = 1,
            onContainer = {},
            onPage = { pages += it }
        )

        assertFalse("First collect slice should be incomplete", result1.complete)
        assertEquals(1, api.queryCalls.size)
        assertNull("First call should start with null cursor", api.queryCalls[0].startCursor)
        assertEquals(listOf(page1Id), pages.map { it.id })

        val result2 = discovery.collect(
            identity = "test-ws",
            sourceEpoch = 1L,
            maxRequestsPerSource = 1,
            onContainer = {},
            onPage = { pages += it }
        )

        assertTrue("Second collect slice should complete the data source", result2.complete)
        assertEquals(2, api.queryCalls.size)
        assertEquals("opaque-token-abc", api.queryCalls[1].startCursor)
        assertEquals(listOf(page1Id, page2Id), pages.map { it.id })
    }

    @Test
    fun newContinuationAndStartAfterSourceIdRotatesSourceNativeIdsWithoutPersistingCursors() = runTest {
        val api = FakeNotionApi()
        api.dataSources = listOf(
            dataSource(source1Id, "Source 1"),
            dataSource(source2Id, "Source 2"),
            dataSource(source3Id, "Source 3")
        )

        val attemptedSources = mutableListOf<String>()
        val continuation = StructuralDiscoveryContinuation()
        val discovery = NotionStructuralDiscovery(api, continuation)

        val result = discovery.collect(
            identity = "test-ws",
            startAfterSourceId = source1Id,
            onContainer = {},
            onPage = {},
            onSourceAttempt = { attemptedSources += it }
        )

        assertTrue(result.complete)
        assertEquals(listOf(source2Id, source3Id, source1Id), attemptedSources)
        assertEquals(3, api.queryCalls.size)
        assertTrue("No persisted cursors should exist; all queries start with null cursor",
            api.queryCalls.all { it.startCursor == null })
    }

    @Test
    fun sourceEpochChangeDiscardsEphemeralCursor() = runTest {
        val api = FakeNotionApi()
        api.dataSources = listOf(dataSource(source1Id))

        api.queryDataSourceHandler = { _, _, cursor ->
            when (cursor) {
                null -> NotionPageList(
                    results = listOf(notionPage(page1Id)),
                    nextCursor = "ephemeral-token-123",
                    hasMore = true
                )
                else -> NotionPageList(
                    results = listOf(notionPage(page2Id)),
                    nextCursor = null,
                    hasMore = false
                )
            }
        }

        val continuation = StructuralDiscoveryContinuation()
        val discovery = NotionStructuralDiscovery(api, continuation)

        discovery.collect(
            identity = "test-ws",
            sourceEpoch = 1L,
            maxRequestsPerSource = 1,
            onContainer = {},
            onPage = {}
        )

        assertEquals(1, api.queryCalls.size)
        assertNull(api.queryCalls[0].startCursor)

        discovery.collect(
            identity = "test-ws",
            sourceEpoch = 2L,
            maxRequestsPerSource = 1,
            onContainer = {},
            onPage = {}
        )

        assertEquals(2, api.queryCalls.size)
        assertNull("Cursor must be discarded when sourceEpoch changes", api.queryCalls[1].startCursor)
    }

    @Test
    fun malformedRepeatingCursorRejected() = runTest {
        val api = FakeNotionApi()
        api.dataSources = listOf(dataSource(source1Id))

        var callCount = 0
        api.queryDataSourceHandler = { _, _, _ ->
            callCount++
            if (callCount == 1) {
                NotionPageList(results = listOf(notionPage(page1Id)), nextCursor = "stuck-repeating-cursor", hasMore = true)
            } else {
                NotionPageList(results = listOf(notionPage(page2Id)), nextCursor = "stuck-repeating-cursor", hasMore = true)
            }
        }

        val discovery = NotionStructuralDiscovery(api)

        try {
            discovery.collect(
                identity = "test-ws",
                maxRequestsPerSource = 5,
                onContainer = {},
                onPage = {}
            )
            fail("Expected ReaderLoadException for repeating cursor")
        } catch (e: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.MALFORMED_RESPONSE, e.category)
        }
    }

    @Test
    fun cancellationPropagates() = runTest {
        val api = FakeNotionApi()
        api.dataSources = listOf(dataSource(source1Id))
        api.queryDataSourceHandler = { _, _, _ -> NotionPageList(results = listOf(notionPage(page1Id)), hasMore = false) }

        val discovery = NotionStructuralDiscovery(api)

        try {
            discovery.collect(
                identity = "test-ws",
                onContainer = {},
                onPage = { throw CancellationException("cancel-on-page") }
            )
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals("cancel-on-page", e.message)
        }

        api.queryDataSourceHandler = { _, _, _ ->
            throw CancellationException("cancel-in-query")
        }

        try {
            discovery.collect(
                identity = "test-ws",
                onContainer = {},
                onPage = {}
            )
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals("cancel-in-query", e.message)
        }
    }

    @Test
    fun dataSourceAndPageMetadataParentTypedAndNoGenerativeInference() = runTest {
        val api = FakeNotionApi()
        val ds1 = dataSource(source1Id, name = "Data Source One", databaseId = database1Id)
        val ds2 = dataSource(source2Id, name = "Data Source Two", parent = NotionParent(type = "workspace", workspace = true))
        api.dataSources = listOf(ds1, ds2)

        api.databases = mapOf(
            database1Id to NotionDatabase(
                id = database1Id,
                title = listOf(NotionRichText(plainText = "Parent Database")),
                parent = NotionParent(type = "page_id", pageId = page1Id),
                createdTime = "2026-10-26T10:00:00Z",
                lastEditedTime = "2026-10-26T12:00:00Z"
            )
        )

        val memberPage = notionPage(
            id = page2Id,
            title = "Strictly Factual Page",
            parent = NotionParent(type = "data_source_id", dataSourceId = source1Id),
            createdTime = "2026-10-26T11:00:00Z",
            lastEditedTime = "2026-10-26T13:00:00Z"
        )

        api.queryDataSourceHandler = { id, _, _ ->
            when (id) {
                source1Id -> NotionPageList(results = listOf(memberPage), nextCursor = null, hasMore = false)
                else -> NotionPageList(results = emptyList(), nextCursor = null, hasMore = false)
            }
        }

        val containers = mutableListOf<StructuralContainer>()
        val pages = mutableListOf<StructuralPage>()

        val discovery = NotionStructuralDiscovery(api)
        val result = discovery.collect(
            identity = "ws-identity-sha",
            onContainer = { containers += it },
            onPage = { pages += it }
        )

        assertTrue(result.complete)

        val containerDs1 = containers.first { it.id == source1Id }
        assertEquals(StructuralContainerKind.DATA_SOURCE, containerDs1.kind)
        assertEquals("Data Source One", containerDs1.title)
        assertEquals(StructuralParent.Database(database1Id), containerDs1.parent)
        assertEquals("ws-identity-sha", containerDs1.sourceIdentity)
        assertEquals(PageAccessibilityState.ACCESSIBLE, containerDs1.accessibilityState)

        val containerDb = containers.first { it.id == database1Id }
        assertEquals(StructuralContainerKind.DATABASE, containerDb.kind)
        assertEquals("Parent Database", containerDb.title)
        assertEquals(StructuralParent.Page(page1Id), containerDb.parent)
        assertEquals("ws-identity-sha", containerDb.sourceIdentity)

        val containerDs2 = containers.first { it.id == source2Id }
        assertEquals(StructuralContainerKind.DATA_SOURCE, containerDs2.kind)
        assertEquals(StructuralParent.Unknown("workspace", null), containerDs2.parent)

        val page = pages.single()
        assertEquals(page2Id, page.id)
        assertEquals("Strictly Factual Page", page.title)
        assertEquals(StructuralParent.DataSource(source1Id), page.parent)
        assertEquals(source1Id, page.dataSourceId)
        assertNull(page.databaseId)
        assertEquals("ws-identity-sha", page.sourceIdentity)
        assertEquals(SyncCompleteness.UNKNOWN, page.syncStatus)
        assertEquals(PageAccessibilityState.ACCESSIBLE, page.accessibilityState)
    }
}
