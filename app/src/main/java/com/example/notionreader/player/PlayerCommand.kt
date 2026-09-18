package com.example.notionreader.player

import com.example.notionreader.domain.queue.QueueState

/**
 * A named instruction to playback.
 *
 * ## Why commands rather than buttons
 *
 * A button is a rendering of a command. The Product & Architecture Contract requires the command to exist
 * without the button — "Core Player commands must be representable independently of individual UI widgets so
 * Android media controls, accessibility actions, hardware controls, and future voice control can invoke the
 * same behavior" — and the Core Player & TTS Playback spec defines this set as closed.
 *
 * Before this existed, every Player action was a lambda passed into a composable, so the only thing that
 * could invoke one was the button rendered with it. Nothing else could reach playback, and there was no
 * list of what a Player command even was. That is not a theoretical gap: it is the reason a media session
 * could not have been added without re-deciding every one of these behaviours somewhere else, which is
 * where the two-answers-to-one-question bugs this project has already shipped twice would come from.
 *
 * ## The set is closed
 *
 * A transport maps onto these; it may not add, rename or redefine one. If a transport needs a command that
 * is not here, the specification is amended first.
 *
 * [isTransport] is the classification the spec calls the whole point of the split. A transport command is
 * meaningful with no screen at all; a surface action is meaningful only while the Player is on screen.
 * [OpenQueue] is the one that matters: the UI contract names `Queue` as one of the four primary Player
 * controls, and it is deliberately **not** a transport command, because its effect is to change what the
 * phone is displaying. A lock screen must not offer it, and this flag is what stops one being put there.
 */
enum class PlayerCommand(
    /** Whether this command is meaningful with no screen — see the class comment. */
    val isTransport: Boolean = true
) {

    /** Speaks the current part from its beginning. Clears the end-of-note state. */
    Play,

    /** Stops the current utterance and keeps the reading position. */
    Pause,

    /**
     * Pauses when playing and plays when not.
     *
     * Named rather than left implicit because a headset button and a media session's single play-pause
     * action genuinely send one action rather than a choice of two. It is defined as *exactly* [Play] or
     * [Pause] so it cannot drift from the two it stands for.
     */
    TogglePlayback,

    NextSection,
    PreviousSection,

    /** One speakable part forward or back. Never crosses a Note boundary — that is [NextNote]. */
    NextPart,
    PreviousPart,

    /**
     * Moves to the first part of the current Note, preserving the playing state: speaking from there while
     * playing, moving silently while paused.
     *
     * Unavailable once the queue has completed, because completion is a fact the Active Queue & Reader
     * Session spec owns and restarting would produce a session that is completed and playing at once.
     */
    RestartNote,

    NextNote,
    PreviousNote,

    /**
     * Makes the Note at a given index current, preserving the playing state.
     *
     * The one command that carries an argument, so it is the one whose availability is a property of the
     * *queue* rather than of a command instance. Selecting the Note that is already current is a no-op and
     * not a transition.
     */
    JumpToNote,

    /** Opens the Player's read-only view of the frozen Active Queue. A surface action, not a command. */
    OpenQueue(isTransport = false),

    /** Opens the secondary controls. A surface action, not a command. */
    OpenMorePlaybackControls(isTransport = false);

    /** The commands a transport may offer. */
    companion object {
        val transports: List<PlayerCommand> = entries.filter { it.isTransport }

        /** The commands offered by a surface rather than a transport. */
        val surfaceActions: List<PlayerCommand> = entries.filterNot { it.isTransport }
    }
}

/**
 * Which commands may execute now — the single published answer.
 *
 * ## Why this is one value rather than a predicate per control
 *
 * The specification's rule is that "every surface that offers a command reads that command's availability
 * from the same published value". The purpose is that "can I do this?" has exactly one answer in the
 * application.
 *
 * This project has twice shipped a defect whose shape was two components answering one question from
 * different state — the queue loader and the queue observer (`docs/decisions.md` §19), and again `awaiting`
 * against `pendingRequest` (§22.2) — and both were fixed by making the answer a single fact rather than by
 * adding a guard. A Player button's `enabled` and a media session's advertised commands are the same
 * question, so they read the same value: Media3's `SimpleBasePlayer.setAvailableCommands(Commands…)` is
 * where this maps next.
 *
 * ## Availability is not permission
 *
 * A transport may act on a stale value — a headset button pressed at the instant the queue completed — so
 * [com.example.notionreader.application.ReaderApp.invoke] re-checks before dispatching and rejects a
 * command that has become unavailable. Rejection is neither an error nor an event: it changes nothing and
 * announces nothing.
 */
