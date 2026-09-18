<!--
  HAND-AUTHORED GOVERNING DOCUMENT - not a Notion mirror.

  Unlike everything in docs/notion/, this file is NOT generated and has no Notion counterpart:
  it is authoritative as it stands, in this repository. tools/export-notion-doc.ps1 does not
  know about it and must not be pointed at it. Editing it here is the supported workflow.

  The consequence is recorded rather than hidden: because it has no page in Notion, it cannot
  amend any document that IS a mirror. An amendment to one of those still has to be made in
  Notion and re-exported. See docs/decisions.md 23.9.
-->

# Notion Reader — Background Playback, Media Controls & Hands-Free Commands

> Governing implementation specification. Defines how playback continues while the application is not
> on screen, how the platform's media surfaces reach it, and what owns the session once it stops being
> the task's.

# Summary

This specification exists because of a platform change, a product gap, and a contract requirement that
all point the same way.

**The gap.** This is a listening application that stops listening when the screen goes off. The Player
holds the display awake with `keepScreenOn` as a stated workaround, so the only way to read hands-free is
to leave the phone awake and the application in front of the user — which is the opposite of how anyone
listens for twenty minutes on a bus. `docs/decisions.md` §16.4 recorded this as the largest functional
gap in the product while ranking it last, because it blocks nothing; §23 recorded the product owner
overruling that ordering and moving it first.

**The product contract requirement.** "Playback must eventually support screen-off and background use",
"Core playback behavior must not require the Player screen to remain visually open", and "Core Player
commands must be representable independently of individual UI widgets so Android media controls,
accessibility actions, hardware controls, and future voice control can invoke the same behavior." The
command half of that is the Core Player & TTS Playback spec's; reaching them from outside the application
is this document's.

**The platform change.** Android 17 enforces *background audio hardening*. Every application that
interacts with audio while it has no visible activity must be running a foreground service that is not of
type `SHORT_SERVICE`, whether or not it targets API 37 — and if it does target API 37, that service must
additionally hold while-in-use capabilities. An application that violates this does not fail: playback is
**silenced, with no exception and no failure message from any API**, and an audio focus request returns
`AUDIOFOCUS_REQUEST_FAILED` instead of throwing. That is precisely the failure class this project has
spent several rounds learning to distrust, so the specification's job is not only to build the feature but
to make its own failures loud.

# Goals

- Playback continues with the screen off, the application backgrounded, and the task dismissed.
- The session outlives the task that started it, which is the refactor the ordering decision forced.
- Android's media surfaces — lock screen, notification, headset buttons, media keys — invoke the *same*
  commands as the on-screen Player, through the closed set the Core Player spec defines.
- Audio focus is requested, lost and regained correctly, so the reader does not talk over a phone call
  and a phone call does not leave the reader silent with no way back.
- The reading screen stops being a battery liability: once background playback works, holding the display
  awake is no longer what keeps the reader reading.
- Every failure mode in this area is made observable, because the platform's default is to fail silently.

# Non-goals

This specification does not:

- **add, rename or redefine a Player command.** The command set is closed by the Core Player & TTS
  Playback spec and this document consumes it. If a transport needs a command that does not exist, that
  specification is amended first;
- define what a command *does* — preconditions and effects are the Core Player spec's;
- define queue policy, completion, or what happens to a Note that will not load;
- define resume policy, session persistence, retention or listening history, which the Active Queue &
  Reader Session spec owns;
- define anything about the Command's *visual* design, or add a destination: the UI contract's four
  top-level destinations are unchanged and the Player remains secondary;
- implement Google Cast, a media *browser*, or a content library. `MediaSessionService` is used as a
  transport host, not as a browsable library; `MediaLibraryService` is deliberately not adopted;
- implement playback resumption after a reboot, or any scheme in which the system restarts playback
  without a listener action. See *Process death and reboot* below, which forbids exactly that.

# Ownership boundary

## This specification owns

- the session's **lifetime**, and the correction of its current owner;
- the foreground service, its type, and when it starts and stops;
- the `MediaSession`, the `MediaController` connection and the system transport surfaces;
- audio focus, audio attributes, and the response to every focus outcome;
- the notification and lock-screen representation of a session;
- the mapping from Android media keys and headset buttons onto the command set;
- what happens when the task is dismissed, the process is killed, or the service is destroyed;
- the observability requirements that make this feature's silent failures visible.

## This specification does not own

- **The command set, or any command's meaning.** Resource `R5` in `docs/decisions.md` §23.4 is the
  command surface; this document is one of its transports.
- **Announcements.** What is spoken, and the rule that a required announcement is spoken by the reader's
  TTS engine rather than only exposed as a live region, are the Core Player spec's. This document inherits
  that rule, and it is the reason a screen-off listener can be served at all.
