package com.example.notionreader.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Why a focus request is refused, asked as two questions with their values in the failure message.
 *
 * ## The sequence that got here
 *
 * Focus restoration failed every instrumented check across three attempts, and each attempt assumed the
 * mechanism worked and the *timing* did not — request immediately, wait a frame, retry across frames. All
 * three were wrong. The helper then failed **in isolation**, with no shell and no navigation, and finally
 * the plainest possible case failed too: a `FocusRequester` requested from the test thread after
 * `waitForIdle`, where the node is attached and placed by construction and nothing is timed at all.
 *
 * That failure carried no exception, which rules out "called too early" — an unattached requester throws.
 * A request that is neither granted nor refused loudly is a request that was **declined**, and Compose
 * declines a focus request when the window holding the composition is not focused.
 *
 * ## What it measures
 *
 * `hasWindowFocus()` on the Activity's decor view and the `Boolean` that `requestFocus()` returns, both
 * asserted together so the report contains the values rather than an inference. If the window is not
 * focused, every programmatic focus request in this app is refused for a reason that is entirely the
 * device's — an accessibility overlay or an autofill window holding input focus — and the honest conclusion
 * is that this class of assertion cannot be made on this device rather than that the feature does not work.
 */
class FocusRestoreTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aPlainRequesterGrantedFromOutsideTheCompositionWorks() {
        val requester = FocusRequester()

        compose.setContent {
            Text(
                text = "Row b",
                modifier = Modifier
                    .focusRequester(requester)
                    .clickable { }
            )
        }

        compose.waitForIdle()

        val windowFocused = compose.activity.window.decorView.hasWindowFocus()
        val granted = compose.runOnUiThread { requester.requestFocus() }

        // Both values in one message, because either alone is ambiguous: a refusal with no focus says why,
        // and a grant with no focus would say the two are unrelated.
        assertTrue(
            "requestFocus() returned $granted and hasWindowFocus() returned $windowFocused",
            granted && windowFocused
        )
    }

    @Test
    fun anExplicitFocusableProducesAWorkingTarget() {
        val requester = FocusRequester()

        compose.setContent {
            Text(
                text = "Row b",
                modifier = Modifier
                    .focusRequester(requester)
                    .focusable()
            )
        }

        compose.waitForIdle()

        val granted = compose.runOnUiThread { requester.requestFocus() }

        // The same request against an explicit `focusable()` rather than `clickable`. Which of the two
        // produces a target that accepts focus is the whole question, and the value in the message is the
        // answer — no inference.
        assertTrue("with focusable(): requestFocus() returned $granted", granted)
    }

    @Test
    fun theSemanticsFocusActionIsGrantedInstead() {
        compose.setContent {
            Text(
                text = "Row b",
                modifier = Modifier.clickable { }
            )
        }

        compose.waitForIdle()

        // The accessibility path, which is what a screen reader actually uses and what the app's assertion
        // is standing in for. If this is granted where `FocusRequester` was not, then the environment
        // supports focus and the difference is between the two mechanisms rather than about the device.
        compose.onNodeWithText("Row b").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        compose.onNodeWithText("Row b").assertIsFocused()
    }

    @Test
    fun aRequestFromALaunchedEffectWorks() {
        val requester = FocusRequester()

        compose.setContent {
            Text(
                text = "Row b",
                modifier = Modifier
                    .focusRequester(requester)
                    .clickable { }
            )

            // No frame wait, no retry, and no catch: whatever this does is reported as-is. This is the shape
            // the production helper had, minus the defensive parts that could hide the reason it does
            // nothing.
            LaunchedEffect(Unit) { requester.requestFocus() }
        }

        compose.waitForIdle()

        compose.onNodeWithText("Row b").assertIsFocused()
    }
}

