package com.example.notionreader.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation rules, with no engine stubbed out.
 *
 * That absence is the assertion: `ReaderNavigator` cannot reach playback, so these tests do not need a
 * fake to prove Back does not pause — there is nothing it could call. The contract's "Back reverses
 * visual navigation. It does not reverse playback" is therefore structural rather than observed.
 *
 * The cases below are the rows of the Back table in [ReaderNavigation], plus the one that is a product
 * decision rather than a contract rule.
 */
class ReaderNavigatorTest {

    @Test
    fun theAppStartsOnHome() {
        assertEquals(ReaderDestination.Home, ReaderNavigator().state.value.current)
        assertFalse(ReaderNavigator().state.value.canGoBack)
    }

    @Test
    fun aSecondaryDestinationIsPushedOverTheSelectedTab() {
        val navigator = ReaderNavigator()

        navigator.open(ReaderDestination.Note)

        assertEquals(ReaderDestination.Note, navigator.state.value.current)
        assertEquals(ReaderDestination.Home, navigator.state.value.top)
        assertTrue(navigator.state.value.canGoBack)
    }

    @Test
    fun backFromASecondaryReturnsToTheTabUnderIt() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Browse)
        navigator.open(ReaderDestination.Note)

        assertTrue(navigator.back())

        assertEquals(ReaderDestination.Browse, navigator.state.value.current)
        assertEquals(ReaderDestination.Browse, navigator.state.value.top)
        assertTrue(navigator.state.value.secondary.isEmpty())
    }

    @Test
    fun noteThenPlayerThenBackReturnsToTheNote() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Browse)
        navigator.open(ReaderDestination.Note)
        navigator.open(ReaderDestination.Player)

        assertTrue(navigator.back())

        // Two secondaries deep, which is why the model is a list rather than one field: Play is reachable
        // from the Note screen, so "the previous visual context" is not always a tab.
        assertEquals(ReaderDestination.Note, navigator.state.value.current)

        assertTrue(navigator.back())
        assertEquals(ReaderDestination.Browse, navigator.state.value.current)
    }

    @Test
    fun playAllFromBrowseThenBackReturnsToBrowse() {
        val navigator = ReaderNavigator()
        navigator.openTopLevel(ReaderDestination.Browse)

        navigator.open(ReaderDestination.Player)

        assertTrue(navigator.back())
        // The contract's worked example: Browse -> Play all -> Player -> Back -> Browse.
        assertEquals(ReaderDestination.Browse, navigator.state.value.current)
    }

    @Test
    fun backFromBrowseReturnsToHome() {
        val navigator = ReaderNavigator()
        navigator.openTopLevel(ReaderDestination.Browse)

        // Product decision (§12.5): the bottom-navigation convention rather than finishing the app.
        assertTrue(navigator.back())
        assertEquals(ReaderDestination.Home, navigator.state.value.current)
    }

    @Test
    fun backAtHomeIsNotConsumedSoTheSystemCanFinish() {
        val navigator = ReaderNavigator()

        // The contract's "at top-level destinations, Android system Back follows normal application
        // behavior" — which needs this to say "not mine" rather than to swallow the press.
        assertFalse(navigator.back())
        assertEquals(ReaderDestination.Home, navigator.state.value.current)
    }

    @Test
    fun selectingATabDiscardsWhateverIsPushedOverTheOtherTab() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Note)

        navigator.openTopLevel(ReaderDestination.Browse)

        // A stale Note pushed over an abandoned tab is context nobody asked to keep; the tab's *own*
        // state (Browse's query) is what the contract requires to survive.
        assertEquals(ReaderDestination.Browse, navigator.state.value.current)
        assertTrue(navigator.state.value.secondary.isEmpty())
    }

    @Test
    fun openingATopLevelDestinationAsIfItWereSecondarySelectsTheTab() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Note)

        // The split comes from the destination, so a caller cannot ask for the Player to become a tab.
        navigator.open(ReaderDestination.Home)

        assertEquals(ReaderDestination.Home, navigator.state.value.current)
        assertTrue(navigator.state.value.secondary.isEmpty())
    }

    @Test
    fun onlyThePopulatedContentSurfacesAreTopLevel() {
        // "The Player is not a fifth bottom-navigation destination" and "The Note screen is not a
        // top-level destination" — the shell reads this, so it is asserted rather than assumed.
        //
        // Queues joined this list when the Listening tab gave it content. It is the same rule that has
        // kept Settings out: a top-level destination is one a listener can be sent to and find something
        // there, and the UI contract's four destinations are shipped as three while Settings has nothing
        // to show. The list is asserted exactly, rather than by counting, because a destination silently
        // becoming a tab is what this test exists to notice.
        assertEquals(
            listOf(ReaderDestination.Home, ReaderDestination.Browse, ReaderDestination.Queues, ReaderDestination.Settings),
            ReaderDestination.entries.filter { it.isTopLevel }
        )
        assertFalse(ReaderDestination.Note.isTopLevel)
        assertFalse(ReaderDestination.Player.isTopLevel)
        assertFalse(ReaderDestination.SettingsPlayback.isTopLevel)
    }

    @Test
    fun backFromSettingsReturnsHomeRatherThanFinishing() {
        val navigator = ReaderNavigator()
        navigator.openTopLevel(ReaderDestination.Settings)

        assertTrue(navigator.back())
        assertEquals(ReaderDestination.Home, navigator.state.value.current)
    }

    @Test
    fun openingSettingsSubpagePushesSecondaryAndRestoresFocusIdOnBack() {
        val navigator = ReaderNavigator()
        navigator.openTopLevel(ReaderDestination.Settings)

        navigator.open(ReaderDestination.SettingsPlayback, fromFocusId = "settings_row_playback")
        assertEquals(ReaderDestination.SettingsPlayback, navigator.state.value.current)

        assertTrue(navigator.back())
        assertEquals(ReaderDestination.Settings, navigator.state.value.current)
        assertEquals("settings_row_playback", navigator.state.value.focusTarget)
    }

    @Test
    fun backFromQueuesReturnsHomeRatherThanFinishing() {
        // The §12.5 rule applied to the new destination: Back reverses visual navigation, and a bottom
        // navigation destination that exits the application on Back is a surprise.
        val navigator = ReaderNavigator()
        navigator.openTopLevel(ReaderDestination.Queues)

        assertTrue(navigator.back())
        assertEquals(ReaderDestination.Home, navigator.state.value.top)
    }

    // --- Where focus goes when Back returns ---------------------------------------------------------

    @Test
    fun backPutsFocusOnTheElementThatOpenedTheScreenItLeaves() {
        // The UI contract's accessibility rule and its worked example: "Browse result 7 -> Note -> Back
        // should preferably restore focus to result 7". The id has to be recorded when the screen is
        // *pushed*, because the activation is the only moment that fact exists — so this asserts the whole
        // round trip rather than the field.
        val navigator = ReaderNavigator()
        navigator.openTopLevel(ReaderDestination.Browse)

        navigator.open(ReaderDestination.Note, fromFocusId = "page-7")
        assertTrue(navigator.back())

        assertEquals("page-7", navigator.state.value.focusTarget)
        assertEquals(ReaderDestination.Browse, navigator.state.value.current)
    }

    @Test
    fun theFocusHintIsConsumedOnceSoItCannotFireLater() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Note, fromFocusId = "page-7")
        navigator.back()

        navigator.focusHandled()

        // A hint that survived being honoured would pull the listener back to the same row every time the
        // screen recomposed, which is the unpredictable focus movement the contract's rule is about.
        assertNull(navigator.state.value.focusTarget)
    }

    @Test
    fun navigatingForwardDiscardsAStaleFocusHint() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Note, fromFocusId = "page-7")
        navigator.back()
        assertEquals("page-7", navigator.state.value.focusTarget)

        navigator.open(ReaderDestination.Player, fromFocusId = "note:play")

        // Recorded for the screen being left. Left pending, it would fire on the *next* return to that
        // screen, on an activation that had nothing to do with it.
        assertNull(navigator.state.value.focusTarget)
    }

    @Test
    fun selectingATabDiscardsAStaleFocusHint() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Note, fromFocusId = "page-7")
        navigator.back()

        navigator.openTopLevel(ReaderDestination.Queues)

        // The id named a row in Browse's list. Focusing it — or anything — in another tab's list would be
        // worse than focusing nothing.
        assertNull(navigator.state.value.focusTarget)
    }

    @Test
    fun twoScreensDeepReturnFocusOneScreenAtATime() {
        // Browse -> Note -> Play -> Player -> Back -> Back. Each Back restores the element that opened the
        // screen it is leaving, which is why the focus target has to live with the *stop* rather than on
        // the navigator as a whole.
        val navigator = ReaderNavigator()
        navigator.openTopLevel(ReaderDestination.Browse)
        navigator.open(ReaderDestination.Note, fromFocusId = "page-7")
        navigator.open(ReaderDestination.Player, fromFocusId = "note:play")

        navigator.back()
        assertEquals(ReaderDestination.Note, navigator.state.value.current)
        assertEquals("note:play", navigator.state.value.focusTarget)

        navigator.focusHandled()
        navigator.back()
        assertEquals(ReaderDestination.Browse, navigator.state.value.current)
        assertEquals("page-7", navigator.state.value.focusTarget)
    }

    @Test
    fun backWithNothingRecordedLeavesNoFocusHint() {
        val navigator = ReaderNavigator()
        navigator.open(ReaderDestination.Note)

        navigator.back()

        // A route with nothing identifiable to return to says so rather than naming a placeholder, so
        // nothing is focused and the screen's own entry behaviour applies.
        assertNull(navigator.state.value.focusTarget)
    }

    @Test
    fun aRepeatedTripToTheSameRowAsksForFocusAgain() {
        // The hint is consumed each time, so the second visit is a *change* in the navigator's state and
        // the screen's effect re-runs. A hint that were merely "still set" would silently stop working the
        // second time the listener opened and closed the same row.
        val navigator = ReaderNavigator()

        navigator.open(ReaderDestination.Note, fromFocusId = "page-7")
        navigator.back()
        navigator.focusHandled()
        assertNull(navigator.state.value.focusTarget)

        navigator.open(ReaderDestination.Note, fromFocusId = "page-7")
        navigator.back()

        assertEquals("page-7", navigator.state.value.focusTarget)
    }
}
