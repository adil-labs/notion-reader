<!--
  GENERATED FILE - do not edit by hand.
  Exported from Notion by tools/export-notion-doc.ps1.
  Source page: Notion Reader — Core Player & TTS Playback Spec
  Page id:     3dfc9345-e10a-814e-b9c7-e5e8b4268669
  Notion URL:  https://app.notion.com/p/Notion-Reader-Core-Player-TTS-Playback-Spec-3dfc9345e10a814eb9c7e5e8b4268669
  Notion last edited: 09/18/2026 15:17:00
  Notion is authoritative. Re-run the exporter after editing there.
-->

# Notion Reader — Core Player & TTS Playback Spec

> Governing document, mirrored from Notion. See the header above for provenance.

> Governing implementation specification. Settles the Player's command vocabulary, the secondary playback controls, and what is spoken when playback starts, moves and stops. The Background Playback, Media Controls & Hands-Free Commands specification consumes the command set defined here.

# Summary

This specification defines what a Player command is, which commands exist, how a command is known to be available, and what the listener hears as playback proceeds.

It exists for three reasons.

The Product & Architecture Contract requires that "Core Player commands must be representable independently of individual UI widgets so Android media controls, accessibility actions, hardware controls, and future voice control can invoke the same behavior." Today they are not: every Player action is a lambda passed into a composable, so the only thing that can invoke one is the button that was rendered with it. Nothing else in the application — no media session, no accessibility action, no hardware key — can reach them, and there is no list of what a Player command even is.

The UI Screens & Navigation Contract names four primary Player controls and permits more: "Fine-grained chunk navigation and advanced commands may exist as secondary actions", and the Product & Architecture Contract is more specific still — "Advanced operations such as fine-grained chunk navigation, restart behavior, debugging information, or specialized content handling may be exposed contextually or through secondary actions." Neither the chunk-navigation commands nor restart behavior exist in the Player, although the engine has implemented both since Phase 3 and playFromStart() has never had a production caller.

And background playback is about to be built. A media session, a lock-screen control and a headset button all act on a Player that may not be on screen, and a document that defines them without first fixing the command vocabulary would define a second one — the exact failure the Product & Architecture Contract's conflict protocol exists to prevent.

# Goals
- Define a closed, named set of Player commands, so a new transport maps onto existing behavior rather than inventing a parallel one.
- Make command availability a single published fact, so a disabled button and an unavailable media action cannot disagree about whether an action is possible.
- Define what the reader speaks, and when, so that a listener with the screen off hears a complete account of what happened.
- Expose the secondary controls the contracts already permit, with an interaction that does not turn the Player into a control panel.
- State honestly which labels the current Player uses that its own state cannot support, and correct them once, in the layer that owns Player vocabulary.
- Keep the whole command layer synchronous and Android-free, so its behavior is testable without a device, a dispatcher or a TTS engine.

# Non-goals

This specification does not:
- define how a media session, a foreground service, audio focus or lock-screen controls are implemented — that is the Background Playback, Media Controls & Hands-Free Commands specification, which consumes this one;
- define queue policy: what a queue contains, when it advances, how a failed Note behaves, or what completion means — those belong to the Product & Architecture Contract and the Active Queue & Reader Session spec;
- define resume policy or the announcements an inexact restore produces — the Active Queue & Reader Session spec owns those, and this specification consumes them;
- define how Notion content is fetched, normalized or chunked, or what a chunk is — the Notion Integration Implementation Spec owns that;
- define screen layout, visual design, or which control appears in which row beyond the constraint that the primary set stays small;
- define a playback speed, pitch, sleep timer or voice-command grammar. No governing document names any of them, and this specification does not invent them.

# Ownership boundary

## This specification owns
- the Player command vocabulary: which commands exist, what each one does, and what each one requires to be available;
- which commands are transportable and which are surface-only;
- the secondary controls: fine-grained navigation within a Note, and restart behavior;
- what playback announces and when, including the end of a queue;
- the TTS-side playback contract: utterance granularity, the meaning of Pause, and how a failed or late utterance is handled;
- the Player's own vocabulary in the accessibility tree: control names, disabled-not-inert, and where the secondary controls live;
- the progress line's unit of measurement.

