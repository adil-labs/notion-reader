package com.example.notionreader.application

import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.queue.InMemorySavedQueueStore
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.domain.queue.SavedQueue
import com.example.notionreader.player.FakeReaderEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedQueueCoordinatorTest {

    private val demo = ReaderDocument.fromChunkTexts("demo", "Demo document", listOf("First."))

    private fun createCoordinator(): Pair<SavedQueueCoordinator, ReaderNavigator> {
        val navigator = ReaderNavigator(ReaderNavigation(top = ReaderDestination.Queues))
        val session = ReaderSession(FakeReaderEngine(demo))
        val store = InMemorySavedQueueStore(listOf(SavedQueue("q1", "Queue 1", emptyList(), 0L)))
        val coordinator = SavedQueueCoordinator(
            savedQueueStore = store,
            session = session,
            navigator = navigator,
            clock = { 1000L }
        )
        return coordinator to navigator
    }

    private fun note(id: String) = QueuedNote(id, "Title $id", "First chunk.")

    @Test
    fun pendingQueueMembershipIsReactiveAndItsCountIsDerived() = runTest {
        val (coordinator, _) = createCoordinator()

        // 1. Initial StateFlow values
        assertEquals(emptyList<QueuedNote>(), coordinator.pendingAdd.value)
        assertEquals(0, coordinator.pendingAddCount)

        // 2. beginAddToQueue updates StateFlow
        val notes = listOf(note("n1"), note("n2"))
        coordinator.beginAddToQueue(notes)

        assertEquals(notes, coordinator.pendingAdd.value)
        assertEquals(2, coordinator.pendingAddCount)

        // 3. cancelPendingAdd resets StateFlow
        coordinator.cancelPendingAdd()
        assertEquals(emptyList<QueuedNote>(), coordinator.pendingAdd.value)
        assertEquals(0, coordinator.pendingAddCount)

        // 4. addPendingToQueue empties StateFlow after placement
        coordinator.beginAddToQueue(listOf(note("n3")))
        assertEquals(1, coordinator.pendingAddCount)

        coordinator.addPendingToQueue("q1")
        assertEquals(emptyList<QueuedNote>(), coordinator.pendingAdd.value)
        assertEquals(0, coordinator.pendingAddCount)
    }
}