- **Whether a session is resumable**, and what Continue restores.
- **Persistence.** This specification changes what owns the *live* session; it does not change what is
  written, when, or how it is retained.
- **The mini-player.** It observes the same session as before; only the session's owner moves.

# Definitions

- **Background audio** — audio interaction from an application with no visible activity, including
  playback while the screen is off. Android 17's hardening rules are addressed to exactly this.
- **Foreground service (FGS)** — a service with an active foreground notification, which the platform
  exempts from background execution limits. The media notification is that notification, not a second one.
- **While-in-use (WIU) capability** — a capability granted to an FGS *started while the application was
  visible*, or in response to an explicit user interaction such as a notification tap or a media key. An
  FGS started from the background generally does **not** get it, and without it the audio APIs are blocked.
- **Transient loss** — audio focus lost in a way the platform describes as temporary:
  `AUDIOFOCUS_LOSS_TRANSIENT`, and `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`. Reading stops and is expected to
  resume.
- **Permanent loss** — `AUDIOFOCUS_LOSS`. Reading stops and is **not** resumed automatically.
- **Becoming noisy** — audio output being removed, typically a headset unplugged.
- **Session holder** — the process-scoped owner of the live `ReaderSession`, introduced by this
  specification. It is neither an Activity's nor a ViewModel's.
- **Transport** — anything that invokes a command other than the Player's own composables: the lock
  screen, the notification, a headset button, a media key, a future voice command. The Core Player spec
  defines the term; this document gives transports their platform implementation.

# Why MediaSessionService, and not a service of our own

The platform's own guidance is that an application doing background audio must run a foreground service
of a media type, and that applications using Media3's `MediaSessionService` "are not likely to be
impacted by the background hardening due to the library assisting in managing the playback lifecycle."
The alternative — managing a `mediaPlayback` FGS and an `AudioTrack`/focus interaction by hand — is
strictly more work and is where the documentation's warnings are aimed.

Three requirements follow, and all three are platform requirements rather than preferences:

1. **The service's type is `mediaPlayback`**, declared in the manifest, with the
   `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions. `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
   is required from API 34 and this application's `targetSdk` is 36.
2. **The service is started while the application is visible.** This is not optional politeness: it is
   what grants WIU capability. A service started only once the screen has gone off is exactly the case
   Android 17 blocks. Concretely, pressing Play — a user-initiated operation performed while the
   application is in front of the listener — is the moment the service starts.
3. **The service stops when playback ends, and restarts only on an explicit user action**, as the
   hardening guidance requires. The rules are behavioural and are stated in *Lifecycle* below.

## The failure mode this section exists to prevent

Every failure in this area is silent. Playback is suppressed without an exception; a focus request
returns a failure code rather than throwing; volume calls are ignored. An implementation that is wrong
here looks like an implementation whose audio "just doesn't play sometimes", which on a talkback-first
reader is indistinguishable from a broken speech engine, and on this project is the third appearance of
a recurring shape: `docs/decisions.md` §22.9's `assertIsDisplayed` claim, §19's boolean that answered the
wrong question, and now a platform that reports success while doing nothing.

So this specification requires the diagnostic surface below, and requires an implementation to be
verified with hardening *thrown* rather than merely enabled.

# Where the session lives

## The problem being corrected

Today the chain is:

```plain text
MainActivity
  └── ReaderViewModel        (AndroidViewModel, viewModelScope)
        └── ReaderApp
              └── ReaderSession
                    └── ReaderEngineImpl
                          └── AndroidSpeechEngine
```

Every link is scoped to the task. `ReaderViewModel.onCleared` is the single shutdown point, and it is the
correct answer to the question it was written for — "nothing that must outlive a *screen* lives in a
screen" (`docs/decisions.md` §19.4). The question this specification has to answer is one level up:
nothing that must outlive the *task* may live in the task either. A `MediaSessionService` has its own
lifecycle, which the system may extend past the task's, and the session it controls cannot be owned by
something the task destroys.

## The resolution: a process-scoped session holder

The live session moves to a **session holder**: created once per process, owned by neither the Activity
nor the ViewModel, and reached by both the service and the UI.

```plain text
            session holder  (process-scoped: owns ReaderSession, engine, speech engine)
                  ↑                         ↑
                  |                         |
     MediaSessionService            ReaderViewModel
     (MediaSession + player,        (observes the session, launches I/O)
      foreground claim,             |
      system transport)             MainActivity → ReaderShell → screens
```