## This specification does not own
- The queue. Which Note is current, when the session advances, and what happens when a Note will not load all belong to the Active Queue & Reader Session spec. This specification says what the Player's Next note command does to the transport; it does not redefine when a session advances.
- Resume. The four resolution outcomes and the announcements they require are the Active Queue & Reader Session spec's. The Player consumes a resume decision and must not add an announcement of its own on top of it.
- The four named primary controls. Previous section, Play / Pause, Next section and Queue are named by the UI Screens & Navigation Contract. This specification defines their behavior, not their existence, their label or their placement.
- Content. What a chunk contains, and the fact that a Note has no title chunk, belong to the Notion Integration Implementation Spec (decision D9).
- The session's start verbs. Play and Continue at the Note, Browse and queue level — "Play starts a new listening session; Continue resumes one" — are the Product & Architecture Contract's, and see the disambiguation under Play below, because the Player's transport control shares a word with them.

## Why the command vocabulary is a document and not a class

The command set is the interface between playback and every transport that will ever drive it. A media session, a headset button, an accessibility action and a future voice command all have to agree about what can be asked of playback and what happens when it is asked. Written as a class the agreement is implicit and each transport re-derives it; written as a specification it is one list that a reviewer can hold against every transport added later.

The secondary controls are also the one place in this application where a document is genuinely load-bearing: the Product & Architecture Contract explicitly permits them but does not define them, and the UI contract defers them here. Implementing them without this document would be inventing their semantics.

# Definitions
- Command — a named instruction to playback, with a defined precondition, a defined effect, and a defined availability. Commands are the only vocabulary a transport has.
- Transport — anything that invokes a command other than the Player's own on-screen controls: a media session, a lock-screen control, a headset button, an accessibility action, a future voice command, a widget.
- Part — one speakable unit of a Note: the unit playback advances through, and the unit the Player's progress line counts. The integration layer calls this a chunk. "Part" is the listener-facing word for it, used only in the progress line and in the two fine-grained navigation control names.
- Availability — whether a command may execute now. A command whose availability is false is rejected when invoked.
- Announcement — text spoken by the reader's own TTS engine, as distinct from content, and as distinct from a live region.
- Transport command — a command meaningful with no screen at all.
- Surface action — an action meaningful only while the Player is on screen.

# The command surface

## Why commands rather than buttons

A button is a rendering of a command. The contract requires the command to exist without the button, because a lock screen has no composable, a headset button has no surface, and an accessibility action is not a touch. If the only way to invoke playback is to press a control that was composed, then every transport added later has to reimplement the behavior and re-decide its preconditions — and the two decisions will drift, because nothing makes them the same decision.

This repository has already been bitten by exactly that shape of drift once. Next note while paused started speaking, while Next section while paused correctly only moved the position. One rule for sections and another for Notes was never a decision; it was two implementations of "move, then decide whether to speak". See docs/decisions.md §22.3.

## The closed command set

Transport commands — invokable with no screen, and the complete set a transport may offer:
1. Play
1. Pause
1. TogglePlayback
1. NextSection
1. PreviousSection
1. NextPart
1. PreviousPart
1. RestartNote
1. NextNote
1. PreviousNote
1. JumpToNote

Player surface actions — meaningful only with the Player on screen, and deliberately not transport commands:
1. OpenQueue — the Queue control, which opens a read-only view of the frozen Active Queue. A lock screen must not offer a control whose effect is to change what the phone is displaying, and the UI contract's rule is that it is a view of the current session rather than an operation on it.
1. OpenMorePlaybackControls — the entry to the secondary surface described under Accessibility.

Screen navigation — Back and the destinations themselves — is the UI Screens & Navigation Contract's, and the Player adds nothing to it. In particular no transport command may be mapped onto Back, or onto a gesture.

## The three control classes, and why the split is the point

The distinction between a transport command and a surface action is not bookkeeping. It is the rule that keeps implementation detail off a lock screen and keeps the command list small enough to be a contract. Queue is named by the UI contract as one of the four primary Player controls; it is not a command, and the reasoning is stated here so that the Background Playback specification can consume the list without re-deriving it.

## Command availability

Availability is published as one value, alongside playback state, and every surface reads it from there.

```plain text
PlayerCommandAvailability(
    isPlaying,
    canPlay,
    canPause,
    canMoveToPreviousSection,
    canMoveToNextSection,
    canMoveToPreviousPart,
    canMoveToNextPart,
    canRestartNote,
    canMoveToPreviousNote,
    canMoveToNextNote,
    canJumpToNote
)
```

