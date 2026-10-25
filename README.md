# Notion Reader (Android POC)

An Android, TalkBack-first knowledge exploration and listening companion for Notion.
Discover searches cached Notes by meaning with optional local embeddings, explicit
interests, related Notes and guided listening. Manual reading and hands-free
playback remain available without a model.

Discovery refinement is in progress ([current round](docs/decisions.md#111-storage-review-and-automatic-discovery-interest-audits-2026-10-24)).
Storage presentation is coordinated across Discovery and Offline & storage: usage-first accounting,
radio-selected Automatic vs custom maximums, separate model file reporting, truthful consequences and
accessible expandable policy explanations with discoverable shared larger-limit preferences.
Usage uses binary units and includes incomplete model files. New cached-Note generations publish
automatically outside inspected Discovery views; inspected ordering stays pinned until Refresh.
Interest edits and removal reject obsolete matching failures, and seed changes preserve saved order.
Search errors remain visible during background indexing, new queries withdraw old
matches, and index resets reject late failures. Memory-bound topic coverage is
reported explicitly while previous discoveries remain available.
Validated Note connections are retained independently of topic membership and
homepage limits, with separate reference/similarity evidence, on-demand catalog reads,
pinned inspection, bounded lazy browsing and privacy-safe page retirement.
Topic publication is prepared in the background generation. Opening topic or
connection details reads saved evidence without starting model inference; paged
connections may briefly load from local storage. Clearing the Discovery index also
rejects connection reads already in flight, including listening-preview requests.
Initial cache checking is distinct from an empty cache, so saved-discovery preparation
does not ask users to reopen content that is already cached. Discovery screens and
settings share process-owned lifecycle status and indexing actions: checking,
processing, explicit pause, automatic waits, completion and recovery stay distinct.
Valid cached search and listening remain available while bulk discovery is paused.
Subject details can expose source-supported broader subjects, subtopics and related
subjects. The initial hierarchy requires affirmative source statements; general
automatic hierarchy recall, representative evaluation and
full-scale physical-device/human acceptance remain open. The optional embedding pipeline now uses EmbeddingGemma 2 INT4.
The [500-document public experiment](docs/discovery-wos-evaluation.md) exercised
MiniLM, BGE-small and EmbeddingGemma 2, exposed generic container labels and led to
a publication fix. The user subsequently requested Gemma integration and device verification; category-pair diagnostics do not establish human relevance acceptance.
Requested preparation waits for at least 30% battery or external power, with
device-based remaining embedding-time estimates. Subject and connection analysis
is additional; its duration is not yet estimated.

For the governing contracts, current implementation boundary and historical evidence, start with the
[documentation reading map](docs/README.md). This README is a derived repository summary.

Implemented so far: reader state and navigation, reader engine, Android TTS speech engine, the Note
and Player screens, Notion API/repository, **automatic section progression** (continuous hands-free
reading), a typed load-error taxonomy, block-derived stable identities proven to survive edits,
**Saved and Smart Queues**, **section outline and granular part controls**, **background playback and media controls**, **audio lifecycle hardening**,
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
- Background and screen-off playback use Media3 `MediaSessionService`. Notification, lock-screen and
  headset controls use the existing Player commands. The session and queue-loading loops live in the
  process-scoped `ReaderHost`; leaving a screen or dismissing the task does not own their lifetime.
- Native speech errors, unexpected stops and rejected submissions pause at the current part with a
  retry message. Cancelled or superseded requests cannot complete or fail a newer request. Human
  TalkBack navigation/listening acceptance remains open after an observed external TTS interruption.


## Screens

The UI contract separates manual reading from continuous listening, so the screens are not
interchangeable:

- **Home** — the default destination: Continue listening, up to three other recent
  listening sessions, five recently opened cached Notes and three interest cards.
  Queue labels come from frozen listening history. Recent Note references stay
  private, retain no content snapshot and clear with history/full reset.
- **Discover** — semantic search, Topics in your Notes, connections and one
  **View your interests** entry. It contains no listening-history or queue-preview
  section. An unavailable model has one setup card; model/index controls live in Settings.
- **Browse** — keyword search, metadata facets (Type, Area, Topics, Project, Status),
  sorting and `Play all`. Its **Explore** entry opens the complete deterministic
  Area-and-Topic catalog; selecting an entry opens pre-filtered Browse.
- **Queues** — the Listening tab: Continue listening and Recently completed; the Saved tab: Saved Queues;
  the Smart tab: configured Smart Queues with live match counts.
- **Note** — manual reading and inspection. Renders the whole document in reading order; opening it
  never starts playback and moving focus through it never changes playback progress.
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
- **Settings** — a top-level TalkBack-first hub (`Playback`, `Speech`, `Offline & storage`,
  `Appearance`, `Notion`, `Discovery`, `Privacy & data`, `About`). Each category is a single accessibility focus
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
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On the device, add a text-to-speech engine (Google Speech Services is usually present) and enable
TalkBack to test accessibility. Playback is hands-free once you press **Play**.

## Implementation limits and acceptance gaps

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

```text
domain      -> nothing
player      -> domain
application -> domain, player
data        -> domain
platform    -> player
ui          -> domain, player, application
```

The root package holds the composition root: `ReaderApplication` (the process-scoped owner of the graph),
its `ReaderHost`, `MainActivity` (the Compose host) and `ReaderViewModel` (screen-started I/O).
Only the root may name `data`, `platform` and `application` together for wiring.

- `domain/` — the provider-agnostic model, and nothing else. `ReaderDocument`, `ReaderSection`,
  `ReaderChunk`, `ChunkMetadata`; `ReaderDocumentPosition` and the resolver that reports how much of a
  saved position survived an edit; the `ReaderErrorCategory` taxonomy. `chunkTexts` is the flat projection
  playback consumes.
- `domain/queue/` — `ActiveQueue`, `QueuedNote`, `QueueOrigin`, `QueueState`.
- `domain/session/` — `StoredSession`, the `SessionStore` port, `ListeningHistory` (retention and
  ordering) and `ResumePolicy`. Pure, and JVM-testable with no dispatcher.
- `domain/discovery/` — keyword Browse, semantic model/index/interest ports, source-aware
  token-bounded chunking, vector validation and deterministic Note-level ranking.
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
- `platform/` — `AndroidSpeechEngine` and Player/media adapters; the root `PlaybackService` wires Media3 to the process-owned session.
- `ui/` — state and callbacks only, no business logic.

Two rules matter more than the names. **Notion DTOs never reach the reader domain** — the mapper consumes
`NormalizedBlock`, not `NotionBlock`, which is why the content-mapping code lives in `data/content` while
the types it produces live in `domain`. And **`application` never depends on `ui`**: a read model the
application builds and screens render belongs to the application, which is why `ContinueListeningCard` is
where it is rather than in the file that first drew it.

A third rule is about lifetime. **Nothing that must outlive a screen or task lives in one.**
`ReaderApplication` builds the process-scoped `ReaderHost`, which owns the listening session, active
queue and the loops that advance and load Notes. `ReaderViewModel` handles screen-started work;
clearing it does not shut playback down. Configuration-change and background/task-lifecycle evidence
is recorded in [decisions](docs/decisions.md).

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

The native Gemma tokenizer build also requires Rust/cargo, PowerShell 7 and Android
NDK 29.0.13846066. Gradle builds the locked native crate for the supported Android
ABIs and Windows JVM host. See [model/device tooling](tools/semantic-discovery/README.md#active-gemma-int8-android-verification)
for prerequisites, explicit fixture staging and device checks. The optional model
is downloaded only through Discovery settings.

```
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

Ktor must be 3.6.0 or newer: 3.2.0 contains a field name with spaces that R8 cannot dex for `minSdk 26`
(`Space characters in SimpleName 'use streaming syntax' are not allowed prior to DEX version 040`).

The JVM suite includes the architecture rules (`LayerRulesTest`) and the frozen stored-session wire
format (`SessionWireFormatTest`), both of which exist to fail loudly on changes that would otherwise
be silent.

### Instrumented accessibility tests

Tests under `app/src/androidTest/` assert on the semantics tree — the thing TalkBack actually
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
  **one process start per test**, on an emulator whose process budget the orchestrator then
  competes for. The half of its justification that mattered most, that a truncated run must not read as a
  clean one, is now handled by name instead: the runner reports **INCOMPLETE** whenever fewer tests ran
  than the sources contain. What was given up is recorded in `app/build.gradle.kts`: no test here drives the
  real `MainActivity` or touches on-disk session state, so neither loss is load-bearing.

Controls are driven through `SemanticsActions.OnClick` rather than synthetic taps. That is what an
accessibility service performs, and unlike a synthesised touch it cannot be silently dropped by the
device's window.

**Historical cost measurement** (`docs/decisions.md` §24): **180 tests in 873 s — 4.7 s each**, against
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


## Local semantic discovery

Discovery is optional and automatic. Settings → Discovery → Download model (also reached through
Discover's Set up Discovery) installs the text-only EmbeddingGemma 2 INT4 pack (165 MB, including its bundled tokenizer). Downloads
use the published official LiteRT-community bundle, including its authoritative SentencePiece tokenizer with pinned revisions and verified checksums; Wi-Fi-only is the default. LiteRT-LM CPU inference
runs locally. Note content and queries are not sent to an embedding service, and model binaries
are not bundled in the APK. Bundled SentencePiece tokenization with UTF-16 source offsets and 768-dimensional sentence embeddings replace incompatible derived data while preserving explicit interests and listening state. Browse and listening work without the model.

The published LiteRT bundle and official clustering prefix are verified on the API36
x86_64 emulator and Samsung S25 (SM-S931B/API37): real inference and the public
six-Note indexing/topic/connection/search/evidence-navigation journey pass on both.
The S25 also passes Settings acquisition through the production downloader.
See [the migration evidence](tools/semantic-discovery/litert-migration-2026-10-25.json)
and decision §115. These are scoped device checks, not representative quality,
peak-memory, API26 runtime or human TalkBack speech acceptance.

Only cached Notes are indexed. Semantic search has its own metadata filters and relevance order;
Browse keyword search and Smart Queues retain their behavior. Results show literal source passages.
Automatic topics may overlap, and Notes may remain ungrouped. Topics and connections do not infer
personal interests from reading or listening activity. Named references identify explicit citations
of a unique cached Note's complete title and remain distinct from Similar passages; they do not
promise a verified Notion hyperlink.

Follow topic opens confirmation of a name and editable matching text, saving an independent interest.
Future regrouping, renaming or source loss does not rewrite it. Remove it through Your interests.
Text interests and cached seed-Note interests use the shared collection and details routes, with
compact cards on Home. Interest details includes matching Notes, an older-matches filter (last edited
at least 90 days ago), and a listening preview. Preview allows removing/reordering up to five Notes;
Save uses Saved Queue destinations. Only explicit Play creates a new frozen listening session.

Topic details put member Notes and passage evidence first; Why these Notes? holds provenance.
Connections show a small counted preview with access to the complete pageable catalog.
The catalog separates evidence kinds and offers Refresh when newer discoveries are
available. Comparison cards avoid repeating both Note titles in their action hint.
Connections open Compare passages with complete excerpts. Inspected topic generations adopt new
ordering and labels through explicit Refresh, while revoked or evicted source content is removed
immediately. An unavailable preview entry retains its position without source text and requires
recaching or removal before Play/Save.

One process-owned coordinator serializes Discovery work off the main thread.
After setup it continues automatically in bounded foreground or ordinary WorkManager
background windows, including screen-off operation. Playback, memory pressure and
explicit Pause suspend processing. Balanced requires 30% battery or external power;
Charging only requires external power. Android may defer execution. Durable passage
progress lets long Notes resume across windows without restarting their embeddings.
Created/updated Notes reuse unchanged passage embeddings, including across deferred
work and process restarts. Connection updates scan changed endpoints and unfinished
seeds instead of replaying completed unchanged pairs. Certified isolated topic groups
survive unrelated changes; coupled or uncertified groups use exact regrouping.
Versioned durable checkpoints resume eligible work. All derived
vectors, topics, connections and checkpoints grow automatically in private storage,
with an optional custom maximum (initially 256 MiB). Writes preserve device headroom
of at least 2 GiB or 10% of volume capacity, whichever is greater. The Note cache
defaults to 128 MiB, sized for about 100 Notes of up to 1 MiB encoded size each,
and retains a separately displayed 200-Note guard. Accessible storage sliders,
confirmed cache reductions and an optional extended maximum give explicit control.
Per-record memory safeguards remain separate from library capacity. Exact cosine search
streams per-document vectors. Clearing the index pauses rebuilding until Resume/Rebuild; deleting
the model clears the index and automatic continuity while preserving explicit interests. Full local
reset clears interests too. Cache eviction removes derived content but retains opaque recache
continuity; confirmed deletion or access loss removes that continuity as well.

### Discovery architecture and acceptance

All builds use one section-aware hierarchical engine, owned by the process coordinator.
Indexing, candidate formation, passage projection, evidence validation,
naming, publication, independent connections and persistence run off the UI thread.
There is no build flag, fallback engine or legacy migration execution. Incompatible
old derived generations restart while compatible embeddings and explicit user state
remain. Broad/focused subjects may overlap; established topics require two Notes and
Emerging topics one. Labels come from retained evidence independently of membership
wording. Connection progress includes passages late within a Note.

Architecture adoption does not establish quality or resource acceptance. The prior physical Pixel
10,000-passage/2,500-section synthetic formation benchmark completed in about 15 seconds but measured
about 189 MiB added Java/native memory against a 128 MiB limit. This model-free repeated-direction
workload does not establish varied real-library quality, model-loaded resource use, playback
coexistence or human TalkBack speech/listening acceptance. A separate isolated physical search
benchmark met its warm-query target; it does not establish recommendation quality or formation
acceptance. Candidate counts and provisional editorial annotations are not verified human judgments.

Human quality evaluation is developer acceptance work on public, consented or purpose-built Notes.
Users do not need to label, approve or submit their private library to use Discovery; private feedback
is optional. See the [implementation and acceptance contract](docs/semantic-discovery-implementation-spec.md),
[reproducible model/device evaluation](tools/semantic-discovery/README.md) and
[decision and verification history](docs/decisions.md) for thresholds, evidence and outstanding gates.


The subsequent API36 emulator formation check used section-specific perturbations,
10,000 passages / 2,500 uneven sections and a model-loaded idle baseline: 42.8 s
with 46.7 MiB sampled added Java/native memory. This scoped synthetic result does
not certify physical playback, human quality or TalkBack listening acceptance.
See decisions.md §76 for migration, regression proofs and remaining gates.


Device backend research is recorded in [the Gemma benchmark](docs/gemma-device-backends-2026-10-18.md).
These are historical ONNX backend results. The current default uses the published
EmbeddingGemma 2 text-only INT4 bundle through LiteRT-LM CPU on arm64-v8a and x86_64.
No model conversion or export is performed. QNN NPU remains a fixed-length research prototype; those results
do not establish general Note or Discovery quality acceptance.

The [Vulkan follow-up](docs/gemma-vulkan-2026-10-19.md) verifies Gemma GPU
execution on S25 through ONNX Runtime WebGPU/Dawn. Pixel basic Vulkan compute
works, but Gemma GPU readback failed in that historical ONNX investigation.
The LiteRT migration uses CPU; those GPU measurements do not qualify LiteRT acceleration.


Discovery efficiency now retains one prepared generation across processing slices,
saves progress at orderly yields and phase handoffs, and uses authored areas/topics
only for conservative numerical exclusion of nonmatching evidence. Exact-input
embedding reuse is bounded and cleared on reset/source withdrawal/resource release.
See [the architecture review](docs/discovery-architecture-review-2026-10-19.md).