| Concern | Owner | Lifetime |
|---|---|---|
| the session, its queue, its position | session holder | the process |
| speech (TTS) | session holder, through the engine | the process |
| the foreground claim and the `MediaSession` | `MediaSessionService` | while playback is active |
| the system transport surfaces | `MediaSession` + the player implementation | as above |
| screen state and intent forwarding | `MainActivity` / `ReaderShell` | the task |
| I/O jobs (Note loads, Browse index, history writes) | `ReaderViewModel` | the task |

Three rules make this concrete:

- **`ReaderViewModel` no longer owns the session and no longer shuts it down.** `onCleared` must not end
  playback, because the ViewModel is destroyed on every configuration change and on task dismissal. What
  remains in the ViewModel is what it is good at: a coroutine scope, the three collectors, and the two
  loaders, as §19.4 arranged.
- **The UI observes the session directly, not through `MediaController`.** `MediaController` is the
  transport path: it is how the system, another application, or a future widget asks for a command. It is
  not used as the UI's source of state, because the Player renders things the `Player` interface does not
  model — sections and their spans, the queue as identities and titles, the resume decision, the
  announcement text. Routing those through Media3 would create a second, lossy model of the session and a
  second place for it to be wrong. **One session object, two front ends.**
- **The session holder is not a service.** The service may stop while the session object survives, paused
  and resumable, so that returning to the application shows the session where it was and pressing Play
  starts the service again. Persistence already guarantees the position across process death, so a
  stopped service loses nothing.

## What deliberately does not move

The speech engine stays inside the session holder rather than inside the service, because the UI must be
able to read and speak through it while the service is stopped. This is what keeps the "one engine, one
session, one position" rule true regardless of which transport is active.

# Lifecycle

## When the foreground service starts

On a **user-initiated playback command issued while the application is visible**: the Player's
`Play`, the mini-player's `Play`, a `Play` or `Continue` that starts a session from the Note, Browse or a
queue. Starting at that moment is what grants WIU capability, and it is also honest: the listener asked
for audio, and the system is now told to expect audio.

It does **not** start on launch, on opening a Note, on opening the Player, or when restoring state at
launch. Those are the Active Queue & Reader Session spec's "launch never begins playback" and the UI
contract's "opening a Note never automatically starts playback", and starting a foreground service would
be claiming otherwise.

## While reading

The service is a foreground service of type `mediaPlayback`, and the media notification is its foreground
notification. There is exactly one notification; nothing in this specification posts a second.

## When reading pauses

Playback stops; the session and its position are kept; the notification remains so the listener can
resume, and the transport surfaces remaining available is the whole point of pausing rather than
stopping. The foreground claim is not held indefinitely with nothing playing — the platform's rule is
that an FGS exists to do work, and a paused reader is not doing any.

**A paused session is not a failed one**, and the notification is what says so to a listener who is not
looking at a screen. This is the same distinction the Player already draws between "Paused" and "End of
note", and it matters more here, where the screen is off by definition.

## When the queue completes

The session is marked completed, the end-of-queue announcement is spoken (per the Core Player spec), and
the foreground service **stops and the media session ends**, because playback has permanently ended and
the hardening guidance is explicit that this is when to cease the audio interaction.

The session is not discarded, and the position is not lost: completion is a fact, and the Active Queue &
Reader Session spec already puts a completed session in `Queues → Listening → Recently completed`, where
it is replayable from the beginning. The notification goes away because there is nothing left to control.

## When the task is dismissed

**Playback continues.** Dismissing the task from recents is a navigation act, not a playback act, and the
UI contract's rule is that "Back changes visual navigation, not playback state" — of which task dismissal
is the largest possible instance. The notification remains, and it is the listener's control surface.

This is a deliberate divergence from the template that Media3's own documentation shows for
`onTaskRemoved`, which pauses the player and stops the service. That template is right for an application
whose playback is incidental to a screen; for a listening application it would mean that a listener
clearing their recent apps — a gesture people perform habitually, for unrelated reasons — silently stops
their reading. The divergence is stated here so that an implementer following the documentation's example
recognises it as the wrong example for this product.

## When the process is killed

Nothing resumes. On the next launch, history is read and surfaced, and playback begins only when the
listener presses Continue or Play. This is the Active Queue & Reader Session spec's launch rule, and this
specification adds no exception to it for the service.

## Process death and reboot: playback resumption is refused

Media3 offers *playback resumption*, in which a media session restarts playback after a reboot or an
application kill, typically from a notification or an automatically-created media item. **This
specification does not adopt it.** Playback resumption would start speaking without a listener action,
which is forbidden by the Active Queue & Reader Session spec's launch rule and by the UI contract's rule
for opening a Note, and it is worse in a reading application than in a music one: a book that starts
reading itself aloud is not a convenience.

