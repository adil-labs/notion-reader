# Automatic Discovery Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task. Preserve the existing dirty worktree; no reset or automatic commit.

**Goal:** After setup, automatically finish cached-Note embeddings, topics and connections while foregrounded or in Android-granted background windows, without Continue clicks.

**Architecture:** A process-owned coordinator provides one bounded execution entry point shared by foreground triggers and WorkManager. One mutex serializes all bulk pipeline calls; foreground queries retain priority. WorkManager persists scheduling across ordinary process death and reboot; existing source/model/reset checkpoints persist actual progress.

**Tech Stack:** Kotlin/coroutines, AndroidX WorkManager, existing DataStore, Compose, ONNX Runtime Android and the pinned Gemma INT8 pack.

**Spec:** `docs/superpowers/specs/2026-10-18-automatic-discovery-design.md` — user approved design and direct execution of this plan on 18 October 2026. Governing live-source amendment is Task 1.

## Global constraints

- Explicit model acquisition; cached Notes only; no hidden Notion downloads.
- Balanced default; Charging only optional; both retain complete analysis and evidence thresholds.
- Balanced requires external power or known battery of at least 30 percent. Charging only requires external power.
- Playback, resource pressure and explicit user pause suspend bulk work; withdrawal remains immediate.
- One pipeline and execution gate, no permanent foreground service or notification, no expedited work.
- Existing 64 MiB combined derived-storage budget and model/source/reset checks remain.
- No app uninstall or data clearing for verification. Use the repository device-test wrapper.

## Review focus

- Repeated source and power events must not create concurrent inference or an unbounded job chain.
- Process death between checkpoint and publication must resume without stale or duplicate publication.
- User Pause/reset/model deletion must defeat already queued or executing jobs.
- Unplugging during Charging only or crossing 30 percent must stop bulk work without hiding the reason.
- Updating published results must not move an inspected topic, change focus or alter listening queues.

### Task 1: Amend governing scheduling and lifecycle contracts

Files: create `tools/amend-discovery-automatic-work.ps1`; regenerate the Product,
UI and Integration mirrors through the existing exporter; update the hand-authored
implementation companion.

- [x] Add an idempotent amendment to the three existing live page IDs. Explicitly supersede foreground-only gating and bound-service-exclusive worker ownership; preserve all other privacy/playback/resource/reset requirements.
- [x] Define automatic setup activation, pending-slice continuation, persisted effort and honest OS-wait status. Preserve explicit Pause and destructive-clear semantics.
- [x] Apply, run `pwsh -NoProfile -File tools/export-notion-doc.ps1 -All`, inspect the complete diff and dependent contracts. Do not hand-edit mirrors.

### Task 2: Persist effort and expose policy

Files: `domain/settings/ReaderSettings.kt`, `data/local/DataStoreSettingsRepository.kt`,
`application/DiscoveryWorkPolicy.kt`; existing settings/power test files.

Interfaces: domain `enum class DiscoveryEffort { BALANCED, CHARGING_ONLY }`;
`ReaderSettings.discoveryEffort` defaults to BALANCED;
`DiscoveryPowerState.allows(effort: DiscoveryEffort): Boolean` centralizes the gate.

- [x] Write failing properties for missing/unknown persisted value default, round-trip both choices, unknown battery, 29/30 percent and connected power, plus unplugging Charging only.
- [x] Implement persistence with stable enum identifiers and the pure policy. No threshold or coverage change follows from effort.
- [x] Run the JVM suite; prove the policy boundary test detects a removed power gate.

### Task 3: Share bounded work without competing owners

Files: create `application/DiscoveryWorkCoordinator.kt` and its JVM tests;
modify `ReaderApplication.kt`, `DiscoveryService.kt`, `DiscoveryController.kt`.

Interfaces: `DiscoveryWorkCoordinator.runWindow(maximumMillis: Long): DiscoveryWindowResult`;
result enum `COMPLETE`, `PENDING`, `DEFERRED`, `PAUSED`, `UNAVAILABLE`.
Inject monotonic time and the suspend reconciliation function for deterministic tests.
Expose explicit pending-work information from the controller rather than parsing UI strings.

- [x] Write failing tests for simultaneous callers yielding at most one active bulk call, automatic multi-slice completion, time-window expiry retaining pending work, power/playback deferral and reset defeating stale work.
- [x] Refactor the existing foreground reconciliation owner through the coordinator. A window uses a 120-second active wall-clock ceiling, checked between cancellable work quanta; a native call may finish before the next check. Keep source withdrawal available while bulk work is deferred.
- [x] Remove the bound service's infinite-loop ownership once foreground and scheduled callers share the coordinator. Keep Activity ownership out of long-lived processing.
- [x] Retain and run `unfinishedDiscoveryRequestsItsNextRoundWithoutAContinueAction`; run all JVM tests and LayerRulesTest. Amend architecture tests deliberately only if the governing amendment requires it.

### Task 4: Persist background scheduling

Files: create composition-root `DiscoveryWorker.kt`, `DiscoveryScheduler.kt` and
targeted scheduler tests; modify `ReaderApplication.kt`, `app/build.gradle.kts`
and manifest only where needed.

