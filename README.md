# Notion Reader (Android POC)

An Android, TalkBack-first reader that turns a Notion page into a sequential, hands-free reading
experience. It behaves like an audio/document player rather than a Notion editor.

Implemented so far: reader state and navigation, reader engine, Android TTS speech engine, the Note
and Player screens, Notion API/repository, **automatic section progression** (continuous hands-free
reading), a typed load-error taxonomy, block-derived stable identities proven to survive edits,
**Saved and Smart Queues**, **section outline and granular part controls**, **audio lifecycle hardening**,
**stale-while-revalidate document caching & offline playback**, **adaptive Player navigation stepping**,
and the **Settings hub & subpages with 10 core customization preferences**.

## Reading behaviour

- **Play** reads the current part and then continues automatically: each finished part advances to the
  next, and playback stops on its own after the last part (it does not wrap). A part is one speakable
  chunk, which is a paragraph, a heading, a list item or one of the pieces a long paragraph splits into.
  Reaching the end is reported as "End of note", which is distinct from a user pause: collapsing the
  two would tell a listener the wrong thing about why the reading stopped.
- **Pause** stops the utterance and keeps the position; **Play** starts that part again from the
  beginning, because Android TTS has no reliable pause/resume.
- Reaching the end of the last Note **speaks** "End of queue". It is spoken by the reader's own voice and
  not merely shown, because a listener with the screen off has no screen reader reading the state line to
  them: a queue that simply stopped would be indistinguishable, by ear, from a reader that had failed. It
  is said once per completion, and re-opening the Player does not say it again.
- **Previous section** / **Next section** while playing interrupt the current utterance and read the
  new section from its heading; while paused they only move the position, and they are disabled at the
  document boundaries rather than silently doing nothing.
- **Next section** goes to the start of the following section (the last part when already in the
  final one). **Previous section** returns to the start of the current section, or steps back a section
  when already there — so one press re-reads the section and two presses go back one.
- **Previous part** / **Next part** (available under *More playback controls*) move granularly by
  single speakable chunks (paragraph, heading, or list item), preserving playback state.
- **Restart note** returns playback to the beginning of the current note, restarting speech if playing.
- **Sections outline** (available under *Sections*) presents a hierarchical outline of all headings
  in the Note, highlighting the active section and enabling direct jumping to any section.
- Late completion callbacks from a cancelled utterance are discarded, so pause/next/previous can never
  cause an unexpected jump.
- **Previous note** / **Next note** move a whole Note within the frozen queue, and appear only when there
  is more than one Note to move between. Like the section controls they **preserve the playing state**:
  pressed while paused they move the position and stay silent, and pressed while playing they continue
  reading (announcing the new Note's title, since an unannounced Note change is indistinguishable from
  the reader having stopped). They are also the only way past a Note that will not load, which otherwise
  stalls the queue with Retry as its only offer.
- Audio focus and headset unplug events (`ACTION_AUDIO_BECOMING_NOISY`) are handled automatically,
  pausing playback when audio is disconnected or interrupted, with TTS routed to `USAGE_MEDIA`.
- The listening session survives a configuration change — rotating the phone, changing the font size, or
  the system switching to dark mode at dusk. Speech is not interrupted, and the queue, its position and
  its history entry are the same afterwards (`docs/decisions.md` §19.4).
- The display is held awake while playing so a hands-free session is not interrupted by the screen
  timing out. That is a stated workaround rather than a design: removing it is part of the specified
  background-playback work (`docs/background-playback-and-media-controls-spec.md`), which is not yet
  implemented.

## Screens

The UI contract separates manual reading from continuous listening, so the screens are not
interchangeable:

- **Home** — Continue listening (unfinished listening sessions from history with prominent "Continue"
  resumption), Your queues (recent Saved Queues, with "See all queues"),
  and a bounded Explore preview. "See all Explore" opens the complete, deterministic Area-then-Topic catalog; each entry reports its note count and opens Browse with the corresponding facet.
- **Browse** — the default result set, multi-facet filtering (Type, Area, Topics, Project, Status), search across titles and metadata, active filter summary, sort, and `Play all`.
- **Queues** — the Listening tab: Continue listening and Recently completed; the Saved tab: Saved Queues;
  the Smart tab: configured Smart Queues with live match counts.
- **Note** — manual reading and inspection. Renders the whole document in reading order; opening it
  never starts playback and moving focus through it never changes playback progress.
