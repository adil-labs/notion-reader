# Project: Notion Reader Comprehensive UI/UX, State Management, and Accessibility Audit & Remediation

## Architecture
Notion Reader is an Android POC (`:app` module, Kotlin + Compose, single `MainActivity`) TalkBack-first listening reader.
Layer rules enforced strictly by `com.example.notionreader.architecture.LayerRulesTest`:
- `domain` -> nothing
- `player` -> `domain`
- `application` -> `domain`, `player`
- `data` -> `domain` (all Notion API DTOs like `NotionBlock` encapsulated here)
- `platform` -> `player`
- `ui` -> `domain`, `player`, `application`
- `MainActivity` & `ReaderViewModel`: composition root.
- Playback lifetime is process-scoped via `ReaderHost` and `ReaderApplication`, outliving screens, tasks, and ViewModels.

## Feature Inventory
Every feature / defect identified during the Phase 0 Survey is cataloged below with its assigned milestone.

| # | Feature / Defect | Description | Milestone | Source |
|---|------------------|-------------|-----------|--------|
| 1 | DEF-01 NoteScreen Loading Announcement | Change plural "Loading Notes…" to singular "Loading note…" to match title | M3 | survey_1 |
| 2 | DEF-02 BrowseScreen Loading & Refresh States | Add `liveRegion` & `LinearProgressIndicator` to initial load; expose refresh state | M3 | survey_1 |
| 3 | DEF-03 ExploreCatalogScreen Empty State | Add explicit non-blank empty state with guidance/action | M3 | survey_1 |
| 4 | DEF-04 ListeningTab Actionable Empty State | Add "Browse Notes" button to empty history card | M3 | survey_1 |
| 5 | DEF-05 HomeDashboard Speech Error Surfacing | Surface `speechMessage` banner on Home when playback fails from Home | M3 | survey_1 |
| 6 | DEF-06 DiscoveryScreen Live Region | Add `liveRegion = LiveRegionMode.Polite` to search and status text | M3 | survey_1 |
| 7 | DEF-07 TopicDetailsScreen Crash Guard | Guard `.first` with `.firstOrNull` and handle null source note titles safely | M3 | survey_1 |
| 8 | DEF-08 InterestsContent CollectionItemInfo | Add `collectionItemInfo` to each card in `InterestsContent` | M2 | survey_1 |
| 9 | DEF-09 Note Navigation Scroll Collision | Supply `subjectId = pageId` in `ReaderApp.openNote` for separate scroll states | M1 | survey_2 |
| 10 | DEF-10 Ghost Document on Note Load Failure | Do not render previous document when opening a new note fails | M1 | survey_2 |
| 11 | DEF-11 Reactive State in SavedQueueCoordinator | Make `pendingAdd` reactive (`StateFlow` / snapshot state) | M1 | survey_2 |
| 12 | DEF-12 Reactive Flow Observation in Home | Observe `playback` and `queueState` for up-to-date Home cards | M1 | survey_2 |
| 13 | DEF-13 Missing Receiver Export Flag | Use `RECEIVER_NOT_EXPORTED` in `PlaybackService.kt:124` | M1 | survey_2 |
| 14 | DEF-14 Lifecycle-Aware Flow Collection | Add `lifecycle-runtime-compose` and isolate `ReaderShell` flows | M1 | survey_2 |
| 15 | DEF-15 Missing Role.Button on 12 Clickables | Add `role = Role.Button` to custom clickable rows across all screens | M2 | survey_3 |
| 16 | DEF-16 Disambiguate Queue Reorder Labels | Include member note title in move/remove action labels in `SavedQueueDetailsScreen` | M2 | survey_3 |
| 17 | DEF-17 Silent Speech Failure Notices in Note/Player | Add `liveRegion = LiveRegionMode.Polite` to `statusMessage` and summaries | M3 | survey_3 |
| 18 | DEF-18 Dialog & Overlay Focus Restoration | Restore focus to triggering chip/button on dialog and sub-view dismissals | M4 | survey_3 |
| 19 | DEF-19 Verification & Adversarial Test Coverage | Complete test gates (JVM, Assemble, AndroidTest, Accessibility, E2E) | M5 | survey_all |

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: State Management & Architecture Core | DEF-09, DEF-10, DEF-11, DEF-12, DEF-13, DEF-14 | none | DONE |
| 2 | M2: TalkBack Accessibility & Role Semantics | DEF-08, DEF-15, DEF-16 | none | IN_PROGRESS |
| 3 | M3: Screen States, Empty/Error & Live Announcements | DEF-01, DEF-02, DEF-03, DEF-04, DEF-05, DEF-06, DEF-07, DEF-17 | M1 | PLANNED |
| 4 | M4: Dialog & Overlay Focus Restoration | DEF-18 | M2, M3 | PLANNED |
| 5 | M5: Full Verification & E2E Acceptance | DEF-19 (100% test pass, layer rules, accessibility suite) | M1, M2, M3, M4 | PLANNED |

