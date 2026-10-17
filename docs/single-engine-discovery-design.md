# Single-engine Discovery architecture and migration

Date: 2026-10-17. Implements the user's explicit decision to retire the legacy
Discovery engine. This design is subordinate to the amended governing Notion
contracts; the previous debug/release fallback requirement is superseded by that
user decision. The preceding service/correctness fixes remain part of this tree.

## Intended result

One section-aware hierarchical engine serves debug and release. No streaming
anchor builder, engine-switch flag, rollback property, parallel runtime path or
obsolete executable baseline survives. Valid semantic subjects are retained even
when their members do not share a literal name. Human quality acceptance and
Android performance acceptance remain separately measured requirements.

## Pre-change audited dependency graph

- `app/build.gradle.kts` defines `EXPERIMENTAL_DISCOVERY` and the debug-only
  `experimentalDiscovery` property. `ReaderApplication` passes it through
  `DiscoveryController` into `TopicCoordinator`.
- `TopicCoordinator.build` runs either the legacy `TopicBuilder` sweep or
  `buildExperimental` using `SectionHierarchicalEngine`.
- `TopicDiscoveryEngine.kt` contains the switching interface, the redundant
  `StreamingAnchorEngine` wrapper and the hierarchical implementation.
- `DiscoveredTopics.kt` mixes shared persisted domain types, legacy builder,
  source invalidation and source-phrase naming. These responsibilities must be
  separated before deletion.
- `FileTopicStore` accepts both engine generations and validates obsolete
  anchor-sweep progress. `TopicSnapshot` contains both kinds of progress fields.
- `TopicPublication` branches on the experimental validation marker and uses
  common literal label support as a membership/publication gate.
- Semantic connections scan only three distinct sections from each seed Note.
  Their document cursor is durable, but their intra-document opportunities are
  not. Named references already retrieve independently on the hierarchical path.
- Legacy-only unit tests, direct-inference device tests, replay/comparison tools,
  Python ablations and guard-mutation scripts execute the old builder or encode
  its limits. Shared search/tokenizer/model/evidence/player tooling stays.

## Final stages and ownership

1. **Indexing:** existing optional pinned MiniLM/ONNX pipeline and private
   per-document semantic index. Reuse only model/tokenizer/source-compatible
   records; semantic search remains exact and independent.
2. **Subject formation:** one `SectionHierarchicalEngine`, with work quanta rather
   than admission or topic-count limits. Deterministic opportunities include all
   eligible sections and late passages. Retain broad and independently supported
   focused candidates.
3. **Membership projection:** body passages project onto candidate subject
   evidence, allowing overlapping Notes and ungrouped passages. Semantic/body
   eligibility does not require repeating the label. Keep literal provenance and
   scores; never use reading/listening activity.
4. **Evidence validation and naming:** validate membership independently of names.
   Prefer specific complete authored headings, then bounded literal source
   phrases. A supported representative label need not be common to every member.
   Reject generic/family-only/predicate labels without discarding the retained
   semantic candidate. Keep naming failure explicit and resumable.
5. **Publication:** one policy for Established (at least two distinct Notes) and
   Emerging (one Note) subjects, deterministic duplicate consolidation and stable
   evidence-backed opaque identity. No arbitrary topic-count cap.
6. **Independent connections:** named-reference retrieval is always enabled and
   independent of similarity. Semantic candidate opportunities cover late
   sections/passages; bounded persisted work never claims unvisited opportunities
   complete. Preserve cross-topic/ungrouped eligibility, exact evidence scoring,
   canonical pair identities and the display exposure cap.
7. **Persistence/UI:** publish atomically after cache/model/reset validation.
   Inspected generations use explicit Refresh; mandatory source removal is prompt.
   The application-bound Discovery service owns bulk work off-main, giving queries
   priority and pausing for playback/background/resource pressure. No playback
   command or saved queue is changed.

## Persistence and upgrade contract

- Version the sole-engine topic state and processing policy explicitly. Inspect
  persisted engine/schema identity before trusting topic membership or progress.