- **Player** — the continuous spoken-reading surface. Title, current section, progress, playback state,
  queue position, and adaptive primary controls: Previous / prominent center Play-Pause / Next, governed by a
  dynamic **Step granularity selector** (Section, Part, or Note), with polite TalkBack announcements and
- **Player** — the continuous spoken-reading surface. Title, current section, progress, playback state,
  queue position, and adaptive primary controls: Previous / prominent center Play-Pause / Next, governed by a
  dynamic **Step granularity selector** (Section, Part, or Note), with polite TalkBack announcements and
  the **Queue** snapshot button. Secondary controls are accessible via **More playback controls** (Restart note,
  Sections outline). Back returns to the previous screen while playback continues.
- **Active Queue** — the Player's `Queue` control opens a read-only view of the session's frozen
  snapshot: every Note, in order, with the current one marked. Selecting a row moves the session to that
  Note. There is deliberately nothing to edit — an Active Queue's membership and order are fixed for the
  session's lifetime, and "Saved Queues are edited, Smart Queues are configured, Active Queues are
  played" (`docs/decisions.md` §22).
- **Settings** — the 4th top-level tab: a TalkBack-first hub of 7 categories (`Playback`, `Speech`, `Offline & storage`,
  `Appearance`, `Notion`, `Privacy & data`, `About`). Each category is a single accessibility focus
  stop announcing its name and current summary. Selecting a category navigates to a focused secondary subpage with
  dedicated controls, with Back cleanly restoring focus to the originating category row. Built on reusable design system
  primitives (`SettingsScreenLayout`, `SettingsSection`, `SettingsValueRow`, `SettingsSwitchRow`, `SettingsActionRow`,
  `SettingsDestructiveRow`, `SettingsStatusRow`, `SettingsSingleChoiceDialog`, `ClearCacheConfirmationDialog`), native
  Compose accessibility semantics (`Role.Button`, `Role.Switch`, `Role.RadioButton`), and fine-grained `SpeechRate`
  slider adjustments (0.5×–2.0× in 0.05× steps) alongside system default support.

Queues ships **all three tabs**: **Listening**, **Saved**, and **Smart**. Settings is
fully populated with the core customization preferences.

The **Saved** tab lists the queues the listener built, most recently edited first, each named with its
member count, and offers `Create saved queue`. Activating a queue opens **Saved Queue Details**, which
shows its members in order and offers `Play`, `Edit queue`, `Rename` and `Delete` (with confirmation dialogs).
`Play` snapshots the queue's current membership and order into a new session, and is unavailable on an empty queue
with the reason stated. A member row opens its Note and does not play it.

The **Smart** tab lists the configured query-based queues, each with its current workspace match count,
and offers `Create smart queue`. Evaluates queries through a metadata-aware filter supporting structured tokens (`topic:`, `area:`, `project:`, `status:`, `kind:`, `title:`) and multi-field matching across all Notion metadata. Activating a queue opens **Smart Queue Details**, previewing the
matching notes, offering `Play query` (snapshotting matching notes into a new session), `Rename`, and
`Delete` (with confirmation dialogs).

A queue is filled through the contract's **Add** path: `Add to queue` on a Note and `Add all to queue` on
Browse both open **Queue Destination**, which lists the Saved Queues and `Create new queue`. Choosing one
appends the captured Notes, de-duplicates by identity, and lands on that queue's Details with a report of
what happened — including, in the all-duplicates case, that **nothing** was added, which is a success and
is said as one. Creating a queue from the chooser receives the Notes, so "these things, into a queue" is
completed rather than abandoned at an empty screen.

A queue's membership is changed through **Edit mode**: `Edit queue` on Details reveals the four accessible
moves (`Move up`, `Move down`, `Move to top`, `Move to bottom`) and `Remove` on every member, with `Done` to
leave. Reordering needs no gesture, the moves are unavailable rather than inert at the boundaries, and each
one announces the member and its new position. Edits apply as they are made, so there is nothing to lose;
and Back leaves Edit mode rather than the screen, so the most reflexive gesture cannot commit a change.

The Note's `Add to queue` is present, and so is Browse's `Add all to queue`: both route to Queue
Destination and neither touches playback, because neither is given a way to reach the session.

## Install on a device