The distinction to keep straight: a **media key or headset button press while the session is alive** is a
listener action and is honoured; the **system restarting playback on its own** is not, and is not
implemented.

# Audio focus, audio attributes and interruptions

## Audio attributes, and why they decide the volume key

The reader's speech declares `USAGE_MEDIA` with `CONTENT_TYPE_SPEECH`. That is what makes the hardware
volume keys control the reader, what puts it on the media stream for the purposes of the platform's
ducking and routing decisions, and what makes it visible as media rather than as a notification sound.
Declaring nothing, which is what an unconfigured `TextToSpeech` instance effectively does, leaves the
reader's volume controlled by whichever stream the engine happens to default to.

`USAGE_MEDIA` is preferred over `USAGE_ASSISTANCE_ACCESSIBILITY`, deliberately: that usage identifies
audio *belonging to an accessibility service*, occasionally at a different volume ratio, and this
application is not an accessibility service. Its output is content being read, which is media. The
`CONTENT_TYPE_SPEECH` hint is what keeps the platform's own behaviour sensible — it is the difference
between "music" and "someone talking" for ducking and for the media card.

## Focus outcome handling

| Outcome | Reading | Resumes automatically | Why |
|---|---|---|---|
| `AUDIOFOCUS_GAIN` after a transient loss | starts again | **yes** | the listener's intent to listen did not change; the interruption was the platform's |
| `AUDIOFOCUS_LOSS_TRANSIENT` | stops | — | another application needs the output now |
| `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` | stops | **yes** | see below |
| `AUDIOFOCUS_LOSS` | stops | **no** | another application has taken the output for an indefinite period; resuming would be presuming |
| becoming noisy | stops | **no** | the output the listener chose has been physically removed |

**Ducking is refused, in favour of stopping.** Ducking means attenuating this application's output so it
can be heard under another's. For music that is right; for speech it is useless, because the platform
offers no way to make speech quieter and still intelligible, and an attempt produces a murmur the
listener cannot follow and did not ask for. Stopping and resuming is the honest response to
`CAN_DUCK` for read-aloud content, and the resume on regain is what keeps it from costing the listener
their place.

**Becoming noisy stops the reader silently, and that is the one place this specification accepts silence.**
The listener's headphones have been unplugged; the alternative to silence is to speak the announcement
`Paused` through the phone's loudspeaker, which would broadcast their reading to everyone in the room.
A notification is posted, the position is kept, and the live region carries the state if a screen reader
is running. The privacy of the listener takes precedence over the announcement rule here, and the
exception is bounded to this one cause: every other stop is either announced or has a following sound.

## The reader's speech channel, and what it does not coordinate with

**The reader speaks through its own TTS engine. The application does not implement behaviour aimed at a
screen reader, and does not depend on one being present.**

`docs/decisions.md` §23.14 records this as a product decision, and the Product & Architecture Contract
already draws the same line: "TalkBack → interface controls, labels, focus, manual content exploration, UI
state announcements" and "Reader TTS → continuous Note content playback". This specification stays on the
second half of that split.

- **Every fact a listener must hear is spoken by the reader's own engine**, so it is heard with no screen
  reader running and the screen off. That is why the end of a queue is a spoken announcement rather than a
  live region, and it is the rule the Core Player & TTS Playback spec states as "a required announcement is
  spoken by the reader's TTS engine; a live region may accompany it and may never be the only mechanism".
- **Audio focus is handled uniformly, as a platform requirement.** The rules below concern other
  applications and the platform's own interruptions. They are not conditional on who is speaking, and
  nothing in this application branches on whether a screen reader is enabled.
- **No screen-reader detection is added, and none exists.** A listener who runs a screen reader gets the
  platform's normal interaction with two speech clients, and controls it by pausing one of them. A
  coordination protocol invented by this application would be a second, unverifiable mechanism pretending to
  be a platform one.

Two honest consequences, recorded because they are the reason the alternative was considered and
deliberately not taken:

1. **Two speech sources can overlap.** The platform has one TTS service and this application owns no queue
   in it, so a screen reader reading the interface while the reader reads a Note is audible. That is a
   property of running two speech clients, not a defect this application can fix from its side.
2. **A screen reader's speech may arrive as an audio focus change**, and the rules below will then pause or
   resume the reader exactly as they would for any other application. The alternative — ignore transient
   losses while a screen reader is enabled — is **rejected**: it would special-case one other client out of a
   platform rule, and `docs/decisions.md` §11's listening gate exists precisely because that interaction is
   better *heard* than encoded.