data class PlayerCommandAvailability(
    /**
     * Whether a part can be spoken now: a document is loaded, the current Note is not being fetched, the
     * queue is not stalled, and the queue has not completed.
     *
     * There is deliberately no `queue != null` term. This describes the state the Player is *rendering*, and
     * a null queue is already expressed by the queue-level commands below being unavailable. Adding a term
     * for a condition the Player is unreachable in would make this disagree with a screen rendered in
     * isolation, which is how this project's instrumented tests render it.
     */
    val canPlay: Boolean,
    val canPause: Boolean,
    val canMoveToPreviousSection: Boolean,
    val canMoveToNextSection: Boolean,
    val canMoveToPreviousPart: Boolean,
    val canMoveToNextPart: Boolean,
    val canRestartNote: Boolean,
    val canMoveToPreviousNote: Boolean,
    val canMoveToNextNote: Boolean,
    val canJumpToNote: Boolean,
    val canOpenQueue: Boolean,
    val canOpenMorePlaybackControls: Boolean
) {

    /** Whether [command] may execute. The one place a command is matched to its availability. */
    fun allows(command: PlayerCommand): Boolean = when (command) {
        PlayerCommand.Play -> canPlay
        PlayerCommand.Pause -> canPause
        // Either half being possible is what makes the single action possible.
        PlayerCommand.TogglePlayback -> canPlay || canPause
        PlayerCommand.PreviousSection -> canMoveToPreviousSection
        PlayerCommand.NextSection -> canMoveToNextSection
        PlayerCommand.PreviousPart -> canMoveToPreviousPart
        PlayerCommand.NextPart -> canMoveToNextPart
        PlayerCommand.RestartNote -> canRestartNote
        PlayerCommand.PreviousNote -> canMoveToPreviousNote
        PlayerCommand.NextNote -> canMoveToNextNote
        PlayerCommand.JumpToNote -> canJumpToNote
        PlayerCommand.OpenQueue -> canOpenQueue
        PlayerCommand.OpenMorePlaybackControls -> canOpenMorePlaybackControls
    }

    /**
     * The transport commands that may execute now.
     *
     * The set a media session advertises, so that a lock screen cannot offer an action the Player has
     * disabled and neither can disagree with the other.
     */
    val availableTransports: Set<PlayerCommand>
        get() = PlayerCommand.transports.filterTo(mutableSetOf()) { allows(it) }
}

/**
 * The availability of every command, from the two published states.
 *
 * A free function rather than a method on either state type, because the answer is a function of *both*:
 * `ReaderState` describes the document being read and `QueueState` describes where the session is in its
 * queue, and neither can derive the other. Keeping it here, beside the command set, is what stops a second
 * derivation appearing in a surface or a transport.
 */
fun playerCommandAvailability(state: ReaderState, queue: QueueState): PlayerCommandAvailability {
    // "The queue cannot proceed right now": a Note is being fetched, or it failed and the session is
    // waiting for Retry. While either is true the engine is still holding the *previous* Note, so every
    // control that would act on "the current document" would act on a Note the session has left.
    val blocked = queue.awaiting || queue.isStalled

    // A document to read at all. Empty means the Note yielded no speakable parts, which the loader reports
    // as UNSUPPORTED_DOCUMENT_STRUCTURE rather than letting an inert Play look enabled.
    val hasDocument = state.paragraphs.isNotEmpty()

    val ready = hasDocument && !blocked && !queue.completed

    return PlayerCommandAvailability(
        canPlay = ready,
        canPause = state.isPlaying,
        canMoveToPreviousSection = ready && !state.isAtFirstParagraph,
        canMoveToNextSection = ready && !state.isAtLastParagraph,
        canMoveToPreviousPart = ready && !state.isAtFirstParagraph,
        canMoveToNextPart = ready && !state.isAtLastParagraph,
        canRestartNote = ready,
        // Ordered before `blocked` on purpose: these stay available while the queue is *stalled*, so that a
        // Note which will not load does not cost the listener every Note after it. The spec's "it is never
        // skipped" is about the application never advancing past a failure on its own, which is not this.
        canMoveToPreviousNote = queue.hasPrevious && !queue.awaiting,
        canMoveToNextNote = queue.hasNext && !queue.awaiting,
        canJumpToNote = queue.items.isNotEmpty() && !queue.awaiting,
        // A surface action, so its availability is about whether there is a queue to show rather than about
        // whether playback can proceed.
        canOpenQueue = queue.items.isNotEmpty(),
        // Derived, so the entry that leads to the secondary surface is never a control that opens nothing.
        canOpenMorePlaybackControls = ready && (!state.isAtFirstParagraph || !state.isAtLastParagraph)
    )
}
