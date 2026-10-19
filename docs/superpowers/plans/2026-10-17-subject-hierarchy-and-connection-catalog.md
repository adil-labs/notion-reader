# Subject hierarchy and connection catalog implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline, task by task.

**Goal:** Deliver the approved MiniLM-based subject hierarchy, best-passage
membership and independent complete connection catalog, with reproducible evaluation.

**Architecture:** Extend the sole Discovery engine. Keep subject relationships
separate from topic identity and catalog persistence separate from preview selection.
Preserve process-scoped scheduling, atomic publication and cache-bound evidence.

**Tech Stack:** Kotlin, Compose, kotlinx.serialization/coroutines, JUnit 4,
existing private atomic file stores and pinned MiniLM ONNX pipeline.

**Spec:** `docs/discovery-subject-hierarchy-proposal.md`, approved by the user;
governing live Notion amendments must precede runtime behavior changes.

## Global constraints

- One production engine and MiniLM; no new production model or reranker.
- No generated mirror edits; amend live sources and regenerate all mirrors.
- At most 64 MiB combined derived storage; failed writes retain prior generation.
- Background/playback/resource pauses; one worker, off-main; explicit Refresh.
- At least 90% membership precision, 80% subject recall and useful labels,
  at most 10% false publication; independent human review remains required.
- Android: 10,000 passages, >2,000 uneven sections, <=120 s active processing,
  <=128 MiB additional model-loaded memory, <=1 s cancellation, <2 s warm p95.
- No semantic quality claim from synthetic vectors or provisional labels.

## Review focus

- Same-topic and subset-topic pairs retain useful independent passage connections.
- A Note with many good connections must not lose catalog entries to diversity caps.
- Storage exhaustion preserves prior evidence and honest incomplete status.
- Heading context alone must not establish membership or semantic subsumption.
- Eviction invalidates inspected hierarchy/catalog evidence immediately.

## Task 1: Governing amendments and source verification

Files: live Product, UI, Integration pages; generated `docs/notion/` mirrors.

- [x] Fetch current complete source pages and inspect target sections.
- [x] Amend Product for explicit evidence-backed subject edges, global best-passage
  membership, independent catalog admission and evidence-kind pair identities.
- [x] Amend UI for subject graph navigation, complete pageable connections and focus.
- [x] Amend Integration for edge/catalog versions, invalidation, budget and migration.
- [x] Regenerate with `pwsh -NoProfile -File tools/export-notion-doc.ps1 -All`;
  diff every change. Resolve conflicts before Task 2.

## Task 2: Independent connection retention

Files: `domain/discovery/DiscoveryEvidencePolicy.kt`,
`application/TopicCoordinator.kt`, `domain/discovery/DiscoveredTopics.kt`,
`data/local/FileTopicStore.kt`; tests beside existing domain/application/store tests.

Interfaces: catalog canonical identity includes unordered pages and evidence kind;
preview consumes `selectDiscoveryConnections`, never the reverse.

- [ ] Add failing tests for same/subset memberships, >20 pairs, >2 uses per Note,
  >5 matches per passage, distinct reference/similarity evidence, and resumed scans.
- [ ] Run `:app:testDebugUnitTest`; confirm failures identify the intended guards.
- [ ] Implement independent body retrieval with deterministic strongest-pair
  retention; remove selection/admission caps from persistence.
- [ ] Version retrieval policy; restart incompatible connection cursors without
  erasing compatible embeddings, interests or listening state.
- [ ] Make UI preview selection explicit; keep full catalog accessible and paginated.
- [ ] Add budget/refusal/corruption/invalidation checks and rerun JVM suite.

## Task 3: Hierarchy and specific subject evidence

Files: new `domain/discovery/SubjectHierarchy.kt`; `SemanticIndex.kt`, chunker,
`TopicDiscoveryEngine.kt`, `DiscoveredTopics.kt`, `TopicEvidence.kt`, topic store.

Interfaces: serializable `SubjectRelationship` references subject IDs and current
supporting source evidence; build function consumes validated topics/current records.

- [ ] Pin source-explicit parent evidence and negative examples in governing sources.
- [ ] Write failing tests for supported broad/narrow edges, unrelated heading
  context, shared-title/centroid false parents, multi-parent graph and cycle rejection.
- [ ] Preserve authored hierarchy provenance without using it as body membership.
- [ ] Build evidence-validated parent edges and related-subject links separately;
  keep uncertain candidates unpublished and all specific subjects accessible.
- [ ] Assert best substantive passage globally per Note/topic, deterministic ties,
  independent parent membership, preserved narrow/overlapping subjects.
- [ ] Validate/invalidate/migrate persisted edges and rerun JVM suite.

## Task 4: Catalog storage and accessible navigation

Files: new data catalog store/domain port; controller/coordinator/composition root;
`ui/TopicScreens.kt`; focused JVM and device navigation tests.

