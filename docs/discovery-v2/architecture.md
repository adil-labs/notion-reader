# Discovery v2 — end-to-end architecture

> **Current direction:** The local Gemma 4 E2B / LiteRT-LM replacement and requirement dispositions are defined in [generative-direction.md](generative-direction.md) under the amended governing Notion contracts. Deterministic-only, literal-label-only and activity-ranking passages in this original proposal are superseded where they conflict. This file preserves the original proposal for comparison; it is not the execution authority.

**Status:** proposed target architecture, not shipping behavior. **Scope:** automatic Topics in your Notes, related subject graph, topic quality and discovery ranking. Existing semantic search, manual Browse, listening and independent named-reference connections keep their owning contracts. Consult [the index](README.md).

## Problem and target outcome

Present engine: cached Notes → section-aware passage embeddings → exact hierarchical clustering over eligible section opportunities → membership projection → source-heading/phrase naming → publication → independent connections. The 2026-10-19 review observed 50 indexed Notes with 4,218 passages and 750 sections; many millions of comparisons are plausible because unit of work is passage/section opportunity, not Note count. Existing bounded quanta, proof-based pruning and reuse reduce redundant work, not necessarily semantic usefulness.

Target: **relevant changes → reuse indexed body evidence → match existing concepts → form coherent unmatched concepts → mandatory evidence gates → weighted quality → stable publication → optional relevance ranking.**

~~~text
New/edited cached Note ─┐
Recently opened Note ───┼─> Activity dispatcher (opens change priority, not validity)
Periodic reconciliation ┘              |
                                       v
                           Revision & source check
                                       |
                 ┌─────────────────────┴──────────────────────┐
                 |                                            |
           Content changed                              No content change
                 |                                            |
         Chunk/embed only new                            Activity/ranking
          inputs; reuse cached                               update only
                 |
         Body-evidence eligibility
                 |
         Existing-topic ANN/top-k
          retrieval + exact checks
                 |
          ┌──────┴────────┐
          |               |
    Supported match    Unmatched/ambiguous
          |               |
     Recompute only   Retrieve nearby older
     affected topic   passage evidence; maintain
     memberships      bounded candidate pool
          └──────┬────────┘
                 v
        Source-backed concept candidate
                 |
        Hard gates + evidence quality
                 |
        Provisional / suppress / publish
                 |
         Topic continuity + subject graph
                 |
         Interest-aware presentation only
~~~

The arrow depicting opened Notes is a **proposed non-membership ranking input** and is subject to governing-contract approval. Until authorized, operate in content-change + explicit-interest mode.

## Definition of a meaningful concept

A candidate is a bounded, source-supported idea that can be *identified and differentiated* by evidence: e.g., a mechanism, principle, task/problem, trade-off, technique or substantive area. A reusable domain concept can be explained in a single Note. A heading-only container (Implementation, Overview, Related Notes) is not sufficient. A broad parent can be a real subject if separately supported; it must not collapse independently supported children.

This is an operational heuristic, not machine comprehension: when evidence is ambiguous, **abstain** rather than invent relevance or names.

## Stages and proposed ownership

| Stage | Responsibility | Proposed boundary / reused code |
|---|---|---|
| Trigger | Listen to cached-source revisions, additions, explicit interest and optional recent activity; prioritize, coalesce | DiscoveryIndexing / existing owner and WorkManager |
| Index | Keep source revision, page/section/offset identity, body text provenance, compatible embedding identity | SemanticChunker, SemanticIndexStore, exact-input reuse |
| Retrieve | Top-k current-topic candidates; older indexed supporting passages; do not limit recall to recent Notes or authored metadata | New local candidate retrieval facade; normalized vectors |
| Match | Evaluate passage-to-topic evidence independently; allow overlapping memberships and strong single-Note candidates | New incremental matcher; reuse TopicMembership semantics |
| Candidate | Group unmatched evidence conservatively; split unsupported broad themes; preserve pending work | Bounded CandidatePool and reconciliation |
| Score | Body-only coherence, specificity, explanatory value, label support, independent support, distinctiveness | ConceptQualityScorer; [scoring spec](evidence-scoring.md) |
| Publish | Hard gates, provenance, duplication checks, stable identities, Emerging/Established, provisional/suppressed internal records | Extend TopicPublication after shadow validation |
| Graph | Assert parent/related relationships only with independent affirmative source evidence; reject cycles and unsupported parents | Existing SubjectHierarchy, source-revision checks |
| Rank/UI | Source explanations; intrinsic quality separate from explicit interest and authorized activity; stable TalkBack navigation | Existing Discovery presentation and topic detail screens |
| Persist | Atomic generation, source invalidation, checkpoint, cleanup and rollback | Existing TopicCoordinator / TopicStore, [state contract](state-migration.md) |