- Legacy engine-zero state/checkpoints are incompatible derived data: remove
  them and rebuild from current eligible embeddings. Do not execute the legacy
  builder as a migration step. Dropping unsupported opaque legacy topic IDs is
  preferable to retaining a misleading membership merely for identity stability.
- Previously hierarchical checkpoints are reused only when model, source,
  processing policy and progress invariants match. Otherwise restart safely;
  preserve only continuity supported by surviving passage/source evidence and
  revalidate before publishing.
- Reads must never expose a half-migrated generation. Interrupted migration is
  idempotent. Corrupt/truncated temporary state is discarded or retried; insufficient
  derived storage stays an explicit incomplete/recovery condition.
- Saved interests are independent explicit user data. Preserve names, matching
  text and IDs even when an origin topic becomes unavailable. Preserve session
  history, queues, source cache, embeddings, model and unrelated settings.
- Migration code may recognize old wire fields, but they do not remain active
  clustering configuration or runtime progress in the new domain model.

## File responsibilities

- `domain/discovery/DiscoveredTopics.kt`: shared domain/persistence types only.
- `domain/discovery/TopicDiscoveryEngine.kt`: sole hierarchical formation and
  resumable projection; no pluggable baseline interface.
- `domain/discovery/TopicEvidence.kt`: shared invalidation and literal naming
  extracted from the mixed legacy file, with one membership/naming policy.
- `domain/discovery/TopicPublication.kt`: sole-engine publication and deduplication.
- `application/TopicCoordinator.kt`: source lifecycle, migration integration,
  one formation path, durable connection progress and atomic publication.
- `data/local/FileTopicStore.kt`: versioned atomic persistence, corruption and
  old-wire migration; it never reads or changes saved-interest/session stores.
- Composition root/controller/Gradle: unconditional sole engine.
- Tests: migrate shared properties to sole-engine fixtures; delete tests proving
  only removed mechanics and replace coverage with explicit upgrade, late-topic,
  large-count, independent-naming and resume contracts.
- Tooling/docs: shipping benchmarks reference the sole implementation; historical
  comparison results may remain labeled history, without executable legacy code.

## Acceptance and evidence discipline

Functional scenarios: first install; legacy upgrade; hierarchical checkpoint
upgrade; interrupted migration; corrupt/truncated checkpoint; storage refusal;
deletion/eviction; split/merge; same Notes supporting distinct topics; 30–40
meaningful subjects; late connections; independent references; pause/resume;
explicit interests and playback isolation; accessible navigation.

Android: at least 10,000 passages, more than 2,000 uneven sections, diverse
subjects and long multi-topic sections; active topic processing at most 120 s;
additional Java/native peak at most 128 MiB over model-loaded idle; cancellation
within 1 s; complete durable resume; combined derived storage at most 64 MiB;
warm semantic-search p95 below 2 s; no attributable playback failure/mutation.
Investigate the prior approximately 189 MiB result rather than relabeling it.
Report workload construction, device/API, native/Java peaks and repeated outcomes.

Quality: preserve at least 80% subject recall, at least 90% membership precision,
at most 10% false publication and at least 80% useful source-supported labeling.
Measure overlap, emerging/narrow recall, duplicates, connection usefulness,
explicit-reference precision/recall and identity stability. Independently reviewed
held-out judgments are required; generated vectors, test counts and provisional
annotations cannot establish these targets. Report unmeasured gates explicitly.

## Execution

The user explicitly requested implementation after planning. Execute inline using
the implementation plan, with failing-first regressions and project verification.
Do not revert the uncommitted service/correctness round or create unrelated
commits. Completion reporting separates architecture, functional tests, quality,
Android performance and remaining production risks.


## Implemented processing state

Engine version 4 records authored section count separately from its complete
passage-direction opportunity count. Membership centroids are persisted separately
from representative passage embeddings. Continuity requires content-sensitive
opaque evidence fingerprints, preserving surviving sources on partial deletion.
Semantic connections persist both the Note and passage cursor (256 opportunities
per quantum). Formation uses 250,000 comparison units with cooperative checks;
these quanta limit interruption loss, not library coverage or topic count.