## Interface Contracts

### Note Navigation (`ReaderApp` ↔ `ReaderNavigator` ↔ `ReaderShell`)
- `ReaderApp.openNote(pageId, ...)` MUST pass `subjectId = pageId` to `navigator.open(ReaderDestination.Note, fromFocusId = fromFocusId, subjectId = pageId)`.
- `SaveableStateProvider` in `ReaderShell` preserves state uniquely keyed by `ReaderDestination.Note.name + ":" + pageId`.

### NoteViewer Error & Document Contract
- `NoteViewer.load(pageId, ...)`: When a load operation fails, if `pageId != _document.value.pageId`, `_document` MUST NOT retain the stale previous note.
- `NoteScreen`: When `loadState is DocumentLoadState.Error`, only the error banner and retry action are displayed; no ghost chunks or stale titles from unrelated notes are rendered.

### SavedQueueCoordinator Reactivity Contract
- `SavedQueueCoordinator` exposes `val pendingAdd: StateFlow<List<QueuedNote>>`; `pendingAddCount` is derived from that list. UI observes the membership flow and derives its count from the same snapshot.
- UI components observing pending queue additions receive immediate snapshot invalidation when items are queued.

### Accessibility Role & Action Contracts
- All clickable interactive rows acting as selection or activation controls declare `Modifier.clickable(role = Role.Button, onClick = ...)` or `semantics { role = Role.Button }`.
- In queue editing, action buttons announce distinct titles: `"Move [Title] up"`, `"Move [Title] down"`, `"Remove [Title]"`.
- All asynchronous status messages and search updates declare `liveRegion = LiveRegionMode.Polite`.
- When in-screen dialogs (`FacetSelectionDialog`, `RenameDialog`, `DeleteDialog`) dismiss, focus is restored to the triggering button/chip via `FocusRequester`.

## Code Layout

```text
app/src/main/java/com/example/notionreader/
├── MainActivity.kt                 # Single Activity entry point
├── ReaderApplication.kt            # Application scope, ReaderHost lifecycle
├── PlaybackService.kt              # MediaSession & audio service
├── domain/                         # Pure domain logic (depends on nothing)
├── player/                         # TTS and core player contracts (depends on domain)
├── application/                    # Coordinators, hosts, navigators (depends on domain, player)
│   ├── ReaderApp.kt
│   ├── ReaderNavigator.kt
│   ├── NoteViewer.kt
│   └── SavedQueueCoordinator.kt
├── data/                           # Data mapping & Notion API (depends on domain)
│   ├── content/
│   └── notion/
├── platform/                       # Platform implementations (depends on player)
└── ui/                             # Jetpack Compose UI (depends on domain, player, application)
    ├── ReaderShell.kt
    ├── HomeDashboard.kt
    ├── BrowseScreen.kt
    ├── ExploreCatalogScreen.kt
    ├── ListeningTab.kt
    ├── DiscoveryScreen.kt
    ├── NoteScreen.kt
    ├── PlayerScreen.kt
    ├── MiniPlayer.kt
    ├── QueueDestinationScreen.kt
    ├── SavedQueueDetailsScreen.kt
    ├── SavedTab.kt
    ├── SmartTab.kt
    ├── InterestsScreen.kt
    ├── TopicDetailsScreen.kt
    └── settings/
```