```
.\gradlew.bat :app:assembleDebug
$adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On the device, add a text-to-speech engine (Google Speech Services is usually present) and enable
TalkBack to test accessibility. Playback is hands-free once you press **Play**.

## Not yet implemented

- The mini-player's own screen. It renders today, but not as a destination with its own surface.
- Background/lock-screen playback and media buttons (Media3 `MediaSessionService`). The Player holds the
  screen awake with `keepScreenOn` as a stated workaround, so this is the largest gap in a listening app.
  It now has a **governing specification** (`docs/background-playback-and-media-controls-spec.md`) and is
  **ranked first** among the outstanding work (`docs/decisions.md` §23, D28) — but no code has been written
  for it, and the specification's own Phase 1 is a refactor of the session's lifetime rather than the
  service itself.
- Sentence-level resume after pause, and finer offsets inside a chunk (`characterOffset` is carried
  but always 0). Resume is at chunk granularity.
- A spoken announcement when a restored position is *lost* has not been observed on a device — the four
  resolution paths are covered by unit tests, but only an unchanged-document restore is verified on-device.
- Image and file captions. A caption is speakable text and is currently lost, which the coverage
  census measured at 8 blocks in this workspace.
- Ordered-list numbering: one `LIST_ITEM` kind serves both list styles, so numbering is not recoverable
  on the Note screen.


## Architecture

The layers are the Product & Architecture Contract's, and `LayerRulesTest` fails the build if a package
depends on a layer above it, if a file's package disagrees with its directory, or if a layer named in the
rule table does not exist. The names below are packages under `com.example.notionreader`.

```
platform (Android TTS adapter)
        \
         v
data.notion   data.content   data.local          domain (+ queue / session / discovery)
        \           |              /                       ^
         \          v             /                        |
          ----> application -> player ---------------------+
                    ^
                    |
                   ui  (screens; depends on domain, player and application)

The root package holds the composition root: `ReaderApplication` (the process-scoped owner of the graph), the
`ReaderHost` it builds, `MainActivity` (the Compose host) and `ReaderViewModel` (the I/O a screen's own
action starts). Only the root may name `data`, `platform` and `application` together, which is what wiring is.
```

- `domain/` — the provider-agnostic model, and nothing else. `ReaderDocument`, `ReaderSection`,
  `ReaderChunk`, `ChunkMetadata`; `ReaderDocumentPosition` and the resolver that reports how much of a
  saved position survived an edit; the `ReaderErrorCategory` taxonomy. `chunkTexts` is the flat projection
  playback consumes.
- `domain/queue/` — `ActiveQueue`, `QueuedNote`, `QueueOrigin`, `QueueState`.
- `domain/session/` — `StoredSession`, the `SessionStore` port, `ListeningHistory` (retention and
  ordering) and `ResumePolicy`. Pure, and JVM-testable with no dispatcher.
- `domain/discovery/` — normalized discovery metadata and the query over it.
- `player/` — `ReaderState` (the immutable reading position and navigation), `ReaderEngine`, and the
  `SpeechEngine` port. Depends only on `domain`.
- `application/` — the use cases and application state: `ReaderSession` (the owner of playback),
  `NoteViewer` (the inspected Note, deliberately separate), `QueueLoader`, `BrowseModel`,
  `LoadReaderDocument`, `ReaderNavigator`, and the read models `ContinueListeningCard` and
  `HistoryEntry`.
- `data/notion/` — `NotionApi` (HTTP, retry, error mapping), `NotionModels` (JSON), `NotionRepository`, `NotionTokenProvider` port.
- `data/content/` — `NotionBlockNormalizer` (Notion block types → `NormalizedBlock`),
  `ReaderDocumentMapper`, `TextChunker`.
- `data/document/` — `CachedDocumentRepository` (stale-while-revalidate caching and offline playback orchestration), `DocumentCache` port.
- `data/local/` — `SessionCodec` (the versioned wire form), `DataStoreSessionStore`, `FileDocumentCache` (concurrency-safe atomic cache in `noBackupFilesDir` with LRU pruning), and `ReaderDocumentCodec`.
- `platform/` — `AndroidSpeechEngine`.
- `ui/` — state and callbacks only, no business logic.

Two rules matter more than the names. **Notion DTOs never reach the reader domain** — the mapper consumes
`NormalizedBlock`, not `NotionBlock`, which is why the content-mapping code lives in `data/content` while
the types it produces live in `domain`. And **`application` never depends on `ui`**: a read model the
application builds and screens render belongs to the application, which is why `ContinueListeningCard` is
where it is rather than in the file that first drew it.

A third is about lifetime rather than direction. **Nothing that must outlive a screen lives in one.** The
listening session is owned by the root package's `ReaderViewModel`, not by `MainActivity`, because the
system rebuilds the Activity for every configuration change — rotating the phone, changing the font size,
or the system switching to dark mode at dusk. While the session was owned by the Activity, any of those
ended playback mid-sentence, discarded the queue and its position, and started a fresh session on the demo
document (`docs/decisions.md` §19.4). `tools/probe-config-change.ps1` drives exactly that on a device and
reads what survived.

Because of that split, the hard-coded `ReaderDocument.Demo` and a `NotionRepository`-produced document
are interchangeable without touching navigation.

## Credentials

Never commit a token. The build reads local development credentials from, in order:

1. `local.properties` — `NOTION_TOKEN=...` (Android convention, git-ignored).
2. `.env` in the repository root, either as `NOTION_TOKEN=ntn_...` **or** as a file containing nothing
   but the bare token with no key at all. Quotes, `export ` prefixes, `#` comments, CRLF and a UTF-8 BOM
   are all tolerated. See `.env.example`.

