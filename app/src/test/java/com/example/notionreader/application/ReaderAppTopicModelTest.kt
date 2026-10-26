package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.player.FakeReaderEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReaderAppTopicModelTest {
    @Test fun clearingAllLocalDataDeletesTheOptionalTopicModel() = runTest {
        var deletes = 0
        val model = object : DiscoveryModelManager {
            override val state = MutableStateFlow(DiscoveryModelState(installed = true))
            override fun download() = error("Reset cannot download")
            override fun cancelDownload() {}
            override fun setWifiOnly(wifiOnly: Boolean) {}
            override suspend fun delete() { deletes++; state.value = DiscoveryModelState(storageBytes = 0) }
        }
        val app = ReaderApp(ReaderSession(FakeReaderEngine()), NoteViewer(ReaderDocument.Demo), ReaderNavigator(),
            DefaultLoadReaderDocument { ReaderDocument.Demo }, discoveryTopicModel = model)
        app.clearAllLocalData()
        assertEquals(1, deletes); assertFalse(model.state.value.installed)
    }
}
