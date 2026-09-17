package com.example.notionreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun onlyTheTwoContentSurfacesAreTopLevel() {
        // "The Player is not a fifth bottom-navigation destination" and "The Note screen is not a
        // top-level destination" — the shell reads this, so it is asserted rather than assumed.
        assertEquals(listOf(ReaderDestination.Home, ReaderDestination.Browse), ReaderDestination.entries.filter { it.isTopLevel })
        assertFalse(ReaderDestination.Note.isTopLevel)
        assertFalse(ReaderDestination.Player.isTopLevel)
    }
}
