# Discovery v2 — incremental processing, recovery and bounded costs

> **Current direction:** The local Gemma 4 E2B / LiteRT-LM replacement and requirement dispositions are defined in [generative-direction.md](generative-direction.md) under the amended governing Notion contracts. Deterministic-only, literal-label-only and activity-ranking passages in this original proposal are superseded where they conflict. This file preserves the original proposal for comparison; it is not the execution authority.

**Status:** proposal. The existing single-engine app, cache and scheduler remain shipping until a separately approved migration. This document covers R25–R35 and R40.

## 1. Trigger and priority contract

| Trigger | Allowed effect | Forbidden shortcut |
|---|---|---|
| Cached Note added | Index new body passages, match against topics, search older evidence | Do not infer uncached workspace content |
| Cached Note edited | Reuse unchanged inputs, invalidate changed memberships, rescore affected topics | Do not retain stale excerpts/IDs |
| Cache eviction, deletion or permission loss | Immediately withdraw derived evidence, memberships, labels, candidates, graph links and previews | Do not wait for scheduled completion to hide revoked data |
| Embedding identity/policy change | Invalidate/rebuild incompatible derived model-dependent state | Do not combine incompatible vectors |
| Recently opened unchanged Note | Candidate **ranking priority only if contract permits**; no embedding by default | Do not alter topic correctness or require profiling |
| Followed interest edited | Re-rank displayed eligible topics, rerun explicit interest matching | Do not auto-create a topic from a follow text |
| Idle/charging reconciliation | Check missed historical relationships and proof/coverage gaps | Do not claim scheduled timing is guaranteed |

**Priority:** urgent source withdrawal > interactive search/playback > new/changed source indexing > affected topic updates > pending unmatched pool > historical reconciliation. Fairness prevents perpetual starvation of older candidates; event coalescing and generational IDs prevent repeating identical work.

## 2. Durable work stages

1. Capture stable cache revision, source inventory/version, embedding identity, scoring policy, corpus/lexicon version and last accepted topic generation.
2. Compute source delta; use semanticRevision and exact-input hashes. Retain only embeddings with compatible input/model identity; source offsets and revision are rebound to current passages. Increment counters for reused and generated embeddings.
3. Run body-evidence eligibility, structural-heading detection and feature extraction. Avoid reembedding unchanged content when optional body-only vectors already exist.
4. Retrieve top-k existing topics from local approximate or exact index. Query can be bounded but **acceptance scoring is exact on shortlisted evidence**. Validate top-k recall on held-out fixtures; fallback on uncertain evidence.
5. Project each changed passage to each viable matched topic, choose strongest valid per-Note membership and update **all affected topics**. Do not limit to one topic/Note.
6. Put unassigned evidence into a bounded unmatched pool; retrieve nearest current/older indexed body passages, including ungrouped Notes, without requiring authored metadata agreement. Form local neighborhoods and score candidates.
7. Apply hard gates, quality scores and duplicate/subtopic checks. Persist a new generation atomically; annotate coverage and incomplete work.
8. On resumption, validate sources/models/policies/checkpoints; continue from saved cursor or restart affected slice without displaying stale output.
9. In permitted low-priority windows, reconcile candidates against the larger full eligible corpus to discover old-to-old links missed by limited top-k or earlier boundaries.

## 3. Candidate retrieval and completeness

A hard top-k can miss rare concepts, especially in multilingual or mixed-domain Notes. Therefore use **recall monitoring**, not a false claim of complete coverage:
- Exact small-corpus oracle in JVM/host tests; record Recall@k and false-negative causes on gold labels.
- Adaptive expansion for ambiguous cases (small margin to cut-off, low-quality label, poor body agreement, candidate entropy), within documented work budget.
- Unmatched candidate pool with stable IDs, source hashes and expiry/reconsideration conditions, never an unbounded duplicate list.
- Periodic reconciliation over indexed evidence, optionally sharded/resumable; source/model changes invalidate stale cursors.
- If a budget prevents complete retrieval, expose an **incomplete coverage** state and continue on a later eligible window rather than silently suppressing topics.

