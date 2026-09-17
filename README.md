# Notion Reader (Android POC)

An Android, TalkBack-first reader that turns a Notion page into a sequential, hands-free reading
experience. It behaves like an audio/document player rather than a Notion editor.

Implemented so far: reader state and navigation, reader engine, Android TTS speech engine, Compose
reader screen, Notion API/repository, and **automatic paragraph progression** (continuous hands-free
reading).

## Reading behaviour

- **Play** reads the current paragraph and then continues automatically: each finished paragraph
  advances to the next, and playback stops on its own after the last paragraph (it does not wrap).
- **Pause** stops the utterance and keeps the position; **Play** starts that paragraph again from the
  beginning, because Android TTS has no reliable pause/resume.
- **Next** / **Previous** while playing interrupt the current utterance and read the new paragraph;
  while paused they only move the position.
- Late completion callbacks from a cancelled utterance are discarded, so pause/next/previous can never
  cause an unexpected jump.
- The display is held awake while playing so a hands-free session is not interrupted by the screen
timing out. A background `MediaSessionService` is still the correct long-term answer for playback with
the app closed (see Later Architecture in the spec).

## Install on a device

```
.\gradlew.bat :app:assembleDebug
$adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On the device, add a text-to-speech engine (Google Speech Services is usually present) and enable
TalkBack to test accessibility. Playback is hands-free once you press **Play**.

## Not yet implemented

- Saved reading position (spec Phase 10 — DataStore). Closing the app restarts at paragraph 1.
- Background/lock-screen playback and media buttons (Media3 `MediaSessionService`).
- Sentence-level resume after pause.


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

- `reader/ReaderDocument.kt` — provider-agnostic document model (`id`, `title`, `paragraphs`).
- `reader/ReaderState.kt` — immutable reading position, navigation and playback flags.
- `reader/ReaderEngine.kt` — coordinates navigation and speech through the `StateFlow<ReaderState>` the
  UI observes. Navigation logic lives in `ReaderState`, not in the UI.
- `speech/` — `SpeechEngine` interface, `AndroidSpeechEngine` implementation, and a JVM test double.
- `notion/` — `NotionApi` (HTTP), `NotionModels` (JSON), `NotionRepository` (blocks → `ReaderDocument`).
- `ui/ReaderScreen.kt` — state + callbacks only, no business logic.

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
- HTTP 429 (`rate_limited`) and 529 (`service_overload`) are retried after `Retry-After`; 500/502/503/504
  are retried too because every call here is a read. Retries are limited, with exponential backoff and
  jitter. Non-retryable errors are reported using Notion's own `code` and `message`, mapped to plain
  language (for example `restricted_resource` explains that the page must be shared with the
  integration).
- Blocks with `has_children` are read as nested content in document order (nested list items, toggles,
  columns), bounded by a depth limit. `child_page`, `child_database` and `synced_block` are not
  descended into.
- Supported block types: `paragraph`, `heading_1..3`, `bulleted_list_item`, `numbered_list_item`,
  `quote`, `callout`, `toggle`. Unsupported and empty blocks are skipped instead of failing the page.
- Both `in_trash` (2026-03-11+) and the older `archived` field are decoded, so the app works across API
  versions.

## Build and test

```
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

Ktor must be 3.6.0 or newer: 3.2.0 contains a field name with spaces that R8 cannot dex for `minSdk 26`
(`Space characters in SimpleName 'use streaming syntax' are not allowed prior to DEX version 040`).

## Accessibility rules

- Every control has a self-explanatory accessible name: "Previous paragraph", "Play", "Pause",
  "Next paragraph".
- The reading position is focusable text ("Paragraph 4 of 12"); an empty document reads "No paragraphs".
- Nothing is force-focused and no live regions run while reading, so TalkBack and document TTS never
  talk over each other.
- Errors are plain focusable text containing the reason, so TalkBack can discover them.
- Pause stops the current utterance and keeps the position; Play restarts that paragraph from the
  beginning, since Android TTS has no reliable pause/resume.
