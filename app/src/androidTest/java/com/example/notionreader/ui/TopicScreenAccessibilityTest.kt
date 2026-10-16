package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.notionreader.ui.A11y.assertIsReachable
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.domain.discovery.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TopicScreenAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun followingRequiresExplicitConfirmationAndSavesTheEditedDefinition() {
        var saved = emptyList<String>()
        compose.setContent { InterestDefinitionDialog("Ecology", "forest ecology", "Follow topic", {},
            { name, text -> saved = listOf(name, text) }) }
        compose.onNodeWithText("Interest name").performTextReplacement("My forests")
        compose.onNodeWithText("Matching text").performTextReplacement("  woodland water cycles  ")
        assertEquals(emptyList<String>(), saved)
        compose.onNodeWithText("Save interest").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("My forests", "woodland water cycles"), saved)
    }

    @Test fun cancellingAFollowDoesNotSaveAnything() {
        var saves = 0
        var cancels = 0
        compose.setContent { InterestDefinitionDialog("Ecology", "forest ecology", "Follow topic", { cancels++ },
            { _, _ -> saves++ }) }
        compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, saves)
        assertEquals(1, cancels)
    }

    @Test fun addingAnInterestOffersIndependentDescriptionAndCachedNoteChoices() {
        var seeds = 0
        compose.setContent { DiscoveryScreen("", {}, 0, 0, emptyList(), emptyList(), {}, {}, {},
            { _, _ -> }, {}, { seeds++ }) }
        compose.onNodeWithText("New interest").assertDoesNotExist()
        compose.onNodeWithText("Add interest").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Describe a topic").assertIsReachable()
        compose.onNodeWithText("Choose a Note uses cached Notes only.").assertIsReadable()
        compose.onNodeWithText("Choose a Note").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, seeds)
    }

    @Test fun editedDraftDoesNotRelabelPreviouslySubmittedResults() {
        compose.setContent { DiscoveryScreen("new draft", {}, 1, 1,
            listOf(DiscoveryNoteUi("a", "Trees", "Source excerpt", "Ecology")), emptyList(), {}, {}, {},
            { _, _ -> }, {}, {}, submittedSummary = "Results for old query · All metadata") }
        compose.onNodeWithText("Results for old query · All metadata").assertIsReadable()
        compose.onNodeWithText("Source excerpt").assertIsReadable()
    }

    @Test fun setupHasARealRestorableFocusTarget() {
        compose.setContent { DiscoveryScreen("", {}, 0, 0, emptyList(), emptyList(), {}, {}, {},
            { _, _ -> }, {}, {}, focusTarget = "discovery_settings", onManageDiscovery = {}, semanticAvailable = false) }
        compose.onNodeWithText("Set up Discovery").assertIsFocused()
    }
    @Test fun aTopicIsOneNamedNavigationStopWithDistinctNoteCountAndOneRepresentativeTitle() {
        val passage = SemanticPassage("p", "a", "s", "Ecology", "b", "c", 0, 4, "Roots", "Roots", 0)
        val members = (1..4).map { number -> TopicMembership("note$number", SemanticMetadata("Title $number"),
            passage.copy(pageId = "note$number"), .9f, passage) }
        val topic = DiscoveredTopic("stable", "Forest ecology", passage, EmbeddedPassage(passage, listOf(1f)),
            members + members.first(), 1)
        var opens = 0
        compose.setContent { TopicNavigationRow(topic, { opens++ }) }
        compose.onNodeWithText("4 available Notes").assertIsReachable()
        compose.onNodeWithText("Title 1").assertExists()
        compose.onNodeWithText("Title 2").assertDoesNotExist()
        compose.onNodeWithText("Title 4").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Forest ecology").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opens)
    }

    @Test fun memberEvidenceIsReadableAndOpenUsesTheMemberIdentity() {
        val passage = SemanticPassage("p", "two-theme", "ecology", "Ecology", "b", "c", 0, 4,
            "Forest water evidence", "Forest water evidence", 0)
        val member = TopicMembership("two-theme", SemanticMetadata("Two theme Note"), passage, .9f,
            passage.copy(id = "anchor", pageId = "anchor-note", excerpt = "Anchor forest context"))
        var opened = ""
        compose.setContent { androidx.compose.foundation.layout.Column { TopicMemberRow(member, { opened = it }) } }
        compose.onNodeWithText("Forest water evidence").assertIsReadable()
        compose.onNodeWithText("Anchor forest context").assertDoesNotExist()
        compose.onNodeWithText("Open Two theme Note").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("two-theme", opened)
    }

    @Test fun unavailablePreviewMembersRemainRemovableAndBlockPlayAndSave() {
        var removed = ""
        var moved = ""
        compose.setContent {
            GuidedListeningScreen(listOf(DiscoveryNoteUi("missing", "Unavailable Note", "", ""),
                DiscoveryNoteUi("available", "Trees", "Root evidence", "Ecology")),
                onRemove = { removed = it }, onMoveUp = {}, onMoveDown = { moved = it },
                onPlay = {}, onSave = {}, onBack = {}, unavailableIds = setOf("missing"),
                status = "Some preview Notes are unavailable. Remove them or recache before listening or saving.")
        }
        compose.onNodeWithText("1. Unavailable Note").assertIsReadable()
        compose.onNodeWithText("Note unavailable. Remove it or recache it to use this preview.").assertIsReadable()
        compose.onNodeWithText("Move Unavailable Note down").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("missing", moved)
        compose.onNodeWithText("Remove Unavailable Note").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("missing", removed)
        compose.onNodeWithText("Play").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save to queue").performScrollTo().assertIsNotEnabled()
    }

}
