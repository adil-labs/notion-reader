# Notion Reader (Android POC)

An Android, TalkBack-first reader that turns a Notion page into a sequential, hands-free reading
experience. It behaves like an audio/document player rather than a Notion editor.

Implemented so far: reader state and navigation, reader engine, Android TTS speech engine, the Note
and Player screens, Notion API/repository, **automatic section progression** (continuous hands-free
reading), a typed load-error taxonomy, and block-derived stable identities proven to survive edits.

## Reading behaviour

- **Play** reads the current paragraph and then continues automatically: each finished paragraph
  advances to the next, and playback stops on its own after the last paragraph (it does not wrap).
  Reaching the end is reported as "End of note", which is distinct from a user pause: collapsing the
  two would tell a listener the wrong thing about why the reading stopped.
- **Pause** stops the utterance and keeps the position; **Play** starts that paragraph again from the
  beginning, because Android TTS has no reliable pause/resume.
- **Previous section** / **Next section** while playing interrupt the current utterance and read the
  new section from its heading; while paused they only move the position, and they are disabled at the
  document boundaries rather than silently doing nothing.
- **Next section** goes to the start of the following section (the last paragraph when already in the
  final one). **Previous section** returns to the start of the current section, or steps back a section
  when already there — so one press re-reads the section and two presses go back one.
- Late completion callbacks from a cancelled utterance are discarded, so pause/next/previous can never
  cause an unexpected jump.
- The display is held awake while playing so a hands-free session is not interrupted by the screen
timing out. A background `MediaSessionService` is still the correct long-term answer for playback with
the app closed (see Later Architecture in the spec).

## Screens

The UI contract separates manual reading from continuous listening, so there are two screens and
they are not interchangeable:

- **Note** — manual reading and inspection. Renders the whole document in reading order; opening it
  never starts playback and moving focus through it never changes playback progress.
- **Player** — the continuous spoken-reading surface. Title, current section, progress, playback state,
  and Previous section / Play-Pause / Next section. Back returns to the Note while playback continues.

The Player's `Queue` control and the Note's `Add to queue` action are deliberately absent: both need
an Active Queue, which belongs to a specification that does not exist yet, and a visible control that
does nothing is worse for a TalkBack user than an absent one.

## Install on a device

```
.\gradlew.bat :app:assembleDebug
$adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On the device, add a text-to-speech engine (Google Speech Services is usually present) and enable
TalkBack to test accessibility. Playback is hands-free once you press **Play**.

## Not yet implemented

- Active Queue, sessions and resume. Playback state is session-owned by contract, and that
  specification does not exist yet; closing the app restarts at paragraph 1.
- Queue surfaces (the Player's `Queue` control, the Note's `Add to queue`, the mini-player).
- Browse, Home and Queues: four top-level destinations plus ten secondary screens are specified, and
  none are built. The navigation graph is deliberately not frozen until the screens that determine it
  exist.
- Background/lock-screen playback and media buttons (Media3 `MediaSessionService`).
- Sentence-level resume after pause, and finer offsets inside a chunk (`characterOffset` is carried
  but always 0).
- Image and file captions. A caption is speakable text and is currently lost, which the coverage
  census measured at 8 blocks in this workspace.
- Ordered-list numbering: one `LIST_ITEM` kind serves both list styles, so numbering is not recoverable
  on the Note screen.


## Architecture

```
Notion            demo document
   |                   |
   v                   v
NotionRepository -> ReaderDocument -> ReaderEngine -> UI (Compose, TalkBack)
                                          |
                                          v
                                     SpeechEngine -> Android TextToSpeech
```

The reader never depends on Android TTS or on Notion:

- `reader/ReaderDocument.kt` — provider-agnostic document model: `ReaderDocument(pageId, title,
  sections, lastEditedAt)`, `ReaderSection`, `ReaderChunk`, `ChunkMetadata`. `chunkTexts` is the flat
  projection playback consumes.
- `reader/ReaderState.kt` — immutable reading position, navigation, section outline and playback flags.
- `reader/ReaderEngine.kt` — coordinates navigation and speech through the `StateFlow<ReaderState>` the
  UI observes. Navigation logic lives in `ReaderState`, not in the UI.
- `reader/ReaderDocumentPosition.kt` — the stable-identity handoff type and the resolver that reports how
  much of a saved position survived an edit. It reports facts, never resume policy.
- `reader/ReaderLoadError.kt`, `reader/LoadReaderDocument.kt` — the error taxonomy and the single
  high-level use case the spec asks for, returning `Result<ReaderDocument>`.
- `reader/TextChunker.kt` — sentence → word → hard-split fallback chain and the split threshold.
- `speech/` — `SpeechEngine` interface, `AndroidSpeechEngine` implementation, and a JVM test double.
- `notion/` — `NotionApi` (HTTP, retry, error mapping), `NotionModels` (JSON), `NotionBlockNormalizer`
  (Notion block types → `NormalizedBlock`), `NotionRepository` (wiring), `ReaderDocumentMapper`.
- `ui/NoteScreen.kt`, `ui/PlayerScreen.kt` — state + callbacks only, no business logic.

Because of that split, the hard-coded `ReaderDocument.Demo` and a `NotionRepository`-produced document
are interchangeable without touching navigation.

## Credentials

Never commit a token. The build reads local development credentials from, in order:

1. `local.properties` — `NOTION_TOKEN=...` (Android convention, git-ignored).
2. `.env` in the repository root, either as `NOTION_TOKEN=ntn_...` **or** as a file containing nothing
   but the bare token with no key at all. Quotes, `export ` prefixes, `#` comments, CRLF and a UTF-8 BOM
   are all tolerated. See `.env.example`.

Optional `NOTION_PAGE_ID` / `NOTION_PAGE_URL` selects a specific page; it accepts a dashed UUID, an
undashed ID, or a full `notion.so` URL. **Without it, the app reads the most recently edited page the
integration can see** (via `POST /v1/search`), so a token-only `.env` is enough to load real content.

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
on a list position. `ReaderDocumentPosition` is the handoff type a future session layer persists, and
`ReaderDocument.resolve` reports how much of a saved position still exists (`CHUNK` / `BLOCK` / `SECTION`
/ `NONE`) without deciding what to do about it — resume and fallback policy belong to the Active Queue &
Reader Session specification.

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
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

Ktor must be 3.6.0 or newer: 3.2.0 contains a field name with spaces that R8 cannot dex for `minSdk 26`
(`Space characters in SimpleName 'use streaming syntax' are not allowed prior to DEX version 040`).

## Accessibility rules

Every rule below is a requirement of the UI contract, not a preference.

- Every control has a self-explanatory accessible name: "Previous section", "Play", "Pause",
  "Next section". Nothing is called "back", "forward" or "button".
- Each screen names itself with a pane title, so entering it announces where the user is.
- The reading position is focusable text ("Paragraph 4 of 12", "Section 2 of 4 · Primary Heading").
  An empty document reads "No paragraphs".
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
- Pause stops the current utterance and keeps the position; Play restarts that paragraph from the
  beginning, since Android TTS has no reliable pause/resume.
- Moving between screens never touches playback, and opening a Note never starts TTS.
