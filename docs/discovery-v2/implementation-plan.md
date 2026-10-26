# Discovery v2 — gated implementation and rollout plan

> **Current direction:** The local Gemma 4 E2B / LiteRT-LM replacement and requirement dispositions are defined in [generative-direction.md](generative-direction.md) under the amended governing Notion contracts. Deterministic-only, literal-label-only and activity-ranking passages in this original proposal are superseded where they conflict. This file preserves the original proposal for comparison; it is not the execution authority.

**Status:** proposed work plan. **This commit is documentation only; no production behavior or tests were changed.** References: [requirements](requirements.md), [architecture](architecture.md), [scoring](evidence-scoring.md), [incremental](incremental-processing.md), [state](state-migration.md), [evaluation](evaluation-acceptance.md).

## Mandatory phase 0 — source-of-truth approval and baseline

- Confirm semantics with Product/UI/Integration contract owners: definition of meaningful topic, source-backed labels, hard gates, custom name/follow preservation, incremental candidate lifecycle, storage cap, background work and optional user activity. Update authoritative **Notion** source before editing generated mirrors; export with the repository tool; check dependent specs for conflicts. If not approved, keep v2 experimental and do not ship behavior.
- Preserve one production engine and existing exact checkpoint/corpus validity invariants until explicitly superseded. Do **not** resurrect debug/release selector or permanently parallel engines.
- Freeze current baseline commit, provider identity, topic output, scoring thresholds, engine version, source revisions, all current pass/fail gates and recent Android measurements. Confirm single-engine code ownership in DiscoveryController, TopicCoordinator and the current domain engine.
- Record user-approved privacy and retention policy for optional activity ranking; if unresolved, disable the activity branch.
- Exit: governing-consistency review completed, test fixtures available, baseline reproducible. No semantic code change before exit.

## Phase 1 — measured evidence extraction

**Deliverables:** immutable body-evidence and generic-heading feature functions; normalized structural lexicon + per-corpus DF/IDF; exact/near-duplicate fingerprinting; reason-code registry; logging that does not include private text.

**Target code:** new pure domain helpers adjacent to DiscoveryEvidencePolicy, TopicEvidence and SemanticChunker. Preserve existing search inclusion and model provider APIs.

**Exit:** G01, G06–G09, G17 deterministic unit tests; no private source leakage; heading repetitions never count as independent support.

## Phase 2 — Concept Quality Scorer in shadow mode

**Deliverables:** versioned feature extraction (coherence, specificity, explanatory value, label support, independent support, distinctiveness), hard gates, normalized six-weight policy and per-topic score breakdown. Instrument unknown/abstain. No user-visible publishing change.

**Target code:** ConceptQualityScorer, ScoringPolicy, CandidateEvidenceSnapshot and evaluation harness. Reuse existing topic/candidate storage only in a separate, bounded shadow representation.

**Exit:** all feature invariants verified, multiple domain fixtures, initial human-judged calibration run; report failures rather than silently tune for a target score. Quantify additional inference from body-only disambiguation.

## Phase 3 — source-backed labels and quality-gated publication

**Deliverables:** name/phrase selection from actual supporting body text, generic title-family suppression, explicit publish/provisional/suppress reasons, duplicate/subtopic preservation and one-Note Emerging outcome.

**Target code:** TopicPublication, SubjectProjection, TopicEvidence, name-selection helpers; stable accessible output in DiscoveryTopic details.

**Exit:** G01–G11 + label quality and reference provenance evaluation pass proposed gate. In shadow until governing contract approved. No arbitrary topic count cap.

## Phase 4 — incremental matcher and bounded candidate pool

**Deliverables:** change tracking, compatible exact-input reuse, current-topic lookup, exact shortlisted evidence check, multi-topic membership, unmatched pooling, retrieval of older/unassigned evidence, adaptive top-k expansion, coverage counters and periodic reconciliation.

**Target code:** DiscoveryIndexing and process-owned TopicCoordinator orchestrating new domain matcher; retain existing bounded query priority and independent connections. Avoid a second schedulable engine.