- [ ] Test paged catalog retention and atomic generation referencing with
  interruption, corruption, source deletion and shared-budget refusal.
- [ ] Implement bounded-memory catalog indexing and generation publication;
  include store/checkpoints in clear/model/reset/budget accounting.
- [ ] Add major-subject → subtopic → Note routes plus direct topic access and
  related-subject navigation; retain existing comparison route and evidence kinds.
- [ ] Test semantics order, OnClick actions, real focus targets, Back, Refresh,
  missing sources, large text and session isolation.
- [ ] Assemble, compile androidTest, run affected classes via accessibility wrapper;
  use device probe with actual focused app and discovered controls.

## Task 5: Evaluation and comparisons

Files: `tools/semantic-discovery/`, public corpus manifest, evaluator tests/report.

- [ ] Create 500-Note reproducible public/consented/purpose-built corpus with
  family-separated frozen partitions, revision/provenance and judgment status.
- [ ] Test shipping evaluator with known false positives/negatives and missing
  human judgments; report denominators and missing gates rather than a false pass.
- [ ] Compare exact formation and HNSW/Leiden on identical MiniLM vectors.
- [ ] Verify official BGE-small/Granite R2 artifacts and benchmark on the selected
  pipeline; record unavailable Android/tokenizer checks explicitly.
- [ ] Evaluate labels, hierarchy and connection usefulness independently.
- [ ] Run varied Android resource/resume/playback measurements and preserve failed
  or unmeasured human quality/listening gates.

## Task 6: Final verification and record

- [ ] Use project verification skill: JVM suite, assembly, explicit androidTest
  compilation, relevant wrapped accessibility/device tests and probes.
- [ ] Record failing-first guards and actual results in a new decisions section.
- [ ] Align README and implementation companion; intentionally track new files.
- [ ] Audit every approved requirement; leave goal active for missing deliverables.

Execution authorization: user approved the proposal and explicitly requested
implementation in this chat. Proceed inline; no further general permission request.

## Execution checkpoint

Task 1 completed and source pages re-fetched after amendments. Tasks 2–4 have
working retention, explicit-evidence edges, paged persistence and navigation;
bounded-memory candidate/controller consumption is not complete. Final-source
JVM/debug/androidTest compilation passed with 893 JVM tests before the additional
strongest-body-passage property was added. First v2 wrapped navigation run passed
19/19; a repeat on latest sources is in flight. Consult decisions §77 for red/green
evidence and limitations. Task 5 has not been implemented. Do not mark the goal done.

Latest checkpoint supersedes the counts above: 903 JVM tests pass, debug assembly
and androidTest compilation pass, and the latest changed-UI wrapper completed 19/19
without a retry. Target-Note cursors bound scan quanta; streaming ranking cooperates
with cancellation. UI evidence preparation now follows the visible window/selected
comparison, with a two-candidate homepage selector. Full `TopicSnapshot.connections`
materialization and coordinator pair maps still need replacement with on-demand,
generation-consistent catalog access; preserve pinned Refresh and immediate privacy
invalidation while doing so. General hierarchy recall and Task 5 are unfinished.

Further checkpoint: 910 JVM tests, assembly and androidTest compilation pass;
19/19 targeted navigation tests pass without retry. Semantic accumulation now uses
a quantum-sized update map and indexed ranked merge, and file writing streams its
chunks. Committed snapshots are reopened before publication; unreadable writes do
not replace the inspected view. `FileTopicStore.read()` still fully decodes catalog
evidence. Do not introduce a raw lazy file list without handling pinned generations,
source-removal rewrites/retirement, asynchronous selected-connection lookup and
main-thread equality/hash behavior. `connectionPreview` is currently synchronous
and would perform main-thread I/O under that naive change. Explicit-reference
retrieval still collects its candidate set. These ownership/access changes, broader
hierarchy recall and Task 5 remain required.

On-demand ownership checkpoint (decisions §78): shipping state now holds two
preview pairs plus an opaque catalog reference. Worker-only paged views, explicit
async UI reads, inspection pins, private-source reconciliation/retirement and
fail-closed legacy adapters replace full catalog residency in the shipping path.
923 JVM tests pass. Final changed-UI device verification follows. Cumulative UI
windows and reference candidate collection still need bounded ownership; broader
hierarchy recall and Task 5 remain incomplete.

Reference-opportunity checkpoint (§79): shipping Named references now participate
in the same bounded, durable passage/target sweep with independent admission and
identity. A persisted metadata-only title index avoids full-library reads per
resumed quantum; source removal covers that index. Policy 13 restarts old cursors.
The old full-list helper remains developer/test compatibility only. Cumulative UI
windows, broader hierarchy recall and Task 5 remain unfinished.