The rule, stated normatively:

> Every surface that offers a command reads that command's availability from the same published value. A control is disabled exactly when its command is unavailable, and a transport advertises exactly the commands whose availability is true.

The purpose is that "can I do this?" has one answer in the application. This repository has twice shipped a defect whose shape was two components answering one question from different state — the queue loader and the queue observer (docs/decisions.md §19), and again awaiting against pendingRequest (§22.2). Both were fixed by making the answer a single published fact rather than by adding a guard.

Availability is not permission. A transport may act on stale availability — a headset button pressed at the instant the queue completed — so the command layer re-checks its own precondition when invoked and rejects a command that has become unavailable.

## Rejection is not silence, and rejection is not an error

A command invoked while unavailable:
- does not execute;
- changes no state, including the reading position and the playing state;
- produces no announcement, and is not reported as a failure.

A stale invocation is an ordinary race between a transport and playback, not a fault. Announcing it would put noise into the listening experience to describe something the listener did not perceive, and reporting it as an error would invite a retry of something that was never possible.

# Command semantics

Every command below preserves the playing state unless stated otherwise. This is the rule the UI contract's "navigation does not own playback" and this project's established section behavior both express: a command issued while playing continues reading from the new position, and a command issued while paused moves the position and stays silent.

## Play

Availability — a Note's document is loaded, the current Note is not being fetched, the queue is not stalled, and the queue has not completed.

Effect — speaks the current part from its beginning, and clears the end-of-note state.

Whether the current part is re-read from its start is not an implementation convenience: Android TTS has no reliable pause and resume, so a resumed utterance cannot begin where the previous one stopped. Re-reading one part is the smallest truthful resumption, and it is already the shipped behavior.

The disambiguation this command needs. The Product & Architecture Contract states "Play must not mean resume", and that is about the session verbs: Play from a Note, a Browse result set or a queue starts a new listening session, and Continue resumes one. The Player's transport Play is a different thing with an unfortunate name — it is the second half of the primary Play / Pause control the UI contract names, and it continues reading within a session that already exists. It can never start a session, because starting one is not a transport command at all. A transport must not map its play control onto the session-level Play, and the Background Playback specification inherits this distinction.

## Pause

Availability — playing.

Effect — stops the current utterance and keeps the reading position. No offset inside the interrupted part is stored; the position remains the part.

## TogglePlayback

Availability — Play or Pause is available.

Effect — defined as exactly Pause when playing and exactly Play when not.

It exists because a headset button and a media session's single play-pause action genuinely send one action rather than a choice of two, and refusing to name it would make every transport invent the same branch. Making it an explicit equivalence rather than a third behavior is what stops it drifting from the two it stands for.

## NextSection and PreviousSection

Availability — a document is loaded and the queue is not being fetched, stalled or completed. NextSection is unavailable at the last part; PreviousSection is unavailable at the first part.

NextSection — moves to the first part of the following section; when already in the final section, moves to the last part of the document. It never wraps.

Landing on the next section's first part rather than its second is deliberate and unchanged: a heading is itself a speakable part, so "next section" should begin by reading that heading.

PreviousSection — moves to the start of the current section; when already at that start, moves to the start of the previous section. One press re-reads the current section from its heading, two presses step back a section. That is what makes the control usable while reading rather than only while stationary.

With no outline — a document whose sections are empty degrades both commands to one part forward or back, so neither becomes a dead control on a document that has no headings.

## NextPart and PreviousPart — secondary

Availability — a document is loaded and the queue is not being fetched, stalled or completed. PreviousPart is unavailable at the first part; NextPart at the last.

Effect — moves exactly one part forward or back.

A part command never crosses a Note boundary. At the end of a Note, NextPart is unavailable rather than loading the next Note. Crossing Notes is NextNote, which announces the new Note's title, and a part command that silently did it too would make the two controls mean the same thing in the one case where the listener most needs to tell them apart.

These two commands exist because the Product & Architecture Contract names "fine-grained chunk navigation" as an operation that "may be exposed contextually or through secondary actions". The engine has implemented both since Phase 3; neither was reachable from any surface.

## RestartNote — secondary

Availability — a document is loaded for the current Note, the queue is not being fetched or stalled, and the queue has not completed.

Effect — moves to the first part of the current Note, preserving the playing state: while playing it begins speaking from there, while paused it moves and stays silent. It does not restart the queue, does not start a new listening session, and does not change queue membership or position.