What this does **not** mean is that the application becomes less usable with a screen reader. Everything in
*Accessibility* below still holds, because it is about the application being usable rather than about
coordinating with the thing making it usable.

# The command mapping

## Availability is Media3's `Commands`

Media3's `SimpleBasePlayer` exists for exactly this case — "a custom player that you want to connect to a
`MediaSession`" — and it is built around `setAvailableCommands(Commands…)` plus `invalidateState()`.

That is the Core Player spec's single published availability value, in the platform's own vocabulary. The
mapping is therefore not a translation layer: the same evaluation that disables a button also decides
which commands the media session advertises, so a lock screen cannot offer an action the Player has
disabled, and neither can disagree with the other.

```plain text
Core Player spec:  PlayerCommandAvailability   ← one evaluation
                            ↓
              SimpleBasePlayer.setAvailableCommands(Commands…)
                            ↓
        Player controls · media session · lock screen · headset · media keys
```

## The command set as platform actions

| Command (Core Player spec) | Media3 / platform expression |
|---|---|
| `Play` | `COMMAND_PLAY_PAUSE` + `PLAYER_COMMAND_PLAY`, `play()` |
| `Pause` | `COMMAND_PLAY_PAUSE` + `PLAYER_COMMAND_PAUSE`, `pause()` |
| `TogglePlayback` | `COMMAND_PLAY_PAUSE`, `play()`/`pause()` per state |
| `NextSection` / `PreviousSection` | `COMMAND_SEEK_TO_NEXT` / `COMMAND_SEEK_TO_PREVIOUS` |
| `NextPart` / `PreviousPart` | `COMMAND_SEEK_FORWARD` / `COMMAND_SEEK_BACK` |
| `RestartNote` | `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` on the current item, plus a restart seek |
| `NextNote` / `PreviousNote` | `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` / `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` |
| `JumpToNote` | `COMMAND_SEEK_TO_MEDIA_ITEM` |
| `OpenQueue`, `OpenMorePlaybackControls` | **not mapped** — surface actions, absent from `Commands` |

Two of these deserve their reasoning stated, because the mapping is where a transport could quietly
change a command's meaning:

- **Sections and parts reuse the seek commands, and that is a compromise.** Media3's model is a playlist
  of media items with a position inside one; "next section" and "next part" are both *within* the current
  item. Two distinct commands share one platform command because the platform has one. The **on-screen
  Player keeps both controls distinct** — the mapping is only for transports, and a transport that
  conflates them can only ever invoke the one it was mapped to. This is recorded as a limitation rather
  than papered over: a media session's "seek forward" means `NextPart`, and section navigation is a
  Player control.
- **`RestartNote` is expressed as a seek to the beginning**, not as a media-item change, so no transport
  can turn it into "previous track". The Core Player spec makes it unavailable once a queue has
  completed, and the mapped command is removed from `Commands` in that state, so the platform's
  skip-previous control is greyed rather than doing something else.

## Media keys and headset buttons

Media3's `MediaSession` performs the button-to-command routing; what this specification fixes is the
mapping, so that it is a decision rather than an accident of whichever button the platform reports first.

| Input | Command |
|---|---|
| `KEYCODE_MEDIA_PLAY` | `Play` |
| `KEYCODE_MEDIA_PAUSE` | `Pause` |
| `KEYCODE_MEDIA_PLAY_PAUSE`, headset hook press | `TogglePlayback` |
| `KEYCODE_MEDIA_NEXT`, headset double-press | `NextNote` |
| `KEYCODE_MEDIA_PREVIOUS`, headset triple-press | `PreviousNote` |
| `KEYCODE_MEDIA_STOP` | `Pause` |
| `KEYCODE_MEDIA_FAST_FORWARD` | `NextPart` |
| `KEYCODE_MEDIA_REWIND` | `PreviousPart` |

**Why a headset's skip button moves a whole Note rather than a section.** The media session's model is a
playlist of items, and a Note is the item — the Player already says "Note 3 of 8", and the queue is
already the mental model of a playlist. Mapping skip to a section would make the physical button
inconsistent with the media session it belongs to, and would leave no key at all for moving between
Notes. The finer controls are on the screen and behind the secondary surface, where their labels are
visible.

`KEYCODE_MEDIA_STOP` maps to `Pause` rather than being unhandled, because the Core Player spec defines no
`Stop` command: a transport that can only express "stop" must not be given a different behaviour from
`Pause`, and a key that does nothing at all is worse than one that does the nearest true thing.

# The notification and the lock screen

## Required content

| Element | Value | Source |
|---|---|---|
| title | the current Note's title | the queue's current item, which is what names the Note before its document is loaded |
| secondary line | the current section title when there is one, otherwise the queue position | the session's state, the same two facts the Player shows |
| playing state | playing or paused | the same value the Player's live region carries |
| artwork | none | there is no artwork, and inventing a placeholder for a reading application adds an element that would be read aloud and says nothing |