Layer ownership must honor repository boundaries (domain cannot depend on application, data or UI). The existing single process-owned coordinator arbitrates scheduled and foreground work. WorkManager grants execution windows; it is not a separate engine.

## Matching vs formation

1. Changed eligible passages query **all existing valid topic representations** (bounded top-k), then exact-score candidates and validate specific body support. Do not use current-topic membership or an authored heading as proof.
2. If multiple genuine topics qualify, attach to each using independently supported passages. A Note supplies at most one strongest passage per topic for display/membership, but all evidence may inform quality after duplicate control.
3. If nothing confidently matches, retain the passage as unmatched; retrieve nearby older Notes from the complete eligible index; form provisional concepts only with body-grounded support.
4. Candidate generator may use clustering/graph neighborhoods on *bounded affected neighborhoods*. Full-library analysis is a low-priority reconciliation only, not the routine path per edit.
5. Avoid hard top-k false negatives by logging retrieval misses on gold fixtures and scanning a broader neighborhood on abstention/low coverage. An uncached Note is not silently inferred or fetched from the workspace.

No similarity value is itself a calibrated probability. Current engine thresholds (formation distance .35, membership .60, retention .59) are baseline implementation constants, **not automatically transferable** to a new candidate matcher or Gemma/MiniLM representation.

## Topic states, naming, identity and hierarchy

- Internal: **Unmatched** → **Provisional** → **Candidate accepted** or **Suppressed** (reversible on new evidence), with explicit reasons and version.
- Published: **Emerging** = valid topic with one canonical Note; **Established** = valid topic with at least two. Topic publication still requires hard gates and quality threshold, not an automatic promotion on Note count.
- Names: select specific source-supported headings or distinctive literal body phrases only; no generic heading promotion and no title-family label. No name → remain provisional. Display topic source passage and quality explanation without fabricating natural-language claims.
- Identity: persist source-backed IDs and continuity through stable revisions; deterministic conflict handling on splits/merges; protect user follows/renames against arbitrary identity reassignment.
- Hierarchy: separate subject edges (ParentChild / Related) from topic membership and from Note connections. The existing code recognizes explicit statements such as “X is a subtopic of Y”. Never infer an edge merely from cosine similarity or shared headings.

## Ranking without changing quality

Proposed intrinsic Q = 100 × sum(normalized weights × feature scores). Optional relevance R is separate: recent opening 45%, followed interests 35%, recent add/edit relevance 20% (illustrative and permission-dependent). An initial display score is 0.70 × Q + 0.30 × (100 × R). These are **presentation hypotheses**, not release behavior or authorization for covert activity profiling. If activity signals are forbidden or unavailable, use explicit interests and neutral/fixed ordering. Old published topics remain reachable.

## Failure and privacy invariants

- Delete, permission loss, revision mismatch, reset or embedding-model change invalidates stale derived evidence before it can be displayed; scrub references from pending pool, checkpoint, explanations and graph as appropriate.
- A paused or incomplete processing window must report incomplete coverage, not a complete empty result. Never expose partial candidate generations as validated publications.
- Foreground semantic search, reading, TTS and player lifecycle take priority over bulk computation; no playback or Active Queue mutation is permitted.
- Source texts and embeddings remain local. Persist minimal user activity only if governing contract and user controls permit it.

See [incremental processing](incremental-processing.md), [state and migration](state-migration.md), [evaluation](evaluation-acceptance.md), and [implementation plan](implementation-plan.md).