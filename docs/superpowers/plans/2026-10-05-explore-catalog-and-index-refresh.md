# Explore Catalog and Index Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every populated Area and Topic discoverable through a deterministic Explore Catalog while retaining a compact Home preview and cache-first discovery refresh.

**Architecture:** `ReaderApp` derives an explicit catalog from normalized `ReaderIndexEntry` metadata, separating Area and Topic lists from Home's bounded preview. A new secondary destination renders that catalog and reuses existing `openExploreCard` Browse-facet navigation. `ReaderHost` continues to hydrate cached entries first; `ReaderViewModel` forces its existing foreground refresh, which replaces the catalog on success and retains cached entries on failure.

**Tech Stack:** Kotlin, coroutines/StateFlow, Jetpack Compose, JUnit 4, Compose accessibility tests.

**Spec:** `docs/notion/ui-screens-and-navigation-contract.md`

## Global Constraints

- Areas precede Topics; each contains every nonempty normalized metadata value and is sorted case-insensitively.
- Explore rows only open pre-filtered Browse and never create or alter queues/playback.
- Cached Browse entries render immediately; failed foreground refreshes retain them.
- Preserve the repository layering and TalkBack semantics conventions.

## Review Focus

- Case-only duplicate metadata must count one facet and have deterministic representative text.
- A metadata value attached twice to one Note must count that Note once.
- A Topic that has the same display title as an Area must remain a distinct catalog facet.
- Refresh failure after cache hydration must keep both preview and catalog available.
- Back from Explore Catalog must return to Home without altering playback.

---

### Task 1: Derive deterministic Explore catalog data

**Files:**
- Modify: `app/src/main/java/com/example/notionreader/application/ExploreCard.kt`
- Modify: `app/src/main/java/com/example/notionreader/application/ReaderApp.kt`
- Test: `app/src/test/java/com/example/notionreader/application/ReaderAppTest.kt`

**Interfaces:**
- Produces: `ExploreCatalog(val areas: List<ExploreCard>, val topics: List<ExploreCard>)` and `ReaderApp.exploreCatalog()`.
- Consumes: normalized `ReaderIndexEntry.area`, `ReaderIndexEntry.topics`, and existing `ExploreCard` navigation fields.

- [ ] **Step 1: Write failing catalog tests** for every Area surviving Topic-heavy data, alphabetical case-insensitive order, and unique per-Note counts.
- [ ] **Step 2: Run `:app:testDebugUnitTest --tests ...ReaderAppTest`** and verify the tests fail because `exploreCatalog` does not exist.
- [ ] **Step 3: Implement `ExploreCatalog` and `ReaderApp.exploreCatalog()`** using separate Area and Topic grouping, stable ordering, and facet-specific cards.
- [ ] **Step 4: Run the targeted JVM tests** and verify they pass.

### Task 2: Add Explore Catalog navigation and Home handoff

**Files:**
- Modify: `app/src/main/java/com/example/notionreader/application/ReaderNavigator.kt`
- Modify: `app/src/main/java/com/example/notionreader/application/ReaderApp.kt`
- Modify: `app/src/test/java/com/example/notionreader/application/ReaderAppTest.kt`
- Test: `app/src/test/java/com/example/notionreader/application/ReaderNavigatorTest.kt`

**Interfaces:**
- Produces: secondary `ReaderDestination.ExploreCatalog` and `ReaderApp.openExploreCatalog()`.
- Consumes: existing `ReaderNavigator.open`/`back` semantics.

- [ ] **Step 1: Write failing navigation tests** for opening the catalog from Home and returning to Home with playback untouched.
- [ ] **Step 2: Run the targeted JVM tests** and verify the destination is absent.
- [ ] **Step 3: Add the destination and application handoff** without changing top-level navigation.
- [ ] **Step 4: Run the targeted JVM tests** and verify they pass.

### Task 3: Render accessible catalog and preview link

**Files:**
- Modify: `app/src/main/java/com/example/notionreader/ui/HomeScreen.kt`
- Create: `app/src/main/java/com/example/notionreader/ui/ExploreCatalogScreen.kt`
- Modify: `app/src/main/java/com/example/notionreader/ui/ReaderShell.kt`
- Test: `app/src/androidTest/java/com/example/notionreader/ui/HomeScreenAccessibilityTest.kt`
- Create or modify: `app/src/androidTest/java/com/example/notionreader/ui/ExploreCatalogAccessibilityTest.kt`

**Interfaces:**
- Consumes: `ExploreCatalog`, existing `ExploreCard`, and `ReaderShell` callbacks.
- Produces: a single reachable “See all Explore” action and one reachable row per catalog facet.

- [ ] **Step 1: Write failing semantics tests** for the See all action, Areas-before-Topics reading order, and facet activation.
- [ ] **Step 2: Compile/run the targeted test through `tools/run-accessibility-tests.ps1 -Class ...`** and verify the expected failures.
- [ ] **Step 3: Add the Home preview action, catalog screen, pane title, section headings, and merged clickable rows.**
- [ ] **Step 4: Run the targeted accessibility classes** and verify they pass.

### Task 4: Prove startup refresh behavior and record the round

**Files:**
- Modify: `app/src/test/java/com/example/notionreader/application/ReaderIndexStartupTest.kt`
- Modify: `README.md`
- Modify: `docs/decisions.md`

**Interfaces:**
- Consumes: `ReaderHost.loadCachedIndex`, `ReaderViewModel` foreground index fetch, and `ReaderApp.loadBrowseIndex` failure preservation.

- [ ] **Step 1: Write or tighten tests** proving cached discovery entries publish before the foreground load and a successful foreground load replaces them; prove failure retains cache.
- [ ] **Step 2: Run the targeted JVM tests**, observing failing-first only for a newly added production guard.
- [ ] **Step 3: Make the smallest production change only if those tests expose a gap; otherwise retain the already-correct cache-first refresh path.**
- [ ] **Step 4: Update README and append the durable decision/verification record.**

### Task 5: Verify the complete change

**Files:**
- Verify all modified files.

- [ ] **Step 1: Run `./gradlew.bat :app:testDebugUnitTest`.**
- [ ] **Step 2: Run `./gradlew.bat :app:assembleDebug :app:compileDebugAndroidTestKotlin`.**
- [ ] **Step 3: Run each affected accessibility class through the repository wrapper.**
- [ ] **Step 4: Review generated-spec and source diffs, check tracked files, and report human-only TalkBack listening gates separately.**
