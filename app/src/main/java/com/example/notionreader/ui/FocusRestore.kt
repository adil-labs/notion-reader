package com.example.notionreader.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Puts focus back on the element the listener activated, when Back brings them to the surface that owns it.
 *
 * ## What this implements
 *
 * The UI contract's accessibility rule: "When returning from a secondary destination, restore the previous
 * meaningful focus target when it still exists", whose worked example is
 *
 * ```plain
 * Browse result 7
 * → Note
 * → Back
 * ```
 *
 * landing focus on result 7 rather than on the top of the list. The identifier comes from
 * [com.example.notionreader.application.ReaderNavigator], which records *what was activated* at the moment
 * a screen is pushed — the only moment that fact exists — and hands it back when that screen is popped.
 *
 * ## Why it is a modifier rather than a screen-level effect
 *
 * Because the thing to focus is an *item in a list*, and only the item knows when it exists. A screen-level
 * effect would have to search the composed tree for the target, which Compose does not offer; asking each
 * item "are you the one?" is the shape the framework actually supports, and it degrades correctly: an item
 * that is not composed cannot take focus, which is why the target is only honoured when it is on screen.
 *
 * The restored scroll position is what makes that harmless in practice. The listener activated a row they
 * could see, so the position saved with the destination necessarily includes that row
 * ([androidx.compose.runtime.saveable.SaveableStateHolder] in the shell is what preserves it), and the row
 * is composed by the time this runs. A target that is nonetheless missing is consumed rather than left
 * pending — see [com.example.notionreader.application.ReaderNavigator.focusHandled] — so it can never fire
 * later as a surprise.
 *
 * ## What it is honest about
 *
 * Focus here is Compose's input focus, which a test can assert with `assertIsFocused()`. Whether TalkBack's
 * *speech* focus follows a programmatic focus request is not something a test can see, and it is the same
 * gap the integration spec's long-open gate names: the whole flow performed with TalkBack enabled, heard
 * rather than asserted. `docs/decisions.md` §21 records that rather than implying this closes it.
 *
 * ## Usage
 *
 * The returned modifier comes before the clickable modifier in the chain, which is the order the framework
 * documents for a requester and the target it attaches to:
 *
 * ```kotlin
 * Modifier
 *     .restoreFocusOn(target = focusTarget, id = entry.pageId, onHandled = onFocusHandled)
 *     .clickable(onClick = onClick)
 * ```
 *
 * ## Why it also adds an explicit `focusable()`
 *
 * **Because `clickable` on its own does not give a `FocusRequester` a target it can be granted against.**
 * That was the actual reason every focus-restoration assertion in this project failed for three rounds, and it
 * is worth stating precisely because the reasoning that preceded it was wrong in an instructive way.
 *
 * The observable was `requestFocus()` returning `false` while `hasWindowFocus()` returned `true`, with the
 * node composed, placed, correctly sized and carrying a `RequestFocus` action in its semantics. That was read
 * as "the request arrived too early and was dropped" — because a requester that was never attached *throws*,
 * and this did not throw — and the response was to retry across frames. The retry never helped, and the
 * reason is now measured rather than argued: a requester on a node whose only focus modifier is `clickable`
 * is refused, on every attempt, at every frame.
 *
 * Measured on an emulator with nothing intercepting focus (`enabled_accessibility_services=null`,
 * `touch_exploration_enabled=0`), so the device is not a variable. `nodes` is how many semantics nodes the
 * row produced, because a fix that added a second focus stop per row would be a different problem.
 *
 * | chain | granted | reports `Focused` | nodes |
 * |---|---|---|---|
 * | `focusRequester + clickable` | no | no | 1 |
 * | `clickable + focusRequester` (order swapped) | no | no | 1 |
 * | `focusRequester + focusTarget + clickable` | yes | **no** | 1 |
 * | `focusRequester + focusable + clickable` | yes | **yes** | 1 |
 * | `focusRequester + clickable + focusable` | yes | no | 1 |
 *
 * `focusable()` **before** `clickable`, and both halves of that are load-bearing:
 *
 * - `focusTarget()` instead would be granted and would take focus, but the node then does not *report* being
 *   focused, so nothing — not a test, and not an accessibility service — can observe where focus went. A
 *   restoration that works and cannot be seen is not a restoration.
 * - `focusable()` after `clickable` is granted and still reports `false`, which is the same failure one step
 *   further along.
 *
 * And the worry that made `focusTarget()` look more attractive — that `focusable()` would add a second focus
 * stop to a row that is already clickable — is **refuted by the last column**: one semantics node either way.
 * `focusable()` on the same modifier chain as `clickable` marks the same node focusable rather than creating
 * another one, so a listener still encounters one thing per row.
 */
