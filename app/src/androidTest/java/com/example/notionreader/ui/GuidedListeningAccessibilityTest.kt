package com.example.notionreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GuidedListeningAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPreviewsKeepActionsVisibleAtLargeTextBeforeAndAfterScrolling() {
        var plays = 0
        var saves = 0
        var moved = ""
        val notes = (1..5).map { DiscoveryNoteUi("$it", "Forest $it", "Trees protect soil and shelter wildlife. ".repeat(30), "Ecology") }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 480.dp)) {
                        GuidedListeningScreen(notes, {}, { moved = it }, {}, { plays++ }, { saves++ }, {})
                    }
                }
            }
        }
        compose.onAllNodesWithText("Play").assertCountEquals(1)
        compose.onAllNodesWithText("Save to queue").assertCountEquals(1)
        compose.onNodeWithText("Play").assertIsReachable().assertIsDisplayed()
        compose.onNodeWithText("Save to queue").assertIsReachable().assertIsDisplayed()
        compose.onNodeWithText("Move Forest 5 up").performScrollTo().assertIsReachable()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("5", moved)
        assertEquals(0, plays)
        compose.onNodeWithText("Save to queue").assertIsReachable().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, saves)
        assertEquals(0, plays)
        compose.onNodeWithText("Play").assertIsReachable().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, plays)
    }

    @Test fun emptyPreviewsExposeDisabledActionsWithoutStartingListening() {
        compose.setContent {
            GuidedListeningScreen(emptyList(), {}, {}, {}, { error("Empty preview must not play") },
                { error("Empty preview must not save") }, {})
        }
        compose.onNodeWithText("Play").assertIsReachable().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Save to queue").assertIsReachable().assertIsDisplayed().assertIsNotEnabled()
    }
}