`NOTION_PAGE_ID` / `NOTION_PAGE_URL` (a dashed UUID, an undashed ID, or a full `notion.so` URL) **is
resolved and reported by the build but read by nothing at runtime.** It used to select the page loaded
at launch. The app now starts on Home and reaches a Note through a Browse result, which names the page
itself, so a page chosen at launch could only ever have been the wrong one — the eager load that
consumed this value was removed (`docs/decisions.md` §17). The reader's auto-discovery path is in the
same position: a blank page reference resolving to the most recently edited page via `POST /v1/search`
is correct and tested, but only tests reach it. Both are recorded as open items rather than deleted,
because pinning the app to one known page is how the pagination and block-coverage acceptance criteria
were verified on device, and the shell will likely want a way to do that again.

`NOTION_TOKEN` and `NOTION_PAGE_ID` are exposed through `BuildConfig`, which means they are compiled
into the APK. That is fine for local development only; a shipped app must fetch the token at runtime.

If no token is configured the app simply keeps reading the hard-coded demo document, so the build and
the reader never require network access or credentials.

## Notion API notes

Grounded in the published reference and request-limit documentation:

- `Authorization: Bearer <token>` plus `Notion-Version` on every request; the version constant is
  `2026-03-11` (`KtorNotionApi.NOTION_VERSION`).
- Page content is read with `GET /v1/blocks/{page_id}/children`, paginated with `start_cursor` and
  `page_size` (Notion caps `page_size` at 100).
- `GET /v1/pages/{page_id}` supplies the title; the property whose `type` is `title` is used.
- Every failure is mapped onto the integration spec's nine-category taxonomy
  (`reader/ReaderErrorCategory`), and **retry policy is derived from the category, not the status
  code**, so "retry only transient failures" and "do not retry permission failures" are decided in
  exactly one place. Notion's own `code` wins over the HTTP status, because `unauthorized` and
  `restricted_resource` can both arrive as 403.
- HTTP 429 (`rate_limited`, honouring `Retry-After`) and 529/5xx are retried. Nothing else is: a 401 or
  403 is reported on the first response, since repeating it cannot change the answer.
- Each failure carries two strings — a one-sentence user-facing `message` that gets read aloud, and a
  `diagnostic` with the status, code and Notion's message that only ever goes to a log.
- Blocks with `has_children` are read as nested content in document order (nested list items, toggles,
  columns), bounded by a depth limit whose breach is reported as a diagnostic rather than silently
  discarding content. `child_page`, `child_database` and `synced_block` are not descended into.
- Supported block types: `paragraph`, `heading_1..3`, `bulleted_list_item`, `numbered_list_item`,
  `to_do`, `quote`, `code`, `divider` (structural only), plus `toggle` and `callout` as containers whose
  own text is readable. Unsupported and empty blocks are skipped instead of failing the page.
- Both `in_trash` (2026-03-11+) and the older `archived` field are decoded, so the app works across API
  versions.
- This API version **rejects the `after` parameter in both the body and the query**, so blocks cannot be
  inserted at a position — appends only land at the end. Tooling works around this rather than assuming
  it.

## Stable identities

Chunk ids are `<sourceBlockId>:<part>` and section ids are the heading block's id, so nothing is keyed
on a list position. `ReaderDocumentPosition` is what the session layer persists, and
`ReaderDocument.resolve` reports how much of a saved position still exists (`CHUNK` / `BLOCK` / `SECTION`
/ `NONE`) while deciding nothing about it — what to do with each outcome is `reader/ResumePolicy.kt`, and
belongs to the Active Queue & Reader Session specification.

