# Structural knowledge implementation plan

**Goal:** Implement the approved Phase 1 scope without changing reader or Discovery semantics.

**Architecture:** Extend the existing Notion API/repository and cache lifecycle.
Store versioned structural facts separately from semantic inference and user state.
Use existing file-store atomic replacement, bounded storage admission and process ownership.

**Tech stack:** Kotlin, kotlinx serialization/coroutines, Ktor, existing atomic file stores, JUnit 4.

**Spec:** User-supplied Phase 1 requirements, subordinate to the seven governing
contracts. See [API evidence](../../knowledge-system/phase1-api-evidence.md).

## Constraints and review focus

No new UI, AI processing, topic catalog, graph database, Notion mutation or PRs.
The user subsequently authorized focused commits, merge to `main` and push to
`origin` after verification; the primary agent owns those operations.
Preserve reader document/chunk/section IDs and user state. Opening a Note
must not depend on workspace traversal. A failed or partial enumeration cannot
prove deletion. Rejected/obsolete writes must not republish withdrawn content.

Review particularly: ambiguous 404s; relation truncation; cache-clear races;
multiple invalidation consumers; source changes during suspended I/O; corrupt or
future-version storage; malformed/repeating pagination; URL host validation;
bounded work and cancellation. Fresh verification must distinguish actual test
execution from Gradle's reuse of results.

## 1. Typed facts, extraction and storage

- [x] Correct the delegated audit/design against code and official API evidence.
- [x] Add domain structural records and query/store ports under `domain/structure`.
      Distinguish database/data-source parents, provenance and source identity,
      unknown/partial states and missing-or-inaccessible from confirmed trash.
- [x] Extend `NotionModels.kt` and `NotionApi.kt` additively for parent metadata,
      reference properties, data sources and paginated property items. Keep existing
      API fakes source compatible and reject incomplete pagination as complete.
- [x] Add `data/structure` extraction from normalized provider-independent facts;
      stable IDs, sibling order, section heading paths and explicit edge kinds.
      Existing reader normalization and playback policy remain unchanged.
- [x] Add atomic versioned file persistence with per-page equality reuse,
      bounded reads/writes, source epochs and durable pending page work. Restart
      API traversal after process recreation; do not persist opaque cursors.
- [x] Add JUnit properties for hierarchy, ordering, links, relations, unsupported
      and synced blocks, storage restoration/migration/corruption and safe failure.
      Run tests and failing-first guard proofs with exact outcomes recorded.

## 2. Existing pipeline integration

- [x] Extend `NotionRepository` and the existing cache/auto-cache orchestration
      with structural observation and resumable bounded work, sharing fetched
      children where possible. Do not add a competing workspace synchronizer.
- [x] Wire process-owned structure storage/query access in `ReaderApplication`.
      Integrate cache budgets, reset/source invalidation and eligibility before
      publishing, including when documents were already evicted.
- [x] Test rename/move/reorder/property edits, unrelated-file reuse, cancellation,
      recreation, transient failure, recovered access and late commits after reset.
- [x] Verify existing reading/playback/Discovery behavior through existing JVM
      tests; run relevant device checks only for claims needing Android behavior.

## 3. Audit and delivery

- [x] Review all changes independently, including provenance, migration,
      invalidation, concurrency and performance. Correct findings and rerun
      affected checks. No success claim based solely on delegated prose.
- [x] Use `verify-notion-reader`: JVM suite, debug assembly and Android-test
      compilation; targeted wrapper/device checks if applicable.
- [x] Publish before/after matrix with real code/test/source links, model/query
      documentation, exact verification, API limits and remaining gaps. Append
      numbered decision evidence and align README/documentation map.
- [x] Preserve Antigravity workflow lessons in tracked documentation. Inspect
      final diff and intentionally stage new repository infrastructure; make
      focused commits, merge to `main` and push under the subsequent authorization.

Antigravity implements bounded increments; the primary agent reviews each diff
and owns acceptance. Do not overlap Gradle/device operations across processes.


Execution evidence and limits: [corrective review report](../../knowledge-system/phase1-review-followup.md).
The final integration checkbox includes the user's authorized main merge/push;
its concrete result is recorded by the Git history and final response.
