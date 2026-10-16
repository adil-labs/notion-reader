# Home and Discover Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. Implementation authorized by the user on 16 October 2026.

**Goal:** Make Home the listening-first default and give Discover a focused search, topics and connections experience.

**Architecture:** Reuse process-scoped listening history, queue and discovery state. Add a separate bounded local store only for explicitly opened Notes. Recency is a navigation aid and never enters embedding, topic or recommendation ranking.

**Tech Stack:** Existing Kotlin, Compose, StateFlow, private local persistence, JUnit 4 and repository accessibility wrapper. No new navigation framework or inference dependency.

**Spec:** User-approved Home/Discover separation in this conversation. Amend the governing Notion contracts before implementation; this plan does not override them.

## Product decisions

- Five top-level destinations: Home, Discover, Browse, Queues, Settings. Cold launch defaults to Home; restoring an existing task preserves its destination and navigation state.
- Home order: Continue listening; Recent listening; Recently opened Notes; Your interests. Use compact cards and bounded previews, never expanded recommendation lists.
- Continue listening shows one primary resumable session using existing selection semantics. Recent listening shows up to three other session cards, including queue labels/origins, without duplicating the primary session. Preserve existing resume/replay semantics and full history under Queues.
- Recently opened Notes shows up to five successful explicit manual opens, newest first and deduplicated by canonical identity. Playback loading, prefetch, indexing, previews and background refresh never count as opens. Keep at most twenty opaque source references and timestamps privately; resolve labels from eligible current content. Do not retain an evicted Note's content snapshot. Clear with history clearing and full local reset; confirmed deletion or permission loss removes its reference.
- Home shows up to three interest cards in stable saved order, followed by View all interests. Opening an interest shows its existing matches and listening preview; it never starts playback. Do not add interest-interaction tracking merely to order these cards.
- Discover order: Search your cached Notes with coverage; Topics in your Notes; Connections between Notes; one View your interests entry. Matching and Older Notes remain within relevant details. Remove Continue listening, queue shortcuts and the expanded interest collection from this destination.
- Add a shared Your interests secondary destination, accessible from Home and Discover. Keep Add/edit/remove/seed selection reachable there, including when there are no interests or no model.
- Browse retains keyword search, facets, sorting and Explore. Queues retains queue management and full listening history. Model/index controls remain in Settings.
- Keep the existing mini-player available where currently governed. Tab switching and opening recent items never alter playback or frozen queue membership.

## Global constraints

- Never hand-edit docs/notion; amend Notion, regenerate with tools/export-notion-doc.ps1 -All, and inspect every diff.
- Preserve layer rules, process-scoped playback and existing session serialization.
- Recent-note storage is optional navigation history, independent of cache LRU and discovery interests; no content logging or behavioral profiling.
- Background changes must not steal focus or reorder a list being inspected. Use stable IDs, real focus targets and explicit update adoption where needed.
- Do not bundle or reset the model, evict cached Notes, or discard existing history during navigation migration.

## Review focus

1. Existing restored tasks must not jump to Home; a genuinely new launch must start there.
2. A Note load that fails, is canceled or is superseded must not create a recent entry.
3. Returning to a recent Note must restore its originating card's focus and scroll, even after background updates.
4. Deleted queues and renamed/edited source queues must not change or misidentify historical frozen sessions.
5. Missing model, empty interests, unavailable Notes and cleared history must leave useful accessible navigation.

## Task 1: Amend governing sources and lock recency semantics

**Files:** Generated product/navigation/integration/session mirrors under docs/notion; hand-authored docs/semantic-discovery-implementation-spec.md; review both queue/background governing specs.

- [x] Read the governing source pages and latest decisions, checking Home default, five destinations, existing history retention and privacy controls.
- [x] Amend live product and navigation sources with the product decisions above. Amend integration/session sources for successful manual-open history, retention/reset rules and restoration compatibility where their scope requires it. Resolve any same-rank conflict before coding.
- [x] Regenerate all mirrors and inspect diffs, including unchanged model/session contracts. Do not include unrelated upstream authoring changes.
- [x] Update the hand-authored implementation specification with the approved recency boundary and acceptance criteria.

## Task 2: Add Home without losing navigation state

**Modify:** application/ReaderDestination.kt, ReaderNavigator.kt, ReaderApp.kt, ReaderFocus.kt and ui/ReaderShell.kt under app/src/main/java/com/example/notionreader.

**Interface:** Separate ReaderDestination.Home from Discover. Give ReaderApp explicit openHome() and openDiscover() routes; add the shared interests secondary route and stable source-specific focus IDs.

- [x] Add failing-first ReaderNavigatorTest assertions for five top-level routes, cold-launch Home, preservation of a restored Discover destination, and independent tab state.
- [x] Add failing-first routing tests for Home/Discover → interest details → Note/preview → Back, preserving the exact origin rather than always returning to Discover.
- [x] Implement the minimal route changes and five labelled tab icons. Update stale route comments rather than relying on misleading legacy Home names.
- [x] Run the full JVM suite. Ensure tab changes leave the session, active queue and Browse query/facets untouched.

## Task 3: Build bounded Home recency projections

**Modify:** application/ReaderApp.kt, ui/HomeScreen.kt and composition-root wiring in ReaderApplication.kt.
**Create:** domain/session/RecentNoteHistory.kt, data/local/FileRecentNoteHistoryStore.kt and application/RecentNotesController.kt, with corresponding JUnit tests.