Why it is unavailable once the queue has completed. The Active Queue & Reader Session spec makes completion a fact: reaching the end of the final Note "marks the session completed and stops automatic progression", and "it does not... implicitly start anything". A restart that left a session both completed and playing would be a state the session model does not describe, and inventing it here would redefine a fact that spec owns. Replay of a completed session is defined there and is reachable from Queues → Listening → Recently completed, which the UI contract also defines. Whether a completed queue may additionally be replayed from the Player is a queue-level action rather than a transport command, and belongs to the Queue Creation & Queue Management specification.

## NextNote and PreviousNote

Availability — the queue has more than one Note, the queue is not being fetched, and a Note exists in that direction. Both remain available while the queue is stalled on a Note that will not load.

Effect — moves the session to the Note before or after the current one, preserving the playing state. Loading that Note is the session's business, not the Player's; the command states the intent and the transition announces the new Note's title when it plays.

The stalled-state availability is deliberate and is not a contract breach. The Active Queue & Reader Session spec's rule is that a Note which cannot be loaded "is never skipped" — about the application never advancing past a failed Note on its own. This is a listener pressing a control that says what it will do, on a queue whose membership has not changed. Without it, one unreadable page would cost the listener every Note after it.

## JumpToNote

Availability — the queue is not being fetched, and the requested index is a valid position in the queue.

Effect — makes the Note at that index the current Note, preserving the playing state.

Jumping to the Note that is already current is a no-op. It is not a transition: nothing is fetched, nothing is announced, and the position is not reset. Treating it as a transition rewound the current Note when a listener selected the row they were already on.

This command is what the Queue view's rows invoke. It is a transport command rather than a surface action because "make the fifth Note current" is meaningful without a screen, even though the only surface that currently offers it is the queue view.

## Commands the Player deliberately does not have

Defining the command set means defining what is outside it, so that a transport does not add one by mapping something else onto it:
- No Stop. Pausing keeps the position; stopping implies discarding it, and discarding a position is not a capability this product defines. A transport that can only express a stop must map it to Pause. The UI contract's Back prohibition names Stop for the same reason: it is not a state the user can be put into by an ambiguous gesture.
- No separate track-skip vocabulary. A headset's next-track button maps to NextNote, not to a second command with the same effect. Which physical control maps to which command is the Background Playback specification's decision; the set of commands is fixed here.
- No queue editing. Nothing in this specification adds, removes or reorders a Note, and no transport may be given such a command. The Product & Architecture Contract's model is unambiguous that Active Queues are played, not edited.
- No speed, pitch, sleep timer or voice grammar. None is named by a governing document.
- No accessibility-only command. A command that exists only as an accessibility action is a control the listener cannot reach by any other means, which is the same defect as a gesture-only control.

# The speech contract

## One utterance per part

Playback speaks exactly one part per utterance, and advances when that utterance completes. This is what makes progress reportable at all: a part boundary is both the resume granularity and the point at which the position is captured.

## Why Pause is a stop and Play re-reads the part

Android TTS has no reliable pause and resume. A pause that discarded the position would lose the listener's place; a resume that guessed an offset into an utterance it had cancelled would be inventing one. So Pause stops the utterance and keeps the position at the part, and Play speaks that part from its beginning. The cost is one repeated part. The alternative costs an unrecoverable position.

## A failed utterance is reported, never skipped

If the speech engine fails to speak a part, playback stops and the failure is surfaced. It does not advance to the next part.

This is the same rule the Active Queue & Reader Session spec applies to a Note that will not load, applied one level down: silently skipping content the listener asked to hear is the one thing a reading application must not do, because the listener cannot tell skipping from content that was never there. In particular a device with no usable TTS voice is reported once, rather than producing a silent session that looks like a working one.

## A late completion callback is discarded

Completion callbacks are matched against the utterance that issued them. Any operation that cancels an utterance — pause, a move, restarting, replacing the document, shutting the engine down — invalidates that utterance's callback, so a late one cannot move the position.

Without this, a NextSection issued as an utterance ended could advance twice, or a paused reader could jump forward when the utterance it cancelled reported completion. The behavior is already implemented and tested; it is restated here because it is part of what a command means rather than an implementation detail of one engine.

## Announcements are spoken by the reader, not only exposed as live regions

