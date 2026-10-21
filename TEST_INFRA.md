# E2E Test Infra: Notion Reader UI/UX, State & A11y Verification

## Test Philosophy
- Opaque-box, requirement-driven, TalkBack-first verification.
- Enforce strict layer rules (`LayerRulesTest`) and process-scoped playback lifetime (`ReaderHostTest`).
- Methodology: Category-Partition + Boundary Value Analysis + Pairwise Combinatorial + Real-World Workload Testing.

## Feature Inventory & Test Mapping
| # | Feature / Defect | Source (Requirement) | Tier 1 (Functional) | Tier 2 (Boundary) | Tier 3 (Cross-Feature) | Tier 4 (Scenario) |
|---|------------------|----------------------|:-------------------:|:-----------------:|:----------------------:|:-----------------:|
| 1 | DEF-01 NoteScreen Loading Announcement | R1 / R3 | ✓ (singular text) | ✓ (empty note) | ✓ (nav to note) | ✓ (reader flow) |
| 2 | DEF-02 BrowseScreen Loading & Refresh | R1 / R3 | ✓ (liveRegion) | ✓ (empty cache) | ✓ (cached + refresh) | ✓ (catalog browse) |
| 3 | DEF-03 ExploreCatalogScreen Empty State | R1 | ✓ (empty areas/topics) | ✓ (partial catalog) | ✓ (explore -> topic) | ✓ (empty explore exit) |
| 4 | DEF-04 ListeningTab Actionable Button | R1 / R3 | ✓ (browse button) | ✓ (no history) | ✓ (button navigates) | ✓ (fresh start flow) |
| 5 | DEF-05 HomeDashboard Speech Error | R1 / R2 | ✓ (banner display) | ✓ (tts crash simulation) | ✓ (continue -> error) | ✓ (error recovery) |
| 6 | DEF-06 DiscoveryScreen Live Region | R3 | ✓ (liveRegion) | ✓ (no search matches) | ✓ (typing query) | ✓ (discovery workflow) |
| 7 | DEF-07 TopicDetailsScreen Crash Guard | R1 | ✓ (missing otherId) | ✓ (null source note) | ✓ (back navigation) | ✓ (graph traversal) |
| 8 | DEF-08 InterestsContent ItemInfo | R3 | ✓ (itemInfo index) | ✓ (single item list) | ✓ (toggle interest) | ✓ (settings review) |
| 9 | DEF-09 Note Navigation Scroll Collision | R2 | ✓ (separate subjectId) | ✓ (long note scroll) | ✓ (note A -> note B) | ✓ (deep reading flow) |
| 10 | DEF-10 Ghost Document on Note Failure | R1 / R2 | ✓ (no ghost chunks) | ✓ (offline failure) | ✓ (open A -> fail B) | ✓ (fault injection) |
| 11 | DEF-11 Reactive SavedQueueCoordinator | R2 | ✓ (pendingCount flow) | ✓ (0 to 1 queue item) | ✓ (queue add -> UI) | ✓ (queue creation) |
| 12 | DEF-12 Reactive Home Observation | R2 | ✓ (playback observation) | ✓ (queue end event) | ✓ (background advance) | ✓ (home update flow) |
| 13 | DEF-13 Receiver Export Flag | R4 | ✓ (RECEIVER_NOT_EXPORTED) | ✓ (API 36 intent) | ✓ (audio becoming noisy) | ✓ (headphone unplug) |
| 14 | DEF-14 Lifecycle-Aware Flow Collection | R2 | ✓ (collectAsStateWithLifecycle) | ✓ (Activity STOPPED) | ✓ (background playback) | ✓ (screen-off flow) |
| 15 | DEF-15 Missing Role.Button on Clickables | R3 | ✓ (Role.Button present) | ✓ (12 clickables) | ✓ (TalkBack click) | ✓ (full a11y tour) |
| 16 | DEF-16 Disambiguate Queue Reorder Labels | R3 | ✓ (includes note title) | ✓ (identical titles) | ✓ (move up/down) | ✓ (queue edit flow) |
| 17 | DEF-17 Silent Speech Failure Notices | R3 | ✓ (liveRegion = Polite) | ✓ (engine reset) | ✓ (player error notice) | ✓ (playback retry) |
| 18 | DEF-18 Dialog Focus Restoration | R3 | ✓ (focus restored) | ✓ (cancel vs confirm) | ✓ (dialog -> screen) | ✓ (filter & rename) |

## Test Architecture
- Test Runner 1 (JVM Fast Suite): `.\gradlew.bat :app:testDebugUnitTest`
  - Runs in ~2 seconds. Validates domain, player, application coordinators, layer rules, and ViewModel flows.
- Test Runner 2 (Architecture Enforcer): `.\gradlew.bat :app:testDebugUnitTest --tests com.example.notionreader.architecture.LayerRulesTest`
- Test Runner 3 (Build & Compilation):
  - `.\gradlew.bat :app:assembleDebug`
  - `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
- Test Runner 4 (Instrumented Accessibility Suite):
  - Repository wrapper: `pwsh -File tools/run-accessibility-tests.ps1`
  - Targeted class runs: `pwsh -File tools/run-accessibility-tests.ps1 -Class <TestClass>`

## Acceptance Thresholds
- JVM unit tests: 100% pass, 0 errors, 0 failures.
- Layer rules: 0 violations.
- Debug build & AndroidTest compile: Clean 0 errors.
- Instrumented accessibility tests: Complete suite pass with valid wrapper verdict.
- Every bug fix backed by a failing-first regression test.