No element of the notification may be invented from a source the Player does not use. The notification is
a second rendering of one session, not a second opinion about it — the same rule invariant 26 applies to
the mini-player.

## Required actions

`Previous note`, `Play`/`Pause`, `Next note`, in the platform's notification-action form, subject to the
availability rules above. Media3's default notification provider supplies these from the player's
available commands, which is why availability and actions cannot drift.

## What the notification must not contain

- **`Queue`.** It is a surface action under the Core Player spec's classification, because its effect is
  to change what the phone is displaying. A lock screen must not offer a control whose only outcome is to
  bring an application to the foreground in a particular state.
- **Anything that edits the queue.** The Product & Architecture Contract's model is that Active Queues
  are played, not edited.
- **Section or part navigation.** The notification is the smallest useful control surface; the secondary
  controls belong to the Player. A notification with eight actions is a control panel, which the product
  contract forbids twice over.

## Notification permission

API 33 and above require `POST_NOTIFICATIONS` at runtime. It is requested **at the first playback action**,
with a rationale, rather than at launch: the request is meaningful at that moment and meaningless before
it.

A **denial must not stop playback, must not fail a command, and must not be reported as a playback
error.** It degrades the listener's control surface, which is worth saying plainly in the application's
own words once, and nothing else. This is the same posture the Active Queue & Reader Session spec takes
toward a history write failure: a failure in a supporting surface is not a playback failure.

## Lock screen and session activity

The `MediaSession` publishes the same metadata, and a tap on the notification or the lock-screen media
card opens the **Player** — not Home, and not the Note. The listener reached a control surface for the
current session; the destination that describes the current session is the Player. This is a
`setSessionActivity` intent, and it must not carry a back stack that would make Back from the Player
behave differently from Back pressed after opening the Player normally.

# Accessibility

## The reader's voice remains the only required channel

Every requirement in this specification is satisfiable with a screen reader disabled and the screen off.
That is the Core Player spec's rule — a required announcement is spoken by the reader's own TTS engine,
and a live region may accompany it but must never be its only mechanism — and this feature is the reason
that rule exists. The lock screen, the notification and the media card are *additional* surfaces, not
substitutes for speaking.

## The media surfaces are readable, and are not made to re-announce

The media notification and the lock-screen card are focusable and are read by whatever screen reader is
running. The application does not coordinate with that, but it does avoid making it worse — and both rules
below are about the notification being *correct* rather than about a screen reader specifically:

- **The media notification must not carry a live region.** A notification whose content changes as the
  player moves through a Note would cause the platform to re-announce it repeatedly — over the reader's own
  voice, once per Note. That is undesirable for every listener, and it is the same failure the Player's
  non-live progress line exists to avoid.
- **The notification's content description must be one coherent sentence**, not title, state and position
  as three separate announcement events. This is the mini-player's "logically grouped accessible region"
  rule, applied to a surface the system renders on this application's behalf.

## Transparency about the foreground service

A listener using a screen reader can and should be able to tell that a foreground service is running. The
notification is that disclosure: the application must not run a `mediaPlayback` foreground service while
appearing to have no active session, because a foreground service with no visible reason reads as
malware to someone who finds it in system settings.

## Focus is still never moved

Nothing in this specification moves accessibility focus — not a command, not a notification update, not
the transition to or from background. The Player's screens do not become focus targets because playback
started, and the notification is not force-announced.

# What changes in the application

