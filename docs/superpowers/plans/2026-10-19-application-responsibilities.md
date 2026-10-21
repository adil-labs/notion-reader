# Application responsibility refactor implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete all four responsibility refactors without changing reader behavior.

**Architecture:** Keep public facades and process ownership. Extract cohesive internal
collaborators and share invalidation/runtime state rather than duplicating it.

**Tech Stack:** Kotlin, coroutines, Compose, JUnit 4, existing Gradle module.

**Spec:** ../specs/2026-10-19-application-responsibilities-design.md

## Global constraints

- Preserve governing specifications, layer rules and process-owned playback loops.
- No new dependencies, Gradle modules, model downloads or physical-device changes.
- All expensive application work respects injected dispatchers.
- New regression guards need failing-first evidence; retain facade contract tests.

## Review focus

- Reset during suspended retrieval must reject results even when cache is unchanged.
- Superseded queries must not publish results or errors over newer requests.
- Source edits withdraw evidence without overwriting concurrent preview edits.
- Queue extraction preserves pending Add, duplicate reports and frozen playback.
- Destination extraction preserves subject-keyed state and real focus targets.

### Task 1: Shared validity and retrieval

- [x] Add a dispatcher property covering search ranking and seed retrieval; observe failure.
- [x] Create DiscoveryValidity for reset epoch, source revision tokens and eligibility.
- [x] Create DiscoveryRetrieval for exact ranking, seed aggregation and catalog reads.
- [x] Keep controller delegation, inference locks and diagnostic isolation intact.
- [x] Run JVM suite and build; retain reset/source and request-order regressions.

### Task 2: Discovery ownership and state

- [x] Extract indexing, interests and preview responsibilities behind the facade.
- [ ] Finish grouped state and sealed request outcomes; the named topic type alone is insufficient.
- [ ] Migrate callers to named topic properties and remove compatibility aliases.
- [x] Verify full JVM suite and build; test new boundary risks where needed.

### Task 3: ReaderApp responsibilities

- [ ] Finish Discovery listening-action extraction and tighten coordinator responsibilities.
- [ ] Keep navigation/session handoffs explicit in the facade; retain ReaderHost loops.
- [ ] Correct remaining Activity ownership comments; retain facade contract tests.
- [x] Run JVM suite, build and applicable flow verification.

### Task 4: Destination screens and delivery

- [x] Extract topic, connection and interest details into focused composables/files.
- [x] Keep prepared evidence access, subject state, controls and semantics unchanged.
- [x] Run JVM suite, assembly, androidTest compilation and affected wrapper classes.
- [ ] Share saved-connection presentation/loading instead of duplicated screen logic.
- [ ] Perform relevant device probe and resolve independent review findings.
- [ ] Update README/decisions and audit every design completion requirement.

### Immediate stabilization before further extraction

- [x] Prove and fix related-request ordering across dispatch, reset and stale failure.
- [x] Keep query/related/matching publication in a single policy using typed reset/cache tokens;
  do not duplicate local epoch predicates in each success and failure path.
- [ ] Reproduce Browse/Discovery eligibility reports, distinguishing catalog rows,
  cached content, pending reconciliation, model availability and paused processing.
- [ ] Reproduce Home repeated-Note reports; distinguish repeated session identities
  from distinct playthroughs. Amend governing Notion sources before any new Home
  per-Note deduplication policy; preserve full listening history.
- [x] Add failing-first regression properties for the confirmed stabilization defects
  and verify this slice with JVM/build and targeted accessibility/device checks.
- [x] Couple interest-match success keys and Notes in one explicit result state;
  cancelled refreshes must not cache a success independently of empty visible results.

## Execution ledger

User explicitly instructed proceeding with implementation after written design review.
Earlier completion labels overstated delivery. Independent review found an introduced
related-request dispatch race, unfinished state redesign and coordinator boundary gaps.
The review reran 1,028 JVM tests successfully and checked debug assembly/androidTest
compilation, but those checks do not establish the missing design requirements.
Decision §101 is historical evidence of that incomplete claim, not final acceptance.
The user authorized continuing fixes and reported cache/Discovery and Home repetition
defects. Stabilize those paths first, then finish the reopened structural tasks.

Current stabilization slice implements shared DiscoveryRequests publication ownership,
typed read validity, explicit cache checking/ready/failure status and a single pending
queue membership flow with derived count. Final verification and device observations
are recorded in decisions §102 (1,035 JVM tests, builds, individual accessibility
classes, repeated queue stress and emulator readiness probe). Home same-session
duplication is not yet reproduced; distinct playthroughs remain distinct history entries.
Interest matching now has grouped sealed outcomes and owns success key/results together.
Search/preview/processing state grouping and the other reopened boundaries remain open.


### Incremental source-update slice (user-requested continuation)

- [x] Preserve current-source reusable passage vectors before destructive withdrawal.
- [x] Preserve both completed and unvisited reusable evidence in replacement checkpoints.
- [x] Persist typed connection delta inputs and unfinished directional seeds together.
- [x] Iterate affected targets directly and retain completed unchanged seed work.
- [x] Reuse only certified isolated unchanged topic groups, with exact fallback and
  current-evidence projection; never preserve a group merely for its old identity.
- [x] Prove regression failures and shipping-store/process recreation behavior.
- [x] Complete final review, verification and decision §103 evidence record.


### Shared Discovery lifecycle slice (user-requested continuation)

- [x] Publish saved pause permission with initial inventory; serialize pause commands
  against that publication and remove stale late permission copies.
- [x] Derive typed lifecycle and indexing/search action availability in process state.
- [x] Separate actual pending work, pinned inspected coverage and failure/recovery.
- [x] Reuse shared passive status/coverage/actions throughout relevant Discovery screens.
- [x] Prove startup, command ordering, no-work waits and storage recovery regressions.
- [x] Finish targeted accessibility verification and preserving-data Pixel check (§104).
