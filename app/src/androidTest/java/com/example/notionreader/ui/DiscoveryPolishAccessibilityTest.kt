package com.example.notionreader.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiscoveryPolishAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun discoveryEffortChoicesAreReachableAndExposeTheirSelection() {
        val effort = mutableStateOf(com.example.notionreader.domain.settings.DiscoveryEffort.BALANCED)
        compose.setContent {
            com.example.notionreader.ui.settings.DiscoverySettingsScreen(
                com.example.notionreader.ui.settings.DiscoverySettingsUi("Gemma INT8", effort = effort.value),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, onEffortChange = { effort.value = it })
        }
        compose.onNodeWithText("Balanced").performScrollTo().assertIsReachable().assertIsSelected()
        compose.onNodeWithText("Charging only").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Charging only").assertIsSelected()
        compose.onNodeWithText("Balanced").assertIsNotSelected()
        compose.onNodeWithText("Continue discovery").assertDoesNotExist()
    }
    @Test fun anEmptyQueryHasNoDisabledSearchButtonAndBothSearchActionsSubmitEnteredText() {
        val query = mutableStateOf(""); var searches = 0
        compose.setContent { DiscoveryScreen(query.value, { query.value = it }, 3, 3, emptyList(), emptyList(),
            { searches++ }, {}, {}, { _, _ -> }, {}, {}, onViewInterests = {}) }
        compose.onAllNodesWithText("Search").assertCountEquals(0)
        compose.onNodeWithContentDescription("Search cached Notes").assertDoesNotExist()
        compose.onNodeWithText("Search your cached Notes").performTextInput("woodland water")
        compose.onNodeWithContentDescription("Search cached Notes").assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, searches)
        compose.onNodeWithText("Search your cached Notes").performImeAction()
        assertEquals(2, searches)
    }
    @Test fun aTopicPreviewHasOneRepresentativeAndOneNavigationAction() {
        val p = SemanticPassage("p", "a", "s", "Ecology", "b", "c", 0, 4, "Roots", "Roots", 0)
        val members = (1..4).map { TopicMembership("n$it", SemanticMetadata("Representative $it"), p.copy(pageId = "n$it"), .9f, p) }
        val topic = DiscoveredTopic("stable", "Forest ecology", p, EmbeddedPassage(p, listOf(1f)), members, 1)
        var opened = 0
        compose.setContent { TopicNavigationRow(topic, { opened++ }) }
        compose.onNodeWithText("Representative 1").assertExists()
        compose.onNodeWithText("Representative 2").assertDoesNotExist()
        compose.onNodeWithText("4 available Notes").assertIsReachable()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Forest ecology").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }
    @Test fun aConnectionIsOneNamedActionAndKeepsTheSecondPassageInDetails() {
        val p = SemanticPassage("left", "a", "s", "Ecology", "b", "c", 0, 4, "Left source evidence", "Roots", 0)
        val left = TopicMembership("a", SemanticMetadata("Alpha"), p, .9f, p)
        val right = TopicMembership("b", SemanticMetadata("Beta"), p.copy(id = "right", pageId = "b", excerpt = "Right source evidence"), .9f, p)
        var opened = 0
        compose.setContent { ConnectionNavigationRow(NoteConnection("pair", left, right, .9f), { opened++ }) }
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("Left source evidence").assertExists()
        compose.onNodeWithText("Right source evidence").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Compare passages").assertIsReachable().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

}