An announcement that is required for the listener to understand what happened must be spoken by the reader's own TTS engine. A live region is an additional exposure of the same fact, used when a screen reader is active, and must never be the only mechanism.

The reason is the listening mode this whole product exists for. With the screen off and no screen reader running, there is no live region being read to anyone: the reader's TTS is the listener's only channel. A reader that relies on a live region for "end of queue" says nothing at all in exactly the situation background playback is being built for.

A cross-specification note, recorded rather than assumed. The Active Queue & Reader Session spec describes the announcements an inexact restore requires as "live-region announcements, added to the spoken sequence". Read as "also exposed as a live region", that is consistent with this section. Read as "a live region is the mechanism", it would leave a screen-off listener unannounced. This specification adopts the first reading and states it explicitly rather than leaving it to interpretation; whether that sentence should be amended to say so belongs to that document, and this specification does not amend it.

# Announcements

## The announcement budget

At most one announcement precedes any content, and an announcement never delays content beyond itself. Two announcements back to back before the first word of a Note is worse than one, because the listener cannot tell which of them describes what they are about to hear.

## What each transition announces
- A part change — nothing. The content is the signal, and every announcement here would be one announcement per part.
- A section change by command — nothing beyond the content itself. The command lands on a heading when the section has one, so the heading is read as the first part of the new section. Announcing a section title and reading the heading would say it twice.
- A Note transition — the new Note's title, because a Note has no title part (decision D9) and an unannounced Note change is indistinguishable from the reader stopping. Already implemented.
- An inexact restore — whatever the Active Queue & Reader Session spec's resume decision requires, and nothing added. The Player must not duplicate it; a duplicate announcement immediately after a resume is indistinguishable from a stutter.
- The end of a queue — see below.

## The end of a queue must be spoken

When the final Note of a queue finishes, the reader speaks a short end-of-queue announcement and then falls silent. It is spoken once per completion, and re-entering the Player afterwards must not speak it again.

This is a defect in the shipped behavior rather than an enhancement, and it is the direct consequence of the announcement rule above. Today, reaching the end of the final Note stops and reports "End of queue" in the playback state line, which is a live region. With TalkBack active the listener hears it. With the screen off — the mode the product contract requires playback to eventually support, and the mode a listener actually listens in — nothing is spoken, and the reader's silence is indistinguishable from the reader having failed. That is the same reasoning that made the between-Notes title announcement a spoken one rather than a live region, applied to the one transition that has no following Note to carry it.

For a single-Note session the end of the Note and the end of the queue are the same moment, so there is one announcement rather than "End of note" followed by "End of queue".

## A section title is announced once

Neither the Player nor a resume may announce a section title that has just been announced. The Active Queue & Reader Session spec requires this of a resume, and the same rule applies to the Player's own rendering: an announcement is a moment in the spoken sequence, not a property of a screen.

# The progress line

## What the current line counts, and why the label is wrong

The Player's position line reads Paragraph N of M. N and M are computed over the document's flat list of speakable units, which the player layer populates from the document's chunk projection. Those units are not paragraphs.

A chunk is produced for headings, list items, to-do items, quotes and code blocks as well as paragraphs, and a single long paragraph is split into several parts at sentence and word boundaries by the chunker. So on a real document the line reports the part count under the name of a paragraph, and it is wrong for every heading, every list item, every quote, every code block and every split paragraph that precedes the listener's position.

It is a label the state cannot support, which is the same class of defect as a queue row that reported "Completed" for Notes the listener had not heard: the number is right and the noun is a claim nothing backs.

## What it says instead

The progress line reads Part N of M, where a part is one speakable unit — the unit the count already measures, and the unit the Player's fine-grained navigation moves by.

Alternatives considered and rejected:
- A noun per kind — "Heading 3 of 12" where the current part is a heading. Rejected because the count would then run over a mixed list, so the listener would have to be told that the twelfth thing includes headings, which is a longer explanation than the problem.
- Chunk N of M — accurate but internal vocabulary, and never a word a listener should be taught.
- Dropping the count — removes the half of the line that is correct and useful.

The empty document reads Nothing to read, replacing No paragraphs. An empty document has no parts, so it must not name a unit at all.

This changes a user-visible string, so it changes the README, the Player's instrumented assertions, and any test that asserts the old wording.

# Accessibility

## Control naming