`ListeningHistory` (retention and ordering) and `SessionCodec` (the versioned wire form) are pure, and
`SessionStore` is the interface with `InMemorySessionStore` in tests and `DataStoreSessionStore` on the
device. A record whose `schemaVersion` is not the current one is discarded rather than migrated, which is
a deliberate v1 policy: a migration that guesses wrong moves a listener to a place they were not.

The claim that ids survive edits is a fact about Notion, not about our mapper, so it is verified
against the real API rather than a hand-edited fixture:

```
pwsh -File tools/verify-identity-stability.ps1
```

It edits the fixture page in place, captures the edited state, restores the page, and *fails* if the
restore does not verify. The two snapshots it produces are committed, so `ReaderIdentityStabilityTest`
runs offline in CI against real API data.

## Build and test

```
./gradlew.bat :app:testDebugUnitTest       # 534 JVM tests
./gradlew.bat :app:assembleDebug
```

Ktor must be 3.6.0 or newer: 3.2.0 contains a field name with spaces that R8 cannot dex for `minSdk 26`
(`Space characters in SimpleName 'use streaming syntax' are not allowed prior to DEX version 040`).

The JVM suite includes the architecture rules (`LayerRulesTest`) and the frozen stored-session wire
format (`SessionWireFormatTest`), both of which exist to fail loudly on changes that would otherwise
be silent.

### Instrumented accessibility tests

The 180 tests under `app/src/androidTest/` assert on the semantics tree — the thing TalkBack actually
consumes — so they need a device or emulator. Run them through the wrapper rather than invoking Gradle
directly:

```
pwsh -File tools/run-accessibility-tests.ps1
```

The wrapper exists because the device has to be prepared in ways a plain Gradle run cannot do:

- **It requires the device to be unlocked, and refuses to run otherwise.** With the keyguard up the test
  activity never reaches the foreground, so no Compose hierarchy is drawn and *every* test fails with
  "No compose hierarchies found in the app". That is very nearly the whole suite failing, in exactly the
  shape of a catastrophic regression, while the only thing wrong is that a phone on a desk locked itself.
  The wrapper wakes the display, dismisses the keyguard and verifies that it went, retrying because a
  dismiss issued in the same breath as the wake can race. It will not attempt a secure unlock: that needs
  the owner's PIN.
- **It raises `screen_off_timeout` for the duration**, because a stock 30-second timeout is shorter than
  a run and a display that sleeps mid-test is indistinguishable from a layout bug. The original values
  are restored in a `finally`. `stay_on_while_plugged_in` is set too, but it only has any effect while
  charging, so on battery — the usual case — `screen_off_timeout` is what keeps the display on. The
  wrapper says so rather than implying otherwise.

Two other things are pinned deliberately, both because the failure they prevent is misleading:

- **`espresso-core:3.7.0`.** `ui-test-junit4` pulls in Espresso 3.5.0 transitively, whose idle path
  reflects on the hidden `InputManager.getInstance()` on Android 17 / API 37. The symptom is *every*
  test failing in `Espresso.onIdle`, which reads as a broken suite rather than a stale library.
- **No test orchestrator, deliberately** (`docs/decisions.md` §23.31, §23.32). It was added to stop one
  transient device-side process death from aborting a whole run — and it does that — but it buys that with
  **one process start per test**, 180 of them on an emulator whose process budget the orchestrator then
  competes for. The half of its justification that mattered most, that a truncated run must not read as a
  clean one, is now handled by name instead: the runner reports **INCOMPLETE** whenever fewer tests ran
  than the sources contain. What was given up is recorded in `app/build.gradle.kts`: no test here drives the
  real `MainActivity` or touches on-disk session state, so neither loss is load-bearing.

Controls are driven through `SemanticsActions.OnClick` rather than synthetic taps. That is what an
accessibility service performs, and unlike a synthesised touch it cannot be silently dropped by the
device's window.

**What this suite costs, measured** (`docs/decisions.md` §24): **180 tests in 873 s — 4.7 s each**, against
**534 JVM tests in 0.8 s (~1.5 ms each)**. The instrumented cost is *flat across every class*, which makes it
fixed per-test setup — Activity launch, instrumentation, Compose idle, teardown — rather than the assertions.
So the JVM suite is the default check (automated on PRs and pushes via GitHub Actions in `.github/workflows/ci.yml`), `-Class` is the way to iterate, and the plan is to move the pure-UI
classes to Robolectric and leave only focus, Back dispatch and one smoke test on a device. That move is a
**spike first**, because Robolectric does not render and these helpers assert geometry.