@Composable
fun Modifier.restoreFocusOn(
    /** The identifier the navigation layer recorded, or null when Back did not bring anyone here. */
    target: String?,
    /** This element's own identifier. Focus is requested only when the two agree. */
    id: String?,
    /** Called after the request, so that a one-shot hint is consumed exactly once. */
    onHandled: () -> Unit
): Modifier {
    val requester = remember { FocusRequester() }

    // Keyed on the two ids rather than on the callback: a parent that recomposes with a fresh lambda must
    // not re-request focus, or every recomposition would pull the listener back to this element.
    LaunchedEffect(target, id) {
        if (target == null || id == null || target != id) return@LaunchedEffect

        // **Retried across frames, because a node that exists is not yet a node that can take focus.**
        //
        // `requestFocus` on a target that is composed but not *placed* does nothing and reports nothing —
        // and a `LazyColumn` places its items during layout, so there is no single frame guaranteed to be
        // late enough on a screen that scrolls its target into view.
        //
        // This retry used to be the fix for the wrong problem. It was added because every assertion failed
        // and a refusal that neither threw nor was granted looked like a timing failure; it is kept because
        // placement is a real claim for a lazy list, and the measurement above is what distinguishes the two.
        // Whether it is still load-bearing is answered by setting [FOCUS_ATTEMPT_FRAMES] to 1 and running the
        // suite, and that experiment is recorded in `docs/decisions.md` §23.29 rather than assumed here.
        //
        // The bound is what keeps this honest. It stops as soon as the request is granted, and if it never
        // is, the hint is consumed anyway so it cannot fire later on an unrelated visit (see
        // `ReaderNavigator.focusHandled`). `requestFocus` also throws when the requester was never attached
        // at all, which is the legitimate case of a target that is not composed — the row is not on screen
        // — so that is caught rather than allowed to crash a screen.
        var granted = false
        var attempts = 0
        while (!granted && attempts < FOCUS_ATTEMPT_FRAMES) {
            withFrameNanos { }
            granted = runCatching { requester.requestFocus() }.getOrDefault(false)
            attempts++
        }

        onHandled()
    }

    // The second half of the fix, and the whole of it: an explicit focus target for the requester to attach
    // to, marked focusable rather than merely a target so that where focus went is observable. Without this,
    // `focusRequester` alone is a requester with nothing to be granted against.
    return focusRequester(requester).focusable()
}

/**
 * How many frames a focus request is retried for before the hint is given up on.
 *
 * ## What it is, and what it is not
 *
 * **Not a requirement, and not the fix.** It was added for a cause that turned out to be wrong: the requests
 * were being refused because the modifier chain gave them nothing to be granted against, and retrying could
 * not help. With that fixed, **one frame is enough** — measured by setting this to `1` and running
 * `ReaderShellAccessibilityTest`, whose 16 tests include the `LazyColumn` case, all of which pass.
 *
 * So this is a bound rather than a mechanism, and it is kept for two reasons that survive the measurement:
 *
 * - **It cannot mask a failure.** If the request is never granted, focus does not happen whether the loop ran
 *   once or eight times; the loop only stops early when it has succeeded. There is no version of this that
 *   turns a broken focus restore into a silent one.
 * - **Placement is a real race that the emulator is not the worst case for.** A `LazyColumn` places its items
 *   during layout, and a slower device with a longer list is exactly where one frame might not be enough. Eight
 *   is about a tenth of a second of a listener's attention in the worst case, and none at all in the common
 *   one, since it returns as soon as the first attempt succeeds.
 */
private const val FOCUS_ATTEMPT_FRAMES: Int = 8
