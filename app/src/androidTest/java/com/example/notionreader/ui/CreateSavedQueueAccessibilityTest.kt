package com.example.notionreader.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.example.notionreader.ui.A11y.assertIsReadable
import com.example.notionreader.ui.A11y.assertIsReachable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * `Create saved queue`.
 *
 * Three things a device alone can answer, and each is a way this screen can look right and be unusable:
 *
 * - the field has a name, so a listener is told what to type rather than finding an unlabelled box;
 * - `Create` is **unavailable while the name is blank**, which is the rule the model states and the screen
 *   reads — a listener is told what is unavailable rather than pressing something that does nothing;
 * - a name of spaces counts as blank, which is the case a screen checking `isNotBlank()` for itself would
 *   get wrong while the model refused it.
 *
 * The rule itself is not asserted here: it belongs to `SavedQueueDraft` and is tested with no device. What
 * is asserted is that the screen *reads* that rule instead of restating it — which is the difference between
 * one definition and two that can drift.
 *
 * ## What is deliberately not asserted
 *
 * Nothing here asserts that the queue was created or that the screen left. Both depend on the outcome of a
 * write this composable cannot see, and both are asserted where they happen: `ReaderAppSavedQueuesTest` for
 * the write and the published list, and `ReaderViewModel.createSavedQueue` for leaving the screen only when
 * a queue exists.
 */
class CreateSavedQueueAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(onCreate: (String) -> Unit = { }) {
        compose.setContent { CreateSavedQueueScreen(onCreate = onCreate) }
    }

    @Test
    fun theFieldIsNamedSoAListenerIsToldWhatToType() {
        show()

        compose.onNodeWithText("Queue name").assertExists()
    }

    @Test
    fun theScreenNamesItselfSoEnteringItSaysWhereTheListenerIs() {
        show()

        compose.onNodeWithText("Create saved queue").assert(A11y.hasHeading())
    }

    @Test
    fun createIsUnavailableUntilTheNameIsUsable() {
        // "A disabled move is disabled, not inert" is the project's rule wherever a control can be
        // unavailable, and this is that rule on a text field: with nothing typed there is nothing to create.
        show()

        compose.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun createBecomesAvailableOnceANameIsTyped() {
        show()

        compose.onNodeWithText("Queue name").performTextInput("Reading")

        compose.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun aNameOfSpacesIsStillNotUsable() {
        // The boundary the screen inherits rather than implements: the model trims, so " " is blank. A screen
        // that decided for itself with `isNotBlank()` would offer Create here and the model would refuse it.
        show()

        compose.onNodeWithText("Queue name").performTextInput("   ")

        compose.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun pressingCreateReportsTheNameTheListenerTyped() {
        val names = mutableListOf<String>()
        show(onCreate = { names += it })

        compose.onNodeWithText("Queue name").performTextInput("Reading")
        compose.onNodeWithText("Create").performSemanticsAction(SemanticsActions.OnClick)

        // The raw text, not the trimmed name: the model is what trims it, and a screen that sent a trimmed
        // value would be a second place that decides what the name is.
        assertEquals(listOf("Reading"), names)
    }

    @Test
    fun theControlIsReachableAndNotAnUnlabelledButton() {
        show()

        compose.onNodeWithText("Queue name").performTextInput("Reading")
        compose.onNodeWithText("Create").assertIsReachable()

        A11y.assertEveryClickableIsLabelled(compose.onAllNodes(A11y.ANY))
    }

    @Test
    fun nothingOnThisScreenPlaysAnything() {
        // The contract: "Creating a Saved Queue does not start playback." There is no playback control here at
        // all, which is the strongest form of that statement.
        show()

        compose.onAllNodesWithText("Play", substring = true).assertCountEquals(0)
    }
}
