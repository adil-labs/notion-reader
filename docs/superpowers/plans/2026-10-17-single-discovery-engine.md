# Single Discovery Engine Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline,
> task by task. The user explicitly authorized implementation beyond this plan.

**Goal:** Retire the legacy algorithm and deliver one safely migrated,
section-aware, evidence-backed Discovery architecture across debug and release.

**Architecture:** Preserve the application-bound background service and shared
MiniLM indexing/search/player boundaries. Remove switching abstractions and
legacy progress; keep formation, projection, validation, naming, publication,
connections and persistence separately testable.

**Tech Stack:** Existing Kotlin, coroutines, Compose, kotlinx.serialization,
ONNX Runtime, JUnit 4 and repository Android wrappers; no new model/service.

**Spec:** [Single-engine design](../../single-engine-discovery-design.md), the
user's retirement decision and the amended Product/UI/Integration contracts.

## Global constraints

- No legacy execution or debug/release algorithm difference, including tooling.
- All eligible evidence eventually receives opportunity; no topic-count ceiling.
- Preserve explicit interests, queues, listening state, source cache and valid
  model/tokenizer/source-compatible embeddings.
- Atomic generations, private storage at most 64 MiB and current removal rules.
- Preserve independent named references, playback isolation and accessibility.
- Android performance: 10,000 passages / >2,000 uneven sections, <=120 s active,
  <=128 MiB added Java/native, <=1 s cancellation, warm-search p95 <2 s.
- Quality: recall >=80%, precision >=90%, false publication <=10%, useful
  source-supported labels >=80%; no pass without independent reviewed evidence.

## Review focus

- Upgrade/crash while both topic and checkpoint stores contain incompatible data.
- Long Notes with useful late sections or several subjects in one section.
- Semantic membership without a literal phrase common to every member.
- Split/merge identity when legacy membership was wrong or source evidence lost.
- Memory/work/storage pressure must remain incomplete, never silently skip input.

## Task 1: Reconcile governing contracts and migration specification

**Files:** three live governing Notion pages and regenerated mirrors;
`tools/adopt-single-discovery-engine.ps1`; design above; implementation companion.

- [x] Audit current main/working-tree dependencies without undoing prior work.
- [x] Write final architecture and migration plan.
- [x] Implement dry-run exact-source amendments removing contradictory baseline,
  flag and common-label membership requirements; add sole-engine migration and
  stage contracts. Review dependent UI/Integration pages.
- [x] Apply authorized live amendments, regenerate all mirrors and diff them;
  preserve complete domain-model code blocks.

## Task 2: One runtime path and safe durable upgrade

**Files:** `DiscoveredTopics.kt`, `TopicEvidence.kt`, `TopicDiscoveryEngine.kt`,
`TopicCoordinator.kt`, `DiscoveryController.kt`, `ReaderApplication.kt`,
`FileTopicStore.kt`, `app/build.gradle.kts`, unit/store/integration tests.

**Interfaces:** one concrete `SectionHierarchicalEngine.step(records, previous,
policy, workBudget, checkActive): TopicSnapshot`; shared `invalidateTopics` and
source-phrase helpers; versioned `TopicSnapshot` and `TopicFormationProgress`;
`TopicStore.read/writeDetailed/clear` continue to isolate derived persistence.

- [x] Add failing upgrade tests: engine-zero state discarded with no user-data
  mutation; compatible embeddings reused; old hierarchical progress safely
  resumes/restarts; interrupted/corrupt/storage-refused migration stays safe.
- [x] Extract shared evidence helpers, delete the streaming builder and switching
  interface/wrapper, remove the flag/property/controller selection parameter.
- [x] Replace coordinator legacy sweep with sole hierarchical orchestration;
  remove obsolete progress/config fields and wire migration at persistence.

Execution ledger (in progress): Production `compileDebugKotlin` passes after
removing the builder/interface/wrapper, Gradle flag/property and old coordinator
sweep. Header-only persistence decoding discards engine-zero state before
materializing memberships. Migration tests: three intended red failures first,
then all four green (legacy generation/checkpoint, compatible hierarchical
resume, interrupted temporary state). Three architecture guards failed first;
they and the converted coordinator integration tests now pass together. Shared
evidence helpers moved to `TopicEvidence.kt`; old anchor-sweep fields are gone.
Legacy-mechanism tests are being removed while shared product properties are
converted to sole-engine inputs; do not treat the current test-count reduction
as a pass until the complete suite and replacement scenario coverage are audited.

Ruling: compatible old hierarchical work may resume only when its processing
signature/policy agrees; the new policy version intentionally restarts incompatible
progress. No interest/session/cache store is cleared by topic-store migration.

Investigation note: one whole-suite attempt was deliberately stopped after the
old reset fixture awaited a legacy checkpoint boundary the new engine did not
reach. This incomplete run is not a verdict. The fixture now selects a one-unit
formation work quantum and requires the real checkpoint boundary with a bounded
wait, retaining reset/privacy assertions. A temporary editing helper read three
UTF-8 files using the Windows locale; their text was repaired and the helper is
not shipping tooling. The subsequent targeted architecture/migration/integration
run passed. Full-suite verification is still in progress.
- [x] Convert shared test fixtures to the sole engine; delete tests/fixtures whose
  sole purpose is legacy mechanics. Record intentional test-count changes.
- [x] Verify JVM tests plus debug/release compilation and absence of executable
  legacy symbols/selection branches. Inspect both generated BuildConfig classes.

