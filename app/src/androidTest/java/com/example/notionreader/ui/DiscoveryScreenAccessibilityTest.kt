package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.material3.Text
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.assertIsReadable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiscoveryScreenAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listeningPrecedesSearchAndDiscoveryInReadingOrder() {
        compose.setContent {
            DiscoveryScreen("", {}, 3, 3, emptyList(), emptyList(), {}, {}, {}, { _, _ -> }, {}, {},
                continueContent = { Text("Listening marker") }, topicsContent = { Text("Topic marker") })
        }
        fun texts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        val reading = texts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        org.junit.Assert.assertTrue(reading.indexOf("Listening marker") < reading.indexOf("Search your cached Notes"))
        org.junit.Assert.assertTrue(reading.indexOf("Search your cached Notes") < reading.indexOf("Topic marker"))
    }

    @Test fun missingModelOffersOneSetupActionInsteadOfUnavailableSearchAndTopicMessages() {
        var settings = 0
        compose.setContent {
            DiscoveryScreen("", {}, 0, 2, emptyList(), emptyList(), {}, {}, {}, { _, _ -> }, {}, {},
                semanticAvailable = false, status = "Download the optional model to discover your Notes.",
                onManageDiscovery = { settings++ }, topicsContent = { Text("Topic discovery paused") })
        }
        compose.onNodeWithText("Topic discovery paused").assertDoesNotExist()
        compose.onNodeWithText("Search your cached Notes").assertDoesNotExist()
        compose.onNodeWithText("Set up Discovery").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, settings)
    }

    @Test fun sourceEvidenceIsReadableAndOpeningANoteOnlyInvokesNavigation() {
        var opened = ""
        compose.setContent {
            RelatedNotesScreen(listOf(DiscoveryNoteUi("a", "Trees", "Roots retain water", "Ecology")),
                onOpenNote = { opened = it }, onBack = {})
        }
        compose.onNodeWithText("Roots retain water").assertIsReadable()
        compose.onNodeWithText("Ecology").assertIsReadable()
        compose.onNodeWithText("Open Trees").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("a", opened)
    }

    @Test fun previewEditingDoesNotStartPlayback() {
        var removed = ""
        var plays = 0
        compose.setContent {
            GuidedListeningScreen(listOf(DiscoveryNoteUi("a", "Trees", "Roots", "Ecology")),
                onRemove = { removed = it }, onMoveUp = {}, onMoveDown = {},
                onPlay = { plays++ }, onSave = {}, onBack = {})
        }
        compose.onNodeWithText("Remove Trees").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("a", removed)
        assertEquals(0, plays)
        compose.onNodeWithText("Play").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, plays)
    }

    @Test fun anExplicitInterestIsTrimmedBeforeSaving() {
        var saved = ""
        compose.setContent {
            DiscoveryScreen("", {}, 0, 0, emptyList(), emptyList(), {}, {},
                onAddInterest = { saved = it }, onEditInterest = { _, _ -> },
                onRemoveInterest = {}, onSelectSeed = {})
        }
        compose.onNodeWithText("Add interest").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Describe a topic").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("New interest").performTextInput("  forest ecology  ")
        compose.onNodeWithText("Save interest").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("forest ecology", saved)
        compose.onNodeWithText("0 of 0 cached Notes indexed").performScrollTo().assertIsReadable()
    }

    @Test fun aSingleNoteCannotBeMovedBeyondThePreviewBoundaries() {
        compose.setContent {
            GuidedListeningScreen(listOf(DiscoveryNoteUi("a", "Trees", "Roots", "Ecology")),
                onRemove = {}, onMoveUp = {}, onMoveDown = {}, onPlay = {}, onSave = {}, onBack = {})
        }
        compose.onNodeWithText("Move Trees up").assertIsNotEnabled()
        compose.onNodeWithText("Move Trees down").assertIsNotEnabled()
    }

    @Test fun duplicateSourceNotesHaveOnlyOneOpenAction() {
        val note = DiscoveryNoteUi("a", "Trees", "Roots", "Ecology")
        compose.setContent { RelatedNotesScreen(listOf(note, note), {}, {}) }
        compose.onAllNodesWithText("Open Trees").assertCountEquals(1)
    }

    @Test fun noteRelatedNavigationNeverInvokesPlay() {
        var related = 0
        var played = 0
        compose.setContent {
            NoteScreen(A11y.document(), com.example.notionreader.application.DocumentLoadState.Loaded(A11y.document()),
                onPlay = { played++ }, onRetry = {}, onRelatedNotes = { related++ })
        }
        compose.onNodeWithText("Related Notes").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, related)
        assertEquals(0, played)
    }

    @Test fun editingASeedChoosesAnotherNoteWithoutConvertingItToText() {
        var seedEdit = ""
        var textEdits = 0
        compose.setContent {
            DiscoveryScreen("", {}, 0, 0, emptyList(),
                listOf(DiscoveryInterestUi("interest", "Trees", seed = true)), {}, {}, {},
                onEditInterest = { _, _ -> textEdits++ }, onRemoveInterest = {}, onSelectSeed = {},
                onEditSeed = { seedEdit = it })
        }
        compose.onNodeWithText("Edit Trees").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("interest", seedEdit)
        assertEquals(0, textEdits)
    }
}
