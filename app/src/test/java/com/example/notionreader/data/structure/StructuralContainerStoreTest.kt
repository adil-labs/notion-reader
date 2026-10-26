package com.example.notionreader.data.structure

import com.example.notionreader.domain.structure.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StructuralContainerStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val page = "11111111-1111-1111-1111-111111111111"
    private val dataSource = "22222222-2222-2222-2222-222222222222"
    private val database = "33333333-3333-3333-3333-333333333333"
    private val parent = "44444444-4444-4444-4444-444444444444"
    private fun container() = StructuralContainer(dataSource, StructuralContainerKind.DATA_SOURCE,
        "Fixture table", StructuralParent.Database(database), "source", "revision")

    @Test fun containerMetadataAndNativeParentTypesRestoreOffline() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource("source", 1)
        store.writeContainer(container(), "source", 1)
        assertEquals(container(), FileStructuralKnowledgeStore(directory).getContainer(dataSource))
    }

    @Test fun permissionWithdrawalRemovesThePrivateContainerLabel() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource("source", 1)
        store.writeContainer(container(), "source", 1)
        store.writeContainer(container().copy(accessibilityState = PageAccessibilityState.RESTRICTED_PERMISSION), "source", 1)
        assertNull(FileStructuralKnowledgeStore(directory).getContainer(dataSource))
        assertFalse(store.readIndex().pages.containsKey(dataSource))
    }

    @Test fun wholeSourceClearHidesAContainerPayloadLeftBehindByACrash() = runTest {
        val directory = temporary.newFolder()
        val store = FileStructuralKnowledgeStore(directory)
        store.initSource("source", 1)
        store.writeContainer(container(), "source", 1)
        val file = File(directory, "pages/container-$dataSource.json")
        val prior = file.readBytes()
        store.clearSource("source", 1)
        file.writeBytes(prior)
        assertNull(FileStructuralKnowledgeStore(directory).getContainer(dataSource))
    }

    @Test fun ancestorsFollowDataSourceAndDatabaseParentsWithoutCallingThemPages() = runTest {
        val store = FileStructuralKnowledgeStore(temporary.newFolder())
        store.initSource("source", 1)
        store.writeContainer(container(), "source", 1)
        store.writeContainer(StructuralContainer(database, StructuralContainerKind.DATABASE, "Fixture database",
            StructuralParent.Page(parent), "source"), "source", 1)
        val childPage = StructuralPage(page, "Child", StructuralParent.DataSource(dataSource), sourceIdentity = "source")
        val rootPage = StructuralPage(parent, "Parent", StructuralParent.Workspace, sourceIdentity = "source")
        store.writePage(StructuralPageRecord(childPage, emptyList(), emptyList(), emptyList(), sourceIdentity = "source"), "source", 1)
        store.writePage(StructuralPageRecord(rootPage, emptyList(), emptyList(), emptyList(), sourceIdentity = "source"), "source", 1)
        assertEquals(listOf(parent), store.getAncestors(page).map { it.id })
        assertEquals(StructuralParent.DataSource(dataSource), store.getPage(page)!!.parent)
    }
}