| File / area | Change |
|---|---|
| `MainActivity` | stops owning the session; still renders the shell |
| `ReaderViewModel` | stops owning the session and stops shutting it down in `onCleared`; keeps its scope, collectors and loaders |
| new: session holder | process-scoped owner of `ReaderSession`; created once, reached by the service and the ViewModel |
| new: `PlaybackService : MediaSessionService` | hosts the `MediaSession` and the player implementation; the foreground claim |
| new: a `SimpleBasePlayer` subclass | exposes the session through Media3, with availability mapped to `Commands` |
| `AndroidManifest.xml` | the service with `android:foregroundServiceType="mediaPlayback"`, the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions, `POST_NOTIFICATIONS`, and the Media3 `MediaButtonReceiver` if the library requires it for the chosen version |
| `platform/AndroidSpeechEngine` | declares its audio attributes (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`) so focus and the volume keys behave |
| `app/build.gradle.kts` | adds `androidx.media3:media3-session` |
| `PlayerScreen` | **removes `keepScreenOn`** — see below |

## Removing `keepScreenOn` is part of this feature, not a follow-up

The Player holds the display awake while playing, and both the README and `docs/decisions.md` §18 describe
it as a stated workaround for exactly this gap. Once playback survives the screen going off, holding it
awake stops being what keeps the reader reading and becomes a battery cost plus a behaviour no one
asked for: a phone that will not sleep because an application is reading to its owner.

It is removed in the phase that makes it unnecessary, and the README's workaround note is removed with
it. Leaving it in place would make the feature's own acceptance test — "read with the screen off" —
unobservable, because the screen would never go off.

# Test strategy

## Unit tests (JVM, no device)

- the availability mapping produces exactly the commands whose availability is true, and a command that
  becomes unavailable is removed from the advertised set in the same evaluation rather than a later one;
- the session holder's lifetime is independent of the ViewModel: destroying and recreating a ViewModel
  leaves the session, its queue, its position and its speaking state untouched;
- the focus outcome table is implemented as data: each outcome produces the documented stop/resume
  pair, including that a permanent loss does not resume and a transient one does;
- becoming noisy stops without announcing;
- the media-key mapping is total over the table above, and a key with no mapping does not fall through to
  another command.

## Instrumented tests (semantics tree)

- the Player no longer holds the display awake, asserted on the view's `keepScreenOn` after playback
  starts;
- the media notification's content description is one announcement rather than several, and it carries no
  live region;
- the Player's primary control set is unchanged by the presence of a media session — the mapping adds no
  composable.

## Device validation

This is the only part of this specification that can settle its own central claim, and it is therefore
stated as a sequence rather than as a list of checks.

```bash
# 1. Confirm WHAT is on screen before interpreting anything.
adb shell dumpsys window | Select-String mCurrentFocus

# 2. Turn silent audio failures into loud ones for the duration.
adb shell cmd audio set-enable-hardening throw

# 3. Confirm the application is compliant rather than merely working on this device.
adb shell cmd audio set-enable-hardening enable
adb shell dumpsys audio | Select-String AudioHardening
#    "level: full"    -> a foreground service is running but WITHOUT while-in-use capability
#    "level: partial" -> no foreground service at all
#    no entry         -> compliant
adb shell cmd audio set-enable-hardening disable   # restore in a finally, as the test runner does with screen_off_timeout

# 4. The behaviour itself, with the screen off.
adb shell input keyevent 26                        # screen off
#    read for several minutes; the reading must continue, and the Note transitions must be announced
# 5. Transport surfaces.
adb shell input keyevent 85                        # KEYCODE_MEDIA_PLAY_PAUSE -> TogglePlayback
adb shell input keyevent 87                        # KEYCODE_MEDIA_NEXT       -> NextNote
adb shell dumpsys media_session                    # the session, its state and its playback info
adb shell dumpsys activity services <package>      # the service and its foreground type
```

What the probe must establish, in order, because each answer can change the next question:

1. **Does reading continue with the screen off?** If not, is there an `AudioHardening` entry — because
   "no audio" and "silenced audio" look identical from the listener's side and require opposite fixes?
2. **Does audio focus behave as the table says?** Play, then have a second application take focus — an
   incoming call, or another media app — and confirm that reading stops on a transient loss and resumes on
   regain, and that it does not resume after a permanent loss. This replaces the screen-reader measurement
   this specification previously planned: the rules are uniform, and nothing in the application branches on
   who else is speaking (`docs/decisions.md` §23.14).
3. **Does force-stop restore state without speaking?** `am force-stop`, relaunch, and confirm history
   names the right position and that **nothing is spoken** until Continue is pressed.
4. **Does dismissing the task keep playing?** Swipe the task away and confirm the reading continues and
   the notification remains.

The probe must state what it **cannot** distinguish, per this project's rule: whether the end-of-queue
announcement was *chosen* by the reader or produced by the platform is not observable from a transcript
of utterances, and the "resume after transient loss" case cannot be produced reliably on demand without a
second application that requests focus.

# Implementation phases

## Phase 1 — The session holder (refactor R10)

Move the session out of the task with no behaviour change: the service does not exist yet, and the
application is otherwise identical apart from owning its session one level up. This phase's acceptance is
that the JVM suite is unchanged and that destroying and recreating the Activity mid-playback no longer
has any way to reach the session — the correction §19.4 made for screens, applied to tasks.

## Phase 2 — The service and the session

`MediaSessionService`, the `SimpleBasePlayer` implementation, the manifest declarations, and the
availability-to-`Commands` mapping. Playback driven from the UI now also puts a media session on the
device; backgrounding the application no longer depends on a visible screen.

## Phase 3 — Focus, attributes and interruptions

Audio attributes, the focus outcome table, becoming noisy, and the hardening diagnostics. This is the
phase whose failures are silent, so its acceptance is the `set-enable-hardening throw` run rather than a
green build.

## Phase 4 — The notification, the lock screen and the media keys

The notification provider and its content, the session activity, the media-key mapping, and the
`POST_NOTIFICATIONS` request with its denial path.

## Phase 5 — Remove the workaround

Delete `keepScreenOn`, correct the README, and re-run the instrumented suite, which contains an assertion
about the Player holding the display awake that must be inverted rather than deleted — the assertion was
guarding a real property and its replacement guards the opposite one.

# Acceptance criteria

1. Reading continues with the screen off, the application backgrounded, and the task dismissed.
2. With hardening *thrown*, no `AudioHardening` entry is produced for the application's package — the
   claim is that it is compliant, not that it happened to work.
3. The session survives the destruction of the Activity and of the ViewModel, and is ended only by
   completion, by an explicit user stop, or by the process dying.
4. The service starts while the application is visible, so it holds while-in-use capability.
5. The foreground service stops when playback permanently ends, and restarts only on a user action.
6. A dismissed task does not stop playback.
7. Nothing resumes playback on its own after a process death, a reboot, or a service restart.
8. Audio focus is requested, and each of the five documented outcomes behaves as the table says.
9. Becoming noisy stops the reader without speaking.
10. Every transport surface offers exactly the commands that are currently available, from the same
    published value the Player's controls use.
11. The notification carries exactly the documented content and actions, and no others; it contains no
    `Queue` control.
12. The notification is not a live region, and its content description is one announcement.
13. A denied `POST_NOTIFICATIONS` does not stop playback and is not reported as a playback error.
14. The Player no longer holds the display awake.
15. Audio focus is handled uniformly. No behaviour anywhere in the application is conditional on whether a
    screen reader is enabled or on which one is running, and every fact a listener must hear is spoken by
    the reader's own engine (§23.14).

# Architectural invariants

1. Nothing that must outlive the task lives in the task.
2. One session object, two front ends: the UI observes the session, and the media session transports to
   it. Neither is a second source of state.
3. Availability is evaluated once and published once; transports and controls read it, and never
   re-derive it.
4. A transport may not add, rename or redefine a command.
5. Launch never begins playback, including after a service restart.
6. Playback is not on the persistence path, and persistence is not on the playback path.
7. A failure in a supporting surface — a notification, a permission, a history write — is never reported
   as a playback failure.
8. A required announcement is spoken by the reader's TTS engine; the platform surfaces may accompany it
   and may never be its only mechanism.
9. The command layer holds no Android type; the transport layer holds no domain rule.

# Open questions

- **Whether a media session should exist while the session is merely paused.** This specification keeps the
  notification so a listener can resume, and does not hold the foreground claim with nothing playing. The
  exact point at which the platform tears the service down while paused is the platform's to decide, and
  the behaviour that matters — the listener can still resume, and nothing is lost — is stated.
- **`MediaLibraryService` and a browsable library.** Deliberately not adopted. The product contract's
  "Raw Notion navigation" is a different, deliberately secondary, form of navigation, and a platform
  media browser would create a second discovery model to keep in step with Browse.
- **Cast.** Not adopted, and the skill exists in the Android catalog if it is ever wanted. Casting a
  Note would have to answer what a "media item" is for a document with sections, which is a product
  question this specification does not open.
- **Whether `NextSection` should get a platform command of its own.** It cannot today: the platform has
  no command that means "next section". If a future Media3 version adds one, this mapping is amended
  here rather than being extended ad hoc in the implementation.
- **Screen-reader coordination.** Settled, not open: the application does not implement it
  (`docs/decisions.md` §23.13 and §23.14). A future decision to coordinate with a screen reader would have
  to answer what the coordination *is* and how it would be verified, and this specification records that
  the rejected alternative — ignoring transient focus losses while a screen reader runs — is not a
  coordination protocol so much as a special case.
- **The Active Queue & Reader Session spec's live-region wording.** **Closed.** The sentence was amended in
  Notion and re-mirrored (`docs/decisions.md` §23.11) to read "spoken by the reader's own TTS engine, and are
  also exposed as live regions", so this specification's dependency on the adopted reading is now a
  dependency on the text itself.

# Next implementation step

Phase 1 is the refactor that unblocks everything else and changes no behaviour: move the session to a
process-scoped holder, keep the JVM suite green, and prove by test that destroying the ViewModel no longer
ends the session. It should be done before the service exists, because a service that owns a session the
ViewModel also owns is the two-owners defect this project has removed twice, in the layer where it would
be hardest to see.
