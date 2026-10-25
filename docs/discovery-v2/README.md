# Discovery v2 — documentation index

**Status:** proposed, not implemented, not release-approved. **Date:** 2026-10-25. **Target:** Notion Reader Android, fully local. This directory records the user's new design direction and the 49-item requirements checklist. Numerical weights and thresholds are experimental defaults, not measured quality guarantees.

## Read in this order

1. [Requirements](requirements.md) — all 49 requested requirements, scope, acceptance intent and non-goals.
2. [Architecture](architecture.md) — end-to-end operation, responsibilities, evidence flow, topic lifecycle and user experience.
3. [Evidence, scoring and naming](evidence-scoring.md) — deterministic features, hard gates, generic-heading isolation, weights and abstention.
4. [Incremental processing and resource control](incremental-processing.md) — triggers, candidate retrieval, reuse, reconciliation and work budgets.
5. [State, migration and safety](state-migration.md) — versioned records, topic continuity, privacy, removal and atomic updates.
6. [Evaluation and acceptance](evaluation-acceptance.md) — annotated fixtures, quality metrics, regression, performance and TalkBack validation.
7. [Implementation plan](implementation-plan.md) — phases, dependency gates, risks, rollout and rollback.
8. [Traceability matrix](traceability.md) — every R01–R49 requirement mapped to a proposed implementation contract and verification.

## Decision summary

- Discover a **specific, source-supported concept**, not a repeated heading, broad document category or high cosine score by itself.
- Use **existing local passage embeddings** and deterministic analysis; no generative LLM, auxiliary classifier, reranker, cloud inference or Python runtime required for the Android product.
- Prioritize new/changed Notes; opening a Note may affect presentation priority, but **never confers topic validity** and must not trigger unnecessary embeddings.
- Match new evidence against existing topics first; build unmatched candidates with nearby older indexed evidence; periodically reconcile incomplete/missed relationships.
- Publish only after **hard source/body/label checks**, followed by calibrated **six-signal quality scoring**. Preserve one-Note Emerging topics and overlapping Note membership.
- Keep **topic quality** independent of activity-based **display relevance**. Older topics remain discoverable.
- Reuse existing query/playback priority, battery, storage, source invalidation, checkpoints and WorkManager orchestration.
- **Shadow evaluation precedes user-visible behavior changes.** No performance, semantic-accuracy or release gate is asserted passed.

## Documentation authority and conflicts

This directory is a **design proposal**, not an eighth governing contract. Consult [the documentation guide](../README.md), [Product & Architecture](../notion/product-and-architecture-contract.md), [UI](../notion/ui-screens-and-navigation-contract.md), [Integration](../notion/notion-integration-implementation-spec.md), and [the current implementation companion](../semantic-discovery-implementation-spec.md). The Notion-backed contract mirrors are generated and must not be hand-edited. Product semantic changes must first be approved and applied in the authoritative Notion pages, mirrors exported, and dependent specs reconciled before implementation. In particular:

- Existing contract language excludes behavioral profiling / use of reading activity for membership. The proposed optional, on-device, non-membership activity ranking requires explicit source-of-truth review/approval before activation. Default may remain activity-neutral.
- The production path currently uses a sole section-aware hierarchical engine. Replacing its formation algorithm requires a governing amendment; an independent production selector or legacy parallel engine must not be introduced without that change.
- Existing published relationships require affirmative source statements, not mere vector similarity. That safety restriction remains.
- Browse, active queues, playback, interests, and named-reference connections are separate feature contracts and must not be reinterpreted by the topic scorer.

Historical engineering evidence: [19 October architecture review](../discovery-architecture-review-2026-10-19.md), [single-engine design](../single-engine-discovery-design.md), [decision log](../decisions.md). These documents describe the current/historical system; this directory describes a *future proposal*.

## What is settled vs open

**Design choices to prototype:** deterministic scoring and audit reasons; generic-heading isolation; two-stage hard gates plus quality scoring; source-backed labels; incremental matched/unmatched work; bounded global reconciliation; versioned local state; evidence-safe invalidation.

**To validate:** six feature implementations; cosine calibration for each embedding model; normalization; initial weights 25/20/20/15/10/10; experimental publish/provisional thresholds 75/55; per-device budgets; top-k recall; human-rated usefulness and misleading-label error rates; behavior for languages beyond English; quality/ranking consent and retention rules; contract amendments.

**Do not claim:** that 49 requirements are already implemented; that 49/49 have been empirically verified; that any score is a probability of truth or conceptual understanding; or that reduced global work has already been measured.