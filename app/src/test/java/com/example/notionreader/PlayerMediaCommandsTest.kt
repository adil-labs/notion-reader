package com.example.notionreader

import androidx.media3.common.Player
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.PlayerCommandAvailability
import com.example.notionreader.player.playerCommandAvailability
import com.example.notionreader.domain.queue.ActiveQueue
import com.example.notionreader.domain.queue.QueueOrigin
import com.example.notionreader.domain.queue.QueueState
import com.example.notionreader.domain.queue.QueuedNote
import com.example.notionreader.player.ReaderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command set as a media session advertises it.
 *
 * ## Why this is worth testing on the JVM
 *
 * The Background Playback specification's central claim about commands is that availability is "evaluated
 * once and published once; transports and controls read it, and never re-derive it". These tests are what
 * make that claim checkable rather than aspirational: they assert that the *same* value the Player's buttons
 * read produces the set of commands a lock screen would offer, so the two cannot disagree.
 *
 * They also pin the two things the mapping must not do — offer a surface action as a transport, and invent a
 * meaning for a platform command this product has no equivalent for.
 *
 * Media3's `Commands` **container** cannot be built in a JVM unit test — `Player.Commands.Builder` uses
 * `android.util.SparseBooleanArray`, which the stubbed `android.jar` refuses — so these tests assert
 * against [mediaCommandSetFor], which is the whole of the logic: which command ints are offered. The
 * `Commands` construction in [mediaCommandsFor] is the mechanical translation of that set and is covered on
 * a device.
 *
 * The `Player.COMMAND_*` constants *are* usable here, because they are compile-time ints. That distinction
 * between the constants and the container is the thing to remember about this file.
 */
class PlayerMediaCommandsTest {

    private fun state(index: Int = 0, isPlaying: Boolean = false, parts: Int = 4) = ReaderState(
        paragraphs = List(parts) { "part $it" },
        currentParagraphIndex = index,
        isPlaying = isPlaying
    )

    private fun queue(size: Int = 3, index: Int = 1) = QueueState(
        queue = ActiveQueue.snapshotOf(
            List(size) { QueuedNote(pageId = "page-$it", title = "Note $it") },
            QueueOrigin.BrowseResults
        ),
        index = index
    )

    private fun availabilityOf(
        state: ReaderState = state(),
        queue: QueueState = queue()
    ) = playerCommandAvailability(state, queue)

    // --- The mapping is the availability, not a second decision ------------------------------------

    @Test
    fun theAdvertisedCommandsAreExactlyTheAvailableTransportCommands() {
        val availability = availabilityOf(state = state(index = 2, isPlaying = true))

        val advertised = mediaCommandSetFor(availability)

        val expected = availability.availableTransports.mapNotNull { mediaCommandFor(it) }.toSet()

        assertEquals(expected, advertised)
    }

    @Test
    fun aCommandThatBecomesUnavailableIsNotAdvertised() {
        // At the last part there is no next part and no next Note.
        val atTheEnd = availabilityOf(state = state(index = 3), queue = queue(size = 1, index = 0))
        val advertised = mediaCommandSetFor(atTheEnd)

        assertFalse("no next part to seek to", advertised.contains(Player.COMMAND_SEEK_FORWARD))
        assertFalse("no next Note to skip to", advertised.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))