**Interfaces:** RecentNoteReference(pageId: String, openedAt: Instant); RecentNoteHistoryStore.read()/write(references); RecentNotesController.recordSuccessfulOpen(pageId) and clear(). Inject a clock for deterministic ordering tests. Keep persisted IDs opaque and resolve display metadata against eligible current sources.

- [x] Write and run failing tests for canonical deduplication, newest-first ordering, the twenty-reference storage limit and five-card projection, failed/superseded loads, eviction, permanent removal, corruption, history clearing and full reset.
- [x] Record only after the current explicit manual request successfully publishes its Note. Keep background loading paths outside this API. Do not modify document-cache access timestamps to manufacture history.
- [x] Derive Continue listening and three other recent sessions from existing history, preserving frozen origins and resume/replay behavior. Test exclusion of the primary session and safety when the originating Saved Queue no longer exists.
- [x] Render the four Home sections with bounded cards and concise empty states. Show queue origins honestly; do not relabel recently edited queues as recently played.
- [x] Add property tests proving recent-note/interest navigation does not call Play, replace a queue, change an interest definition or affect discovery ranking.
- [x] Run the full JVM suite and debug assembly.

## Task 4: Simplify Discover and share interest navigation

**Modify:** ui/DiscoveryContent.kt, DiscoveryScreen.kt, TopicScreens.kt, ReaderShell.kt and application routing/focus files.
**Create:** ui/InterestsScreen.kt for the shared interest collection.

- [x] Add failing semantics/flow tests asserting Discover contains search, topics, connections and one interests entry, with no Continue listening, queue list or expanded interests feed.
- [x] Add tests for Home's interest cards and View all, Discover's View your interests, empty-state Add interest, missing-model management access, and Back restoration to each originating control.
- [x] Reuse existing interest details, matched-Note evidence and editable five-Note listening preview. Keep explicit Play as the sole session-start action.
- [x] Ensure existing Note and Player Related Notes routes still work with the new top-level arrangement.
- [x] Verify small screens and large font sizes: five readable tab labels, no clipped controls, stable TalkBack headings/card descriptions, and no duplicate mini-player/current-session controls.
- [x] Run JVM tests and compile androidTest. Prefer JVM Compose semantics tests where practical; use targeted device classes for focus and Back behavior.

## Task 5: Verify the complete product flow and record delivery

- [x] Use verify-notion-reader. Run :app:testDebugUnitTest, :app:assembleDebug and :app:compileDebugAndroidTestKotlin, followed by the affected targeted wrapper classes. Never treat a partial run as a pass.
- [x] Prove new regression guards fail for their claimed reasons, including false recent entries from background loads and forced Home navigation on task restoration.
- [x] On the test emulator, cover Home → recent session → Player → Back; Home → recent Note → Back; both interest entry points → preview → Play; and all five tabs while playback continues.
- [x] Install the update on the Pixel preserving its model/cache/history. Inspect actual layout and confirm the selected-interest playback path. Human TalkBack speech acceptance remains a separate recorded gate.
- [x] Update README and append a numbered decisions record with actual verification results, migration behavior and remaining acceptance gaps. Do not imply this navigation work closes existing grouping-quality or performance gates.

**Completion:** Home is the default useful resume surface; Discover is visibly focused; interests remain easy to manage; navigation/focus and playback isolation pass; recency remains separate from recommendation logic.

## Execution ledger

- Task 1: governing product/navigation/integration/session sources amended in Notion, mirrors regenerated and reviewed; dependent queue contract aligned.
- Task 2: Home/Discover are separate destinations; five-tab shell and shared interests route implemented. New-launch/default and restored-tab JVM tests observed red before implementation. Real task test observed red for new-task inheritance of Discover; corrected at the Activity boundary without replacing the process session.
- Task 3: bounded private manual-open references, cache-bound card labels and history/reset cleanup implemented. Eight durable guard mutations fail exactly their named assertions, followed by a restored full-suite pass. Reviewer found and confirmed fixes for late invalidation acknowledgement and failed clear reporting.
- Task 4: Discover has no history/queue section or expanded interest collection; Home and Discover use a shared interest-management destination. Initial six-test Home class and updated nine-test topic/navigation class pass through the wrapper. The final eight-test class also passes text/seed creation and eviction focus fallback.
- Task 5: 765 JVM tests, debug build and Android-test compilation passed; targeted wrapper/device flows and physical layout inspection recorded in decisions section 49.
- Ruling: timestamps use epoch milliseconds, matching the repository's injected history clock rather than introducing a new time serialization contract.
- Ruling: recent queues are frozen listening-session entries with source labels, not a second edited-queue list. Interests keep saved order, without new usage tracking.
- Ruling: preserve richer process-owned navigation on Activity recreation; restore a saved top-level tab into a new process; an explicitly new task starts at Home. No automatic playback restoration is introduced.
- Ruling: recent Notes resolve labels only from cached documents; an evicted reference remains opaque and unavailable until recached. Recency never enters ranking or clustering.
- Ruling: isolate repeated preview/Play tests on the emulator to avoid adding test sessions to the personal Pixel history. Physical checks verify installation, preserved model/cache/history and both actual layouts; human speech/listening acceptance remains open.