The command name and the control's accessible name are the same vocabulary in the same words. The complete set of names a listener can encounter on the Player is:
- Play, Pause
- Previous section, Next section
- Previous part, Next part
- Restart note
- Previous note, Next note
- Queue
- More playback controls

Nothing is called "back", "forward", "more", "options" or "button", and no control is left with a bare or decorative name. A control whose label does not say what it does is a control a TalkBack user cannot use, which is why this list is normative rather than illustrative.

## Disabled rather than inert

A control whose command is unavailable is disabled in the semantics tree, so a screen reader announces it as unavailable rather than allowing a press that does nothing. Availability is read from the single published value, so a disabled control and an unadvertised transport action are the same fact rather than two evaluations of it.

## Where the secondary controls live

The primary control set stays small. The UI contract requires that the Player "should emphasize listening" and the Product & Architecture Contract is explicit that "the main interface must not become a control panel for every Player capability". Adding four more buttons to the Player's rows would do exactly that.

The secondary commands are therefore reached through one labelled control that opens a secondary surface containing Restart note, Previous part and Next part. Its accessible name states what is inside it, so the listener knows where the thing they want lives before opening it.

Two constraints on that surface:
- It may not become the only route to a command that is not secondary. A primary control moved into it would be a control the listener has to hunt for.
- The secondary commands must not be reachable only through a gesture. An accessibility action that supplements the surface is acceptable; an accessibility action that replaces it is not, because a control reachable only from a screen reader's actions menu is not reachable by a listener who does not know the menu exists.

## Live regions

The playback state line — Playing, Paused, End of note, End of queue — is a polite live region, because those are required announcements and they change at moments the listener cares about. The progress line deliberately is not live, so it cannot talk over the document as every part advances it.

Where an announcement is both spoken and exposed as a live region, the live region sits on the announcement text itself, never on a container that does not merge its descendants. A container that does not merge creates its own semantics node, a screen reader watches an empty node, and the announcement silently never fires. That defect has already been found once in this project, on the Note screen's error message.

## The Player never moves focus

No command, announcement or state change moves the screen reader's focus. Moving focus during playback would put one voice on top of another, and the contract's rule that playback "must not depend on the current visual screen or accessibility focus" is the same rule seen from the other side.

# Test strategy

## Unit tests

Testable with no device, no dispatcher and no TTS engine, because the command layer is synchronous and the speech engine is a port:
- each command's availability is false in exactly the states it cannot act in, and true in the ones it can;
- TogglePlayback is equivalent to Play when paused and to Pause when playing, asserted as an equivalence rather than as two behaviors;
- a rejected command leaves the reading position, the playing state and the queue untouched, and produces no announcement;
- every command that moves preserves the playing state in both directions — silently while paused, speaking while playing;
- NextPart and PreviousPart never change the current Note, including at the document's last part;
- RestartNote returns to the first part of the current Note and does not restart the queue;
- RestartNote is unavailable once the queue has completed;
- JumpToNote to the current index is a no-op: no transition, no announcement, no position reset;
- the end of a queue produces exactly one spoken announcement, and entering the Player afterwards produces none;
- a part change produces no announcement;
- a late utterance completion is discarded after each cancelling operation;
- the progress line's count equals the document's part count and its unit is the part, asserted as a property of the line rather than as a literal string, so that a document full of headings and split paragraphs is what proves it.

## Instrumented tests

Against the semantics tree, because that is what a screen reader consumes:
- every control in the Player carries one of the documented names, and none reads as a bare or decorative label;
- a control whose command is unavailable is disabled in the semantics tree rather than enabled-and-inert;
- the progress line is not a live region, and the playback state line is;
- the end-of-queue announcement is a live region on the announcement itself, and its element exists rather than being asserted absent;
- the secondary surface's entry control names what it contains;
- no command is reachable only through an accessibility action or a gesture;
- the primary control set does not grow when the secondary surface is added.

## Device validation

Behavioral, and it needs a device with the screen off, because the claim being tested is about the one channel that survives the screen going dark:
- with the screen off, a queue played to its end produces an audible end-of-queue announcement followed by silence;
- pausing and playing re-reads the current part from its beginning;
- a headset or transport command is not part of this specification's validation — it belongs to the Background Playback specification, which is where audio focus and the service lifecycle are decided.

Before believing any UI dump on a device, confirm the foreground application. android layout does not report which app it dumped, so with the keyguard up it returns the lock screen in the same shape as an application, and a missing control then reads as a real defect in a UI that is not on screen.

