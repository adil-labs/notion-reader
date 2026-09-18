package com.example.notionreader.ui

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
 * The returned modifier must come **before** the focusable modifier in the chain, because the modifier that
 * creates the focus target is the one that has to be able to find the requester:
 *
 * ```kotlin
 * Modifier
 *     .restoreFocusOn(target = focusTarget, id = entry.pageId, onHandled = onFocusHandled)
 *     .clickable(onClick = onClick)
 * ```
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
        // `requestFocus` on a target that is composed but not attached and placed does nothing and reports
        // nothing — and `LazyColumn` places its items during layout, so there is no single frame that is
        // guaranteed to be late enough. The first version requested once and gave up; the second waited one
        // frame and still gave up. On the device all four focus-restoration checks failed with focus
        // nowhere, while the node dump showed the right node with the right bounds and a `RequestFocus`
        // action — which is the picture of a request that arrived too early and was dropped, not of a
        // mechanism that cannot work.
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

    return focusRequester(requester)
}

/**
 * How many frames a focus request is retried for before the hint is given up on.
 *
 * Eight is comfortably more than a `LazyColumn` needs to place an item it has already been asked for, and
 * small enough that a target which will never be focusable costs about an eighth of a second of a
 * listener's attention rather than a visible pause.
 */
private const val FOCUS_ATTEMPT_FRAMES: Int = 8
