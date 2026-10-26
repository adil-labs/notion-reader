package com.example.notionreader.data.structure

import com.example.notionreader.data.notion.NotionApi
import com.example.notionreader.data.notion.NotionBlock
import com.example.notionreader.data.notion.NotionDataSource
import com.example.notionreader.data.notion.NotionDatabase
import com.example.notionreader.data.notion.NotionMention
import com.example.notionreader.data.notion.NotionPage
import com.example.notionreader.data.notion.NotionParent
import com.example.notionreader.data.notion.NotionProperty
import com.example.notionreader.data.notion.NotionPropertyItem
import com.example.notionreader.data.notion.NotionReference
import com.example.notionreader.data.notion.NotionRelationTarget
import com.example.notionreader.data.notion.NotionRichText
import com.example.notionreader.data.notion.NotionTextBlock
import com.example.notionreader.domain.ReaderErrorCategory
import com.example.notionreader.domain.ReaderLoadException
import com.example.notionreader.domain.structure.ExplicitRelationshipKind
import com.example.notionreader.domain.structure.PageAccessibilityState
import com.example.notionreader.domain.structure.RelationshipTarget
import com.example.notionreader.domain.structure.StructuralParent
import com.example.notionreader.domain.structure.SyncCompleteness
import com.example.notionreader.domain.structure.TargetResolutionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotionStructuralCollectorTest {

    @Test fun aFailedChildRequestIsIncompleteAtBothBlockAndPageLevel() = runTest {
        val api = FakeCollectorApi()
        val block = "22222222-2222-2222-2222-222222222222"
        api.onRetrieveBlockChildren = { throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE) }
        val record = NotionStructuralCollector(api).collectPageStructure(pageId, cachedPage = NotionPage(pageId),
            cachedBlocks = listOf(NotionBlock(block, "toggle", hasChildren = true))).pageRecord
        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, record.page.syncStatus)
        assertEquals(com.example.notionreader.domain.structure.ChildCompleteness.PAGINATION_INCOMPLETE,
            record.blocks.single().childCompleteness)
    }

    @Test
    fun anEmptyHydrationDoesNotClaimATruncatedPropertyIsComplete() = runTest {
        val api = FakeCollectorApi()
        val page = NotionPage(id = pageId, properties = mapOf("Links" to NotionProperty(
            id = "links", type = "relation", hasMore = true,
            relation = listOf(NotionRelationTarget("22222222-2222-2222-2222-222222222222")))))
        val result = NotionStructuralCollector(api, maxTargetResolutionRequests = 0)
            .collectPageStructure(pageId, cachedPage = page)
        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, result.pageRecord.page.syncStatus)
        assertTrue(result.pageRecord.outgoingRelationships.any { it.kind == ExplicitRelationshipKind.NOTION_RELATION })
    }

    @Test
    fun aTransientContainerFailureDoesNotBecomeAPermissionRemoval() = runTest {
        val api = FakeCollectorApi()
        api.onRetrieveDatabase = { throw ReaderLoadException(ReaderErrorCategory.NETWORK_UNAVAILABLE) }
        try {
            NotionStructuralCollector(api).collectPageStructure(pageId, cachedPage = NotionPage(id = pageId,
                parent = NotionParent(type = "database_id", databaseId = "22222222-2222-2222-2222-222222222222")))
            org.junit.Assert.fail("A transient container failure must remain retryable")
        } catch (expected: ReaderLoadException) {
            assertEquals(ReaderErrorCategory.NETWORK_UNAVAILABLE, expected.category)
        }
    }

    private val pageId = "11111111-1111-1111-1111-111111111111"

    private class FakeCollectorApi : NotionApi {
        val requestedPages = mutableListOf<String>()
        val requestedBlockChildren = mutableListOf<String>()
        val requestedPropertyItems = mutableListOf<Pair<String, String>>()
        val requestedDataSources = mutableListOf<String>()
        val requestedDatabases = mutableListOf<String>()
        var dataSources = mutableMapOf<String, NotionDataSource>()
        var databases = mutableMapOf<String, NotionDatabase>()
        var pages = mutableMapOf<String, NotionPage>()
        var propertyItems = mutableMapOf<Pair<String, String>, List<NotionPropertyItem>>()
        var blockChildren = mutableMapOf<String, List<NotionBlock>>()

        var onRetrievePage: ((String) -> NotionPage)? = null
        var onRetrieveBlockChildren: ((String) -> List<NotionBlock>)? = null
        var onRetrieveAllPropertyItems: ((String, String) -> List<NotionPropertyItem>)? = null
        var onRetrieveDatabase: ((String) -> NotionDatabase?)? = null
        var onRetrieveDataSource: ((String) -> NotionDataSource?)? = null

        override suspend fun retrievePage(pageId: String): NotionPage {
            requestedPages += pageId
            onRetrievePage?.let { return it.invoke(pageId) }
            return pages[pageId] ?: throw ReaderLoadException(ReaderErrorCategory.PAGE_NOT_FOUND, "Not found: $pageId")
        }

        override suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock> {
            requestedBlockChildren += blockId
            onRetrieveBlockChildren?.let { return it.invoke(blockId) }
            return blockChildren[blockId].orEmpty()
        }

        override suspend fun searchMostRecentPage(): NotionPage? = null
        override suspend fun searchPages(): List<NotionPage> = emptyList()

        override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? {
            requestedDatabases += databaseId
            onRetrieveDatabase?.let { return it.invoke(databaseId) }
            return databases[databaseId]
        }

        override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? {
            requestedDataSources += dataSourceId
            onRetrieveDataSource?.let { return it.invoke(dataSourceId) }
            return dataSources[dataSourceId]
        }

        override suspend fun retrieveAllPropertyItems(pageId: String, propertyId: String): List<NotionPropertyItem> {
            requestedPropertyItems += (pageId to propertyId)
            onRetrieveAllPropertyItems?.let { return it.invoke(pageId, propertyId) }
            return propertyItems[pageId to propertyId].orEmpty()
        }

        override fun close() = Unit
    }

    @Test
    fun reusesCachedPageAndBlocksWithoutRedundantHttpRequests() = runTest {
        val fakeApi = FakeCollectorApi()
        val collector = NotionStructuralCollector(api = fakeApi)

        val cachedPage = NotionPage(
            id = pageId,
            properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Cached Page"))))
        )
        val cachedBlocks = listOf(
            NotionBlock(
                id = "22222222-2222-2222-2222-222222222222",
                type = "paragraph",
                paragraph = NotionTextBlock(richText = listOf(NotionRichText(plainText = "Body text")))
            )
        )

        val data = collector.collectPageStructure(
            pageId = pageId,
            sourceIdentity = "src-1",
            cachedPage = cachedPage,
            cachedBlocks = cachedBlocks
        )

        assertEquals("src-1", data.pageRecord.sourceIdentity)
        assertEquals("Cached Page", data.pageRecord.page.title)
        assertEquals(1, data.pageRecord.blocks.size)
        assertTrue("Zero HTTP requests made for page metadata", fakeApi.requestedPages.isEmpty())
        assertTrue("Zero HTTP requests made for root blocks", fakeApi.requestedBlockChildren.isEmpty())
    }

    @Test
    fun hydratesPaginatedPropertyItemsWhenHasMoreIsTrue() = runTest {
        val fakeApi = FakeCollectorApi()
        val targetPage1 = "22222222-2222-2222-2222-222222222222"
        val targetPage2 = "33333333-3333-3333-3333-333333333333"

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "RelationProp" to NotionProperty(
                    id = "prop-rel",
                    type = "relation",
                    relation = listOf(NotionRelationTarget(id = targetPage1)),
                    hasMore = true
                )
            )
        )
        fakeApi.pages[pageId] = page
        fakeApi.propertyItems[pageId to "prop-rel"] = listOf(
            NotionPropertyItem(id = "prop-rel", type = "relation", relation = NotionRelationTarget(id = targetPage1)),
            NotionPropertyItem(id = "prop-rel", type = "relation", relation = NotionRelationTarget(id = targetPage2))
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 0)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        val relations = data.pageRecord.outgoingRelationships.filter { it.kind == ExplicitRelationshipKind.NOTION_RELATION }
        assertEquals(2, relations.size)
        val targetIds = relations.map { (it.target as RelationshipTarget.InternalPage).pageId }
        assertTrue(targetIds.contains(targetPage1))
        assertTrue(targetIds.contains(targetPage2))
        assertEquals(listOf(pageId to "prop-rel"), fakeApi.requestedPropertyItems)
    }

    @Test
    fun boundedTargetResolutionRespectsRequestBudget() = runTest {
        val fakeApi = FakeCollectorApi()
        val p1 = "22222222-2222-2222-2222-222222222222"
        val p2 = "33333333-3333-3333-3333-333333333333"
        val p3 = "44444444-4444-4444-4444-444444444444"

        fakeApi.pages[p1] = NotionPage(id = p1, properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Target One")))))
        fakeApi.pages[p2] = NotionPage(id = p2, properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Target Two")))))
        fakeApi.pages[p3] = NotionPage(id = p3, properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Target Three")))))

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "Rel" to NotionProperty(
                    id = "prop-rel",
                    type = "relation",
                    relation = listOf(
                        NotionRelationTarget(id = p1),
                        NotionRelationTarget(id = p2),
                        NotionRelationTarget(id = p3)
                    )
                )
            )
        )

        // Set max budget to 2 requests
        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 2)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        val edges = data.pageRecord.outgoingRelationships.filter { it.kind == ExplicitRelationshipKind.NOTION_RELATION }
        assertEquals(3, edges.size)

        // Exactly 2 resolved, 1 remaining unresolved permitted
        val resolvedCount = edges.count { it.resolutionStatus == TargetResolutionStatus.RESOLVED }
        val unresolvedCount = edges.count { it.resolutionStatus == TargetResolutionStatus.UNRESOLVED_PERMITTED }

        assertEquals(2, resolvedCount)
        assertEquals(1, unresolvedCount)
        assertEquals(2, fakeApi.requestedPages.size)
        assertEquals("Target One", data.resolvedTargetTitles[p1])
        assertEquals("Target Two", data.resolvedTargetTitles[p2])
    }

    @Test
    fun ambiguous404MarksTargetUnresolvedNotFoundNeverConfirmedTrash() = runTest {
        val fakeApi = FakeCollectorApi()
        val missingTargetId = "22222222-2222-2222-2222-222222222222"

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "Rel" to NotionProperty(
                    id = "prop-rel",
                    type = "relation",
                    relation = listOf(NotionRelationTarget(id = missingTargetId))
                )
            )
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 10)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        val edge = data.pageRecord.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.NOTION_RELATION }
        assertEquals(TargetResolutionStatus.UNRESOLVED_NOT_FOUND, edge.resolutionStatus)
    }

    @Test
    fun collectsContainerMetadataFromDataSourceParent() = runTest {
        val fakeApi = FakeCollectorApi()
        val dsId = "22222222-2222-2222-2222-222222222222"
        val dbId = "33333333-3333-3333-3333-333333333333"

        fakeApi.dataSources[dsId] = NotionDataSource(
            id = dsId,
            title = listOf(NotionRichText(plainText = "Parent DataSource")),
            parent = NotionParent(type = "database_id", databaseId = dbId),
            inTrash = false
        )

        val page = NotionPage(
            id = pageId,
            parent = NotionParent(type = "data_source_id", dataSourceId = dsId)
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 0)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        assertNotNull(data.containerMetadata)
        assertEquals(dsId, data.containerMetadata!!.id)
        assertEquals(ContainerKind.DATA_SOURCE, data.containerMetadata!!.kind)
        assertEquals("Parent DataSource", data.containerMetadata!!.title)
        assertEquals(dbId, data.containerMetadata!!.databaseId)
    }

    @Test
    fun marksPageRecordPartialWhenPropertyHydrationFails() = runTest {
        val fakeApi = object : NotionApi {
            override suspend fun retrievePage(pageId: String): NotionPage = NotionPage(id = pageId)
            override suspend fun retrieveBlockChildren(blockId: String): List<NotionBlock> = emptyList()
            override suspend fun searchMostRecentPage(): NotionPage? = null
            override suspend fun searchPages(): List<NotionPage> = emptyList()
            override suspend fun retrieveAllPropertyItems(pageId: String, propertyId: String): List<NotionPropertyItem> {
                throw ReaderLoadException(ReaderErrorCategory.SERVER_ERROR, "Failed to paginate property")
            }
            override fun close() = Unit
        }

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "Relation" to NotionProperty(id = "p-rel", type = "relation", hasMore = true)
            )
        )

        val collector = NotionStructuralCollector(api = fakeApi)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, data.pageRecord.page.syncStatus)
    }

    // --- Regression tests for Defect 1: Cancellation and Authorization ---

    @Test
    fun cancellationInPropertyHydrationPropagatesImmediately() = runTest {
        val fakeApi = FakeCollectorApi()
        fakeApi.onRetrieveAllPropertyItems = { _, _ -> throw CancellationException("Hydration cancelled") }
        val page = NotionPage(
            id = pageId,
            properties = mapOf("rel" to NotionProperty(id = "p-rel", type = "relation", hasMore = true))
        )
        val collector = NotionStructuralCollector(api = fakeApi)
        var thrown: Throwable? = null
        try {
            collector.collectPageStructure(pageId = pageId, cachedPage = page)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("CancellationException must propagate from property hydration", thrown is CancellationException)
        assertEquals("Hydration cancelled", thrown?.message)
    }

    @Test
    fun cancellationInChildBlockFetchPropagatesImmediately() = runTest {
        val fakeApi = FakeCollectorApi()
        fakeApi.onRetrieveBlockChildren = { throw CancellationException("Child fetch cancelled") }
        val page = NotionPage(id = pageId)
        val collector = NotionStructuralCollector(api = fakeApi)
        var thrown: Throwable? = null
        try {
            collector.collectPageStructure(pageId = pageId, cachedPage = page)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("CancellationException must propagate from block children", thrown is CancellationException)
        assertEquals("Child fetch cancelled", thrown?.message)
    }

    @Test
    fun cancellationInTargetLookupPropagatesImmediately() = runTest {
        val targetId = "22222222-2222-2222-2222-222222222222"
        val fakeApi = FakeCollectorApi()
        fakeApi.onRetrievePage = { id ->
            if (id == targetId) throw CancellationException("Target lookup cancelled")
            NotionPage(id = id)
        }
        val page = NotionPage(
            id = pageId,
            properties = mapOf("rel" to NotionProperty(type = "relation", relation = listOf(NotionRelationTarget(id = targetId))))
        )
        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 10)
        var thrown: Throwable? = null
        try {
            collector.collectPageStructure(pageId = pageId, cachedPage = page)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("CancellationException must propagate from target lookup", thrown is CancellationException)
        assertEquals("Target lookup cancelled", thrown?.message)
    }

    @Test
    fun authorizationRequiredOnInitialPagePropagatesImmediately() = runTest {
        val fakeApi = FakeCollectorApi()
        fakeApi.onRetrievePage = { throw ReaderLoadException(ReaderErrorCategory.AUTHORIZATION_REQUIRED, "Token expired") }
        val collector = NotionStructuralCollector(api = fakeApi)
        var thrown: Throwable? = null
        try {
            collector.collectPageStructure(pageId = pageId)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("ReaderLoadException must propagate", thrown is ReaderLoadException)
        assertEquals(ReaderErrorCategory.AUTHORIZATION_REQUIRED, (thrown as ReaderLoadException).category)
    }

    // --- Regression tests for Defect 2: Hydration >25 ordered and property type preservation ---

    @Test
    fun hydratesTitleAndRichTextTruncatedAt25WithCompleteOrderedValues() = runTest {
        val fakeApi = FakeCollectorApi()
        val initialTitle25 = (1..25).map { NotionRichText(plainText = "Part $it ") }
        val completeTitle30 = (1..30).map { NotionRichText(plainText = "Part $it ") }
        val completeRichText30 = (1..30).map { NotionRichText(plainText = "Body $it ", href = "https://example.test/$it") }
        val initialRichText25 = completeRichText30.take(25)

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "title" to NotionProperty(id = "prop-title", type = "title", title = initialTitle25, hasMore = false),
                "body" to NotionProperty(id = "prop-body", type = "rich_text", richText = initialRichText25, hasMore = false)
            )
        )
        fakeApi.propertyItems[pageId to "prop-title"] = completeTitle30.map {
            NotionPropertyItem(id = "item-title", type = "title", title = it)
        }
        fakeApi.propertyItems[pageId to "prop-body"] = completeRichText30.map {
            NotionPropertyItem(id = "item-body", type = "rich_text", richText = it)
        }

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 0)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        val expectedTitleString = completeTitle30.joinToString("") { it.plainText }.trim()
        assertEquals(expectedTitleString, data.pageRecord.page.title)
        assertTrue("Property items requested for title", fakeApi.requestedPropertyItems.contains(pageId to "prop-title"))
        assertTrue("Property items requested for rich_text", fakeApi.requestedPropertyItems.contains(pageId to "prop-body"))
        assertEquals((1..30).map { "https://example.test/$it" }, data.pageRecord.outgoingRelationships.mapNotNull {
            (it.target as? com.example.notionreader.domain.structure.RelationshipTarget.ExternalUrl)?.url
        })
    }

    @Test
    fun propertyHydrationFailurePreservesPartialValuesAndMarksIncomplete() = runTest {
        val fakeApi = FakeCollectorApi()
        val initialRelations = listOf(NotionRelationTarget(id = "22222222-2222-2222-2222-222222222222"))
        fakeApi.onRetrieveAllPropertyItems = { _, _ ->
            throw ReaderLoadException(ReaderErrorCategory.SERVER_ERROR, "Temporary timeout")
        }

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "rel" to NotionProperty(id = "p-rel", type = "relation", relation = initialRelations, hasMore = true)
            )
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 0)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        assertEquals(SyncCompleteness.PARTIAL_TRAVERSAL_INCOMPLETE, data.pageRecord.page.syncStatus)
        val relEdges = data.pageRecord.outgoingRelationships.filter { it.kind == ExplicitRelationshipKind.NOTION_RELATION }
        assertEquals(1, relEdges.size)
        assertEquals("22222222-2222-2222-2222-222222222222", (relEdges[0].target as RelationshipTarget.InternalPage).pageId)
    }

    // --- Regression tests for Defect 3: Child traversal permission/access failure propagation ---

    @Test
    fun permissionDeniedOnDescendantBlockTraversalPropagates() = runTest {
        val blockWithChildren = "22222222-2222-2222-2222-222222222222"
        val fakeApi = FakeCollectorApi()
        fakeApi.onRetrieveBlockChildren = { blockId ->
            if (blockId == blockWithChildren) {
                throw ReaderLoadException(ReaderErrorCategory.PERMISSION_DENIED, "Access denied to child block")
            }
            emptyList()
        }
        val page = NotionPage(id = pageId)
        val rootBlocks = listOf(
            NotionBlock(id = blockWithChildren, type = "paragraph", hasChildren = true)
        )
        val collector = NotionStructuralCollector(api = fakeApi)
        var thrown: Throwable? = null
        try {
            collector.collectPageStructure(pageId = pageId, cachedPage = page, cachedBlocks = rootBlocks)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("ReaderLoadException PERMISSION_DENIED must propagate", thrown is ReaderLoadException)
        assertEquals(ReaderErrorCategory.PERMISSION_DENIED, (thrown as ReaderLoadException).category)
    }

    // --- Regression tests for Defect 4: Trashed source and target ---

    @Test
    fun trashedSourcePageReturnsMinimalConfirmedTrashedRecordBeforeFetchingBlocks() = runTest {
        val fakeApi = FakeCollectorApi()
        val page = NotionPage(
            id = pageId,
            inTrash = true,
            properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Secret Trashed Page"))))
        )

        val collector = NotionStructuralCollector(api = fakeApi)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        assertEquals(PageAccessibilityState.CONFIRMED_TRASHED, data.pageRecord.page.accessibilityState)
        assertEquals("", data.pageRecord.page.title) // No title on inaccessible records
        assertTrue(data.pageRecord.blocks.isEmpty())
        assertTrue(data.pageRecord.outgoingRelationships.isEmpty())
        assertTrue("Did not fetch root blocks", fakeApi.requestedBlockChildren.isEmpty())
    }

    @Test
    fun resolvedTargetInTrashBecomesUnresolvedInaccessibleWithoutTitle() = runTest {
        val targetId = "22222222-2222-2222-2222-222222222222"
        val fakeApi = FakeCollectorApi()
        fakeApi.pages[targetId] = NotionPage(
            id = targetId,
            inTrash = true,
            properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Trashed Target"))))
        )

        val page = NotionPage(
            id = pageId,
            properties = mapOf("rel" to NotionProperty(type = "relation", relation = listOf(NotionRelationTarget(id = targetId))))
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 5)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        val edge = data.pageRecord.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.NOTION_RELATION }
        assertEquals(TargetResolutionStatus.UNRESOLVED_INACCESSIBLE, edge.resolutionStatus)
        assertTrue("Trashed target title must not be in resolvedTargetTitles", !data.resolvedTargetTitles.containsKey(targetId))
        assertEquals(1, data.targetPages.size)
        assertEquals(PageAccessibilityState.CONFIRMED_TRASHED, data.targetPages[0].accessibilityState)
        assertEquals("", data.targetPages[0].title) // No title on inaccessible records
        assertEquals(SyncCompleteness.UNKNOWN, data.targetPages[0].syncStatus)
    }

    // --- Regression tests for Defect 5: Duplicate target request reuse and InternalDataSource ---

    @Test
    fun duplicateTargetEdgesReuseResolutionResultAndDoNotDepleteBudget() = runTest {
        val targetId = "22222222-2222-2222-2222-222222222222"
        val fakeApi = FakeCollectorApi()
        fakeApi.pages[targetId] = NotionPage(
            id = targetId,
            properties = mapOf("title" to NotionProperty(type = "title", title = listOf(NotionRichText(plainText = "Target Page"))))
        )

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "rel1" to NotionProperty(id = "p-rel1", type = "relation", relation = listOf(NotionRelationTarget(id = targetId))),
                "rel2" to NotionProperty(id = "p-rel2", type = "relation", relation = listOf(NotionRelationTarget(id = targetId)))
            )
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 1)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        val edges = data.pageRecord.outgoingRelationships.filter { it.kind == ExplicitRelationshipKind.NOTION_RELATION }
        assertEquals(2, edges.size)
        assertTrue("Both edges must be RESOLVED", edges.all { it.resolutionStatus == TargetResolutionStatus.RESOLVED })
        assertEquals(1, fakeApi.requestedPages.size)
        assertEquals("Target Page", data.resolvedTargetTitles[targetId])
        assertEquals(1, data.targetPages.size)
        assertEquals("Target Page", data.targetPages[0].title)
    }

    @Test
    fun resolvesInternalDataSourceTarget() = runTest {
        val dsId = "22222222-2222-2222-2222-222222222222"
        val fakeApi = FakeCollectorApi()
        fakeApi.dataSources[dsId] = NotionDataSource(
            id = dsId,
            title = listOf(NotionRichText(plainText = "Target Data Source")),
            inTrash = false
        )

        val page = NotionPage(
            id = pageId,
            properties = mapOf(
                "mention" to NotionProperty(
                    type = "rich_text",
                    richText = listOf(
                        NotionRichText(
                            plainText = "DataSource Mention",
                            mention = NotionMention(
                                type = "data_source",
                                dataSource = NotionReference(id = dsId)
                            )
                        )
                    )
                )
            )
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 5)
        val data = collector.collectPageStructure(pageId = pageId, cachedPage = page)

        val dsEdge = data.pageRecord.outgoingRelationships.single { it.kind == ExplicitRelationshipKind.DATA_SOURCE_MEMBERSHIP || it.target is RelationshipTarget.InternalDataSource }
        assertEquals(TargetResolutionStatus.RESOLVED, dsEdge.resolutionStatus)
        assertEquals("Target Data Source", data.resolvedTargetTitles[dsId])
    }

    // --- Regression tests for Defect 6: Data-source and Database container metadata ---

    @Test
    fun collectsBothDataSourceAndDatabaseMetadataWithFullFields() = runTest {
        val dsId = "22222222-2222-2222-2222-222222222222"
        val dbId = "33333333-3333-3333-3333-333333333333"
        val fakeApi = FakeCollectorApi()

        fakeApi.dataSources[dsId] = NotionDataSource(
            id = dsId,
            title = listOf(NotionRichText(plainText = "Owning Data Source")),
            parent = NotionParent(type = "database_id", databaseId = dbId),
            inTrash = false,
            createdTime = "2026-01-01T00:00:00.000Z",
            lastEditedTime = "2026-01-02T00:00:00.000Z"
        )
        fakeApi.databases[dbId] = NotionDatabase(
            id = dbId,
            title = listOf(NotionRichText(plainText = "Containing Database")),
            parent = NotionParent(type = "workspace", workspace = true),
            inTrash = false,
            createdTime = "2025-12-01T00:00:00.000Z",
            lastEditedTime = "2025-12-02T00:00:00.000Z"
        )

        val page = NotionPage(
            id = pageId,
            parent = NotionParent(type = "data_source_id", dataSourceId = dsId)
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 0)
        val data = collector.collectPageStructure(pageId = pageId, sourceIdentity = "test-src", cachedPage = page)

        assertEquals(2, data.containers.size)
        val dsContainer = data.containers[0]
        val dbContainer = data.containers[1]

        assertEquals(dsId, dsContainer.id)
        assertEquals(ContainerKind.DATA_SOURCE, dsContainer.kind)
        assertEquals("Owning Data Source", dsContainer.title)
        assertEquals(dbId, dsContainer.databaseId)
        assertEquals("test-src", dsContainer.sourceIdentity)
        assertEquals(PageAccessibilityState.ACCESSIBLE, dsContainer.accessibilityState)
        assertEquals(StructuralParent.Database(dbId), dsContainer.parent)
        assertEquals(Instant.parse("2026-01-01T00:00:00.000Z"), dsContainer.createdAt)

        assertEquals(dbId, dbContainer.id)
        assertEquals(ContainerKind.DATABASE, dbContainer.kind)
        assertEquals("Containing Database", dbContainer.title)
        assertEquals(StructuralParent.Workspace, dbContainer.parent)
        assertEquals(PageAccessibilityState.ACCESSIBLE, dbContainer.accessibilityState)

        assertEquals(data.containerMetadata, dsContainer)
    }

    @Test
    fun containerAccessDenialEmitsMinimalInaccessibleMetadataWithoutTitle() = runTest {
        val dbId = "33333333-3333-3333-3333-333333333333"
        val fakeApi = FakeCollectorApi()
        fakeApi.onRetrieveDatabase = {
            throw ReaderLoadException(ReaderErrorCategory.PERMISSION_DENIED, "Forbidden")
        }

        val page = NotionPage(
            id = pageId,
            parent = NotionParent(type = "database_id", databaseId = dbId)
        )

        val collector = NotionStructuralCollector(api = fakeApi, maxTargetResolutionRequests = 0)
        val data = collector.collectPageStructure(pageId = pageId, sourceIdentity = "src-1", cachedPage = page)

        assertEquals(1, data.containers.size)
        val container = data.containers[0]
        assertEquals(dbId, container.id)
        assertEquals(ContainerKind.DATABASE, container.kind)
        assertEquals("", container.title)
        assertEquals(PageAccessibilityState.RESTRICTED_PERMISSION, container.accessibilityState)
        assertEquals("src-1", container.sourceIdentity)
    }
}