Interfaces: `DiscoveryScheduler.request()` coalesces a unique one-time request;
`DiscoveryScheduler.cancel()` cancels pending Discovery requests;
`DiscoveryWorker.doWork()` obtains the application coordinator and calls runWindow.

- [x] Check official Android docs and select a stable WorkManager version compatible with this project's SDK/toolchain. Inspect its documented constraint, cancellation and retry semantics before implementation.
- [x] Write failing scheduler tests: repeated requests coalesce, complete work returns success, pending/deferred work retries, explicit pause/unavailable returns success without rebuilding, and model deletion/reset cancels work.
- [x] Use ordinary one-time work with unique KEEP and linear minimum-supported retry backoff for unfinished windows; no self-appending job chain. Use the exact in-app Balanced gate and require charging for Charging only (see execution ruling), and recheck the exact app power policy inside each window.
- [x] Add one unique 15-minute periodic recovery request while Discovery is enabled, as a fallback for process death or a trigger arriving while a finishing unique request still exists. It runs no heavy work when complete; it never scans remote Notion.
- [x] Trigger scheduling on verified setup, source changes, compatible pending recovery and settings/power eligibility changes. Replace constraints when effort changes; cancellation preserves checkpoints and explicit pause.
- [x] Test foreground/background races and process restart with durable checkpoints. Run JVM/build/androidTest compilation.

### Task 5: Remove manual continuation and expose effort/status accessibly

Files: `ui/DiscoveryPresentation.kt`, `ui/TopicScreens.kt`,
`ui/settings/DiscoverySettingsScreen.kt`, `ui/ReaderShell.kt`; affected JVM and
device accessibility tests.

- [x] Write failing presentation tests: unfinished unpaused work has no Resume action; explicit user pause still explains recovery; Android/power waiting states are distinct; only a newer pinned snapshot offers Refresh.
- [x] Replace Continue discovery at slice boundaries with automatic progress/waiting text. Keep explicit Settings Pause/Resume and destructive-action recovery.
- [x] Add accessible single-selection Balanced / Charging only controls, persisted through settings. Explain that effort changes availability, not quality or coverage.
- [x] Preserve scoped embedding estimates and separate unknown topic/connection time. No completion deadline, live-region chatter or focus theft.
- [x] Run JVM tests and targeted wrapper accessibility classes, scrolling real lazy content into view before readable/reachable assertions.

### Task 6: Verify the actual user outcome and record evidence

Files: add `app/src/androidTest/java/com/example/notionreader/ui/AutomaticDiscoveryDeviceTest.kt`;
update compact Gemma integration artifact, README, docs map and numbered decisions.

- [x] Seed purpose-built Notes into an isolated test store with the real Gemma pack. Enable once; do not call Resume, Refresh or reconcile repeatedly from the test to drive progress.
- [x] Observe actual scheduler execution with the app backgrounded and screen off; confirm completed embeddings, subjects and meaningful paired connections. Distinguish normal OS scheduling from forced JobScheduler test hooks in recorded evidence.
- [x] Exercise ordinary process death/checkpoint recovery, explicit Pause/reset, playback priority and both effort choices. Simulate battery only on the owned emulator and restore it; retain physical-device data sentinels.
- [x] Prove automatic-continuation and stale-publication guards fail for their intended reasons, then restore and run `:app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`.
- [x] Run applicable repository wrapper self-checks and targeted device tests. Inspect real status/effort and connection UI. Record counts and unobserved limitations honestly; keep representative quality and human TalkBack release gates separate.
- [x] Obtain a focused review, audit all user requirements against current artifacts, and update the decision record. Do not mark the goal complete from scheduler success alone.

## Plan review

All approved design sections map to tasks above. Foreground regression evidence
already exists; it does not substitute for Task 6 background verification. The
effort names and exact power threshold match the design. Scheduler timings are
bounded implementation policy, not promised user-facing completion times. No
production change in this plan is authorized by a green host proxy score alone.

Recommended execution: native in this session, with a focused review at the end,
because scheduler lifetime, power gating and publication share coupled interfaces.

## Execution ledger

Implementation and integration verification completed on 18 October 2026; see
`docs/decisions.md` §95 and the compact Gemma integration artifact for counts,
failing-first proofs, review and qualification limits. All task outcomes are
accounted for; individual worker branches use controller/coordinator tests plus
actual WorkManager device proof rather than a new mocking framework.

Ruling: Balanced uses the exact in-app 30% OR external-power policy rather than
BatteryNotLow, which cannot express it. Charging only uses a persisted charging
constraint. Regular retries and periodic recovery retain checkpoints. UI_HIDDEN
is not pressure. One process coordinator replaces bound-service ownership.

Review extended the original plan with durable per-passage staging: a long Note
otherwise restarted forever across short windows. Reused prefixes avoid writes;
complete records retire leftover staging. Both follow-up fixes failed first.
Settings round-trip and reconstructed scheduler constraints passed on device.
The normal screen-off four-Note test and the real process-kill recovery probe
passed without forced jobs. Fixtures and battery simulation were restored.

973 JVM tests, assembly and Android-test compilation passed. Targeted accessibility,
power, parity, journey, background and recovery results are recorded in §95.
Independent quality, full-scale resource and human TalkBack release gates remain
open; task completion does not assert those acceptance gates passed.