**Exit:** G03/G10/G13–G16 with full-corpus oracle; work scales with changed input on representative edit scenario; no unmeasured recall losses; incomplete work explicitly represented.

## Phase 5 — state migration, deletion safety and topic continuity

**Deliverables:** versioned shadow/published candidate and scoring storage; atomic manifest writes; migration/rollback; source-eviction purge path for candidate pool, embeddings, checkpoints and UI; deterministic identity on split/merge; rename/follow continuity.

**Target code:** TopicSnapshot/TopicStore, FileTopicStore, TopicContinuity, TopicCoordinator, existing source validity manager; never change Active Queue or explicit interest storage unexpectedly.

**Exit:** G12, G18 and G20; power-loss/crash corruption matrix, revocation and late-callback tests. Zero stale-source publication.

## Phase 6 — relevance, hierarchy and accessible presentation

**Deliverables:** separately scored relevance layer (only with source-contract approval), older topic discovery, evidence-validated topic graph, explanation UI, TalkBack focus behavior, progress/coverage reports, automatic worker continuation.

**Target code:** DiscoveryPresentation, TopicScreens/Details, subject hierarchy, interest retrieval and approved activity source. Avoid promoting similarity-only parent-child edges.

**Exit:** G19, R36–R42, R47–R48 acceptance; device and TalkBack manual end-to-end journey; playback remains independent.

## Phase 7 — comparative release gate and rollout

- Replay old and new algorithms on the *same* versioned source snapshots/model identity and annotated gold set, including held-out domains.
- Measure all quality dimensions, missed topics, normalization/feature ablations, latency, RSS, disk, battery, real worker checkpoints, thermal conditions, TTS and TalkBack; set numeric acceptance limits after benchmark review.
- Roll out behind an approved safe migration/feature gate, not a permanent two-engine architecture. Shadow → opt-in debug trial → restricted staged release → normal production only after contract and evidence gates pass.
- On regression, disable v2 publication and restore source-valid prior behavior; do not resurrect a revoked source or mix incompatible models. Keep a documented storage downgrade path.
- Record result in docs/decisions.md only **when measured**, with commit/device/human evidence and failed-gate list; remove historical claims of readiness if falsified.

## Non-functional invariants throughout

No new generative model or cloud upload; no TTS/player/queue mutation; full local content privacy; no false complete coverage; no fixed background schedule promises; active search/read/listening preempts bulk work; stable Note and passage provenance; all app-visible feedback screen-reader navigable.

## Risk and mitigation register

| Risk | Mitigation / stop condition |
|---|---|
| Contextual embedding falsely correlates identical headings | Body-first evidence and bounded cached body-only verification; stop on G01 |
| English-only regex misses other languages | Unknown/abstain; language slice tests; no silent reject of all non-English text |
| Quality thresholds suppress genuine single-Note concepts | Calibrate Emerging separately; track recall and worst cases |
| TF-IDF mistakes common domain-specific heading for boilerplate | Require joint structural/body evidence; don't gate on DF alone |
| Fixed top-k omits rare but good topic | Adaptive expansion and periodic exact oracle comparisons |
| Activity ranking violates existing behavioral-profiling contract | Disabled pending explicit approved amendment/consent |
| New derived persistence retains revoked excerpts | Fail-closed source purge across pool, checkpoints, overlays, pinned views |
| Memory/CPU scales with library size | Per-device budgets, paged records, cooperative yields, representative benchmarks |
| Topic merge/split loses user follows/renames | Identity reservations, alias migrations, comprehensive fixtures |
| Documentation outruns implemented behavior | Mark as proposal, separate phase evidence and guard release gates |

## Definition of done

All Must requirements from R01–R49 have a mapped implementation and passing evidence; Should items have tracked disposition; current governing contracts have approved amendments/mirrors; human usefulness does not regress; resource and accessibility gates have real device evidence; no sensitive corpus was uploaded; implementation and decision record reflect actual results, not intentions.