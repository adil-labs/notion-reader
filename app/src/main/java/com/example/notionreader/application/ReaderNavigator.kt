package com.example.notionreader.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the user is: a selected top-level destination, and whatever is pushed on top of it.
 *
 * ## Why this replaced a single value
 *
 * The first version held one destination, because there were two screens and one edge between them. The
 * UI contract's Back rule is "returns to the previous visual context when possible", and with a shell
 * that stops being one edge:
 *
 * | From | Back goes to | Because |
 * |---|---|---|
 * | Player opened from a Browse result set | Browse | the result set is the context |
 * | Player opened from a **Note** | Note | the Note is the context |
 * | Note | the tab it was opened from | the same rule |
 * | Browse | Home | the product decision in `docs/decisions.md` §12.5 |
 *
 * The second row is why this is a list and not a single field: Play is reachable from the Note screen,
 * so the previous visual context is not always a tab, and Note → Player → Back → Note cannot be
 * expressed by one `secondary`.
 *
 * ## Why a list and not a general back stack
 *
 * Because the graph is genuinely shallower than a stack: a secondary destination is always opened over a
 * top-level one, and selecting a tab discards it. The contract does name two-secondary-deep flows —
 * `Queue Destination → Create Saved Queue` — and those belong to slice F, which is blocked on two missing
 * specifications. When that arrives this becomes a full stack; until then a list of secondaries models
 * exactly what exists, which is more useful than a stack that has never been exercised two deep.
 */
data class ReaderNavigation(
    val top: ReaderDestination = ReaderDestination.Home,
    val secondary: List<ReaderDestination> = emptyList()
) {
    /** The destination on screen. */
    val current: ReaderDestination get() = secondary.lastOrNull() ?: top

    /** True when Back has somewhere visual to go. */
    val canGoBack: Boolean get() = secondary.isNotEmpty() || top != ReaderDestination.Home
}

/**
 * Which surface the user is looking at, and what Back does from there.
 *
 * **This class deliberately holds no reference to playback.** The UI contract's central navigation rule
 * is "Back reverses visual navigation. It does not reverse playback", and the product contract's is
 * "Navigation does not own playback". Both are enforced here by construction rather than by discipline:
 * there is nothing in this file that *could* pause, stop, or move a listening session, so a future edit
 * cannot accidentally make Back mean Pause. It is a stronger guarantee than a comment asking the next
 * person not to, and it is why this type can be tested with no engine stubbed out.
 *
 * One flow rather than two: a `current` alongside a `state` would be two sources of truth for the same
 * fact, which is the class of bug this whole round exists to remove.
 */
class ReaderNavigator(initial: ReaderNavigation = ReaderNavigation()) {

    private val _state = MutableStateFlow(initial)

    /** The current destination and what is under it. Nothing here mutates playback. */
    val state: StateFlow<ReaderNavigation> = _state.asStateFlow()

    /**
     * Shows a destination.
     *
     * A secondary destination is pushed; a top-level one selects that tab. The split is taken from the
     * destination rather than from the caller, so a caller cannot ask for the Player to become a tab.
     */
    fun open(destination: ReaderDestination) {
        if (destination.isTopLevel) {
            openTopLevel(destination)
            return
        }

        _state.value = _state.value.copy(secondary = _state.value.secondary + destination)
    }

    /**
     * Selects a top-level destination, discarding anything pushed over the previous one.
     *
     * Discarding rather than keeping is the bottom-navigation convention, and the contract's rule that
     * "returning to a top-level screen should not unnecessarily reset the user's context" is satisfied by
     * the *destination's* own state (Browse keeps its search, filters, sort and result position) rather
     * than by navigation retaining a stale Note over an abandoned tab.
     */
    fun openTopLevel(destination: ReaderDestination) {
        _state.value = ReaderNavigation(top = destination, secondary = emptyList())
    }

    /**
     * Performs a visual Back.
     *
     * Returns true when this consumed the press, so the caller must not finish. Returning false means
     * "not mine" and the caller should let the system handle it — the contract's "at top-level
     * destinations, Android system Back follows normal application behavior".
     */
    fun back(): Boolean {
        val state = _state.value

        if (state.secondary.isNotEmpty()) {
            _state.value = state.copy(secondary = state.secondary.dropLast(1))
            return true
        }

        // Product decision (§12.5): Back from a top-level destination other than Home returns to Home
        // rather than finishing the app. The literal reading of "normal application behavior" would
        // finish, but bottom-navigation convention returns to the start destination, and the contract's
        // own framing is that Back "reverses visual navigation".
        if (state.top != ReaderDestination.Home) {
            openTopLevel(ReaderDestination.Home)
            return true
        }

        return false
    }
}
