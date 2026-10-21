# Adaptive storage Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement task by task in this session after review. Steps use checkbox syntax.

**Goal:** Support a 100-Note offline library by default and grow Discovery safely with indexed content.

**Architecture:** Domain policy defines limits and admission outcomes. A process-owned coordinator serializes disk reservations across private stores; Android supplies volume measurements. Stores retain atomic publication and existing source-invalidations.

**Tech Stack:** Kotlin, Compose, DataStore, existing file stores, WorkManager, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-21-storage-policy-design.md` (approved; live governing sources amended before code).

**Execution status:** implemented and verified on 2026-10-21. See decisions §108 for measured sizing, ten failing-first guard proofs, 1,087 passing JVM tests, build checks, 18 targeted accessibility tests and the real process-restart settings probe. Human speech and broader library acceptance remain separate.

## Global constraints

- Note default 128 MiB; Automatic Discovery default; initial Custom maximum 256 MiB.
- Target 100 Notes; reference workloads 10/50/100/500 passages per Note, 768 float32 dimensions.
- Preserve explicit settings and cache-only eligibility; add COUNT_100 without changing download defaults.
- Reserve max(2 GiB, 10% of volume capacity); extended usage ceiling 80% of safe envelope.
- No preallocation, automatic whole-library downloads or changes to playback commands.
- Preserve unrelated staged/unstaged work. No generated-mirror hand edits.

## Review focus

- Large/non-ASCII Notes: count serialized UTF-8 bytes rather than characters (Task 2).
- Concurrent stores and atomic replacements: count reservations and staging once (Task 3).
- Low/unknown disk space: defer new growth without deleting valid data (Task 3).
- Protected queue larger than a reduced limit: defer cleanup and explain overage (Task 4).
- Explicit user Pause survives storage recovery and process restart (Task 5).

### Task 1: Amend governing contracts

Files: live Product, UI and Integration Notion pages; regenerated `docs/notion/`; `docs/semantic-discovery-implementation-spec.md`.

- [x] Read full affected live blocks and resolve all fixed-64-MiB clauses, including release acceptance wording. Keep historical benchmark evidence intact.
- [x] Amend Product first, then UI/Integration; review queue/player/background specs for dependent effects. Define the 128 MiB cache default, Automatic Discovery, Custom 256 MiB initial value, reserve and extended ceiling exactly as in the design.
- [x] Run `pwsh -NoProfile -File tools/export-notion-doc.ps1 -All`; inspect every diff for exporter drift. Align the implementation companion.

### Task 2: Reproducible current-format sizing and settings

Files: new `app/src/test/java/com/example/notionreader/data/local/StorageSizingTest.kt`; existing `ReaderDocumentCodec.kt`, `FileSemanticIndexStore.kt`, `ReaderSettings.kt`, `DataStoreSettingsRepository.kt`, `OfflineSettingsScreen.kt`.

- [x] Add deterministic synthetic documents, never modify real captures. Serialize through shipping codecs/stores; report source, semantic, reusable-progress, topic/checkpoint and peak temporary bytes separately. Exercise 100 Notes with 10/50/100/500 passages each, long UTF-8 strings, escaped text and uneven section sizes. Keep size diagnostics content-free.
- [x] Assert the 100-Note, <=1-MiB-per-Note source workload fits 128 MiB; assert vector payload equals passages * 768 * 4; distinguish measured totals from estimates. Do not assert all 100-Note content fits a fixed derived ceiling.
- [x] Add failing settings tests: absent fields yield `noteCacheLimitBytes = 134217728L`, `discoveryStorageAutomatic = true`, `discoveryCustomLimitBytes = 268435456L`; explicit values survive round-trip. Add `COUNT_100` and its existing choice-dialog mapping.
- [x] Implement fields and persistence using existing repository patterns. Preserve autoCacheMode/time-window/count choices. Run `./gradlew.bat :app:testDebugUnitTest`; record measured sizing output before finalizing defaults.

### Task 3: Shared disk admission and store integration

Files: new domain `domain/document/StoragePolicy.kt`; new `application/StorageBudgetCoordinator.kt`; new `platform/AndroidStorageAvailability.kt`; `ReaderApplication.kt`, `FileDocumentCache.kt`, `FileSemanticIndexStore.kt`, `FileTopicStore.kt`.

Interfaces: `StorageSnapshot(totalBytes: Long, freeBytes: Long)`; domain `StorageWriteGate.write(category: StorageCategory, additionalPeakBytes: Long, block: suspend () -> T): T`; `StorageRefused(reason: StorageFailure)` distinguishes unknown space, physical reserve and category-limit refusal. The process coordinator serializes the write and releases admission on cancellation/failure. Map refusal at existing store outcomes; do not conflate it with I/O or validation failures. Preserve 8 MiB semantic-header and 64 MiB semantic/topic-record memory safeguards independently of library capacity.

- [x] Write failing JVM properties for exact reserve boundaries, invalid measurements, overflow, concurrent writes, replacement old-plus-new peaks and reservations released after cancellation/failure. Assert Automatic accepts a >64 MiB workload when safe space exists; Custom rejects growth above its limit.
- [x] Implement policy and process-wide reservation coordinator; Android measurements use the actual no-backup storage volume. Inject shared admission into every participating writer, including progress, checkpoints, catalog/staging and model downloads. Category accounting must include nested files and avoid double counting reservations.
- [x] Replace fixed Discovery write/read limits consistently. Do not leave readers rejecting valid >64 MiB files; retain structural/size validation before allocations. Keep continuity/resource caps separate and visible.
- [x] Run targeted store/coordinator tests, then full JVM suite. Disable each new regression guard, observe its named property fail for the intended reason, restore it and rerun.

### Task 4: Safe cache changes and accessible controls

Files: `DocumentCache.kt`, `FileDocumentCache.kt`, `DocumentCacheCoordinator.kt`, settings screens, `ReaderShell.kt`, `ReaderApp.kt`; existing settings/store/UI tests.

- [x] Add failing properties for speculative-before-user LRU eviction, protection on put and prune, oversized Notes, protected bytes above selected limit and source invalidations reaching Discovery. Validate 100 source Notes remain present under the default reference workload.
- [x] Wire runtime byte limits into put/prune paths. Retain 200-entry default initially and report count-bound exhaustion; do not imply the slider removes that bound.
- [x] Add Note-cache slider with size labels, increase/decrease semantics, commit on adjustment completion, and shrink-impact confirmation. Add Automatic/Custom Discovery choice and advanced maximum slider seeded at 256 MiB. Show actual use, effective safety restriction and model bytes separately.
- [x] Verify slider semantics/persistence and shrink cancellation through JVM Compose tests where supported; compile changed androidTests explicitly and run affected accessibility classes through the wrapper.

### Task 5: Storage-aware background recovery and final verification

Files: Discovery state/indexing/request scheduling, model acquisition, settings presentation, `README.md`, `docs/decisions.md`; new targeted storage tests.

- [x] Write failing tests proving low space pauses new writes while retaining eligible prior results, recovery resumes only permitted work, user Pause survives restart, stale staging cleanup cannot delete active generations and failed model replacement preserves the old installation.
- [x] Add WorkManager storage-not-low constraint as a coarse scheduling gate; keep write-time checks authoritative. Recheck space on new work/retry and normal scheduler opportunities; avoid a polling loop. Present actual storage/validation/I/O causes separately.
- [x] Run the project verification skill: full JVM suite, assembleDebug, applicable androidTest compilation and targeted wrapper classes. Probe actual settings and protected playback behavior on device. Do not claim human TalkBack speech acceptance.
- [x] Append a numbered decision round with measured sizing, formulas, migration defaults, failing-first evidence and unresolved gates. Align README and documentation entrypoint. Inspect scoped diffs/status and intentionally track new files.

## Execution review

Implement natively in the current session after plan approval; no subagents are needed for these shared storage interfaces. Commit only scoped changes when authorized, preserving the existing dirty workspace.