# Implementation phases

## Phase 1 — The command surface and availability

Introduce the command type and the availability value, and route the Player's existing controls through them. Behavior-preserving: nothing visible changes, no command is added or removed, and the only new capability is that the commands now have names and a single availability answer. The engine is not replaced; this is a boundary drawn around what already exists.

## Phase 2 — Announcements

Speak the end of a queue, once per completion, and make re-entry silent. This is a real behavior change and the phase's acceptance is that a screen-off listener can tell a finished queue from a failed one.

## Phase 3 — The progress line

Part N of M, and Nothing to read for an empty document. Update the README and every assertion that names the old wording.

## Phase 4 — The secondary controls

The labelled entry control and the secondary surface, containing Restart note, Previous part and Next part. playFromStart(), which has never had a production caller, gains one here.

## Consumed by a later specification

The MediaSessionService, audio focus, the foreground-service lifecycle, lock-screen metadata, headset button mapping and hands-free commands belong to the Background Playback, Media Controls & Hands-Free Commands specification. That document may add transports but may not add, rename or redefine a command; if it needs one, this specification is amended first.

# Acceptance criteria
1. Every command in the closed set has a stated precondition and a stated effect, and no control in the Player invokes playback by any other route.
1. Availability is published once, and both the Player's controls and any transport read it from the same value.
1. A command invoked while unavailable changes nothing and announces nothing.
1. TogglePlayback is equivalent to Play or Pause in both states, with no separate behavior to drift.
1. Every moving command preserves the playing state; no command starts speaking in a paused session without being Play.
1. No part command crosses a Note boundary.
1. The end of a queue is spoken by the reader's own engine, exactly once per completion.
1. No announcement duplicates another, and no announcement precedes content without being needed.
1. The progress line's unit is the unit the count measures.
1. An empty document does not name a unit it does not have.
1. Every Player control has a self-explanatory accessible name.
1. An unavailable command's control is disabled in the semantics tree.
1. The secondary commands are ordinary accessible controls, not gesture-only and not accessibility-action-only.
1. The primary control set does not grow.
1. Nothing in this specification adds, removes or reorders a Note in a queue.

# Architectural invariants
1. A command is invokable without the screen it is normally displayed on.
1. Command availability is one published fact; no surface derives it independently.
1. Rejection is neither an error nor an event.
1. Commands move; only Play causes speech.
1. The command layer holds no Android type, no coroutine scope and no suspension point.
1. A required announcement is spoken by the reader's TTS engine; a live region may accompany it and may never be the only mechanism.
1. The Player never moves accessibility focus, and no command is reachable only by gesture.
1. Behind the command boundary, the primary controls' names and existence are the UI contract's, and the queue's behavior is the session spec's; this specification changes neither.
1. No transport may add a command. A transport maps onto the closed set.

# Open questions
- Playback rate and pitch. No governing document names a speed control, and Android TTS supports one. Whether the reader offers it belongs to the Settings destination, which the UI contract says is populated only by "the specifications that require them" — so it needs a specification that requires it, and this is not that specification.
- A sleep timer. Same position: a listening feature no document names.
- Whether a completed queue offers replay from the Player. Settled here as unavailable and routed to Queues → Listening → Recently completed, because completion is a fact the Active Queue & Reader Session spec owns and a restart would create a session that is completed and playing at once. If a Player-scoped replay is wanted, it is a queue-level action and belongs to the Queue Creation & Queue Management specification.
- A stalled Note's offer. Next note is the way past a Note that will not load. Whether a stalled queue should offer anything more, and whether move-on deserves its own command name, belongs to the Queue Creation & Queue Management specification.
- The Active Queue & Reader Session spec's live-region wording. Recorded under Announcements above as a reading this specification adopts explicitly. Whether that sentence should be amended to state it belongs to that document.
- Whether Queue should ever be transportable. Today it is a surface action, because it changes what is displayed. A future media-session queue listing would be a different surface reading the same state, not this command.

# Next implementation step

Phase 1 is a boundary around behavior that already exists, so it can be implemented and verified with the JVM suite plus the existing Player instrumented tests. Phase 2 is the phase with a claim that only a device can settle, and it is the one that should be done before background playback, because a screen-off listener who cannot tell a finished queue from a failed one is the worst outcome that slice can produce.

