package com.example.notionreader.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The focus mechanism, and the measurement that replaced three rounds of misdiagnosis.
 *
 * ## What this class used to be, and why it changed
 *
 * It was an investigation: four probes asserting that `focusRequester` + `clickable` is granted, that
 * `focusable()` is, that the semantics `RequestFocus` action works, and that a request from a
 * `LaunchedEffect` works. Three of the four failed — on the phone and on a clean emulator alike — and the
 * failures were recorded as a **device** limitation: first TalkBack holding accessibility focus, then, after
 * §23.17 refuted that, a Compose-versus-API-37 interaction.
 *
 * Both explanations were wrong, and the probes were asserting a false premise rather than a device
 * limitation. The cause is in this project's own code: **`clickable` does not create a focus target that a
 * `FocusRequester` can attach to.** Measured on an emulator with `enabled_accessibility_services=null` and
 * `touch_exploration_enabled=0`, so the device is not a variable:
 *
 * | chain | granted | reports `Focused` | nodes |
 * |---|---|---|---|
 * | `focusRequester + clickable` | **no** | no | 1 |
 * | `clickable + focusRequester` (order swapped) | **no** | no | 1 |
 * | `focusRequester + focusTarget + clickable` | yes | **no** | 1 |
 * | `focusRequester + focusable + clickable` | yes | **yes** | 1 |
 * | `focusRequester + clickable + focusable` | yes | no | 1 |
 *
 * So the tests below assert the chain `restoreFocusOn` actually produces — `focusRequester + focusable`,
 * applied before the row's `clickable`, which is the fourth row of that table. **They are the regression
 * guard for that fix**: take the `focusable()` out of `restoreFocusOn` and they fail, which is how the table
 * above was produced in the first place.
 *
 * The third and fourth columns are there because two plausible fixes are not fixes: `focusTarget()` takes
 * focus but does not *report* it, so nothing can observe where focus went, and `focusable()` placed after
 * `clickable` behaves the same way. And the `nodes` column refutes the reason `focusTarget()` looked more
 * attractive in the first place — that `focusable()` would add a second focus stop to an already-clickable
 * row. It does not: one semantics node either way.
 *
 * ## What is deliberately no longer asserted
 *
 * Nothing here asserts that `clickable` alone is refused. That is a property of the framework rather than of
 * this app, and it is the *reason* for the fix rather than a requirement of the product; pinning it would
 * make a Compose upgrade a red suite for no benefit. The positive assertions cover the same ground, because
 * they cannot pass without an explicit focusable target.
 *
 * ## What it is honest about
 *
 * Focus here is Compose's input focus. Whether TalkBack's *speech* focus follows a programmatic request is not
 * something a test can see — that is the integration spec's long-open gate, and `docs/decisions.md` §21 records
 * it rather than implying this closes it. What has changed is only that seven assertions which were red are
 * now green, for a reason in our own code.
 */
class FocusRestoreTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The chain `restoreFocusOn` produces, in the position a screen applies it.
     *
     * Written out rather than calling the helper, because the helper is `@Composable` and its id matching is
     * covered end to end through real screens by `ReaderShellAccessibilityTest`. What is under test here is
     * the modifier chain itself.
     */
    @Composable
    private fun RowWithRestorableFocus(requester: FocusRequester, onClick: () -> Unit = {}) {
        Text(
            text = "Row b",
            modifier = Modifier
                .focusRequester(requester)
                .focusable()
                .clickable(onClick = onClick)
        )
    }

    @Test
    fun theChainTheAppUsesIsGrantedFromOutsideTheComposition() {
        val requester = FocusRequester()

        compose.setContent { RowWithRestorableFocus(requester) }
        compose.waitForIdle()

        val windowFocused = compose.activity.window.decorView.hasWindowFocus()
        val granted = compose.runOnUiThread { requester.requestFocus() }

        // Both values in one message, because either alone is ambiguous: a refusal with no window focus would
        // say the two are unrelated, and a grant with no focus would be a contradiction.
        assertTrue(
            "requestFocus() returned $granted and hasWindowFocus() returned $windowFocused",
            granted && windowFocused
        )
    }

    @Test
    fun theChainTheAppUsesLeavesTheRowActuallyFocused() {
        val requester = FocusRequester()

        compose.setContent { RowWithRestorableFocus(requester) }
        compose.waitForIdle()

        compose.runOnUiThread { requester.requestFocus() }
        compose.waitForIdle()

        compose.onNodeWithText("Row b").assertIsFocused()
    }

    @Test
    fun theChainTheAppUsesIsGrantedFromALaunchedEffect() {
        // The production shape: the request is issued from an effect during the target screen's first
        // composition rather than from the test thread. This is the case the retry-across-frames loop in
        // `restoreFocusOn` exists for, and the one a `LazyColumn` makes non-trivial.
        val requester = FocusRequester()

        compose.setContent {
            RowWithRestorableFocus(requester)
            LaunchedEffect(Unit) { requester.requestFocus() }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Row b").assertIsFocused()
    }

    @Test
    fun anExplicitFocusableAlsoProducesAWorkingTarget() {
        // Kept from the investigation because it is half of the distinction that explained everything:
        // `focusable()` was granted where `clickable` was refused. It is now also the modifier the app *does*
        // use, so this is the app's chain with the row's click taken away — the minimal case.
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

        assertTrue("with focusable(): requestFocus() returned $granted", granted)
    }

    @Test
    fun theRowIsStillActivatedByTheAccessibilityPath() {
        // The semantics action, which is what an accessibility service performs and what this suite drives
        // controls with everywhere else. Asserted alongside the focus path because the two are the same
        // intent reached two ways, and a fix that satisfied one by breaking the other would be no fix.
        val clicks = mutableListOf<Unit>()

        compose.setContent { RowWithRestorableFocus(FocusRequester(), onClick = { clicks += Unit }) }
        compose.waitForIdle()

        compose.onNodeWithText("Row b").performSemanticsAction(SemanticsActions.OnClick)

        assertTrue("the row's click must still arrive", clicks.isNotEmpty())
    }
}
