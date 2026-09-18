package com.example.notionreader

import androidx.media3.common.Player
import com.example.notionreader.player.PlayerCommand
import com.example.notionreader.player.PlayerCommandAvailability

/**
 * The Player's command set, expressed in Media3's vocabulary.
 *
 * ## Why this is a mapping rather than a second decision
 *
 * The Background Playback specification's central claim about commands is that availability is
 * "evaluated once and published once; transports and controls read it, and never re-derive it". This file
 * is where that claim is cashed: it takes the *same* [PlayerCommandAvailability] that
 * `PlayerScreen` reads to enable and disable its buttons and turns it into the `Commands` a `MediaSession`
 * advertises.
 *
 * The consequence is worth stating plainly, because it is the whole reason the two rounds were done in this
 * order: **a lock screen cannot offer an action the Player has disabled, and the Player cannot disable an
 * action the lock screen still offers**, because there is one evaluation and two renderings of it. Before
 * it existed, a media session would have had to re-decide every precondition somewhere else, which is where
 * this project's two-answers-to-one-question defects have come from twice already
 * (`docs/decisions.md` §19 and §22.2).
 *
 * ## Why it is in the root package
 *
 * Media3 is a platform library, so this cannot live in `player` — that layer holds no transport concern and
 * the specification makes "the command layer holds no Android type" an invariant. It needs the session and
 * the command set together, which is what the composition root is for. `LayerRulesTest` allows the root to
 * name everything and forbids `platform` from reaching `application`, and the correct home for an adapter
 * that needs both is here.
 *
 * ## Nothing here is guesswork about what Media3 offers
 *
 * Every command named below is checked against `androidx.media3.common.Player` by the compiler, so a
 * command this Media3 version does not have is a build failure rather than a control that silently never
 * appears.
 */

/**
 * Media3's command for a Player command, or null when there is no honest equivalent.
 *
 * The nulls matter as much as the mappings. `OpenQueue` and `OpenMorePlaybackControls` are **surface
 * actions** rather than transport commands — their effect is to change what the phone is displaying — and
 * returning null for them is what keeps the queue view off a lock screen. That is enforced twice: here, and
 * by `ReaderApp.invoke` refusing them.
 *
 * ## Two mappings are compromises, and both are recorded rather than hidden
 *
 * Media3's model is a playlist of media items with a position inside one, and a Note is the media item. That
 * makes Note-level navigation exact and part/section-level navigation approximate, because the platform has
 * one "next" and one "previous" and this product has two of each:
 *
 * | Player command | Media3 | Exact? |
 * |---|---|---|
 * | `NextNote` / `PreviousNote` | `SEEK_TO_NEXT_MEDIA_ITEM` / `SEEK_TO_PREVIOUS_MEDIA_ITEM` | yes — a Note *is* the item |
 * | `JumpToNote` | `SEEK_TO_MEDIA_ITEM` | yes |
 * | `RestartNote` | `SEEK_TO_DEFAULT_POSITION` | yes — "start the current item again" is exactly this |
 * | `NextSection` / `PreviousSection` | `SEEK_TO_NEXT` / `SEEK_TO_PREVIOUS` | approximate |
 * | `NextPart` / `PreviousPart` | `SEEK_FORWARD` / `SEEK_BACK` | approximate |
 *
 * The approximation is that Media3's transport has no idea what a "section" is, so the four granularity
 * commands share two platform commands. **Only transports are conflated**: the on-screen Player keeps all
 * four as separate, separately-labelled controls, and a transport that invokes `SEEK_FORWARD` gets
 * `NextPart`, which is the finer of the two. A listener who wants sections uses the Player, where the
 * control is named.
 *
 * This is a revision of the mapping the specification first proposed, which had `RestartNote` expressed as
 * a media-item change. `SEEK_TO_DEFAULT_POSITION` is precisely "return to the start of the current item",
 * so it expresses restart without any risk of a transport turning it into a track skip — which was the
 * concern that produced the original, clumsier mapping.
 */
fun mediaCommandFor(command: PlayerCommand): Int? = when (command) {
    PlayerCommand.Play, PlayerCommand.Pause, PlayerCommand.TogglePlayback -> Player.COMMAND_PLAY_PAUSE

    PlayerCommand.NextSection -> Player.COMMAND_SEEK_TO_NEXT
    PlayerCommand.PreviousSection -> Player.COMMAND_SEEK_TO_PREVIOUS

    PlayerCommand.NextPart -> Player.COMMAND_SEEK_FORWARD
    PlayerCommand.PreviousPart -> Player.COMMAND_SEEK_BACK

    PlayerCommand.RestartNote -> Player.COMMAND_SEEK_TO_DEFAULT_POSITION

    PlayerCommand.NextNote -> Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
    PlayerCommand.PreviousNote -> Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
    PlayerCommand.JumpToNote -> Player.COMMAND_SEEK_TO_MEDIA_ITEM

    // Surface actions. Not transportable, by classification and by refusal.
    PlayerCommand.OpenQueue, PlayerCommand.OpenMorePlaybackControls -> null
}

