package com.example.notionreader.ui

import androidx.test.core.app.ApplicationProvider
import com.example.notionreader.ReaderApplication
import com.example.notionreader.application.InMemorySettingsRepository
import com.example.notionreader.data.notion.*
import com.example.notionreader.domain.settings.ReaderSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the shipping composition root without using a network or modifying Notion. */
class StructuralPipelineIntegrationTest {
    @Test fun incompatibleStructuralStateCannotPreventReadingOrBrowse() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val state = File(application.noBackupFilesDir, "structural-knowledge/source-state.json")
        val savedState = state.takeIf { it.exists() }?.readBytes()
        val previousFactory = application.notionApiFactory
        val pageId = "f1111111-1111-1111-1111-111111111111"
        val blockId = "f2222222-2222-2222-2222-222222222222"
        val page = NotionPage(id = pageId, properties = mapOf("title" to NotionProperty(type = "title",
            title = listOf(NotionRichText(plainText = "Public integration fixture")))))
        var requests = 0
        application.notionApiFactory = { object : NotionApi {
            override suspend fun retrievePage(pageId: String): NotionPage { requests++; return page }
            override suspend fun retrieveBlockChildren(blockId: String) = listOf(NotionBlock(id = blockId,
                type = "paragraph", paragraph = NotionTextBlock(listOf(NotionRichText(plainText = "Public fixture body.")))))
            override suspend fun searchMostRecentPage() = page
            override suspend fun searchPages(): List<NotionPage> { requests++; return listOf(page) }
            override fun close() = Unit
        } }
        try {
            state.parentFile!!.mkdirs()
            state.writeText("{\"schemaVersion\":99}")
            val loader = application.notionLoader(InMemorySettingsRepository(ReaderSettings(cacheNotesForOffline = false)))
            val loaded = loader(pageId, forceRefresh = true)
            assertTrue("Derived structure must not block the shipping reader", loaded.isSuccess)
            assertEquals("Public integration fixture", loaded.getOrThrow().title)
            assertEquals(listOf(pageId), application.readBrowseIndex().map { it.pageId })
            assertTrue(requests >= 2)
            assertTrue("Incompatible structure itself must fail closed", runCatching { application.structuralKnowledge.getPage(pageId) }.isFailure)
        } finally {
            application.notionApiFactory = previousFactory
            if (savedState == null) state.delete() else state.writeBytes(savedState)
        }
    }
}
