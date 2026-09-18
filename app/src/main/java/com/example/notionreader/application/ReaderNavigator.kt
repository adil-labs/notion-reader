package com.example.notionreader.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One pushed screen, and the element the user activated to push it.
 *
 * The second field is what makes the UI contract's focus rule implementable: "When returning from a
 * secondary destination, restore the previous meaningful focus target when it still exists", whose worked
 * example is `Browse result 7 → Note → Back` landing back on result 7. Recording *who* opened a screen is
 * the only way Back can know where to put focus, and it has to be recorded at push time because the
 * activation is the one moment that fact exists.
 *
 * It is an identifier and not a focus object: this class must not hold a Compose `FocusRequester`, and it
 * must not know which screen the id belongs to. `docs/decisions.md` §21 records why the id is a plain
 * string — the destination that receives it is the one that can resolve it, and it silently ignores ids
 * that are not its own.
 */
data class ReaderStop(
    val destination: ReaderDestination,
    /** The element that was activated to open [destination], or null when nothing identifiable was. */
    val fromFocusId: String? = null,
    /**
     * What this screen is *about*, when the destination has to be told.
     *
     * ## Why this is separate from [fromFocusId]
     *
     * The two coincide on the route that exists today — a Saved Queue row is identified by its queue, so
     * activating one pushes Details about that same queue and would return focus to it — and they must not
     * therefore be one field, because the routes that separate them are already named by the contract.
     * Home's *Your queues* opens Details **about a queue** while the element to return focus to is the
     * *Home row*; and the Queue Destination's `Create new queue` pushes a screen about nothing at all with
     * focus returning to the control that was pressed. So one is "what is this screen about" and the other
     * is "where does focus go when this is popped", and a single field would have to mean both badly.
     *
     * It is an identity and not a value: a stop naming a queue by name would be stale the moment the queue
     * was renamed, which is the same distinction `QueueOrigin` and `SavedQueue` both draw between an
     * identity and a label.
     */
    val subjectId: String? = null
)

/**
 * Where the user is: a selected top-level destination, whatever is pushed on top of it, and where focus
 * should land when Back removes one of those.
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
    val secondary: List<ReaderStop> = emptyList(),
    /**
     * The element that should receive focus on the screen Back just returned to, or null.
     *
     * Set by [ReaderNavigator.back] from the popped stop and cleared by [ReaderNavigator.focusHandled],
     * which is what stops it firing more than once. It is a *one-shot hint for the destination below*,
     * not a property of any screen: nothing else may read it, and a top-level switch clears it because a
     * target recorded for one tab's list means nothing in another tab's.
     */
    val focusTarget: String? = null
) {
    /** The destination on screen. */
    val current: ReaderDestination get() = secondary.lastOrNull()?.destination ?: top

    /**
     * What the screen on screen is about, when it needed telling — the queue Saved Queue Details shows.
     *
     * Read from the stop rather than kept in a field of its own, so there is exactly one place that knows
     * which queue is open. A parallel field would be a second source of truth for the same fact, and the
     * two would disagree the first time one was written and the other was not — which is what the whole of
     * this class exists to avoid.
     *
     * Null for every destination that needs nothing told to it, which is all of them but Saved Queue
     * Details.
     */
    val currentSubjectId: String? get() = secondary.lastOrNull()?.subjectId

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
     *
     * [fromFocusId] names the element the user activated to get here. It is optional because not every
     * route has one — a destination reached by a tab, or by a set-level action, has nothing to return
     * focus to — and passing null is the honest answer rather than a placeholder id.
     */
    fun open(destination: ReaderDestination, fromFocusId: String? = null, subjectId: String? = null) {
        if (destination.isTopLevel) {
            openTopLevel(destination)
            return
        }

        _state.value = _state.value.copy(
            secondary = _state.value.secondary + ReaderStop(destination, fromFocusId, subjectId),
            // Navigating forward invalidates any target recorded for the screen being left. Without
            // this, a target that was never consumed would fire on the *next* return to that screen.
            focusTarget = null
        )
    }

    /**
     * Selects a top-level destination, discarding anything pushed over the previous one.
     *
     * Discarding rather than keeping is the bottom-navigation convention, and the contract's rule that
     * "returning to a top-level screen should not unnecessarily reset the user's context" is satisfied by
     * the *destination's* own state (Browse keeps its search, filters, sort and result position) rather
     * than by navigation retaining a stale Note over an abandoned tab.
     *
     * A fresh value rather than a `copy`, so the focus target is cleared with the secondaries: it was
     * recorded for a list in the tab being left, and focusing an unrelated row in the tab being entered
     * would be worse than focusing nothing.
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
     *
     * Popping a secondary also surfaces the element that opened it, so the screen underneath can put
     * focus back where the user left it. That is the whole of the focus mechanism on this side: this class
     * decides *which id* and nothing about how it is applied.
     */
    fun back(): Boolean {
        val state = _state.value
        val popped = state.secondary.lastOrNull()

        if (popped != null) {
            _state.value = state.copy(
                secondary = state.secondary.dropLast(1),
                focusTarget = popped.fromFocusId
            )
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

    /**
     * The destination has put focus where the user left it.
     *
     * Called by whichever screen consumed the target, including when it could not honour it — a row that
     * is no longer composed, say. Clearing on an unhonoured target is deliberate: the alternative is a
     * hint that stays pending until some unrelated event fires it, which is the "guess the user's
     * intent" behaviour the contract's focus rules exist to prevent.
     */
    fun focusHandled() {
        if (_state.value.focusTarget == null) return
        _state.value = _state.value.copy(focusTarget = null)
    }
}