A real failure is retried only when **every** failing test matches a short list of harness signatures —
the harness losing the activity, the test rule failing to tear its own Activity down, or the locked-device
signature above. Any unclassified or mixed result is red immediately, because a retry must never be able to
launder a real defect into a pass. A failure recorded with **no message and no trace** counts as the harness
rather than as the app (`docs/decisions.md` §23.32): an assertion failure always records a stack trace, so an
empty record means the process never produced one. The runner also compares the failures the reports counted
against the per-test records it read, and prints `UNACCOUNTED` when they disagree — so a dropped failure
cannot be silent again.

**A partial run is never reported as a pass** (`docs/decisions.md` §23.30), and once it could have been.
The pass condition was "no failures" with nothing comparing how many tests ran against how many exist — so
a run whose device died mid-suite reported `tests=125` against a suite of 168, four classes simply absent,
and only came out red because one of the classes that *did* run happened to fail. It now reports
**INCOMPLETE** below the bar, and the bar is counted from the `@Test` annotations in the sources rather than
remembered, so it cannot go stale. `tools/check-wrapper-completeness.ps1` proves a truncated run cannot
pass.

**The focus-restore assertions pass, and the reason they once did not was in the app**
(`docs/decisions.md` §23.29). They failed for three rounds under explanations that each blamed the device —
TalkBack holding accessibility focus, then touch exploration, then an API-37 interaction. The measured cause
was that `clickable` gives a `FocusRequester` no target to be granted against: a request on a
`clickable`-only node is refused, while the same chain with an explicit `focusable()` before it is granted.
`restoreFocusOn` now adds that `focusable()`, and all seven pass — on the emulator and on the phone.

The measurement that settled it came from a clean emulator (`tools/setup-emulator.ps1`) rather than from
reasoning, which is why a screen lock is no longer on the path of an instrumented round. What remains
unobservable by any test is whether TalkBack's *speech* focus follows a programmatic request — that is the
integration spec's listening gate, and it needs ears.

Note: the `connectedDebugAndroidTest` task has been seen to exit non-zero when every test passed, and also
to exit 0 with **168 tests green** on an emulator — so treat the exit code as a device-specific observation
rather than a rule. The wrapper does not rely on it either way: it derives its own exit code from the
collected results, reading both the XML and the HTML report and using the worse count, because the two have
disagreed in both directions on this project. The HTML report is at
`app/build/reports/androidTests/connected/debug/index.html`.

## Accessibility rules

Every rule below is a requirement of the UI contract, not a preference.

**The app speaks through its own TTS engine.** It does not implement behaviour aimed at a screen reader
and does not depend on one being present: every fact a listener must hear is spoken by the reader itself,
so it is heard with the screen off and with no screen reader running. The semantics below exist so the
interface is *usable* with one, which is a different thing from coordinating with one
(`docs/decisions.md` §23.14).

- Every control has a self-explanatory accessible name: "Previous section", "Play", "Pause",
  "Next section". Nothing is called "back", "forward" or "button".
- Each screen names itself with a pane title, so entering it announces where the user is.
- The reading position is focusable text ("Part 4 of 12", "Section 2 of 4 · Primary Heading").
  The unit is the **part** — one speakable chunk — because that is what the number counts: a heading, a
  list item and each of the pieces a long paragraph is split into are all parts. An empty document reads
  "Nothing to read".
- Playback state is a **polite live region**, because paused, resumed and end-of-note are required
  announcements. The position line deliberately is **not** live, so it cannot talk over the document as
  every paragraph advances it.
- Controls that cannot act are disabled rather than inert, so TalkBack announces them as unavailable
  instead of letting a press do nothing.
- On the Note screen, decorative bullets and check glyphs are cleared from the semantics tree so
  TalkBack reads the item, not "bullet, item text"; a to-do's state is exposed as a state description
  instead.
- Nothing is force-focused, so TalkBack and the document TTS engine do not talk over each other.
- Loading, empty and error are three distinct textual states — loading is never conveyed by animation
  alone — and an error offers Retry only when its category says a retry could help.
- Pause stops the current utterance and keeps the position; Play restarts that part from the
  beginning, since Android TTS has no reliable pause/resume.
- Moving between screens never touches playback, and opening a Note never starts TTS.
