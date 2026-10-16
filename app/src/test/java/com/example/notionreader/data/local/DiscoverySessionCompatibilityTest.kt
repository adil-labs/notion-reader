package com.example.notionreader.data.local

import com.example.notionreader.application.ReaderDestination
import com.example.notionreader.application.ReaderNavigator
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.session.StoredSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DiscoverySessionCompatibilityTest {
    @Test fun homeIsTheDefaultAndBackReturnsToHome() {
        val nav = ReaderNavigator()
        assertEquals(ReaderDestination.Home, nav.state.value.top)
        nav.openTopLevel(ReaderDestination.Browse)
        nav.back()
        assertEquals(ReaderDestination.Home, nav.state.value.top)
    }

    @Test fun addingADiscoveryOriginPreservesExistingVersionTwoRecords() {
        val existing = """[{"id":"old","notes":[{"pageId":"p","title":"Old"}],"origin":{"type":"single_note"},"index":0,"position":null,"completed":false,"wasPlaying":false,"lastActivityAt":0,"schemaVersion":2}]"""
        val old = SessionCodec.decode(existing)
        assertEquals(1, old.size)
        val newer = old.single().copy(id = "new", origin = QueueOrigin.Discovery("Connected ideas"))
        val decoded = SessionCodec.decode(SessionCodec.encode(old + newer))
        assertEquals(listOf("old", "new"), decoded.map { it.id })
        assertEquals(QueueOrigin.SingleNote, decoded.first().origin)
        assertEquals(QueueOrigin.Discovery("Connected ideas"), decoded.last().origin)
        assertTrue(decoded.all { it.schemaVersion == 2 })
    }
}