An exact global clustering oracle may remain for offline evaluation only. No second production classifier or LLM is added.

## 4. Resource budgets and scheduling

The repository currently has a 250,000 work-quantum default for section formation, a bounded 256-target Note connection sweep, and WorkManager constraints. These are **baseline implementation facts**, not approved v2 budgets.

Define a device-calibrated WorkBudget struct covering: elapsed CPU/window time, maximum comparisons, passage embeddings, bytes of in-memory candidate records, persisted bytes, concurrent inference sessions, and permitted battery/thermal state. Hard constraints:
- Work is off the main thread, cooperative with cancellation, prioritized below active TTS, queries, Note navigation and media-control callbacks.
- Pause for power policy (existing >=30% battery or charging, or Charging-only mode), storage exhaustion, resource pressure, user Pause, generation mismatch and foreground contention; make no fixed wake timing promise.
- Maintain one process-owned serialized work gate. WorkManager requests grant windows; they do not create an independent second pipeline. Periodic recovery should reuse existing scheduling rather than start an overlapping job.
- A bounded work slice must checkpoint a cursor and accounting totals, not drop the work already performed when yielding.
- Never silently truncate the eligible corpus, suppress older evidence by age or discard an over-budget candidate as “invalid.”
- Preserve the player and TTS experience; fail performance acceptance on measurable listening disturbance.
- Bound loaded snapshot/catalog memory and page large catalogs instead of materializing an unlimited number of candidate-to-passage records.

**Numeric budgets are intentionally TBD** until S25/other representative Android profiling. Avoid invented “under 200 ms” or “under 50 MB” guarantees. Record cold/warm data, p50/p95/p99, battery/thermal conditions and corpus size.

## 5. Complexity expectations (to verify, not promise)

Indexing only changed inputs reduces model calls toward O(changed passages). Updating M changed passages against a local topic index is expected to resemble O(M × retrieval cost + shortlist exact scoring); however worst-case fallback/reconciliation can still depend on all eligible evidence. Additional body-only embedding fallback and labeling/c-TF-IDF work must be counted. Do not call this universally sublinear without measurements and bounded-accuracy evidence.

## 6. Persistent state machine

States: WAITING_MODEL, WAITING_POWER, WAITING_STORAGE, PAUSED_USER, INDEXING, MATCHING_EXISTING, FORMING_CANDIDATES, SCORING, RECONCILING, INCOMPLETE, READY and RECOVERY_NEEDED. These are proposed internal states; presentation may map them to a smaller set.

Transitions are idempotent for the same source/generation/policy key. A source revocation cancels or invalidates affected work synchronously with visible-source eligibility, even if persistence later fails. Separate *progress incomplete* from *no useful topics found*.

Resumption keys include model identity, policy version, source content fingerprints, corpus statistics version for DF/TF-IDF, candidate and topic generation, and cursor/checksum. Never reuse a saved scoring vector after calibration or policy changes without revalidation.

## 7. Negative and positive tests

- Add one Note to a 1k-Note fixture: unchanged inputs cause **zero** embedding calls; affected candidates only; outputs match incremental oracle within calibrated tolerance.
- Open but do not change a Note: no embeddings or membership changes; optional ranking update only after approved policy.
- Edit/delete/revoke while a worker is paused or writing: old source cannot appear in saved/preview/pending state.
- A new Note connects to a previously old ungrouped Note; works even with different section headings.
- Exact top-k oracle contains a meaningful topic missed by limited retrieval: fallback/reconciliation eventually recovers it, progress stays incomplete meanwhile.
- Battery dips, TTS begins, user pauses, app process dies or force-stops: correct priorities, checkpoints and realistic WorkManager behavior.
- Huge candidate pool / low storage: bounded memory and honest recoverable status, not truncated or invalid output.

See [state-migration](state-migration.md), [evaluation](evaluation-acceptance.md) and [implementation plan](implementation-plan.md).