Bounded browsing checkpoint (§80): lazy collection keeps at most sixty nearby
literal rows and preserves saved revealed positions. More remains explicit; earlier
rows reload when revisited, with saved Back focus/scroll. The unbounded preparation
branch was removed. 928 JVM tests/build/compilation pass; the restored-heading
navigation wrapper passed 20/20. A repeat includes the strengthened late-row
comparison/Back assertion. General hierarchy recall and Task 5 remain unfinished.

Public baseline checkpoint (§81): corpus capture/validation tooling and seven
properties are implemented; a cached/backing-off public API capture is running.
No 500-source lock or quality acceptance exists yet. Keep Task 5 unchecked until
the full corpus, leakage audit and algorithm/model comparisons are actually run.

Shipping-input checkpoint (§82): explicit public-only Gradle export uses the
shipping tokenizer/chunker, verifies the frozen lock/corpus fingerprint and binds
output/vocabulary hashes. Python/Kotlin fingerprint parity and partial/private
source refusal are tested. 933 JVM tests and build checks pass. Capture is still
running (51/500 at checkpoint); no final lock, passage export or comparison exists.

Vector-artifact checkpoint (§83): shipping token IDs are exported with passages;
local MiniLM runner pins production artifacts and commits a verified, ordered,
content-addressed vector artifact. Five vector and seven corpus properties pass;
real one-input ONNX smoke is finite/unit/384d; 933 JVM tests pass. Full capture and
all algorithm/model comparisons are still pending; no winner or quality gate claim.

Graph-candidate checkpoint (§84): real host HNSW/Leiden baseline and tie-aware
exact-neighbor calibration probe pass four tests; isolated dependency versions are
pinned. The CLI refuses unprepared raw passage artifacts: shared shipping eligible
section/direction opportunity export remains required before the paired run. No
winner, model replacement, 500-Note comparison or downstream quality acceptance.

Shared preparation checkpoint (§85): the sole shipping engine and host export can
now share one internal eligibility/direction preparation function. Existing logic
was extracted, not approximated or reimplemented in Python. Heading-guard mutation
failed the intended property; restored full JVM suite passes (935 tests), along
with debug/androidTest compilation. Host transport/wiring and the full paired run
remain required. Public capture is still live (132/500 at checkpoint).

Native transport checkpoint (§86): checked float32 bridge and JVM preparation
export are wired; Python graph conversion preserves native direction bits/member
provenance without new inference. Alignment-guard mutation failed the intended
property; 938 JVM tests/build checks and six vector/two conversion/four graph tests
pass. Source capture is live (144/500 at checkpoint); full exact seed export,
corpus run/common downstream quality and all acceptance gates remain pending.

Exact-observer checkpoint (§87): explicit host exact task validates shared native
IDs/member provenance/vector bits and observes the actual shipping candidate phase,
then completes normal downstream work. Runtime step uses the same body with no
observer. 939 JVM tests, debug/Android-test compilation and graph properties pass.
Full source capture/run and common downstream graph review remain pending.

Common downstream checkpoint (§88): shipping and host graph review share focused
candidates, centroid/anchor, best-body membership, naming/publication and hierarchy
functions. No runtime graph selector was introduced. Stronger-passage mutation fails
the intended property; 942 JVM tests, debug assembly and androidTest compilation
pass. Full 500-source run/leakage/human review and remaining quality/device gates
are still pending; capture remains live (188/500 at checkpoint).

Leakage checkpoint (§89): full/progress lexical audit and whole-family exclusion
reporting are implemented. Four new properties plus existing related suites pass
31 Python tests; HTTP response-close guard was failing-first. A 211-Note diagnostic
covered all 22,155 pairs with zero lexical risks but an incomplete gate. Live capture
continues (216/500 at checkpoint). No final corpus or semantic-quality acceptance.

Labeled-data checkpoint (§90): user-authorized WOS-JTF diagnostic froze 500 public
source records and isolated publisher labels, producing 861 shipping passages.
Actual exact/graph/common-downstream runs completed for MiniLM, BGE-small and
EmbeddingGemma 2 text-only ONNX. Generic Body publication failed a new regression
and is fixed by policy 14. Source/gold checksum weaknesses found by independent
review are fixed with failing-first proofs; 944 JVM tests, build checks and 43
Python tests pass. Larger models traded more publisher-pair recall for lower
precision and higher measured host cost; no production switch. This source-split
proxy evaluation does not complete Task 5's family-held-out/human/device gates or
general hierarchy/connection quality. See discovery-wos-evaluation.md and §90.

Quantization checkpoint (§92): fixed-input/settings FP32, INT8 and Q4 Gemma runs
completed. INT8 retains FP32-like precision at about 71% less model storage; Q4 is
about 84% smaller with higher proxy recall/F1 but lower precision. All source/token,
model/report hashes and full downstream scores are recorded. 44 Python tests and
954 JVM/build checks pass. No production replacement or human/device quality gate
is implied; Task 5's broader acceptance work remains incomplete.
