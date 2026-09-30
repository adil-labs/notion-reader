package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.example.notionreader.application.SmartQueueCard
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SmartQueueDetailsAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val notes = listOf(
        ReaderIndexEntry("p1", "Android Accessibility", lastEditedAt = null),
        ReaderIndexEntry("p2", "Android TTS", lastEditedAt = null)
    )

    private fun queue(
        id: String = "sq1",
        name: String = "Accessibility Notes",
        queryText: String = "Accessibility",
        memberDescription: String = "2 notes"
    ) = SmartQueueCard(
        queueId = id,
        name = name,
        queryText = queryText,
        memberDescription = memberDescription,
        lastEditedAt = 1000L
    )

    private fun show(
        queue: SmartQueueCard? = queue(),
        matchingNotes: List<ReaderIndexEntry> = notes,
        onOpenNote: (String) -> Unit = {},
        onPlay: (String) -> Unit = {},
        onRename: (String, String) -> Unit = { _, _ -> },
        onDelete: (String) -> Unit = {}
    ) {
        compose.setContent {
            SmartQueueDetailsScreen(
                queue = queue,
                matchingNotes = matchingNotes,
                onOpenNote = onOpenNote,
                onPlay = onPlay,
                onRename = onRename,
                onDelete = onDelete
            )
        }
    }

    @Test
    fun smartQueueDetailsRendersTitleQueryAndNotes() {
        show()

        compose.onNodeWithText("Accessibility Notes").assertIsReadable()
        compose.onNodeWithText("Query: \"Accessibility\"").assertIsReadable()
        compose.onNodeWithText("Matching notes (2)").assertIsReadable()
        compose.onNodeWithText("Android Accessibility").assertIsReachable()
        compose.onNodeWithText("Android TTS").assertIsReachable()
    }

    @Test
    fun playControlIsReachableAndTriggersOnPlay() {
        var playedId: String? = null
        show(onPlay = { playedId = it })

        compose.onNodeWithText("Play").assertIsReachable()
        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals("sq1", playedId)
    }

    @Test
    fun renameControlOpensRenameDialog() {
        show()

        compose.onNodeWithText("Rename").assertIsReachable()
        compose.onNodeWithText("Rename").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("Rename smart queue").assertIsReadable()
        compose.onNodeWithText("Save").assertIsReachable()
        compose.onNodeWithText("Cancel").assertIsReachable()
    }

    @Test
    fun deleteControlOpensDeleteDialog() {
        show()

        compose.onNodeWithText("Delete").assertIsReachable()
        compose.onNodeWithText("Delete").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("Delete smart queue").assertIsReadable()
        compose.onNodeWithText("Delete smart queue \"Accessibility Notes\"?").assertIsReadable()
    }

    @Test
    fun goneStateWhenQueueIsNull() {
        show(queue = null)

        compose.onNodeWithText("Queue no longer exists").assertIsReadable()
    }
}