        // And the way back is still offered, so the session is not simply inert.
        assertTrue(advertised.contains(Player.COMMAND_SEEK_BACK))
        assertTrue(advertised.contains(Player.COMMAND_SEEK_TO_DEFAULT_POSITION))
    }

    @Test
    fun aQueueThatCannotProceedAdvertisesAlmostNothing() {
        val fetching = playerCommandAvailability(state(), QueueState(queue = queue().queue, awaiting = true))
        val advertised = mediaCommandSetFor(fetching)

        assertFalse("nothing may act on the document while a Note is loading", advertised.contains(Player.COMMAND_SEEK_FORWARD))
        assertFalse(advertised.contains(Player.COMMAND_SEEK_BACK))
        assertFalse(advertised.contains(Player.COMMAND_PLAY_PAUSE))
    }

    // --- The two the mapping must refuse ------------------------------------------------------------

    @Test
    fun theSurfaceActionsHaveNoMediaCommandAtAll() {
        // `Queue` opens a view of the frozen Active Queue, and `More playback controls` opens a surface.
        // Both change what the phone is displaying, which is why they are surface actions rather than
        // transport commands - and a lock screen must not be offered either.
        assertNull(mediaCommandFor(PlayerCommand.OpenQueue))
        assertNull(mediaCommandFor(PlayerCommand.OpenMorePlaybackControls))
    }

    @Test
    fun aSurfaceActionIsAbsentEvenWhenItIsAvailableAsASurfaceAction() {
        // Mid-document and playing, which is the one state where every command is available - so this
        // compares the whole set rather than a subset that happens to match.
        val availability = availabilityOf(state = state(index = 2, isPlaying = true))
        assertTrue("there is a queue to show", availability.canOpenQueue)

        val advertised = mediaCommandSetFor(availability)

        // Available as a surface action, and still not something a transport is offered. `SEEK_TO_MEDIA_ITEM`
        // is present because `JumpToNote` is a *transport* command; there is no command for showing a view.
        assertTrue("JumpToNote is transportable", advertised.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM))

        // Every advertised int is the mapping of a *transport* command, so nothing a surface action could
        // have contributed got in.
        val fromTransportCommands = PlayerCommand.transports.mapNotNull { mediaCommandFor(it) }.toSet()
        assertEquals(fromTransportCommands, advertised)
    }

    @Test
    fun severalPlayerCommandsCanShareOnePlatformCommandAndThatIsNotALoss() {
        // The platform has one play-pause action and this product has three commands that use it, so the
        // eleven transport commands map onto nine platform commands. Recorded as a number rather than left
        // implicit, because "fewer platform commands than Player commands" reads like a bug otherwise - and
        // the reason it is not one is that `TogglePlayback` is defined as exactly `Play` or `Pause`, so the
        // three are one behaviour with three names.
        assertEquals(11, PlayerCommand.transports.size)

        val availability = availabilityOf(state = state(index = 2, isPlaying = true))
        assertEquals(9, mediaCommandSetFor(availability).size)

        assertEquals(mediaCommandFor(PlayerCommand.Play), mediaCommandFor(PlayerCommand.Pause))
        assertEquals(mediaCommandFor(PlayerCommand.Play), mediaCommandFor(PlayerCommand.TogglePlayback))
    }

    @Test
    fun aCommandUnavailableAtThisPositionContributesNoCommandEvenThoughItHasAMapping() {
        // The mapping is total for transport commands, so the *filtering* is what keeps an unavailable
        // command off a lock screen. At the first part there is nothing before it, so the two previous part
        // and section commands map to ints that must not be advertised.
        val atTheStart = availabilityOf(state = state(index = 0, isPlaying = true))
        assertFalse(atTheStart.canMoveToPreviousPart)

        val advertised = mediaCommandSetFor(atTheStart)

        assertTrue("the mapping exists", mediaCommandFor(PlayerCommand.PreviousPart) != null)
        assertFalse("but it is not advertised", advertised.contains(Player.COMMAND_SEEK_BACK))
        assertFalse(advertised.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertEquals("nine mapped minus the two unavailable", 7, advertised.size)
    }

    // --- The platform's commands, translated back --------------------------------------------------

    @Test
    fun everyPlatformCommandAMediaSessionCanSendMapsToARealPlayerCommand() {
        // The complete set of Media3 commands this mapping claims to understand. A platform command that
        // maps to null is one this app does not do, and the test names each so that adding a mapping is a
        // deliberate act rather than a side effect.
        val understood = mapOf(
            Player.COMMAND_PLAY_PAUSE to PlayerCommand.TogglePlayback,
            Player.COMMAND_SEEK_TO_NEXT to PlayerCommand.NextSection,
            Player.COMMAND_SEEK_TO_PREVIOUS to PlayerCommand.PreviousSection,
            Player.COMMAND_SEEK_FORWARD to PlayerCommand.NextPart,
            Player.COMMAND_SEEK_BACK to PlayerCommand.PreviousPart,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION to PlayerCommand.RestartNote,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM to PlayerCommand.NextNote,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM to PlayerCommand.PreviousNote
        )

        for ((mediaCommand, expected) in understood) {
            assertEquals("for platform command $mediaCommand", expected, commandForMediaCommand(mediaCommand))
        }
    }

    @Test
    fun aPlatformCommandThisAppDoesNotDoMapsToNothingRatherThanToSomethingPlausible() {
        // Media3 can deliver commands this product has no equivalent for, and the honest response is to do
        // nothing. An `else` that picked a nearby command would be a transport silently meaning something
        // other than what was asked.
        assertNull(commandForMediaCommand(Player.COMMAND_SET_SPEED_AND_PITCH))
        assertNull(commandForMediaCommand(Player.COMMAND_SET_VOLUME))
        assertNull(commandForMediaCommand(Player.COMMAND_SET_SHUFFLE_MODE))
        assertNull(commandForMediaCommand(Player.COMMAND_STOP))
    }

    /**
     * Every Player command that can survive the trip does.
     *
     * Three genuinely do not, and naming them is the point of this test rather than an exclusion list:
     *
     * - **`Play` and `Pause`** both map to `COMMAND_PLAY_PAUSE`, because that is the one action the platform
     *   has, and it maps back to `TogglePlayback` — which is exactly the equivalence the command set defines.
     *   A round trip is impossible for the two halves of a single action, and the collapse is the design.
     * - **`JumpToNote`** carries an index, and a bare platform command int cannot supply one. Mapping it back
     *   would have to invent a destination, so it deliberately maps to nothing.
     *
     * Everything else must round-trip: a transport that invokes what was advertised has to get the command
     * that was advertised.
     */
    @Test
    fun theMappingIsConsistentInBothDirectionsForEveryCommandThatCanSurviveTheTrip() {
        val collapseToOneAction = setOf(
            PlayerCommand.Play,
            PlayerCommand.Pause,
            PlayerCommand.TogglePlayback,
            PlayerCommand.JumpToNote
        )

        for (command in PlayerCommand.transports) {
            if (command in collapseToOneAction) continue

            val media = mediaCommandFor(command)
            assertEquals(
                "round trip for $command",
                command,
                commandForMediaCommand(media!!)
            )
        }

        // And the two halves of play-pause both land on the one action, which is the equivalence rather
        // than a loss.
        assertEquals(PlayerCommand.TogglePlayback, commandForMediaCommand(Player.COMMAND_PLAY_PAUSE))
        assertEquals(mediaCommandFor(PlayerCommand.Play), mediaCommandFor(PlayerCommand.Pause))
    }
}