/**
 * The Player commands a transport may invoke now, as Media3 command ints.
 *
 * Built from [PlayerCommandAvailability.availableTransports], which is the same set the specification
 * defines for exactly this purpose — so a command that is unavailable contributes no Media3 command, and a
 * Media3 command appears only because its Player command is available.
 *
 * **This is the whole of the logic, and it is deliberately separate from [mediaCommandsFor]** — see that
 * function for why the two are not one. Everything that can be got wrong about which commands are offered
 * is here, and here is pure: `Player.COMMAND_*` are compile-time int constants, so reading them needs no
 * Android and this runs in a JVM unit test.
 */
fun mediaCommandSetFor(availability: PlayerCommandAvailability): Set<Int> =
    availability.availableTransports.mapNotNullTo(sortedSetOf()) { mediaCommandFor(it) }

/**
 * [mediaCommandSetFor] as the Media3 `Commands` a `MediaSession` is given.
 *
 * ## Why this is a separate, Android-only function
 *
 * **`Player.Commands.Builder` cannot be constructed in a JVM unit test.** It uses
 * `android.util.SparseBooleanArray`, and the stubbed `android.jar` that unit tests compile against throws
 * `RuntimeException: Method append in android.util.SparseBooleanArray not mocked` from the builder's static
 * initializer — so the class fails to initialize and every call after that reports
 * `NoClassDefFoundError: Could not initialize class ...Player$Commands$Builder`.
 *
 * That was found by writing the test rather than assumed, and it corrected a claim this file previously
 * made: that Media3's types are pure JVM types throughout. They are not — the *constants* are, the
 * *container* is not. The response is not to stop testing it but to put the boundary in the right place:
 * the set of commands is pure and fully tested, and this function is the mechanical translation of it, so
 * there is nothing here left to get wrong.
 *
 * The alternative — `testOptions.unitTests.isReturnDefaultValues = true` — was rejected: it would make the
 * builder silently succeed while doing nothing, which turns a loud failure into a quiet one. That is the
 * exact failure shape this project has spent several rounds learning to distrust.
 *
 * ## There is no second level to get wrong here
 *
 * `Player.Commands` in this Media3 version is a flat set of ints (`add`, `contains`, `get`, `size`). The
 * play/pause granularity that older APIs expressed with sub-commands is expressed instead by the state the
 * player reports: a controller decides which half of play-pause to offer from `playWhenReady` and
 * `PlaybackState`, and `SimpleBasePlayer` itself rejects `play()` when `COMMAND_PLAY_PAUSE` is absent.
 * `TogglePlayback` handles both directions, so advertising `COMMAND_PLAY_PAUSE` is the whole of what this
 * app can say about play and pause.
 */
fun mediaCommandsFor(availability: PlayerCommandAvailability): Player.Commands {
    val builder = Player.Commands.Builder()
    mediaCommandSetFor(availability).forEach(builder::add)
    return builder.build()
}

/**
 * The Player command a Media3 command means, or null when the platform asked for something this app does
 * not do.
 *
 * The null is the interesting case and it is deliberate: Media3 can deliver commands this product has no
 * equivalent for — a `COMMAND_SET_SPEED` from a client that decided to offer one, a `COMMAND_SEEK_TO_ITEM`
 * for a playlist position that does not exist — and the honest response is to do nothing. A `when` with an
 * `else` that picked a plausible command would be a transport silently meaning something other than what
 * was asked.
 */
fun commandForMediaCommand(mediaCommand: Int): PlayerCommand? = when (mediaCommand) {
    Player.COMMAND_PLAY_PAUSE -> PlayerCommand.TogglePlayback

    Player.COMMAND_SEEK_TO_NEXT -> PlayerCommand.NextSection
    Player.COMMAND_SEEK_TO_PREVIOUS -> PlayerCommand.PreviousSection

    Player.COMMAND_SEEK_FORWARD -> PlayerCommand.NextPart
    Player.COMMAND_SEEK_BACK -> PlayerCommand.PreviousPart

    Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> PlayerCommand.RestartNote

    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> PlayerCommand.NextNote
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> PlayerCommand.PreviousNote

    else -> null
}