## Task 3: Independent naming, complete opportunities and connections

**Files:** sole engine, publication/evidence policy, coordinator, domain and
integration tests, processing/checkpoint schema.

- [x] Add failing tests for 40 subjects, overlapping/focused subjects, long
  multi-topic sections, valid semantic membership without common literal labels,
  and semantic connections beyond the first three sections.
- [x] Separate evidence validation from label selection. Retain unnamed valid
  candidates; use specific representative source labels without inventing text.
- [x] Persist bounded intra-Note connection progress and evaluate late evidence;
  preserve independent explicit retrieval and canonical/exposure rules.
- [x] Exercise cancellation/resume, deletion/eviction, identity split/merge,
  storage pressure, query priority and listening-session isolation.
- [x] Prove regressions failing-first and rerun the cheap JVM suite.

## Task 4: Remove executable legacy tooling and validate Android resources

**Files:** `tools/semantic-discovery/`, discovery instrumentation, CI/developer
commands, policy/replay tests and shipping benchmarks.

- [x] Audit every executable legacy reference; remove obsolete ablation/baseline
  fixtures and comparison code, retaining clearly historical result documents.
- [x] Update shipping entrypoints to the sole engine and representative diverse
  workloads; investigate primitive versus boxed vector/memory peaks.
- [x] Run available Android formation/search/resource/cancellation/resume checks
  and report actual device/API/workload/model-loaded baseline and failed gates.
- [x] Run repository-wrapper targeted accessibility classes and the real service
  lifecycle probe with an explicitly selected device.

## Task 5: Review, documentation and acceptance audit

- [x] Independent correctness review, including migration/privacy and race paths.
- [x] Full JVM suite, assemble debug/release, compile androidTest and applicable
  device tests. Check source-derived counts and no hidden fallback paths.
- [x] Update README/companion/diagrams/tooling guide and append decisions §76
  with proofs, actual outcomes, quality denominators and unresolved risks.
- [x] Report architecture migration, functional tests, quality acceptance,
  Android performance and remaining production risks separately. Do not mark
  unmeasured human or physical gates passed.


### Execution ledger continuation

- Sole publication policy now has no experimental marker. Supported representative
  labels are independent of semantic membership wording. Naming/static regression
  tests were observed red then green. Publication fixtures now express actual body
  evidence rather than the retired marker; overlapping-span/source provenance is
  retained. Broad and focused candidates may legitimately share a source label;
  continuity assertion identifies the focused evidence family explicitly.
- Long multi-subject section regression failed with section-mean-only formation,
  then passed after every distinct passage direction within an authored section
  received opportunity. Exact identical directions are contracted without dropping
  their passages. Engine version is now 3, so incompatible old v2 checkpoints
  restart rather than interpreting changed opportunity indices. `totalSections`
  currently counts these section sub-opportunities, a reporting limitation to fix.
- Connection scanning now has a persisted intra-Note passage cursor, processes up
  to 256 passage opportunities per quantum, and has no first-three-section limit.
  A 257-passage restart test passes; temporarily restoring `take(3)` made exactly
  that targeted test fail, and the source was restored in finally. It still needs
  the final green full-suite run after that proof (subsequent full run passed).
- Subject centroid is persisted separately from the actual representative passage
  vector. Removal/reanchor cosine lower bounds now use the direction against which
  member scores were measured; replacement direction validation is explicit.
  A dedicated failing-first centroid-removal test is still outstanding.
- Continuity uses opaque content-sensitive passage fingerprints and requires
  actual evidence overlap; Note overlap alone cannot assign a rewritten topic ID.
  Rewritten-same-ID regression failed first then passed. Merge fixture was repaired
  to retain source content while controlled vectors converge, so it actually tests
  a merge rather than rewriting Astronomy into Gardening.
- Latest full JVM suite: 875 tests, zero failures; assembleDebug and explicit
  compileDebugAndroidTestKotlin passed. The first androidTest compile caught one
  stale named marker argument; it was removed and compilation rerun successfully.
- Release assembly passed using the C: output-location init script after D: filled.
  A subsequent production change requires a fresh release build before final.
  Release lint exposed unsupported no_backup XML domains and datastore-wide
  exclusions masking portable includes. Two backup-policy tests failed first and
  pass after retaining only the exact four portable state includes. Android CLI
  official autobackup docs confirm noBackupFilesDir is always excluded.
- Remaining: centroid regression proof, performance/quality/Android device checks,
  executable legacy-tool cleanup, final review, README/companion/decision record.
  Existing quality measurements below 90% and prior ~189MiB memory remain failures;
  the repeated-eight-direction scale fixture is not varied workload acceptance.


### Final execution status

Implementation and available verification tasks completed. The final source uses
engine version 4 and policy version 11; authored-section counts and opportunity
counts are separate. Additional review fixes and failing-first proofs cover
literal focused membership, partial-deletion continuity, equal labels with
independent evidence, centroid-based reanchor bounds, and paused connection
publication. 880 JVM tests, debug/release assembly, androidTest compilation,
17 navigation tests, two shipping-model tests, three formation/sampler tests and
the service probe passed in their recorded scopes. The final workload variations
passed time/memory/cancellation gates on API36 x86_64 emulator; no physical,
playback, heat or human quality acceptance is implied. Calibration/held-out
provisional quality remains 8/10 and 11/13 precision, below 90%; do not mark that
gate passed. See decisions.md §76 for exact evidence and remaining acceptance.
