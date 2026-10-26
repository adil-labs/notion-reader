# Implementation roadmap and decision gates

Status: proposed sequencing, not an implementation claim. The main-branch documentation suite does **not** change product behavior.

## Phase 0 — inventory and governance (blocker)
Identify concrete Notion adapter/cache capabilities; hierarchy and relation coverage; current stores, source identity, section anchors, UI routes, TTS contracts, migrations and test gaps. Record implemented/partial/absent/unknown with file/test evidence. Reconcile proposed changes with the **authoritative Notion product, UI and integration pages**, export generated mirrors and diff them. Log approvals in numbered decisions. Avoid contradictory standalone specifications.

## Phase 1 — stabilize current Discovery
Work: bound failed-naming retry/backoff; avoid per-topic full-inventory allocations; address partial retrieval UX; validate current-source withdrawal; calibrate semantic admissibility. Gate: device, semantic and accessibility evidence meet existing release gates, including no unjustified source claims. No parallel catalog or hidden fallback generator.

## Phase 2 — faithful Notion structure
Work: missing API pagination/nesting, parent/databases, block/section identity, link/mention/relation extraction, offline navigation, deterministic incremental sync. Gate: hierarchy and explicit-edge fixtures, permission/move/edit/offline tests; accessible Browse works without AI.

## Phase 3 — shared knowledge query layer
Work: unified query interfaces for source, explicit and inferred relationships; passage anchors, hybrid search, contextual related passages, deduplication and freshness. Gate: top-three relevance, passage-link validity, source withdrawal and bounded latency.

## Phase 4 — learning/listening
Work: contextual queues and section listening using existing player contracts, progress continuity and accessible related-knowledge actions. Gate: end-to-end TalkBack and background playback trials.

## Phase 5 — optional advanced assistance (separate approval)
Grounded synthesis, prerequisite hypotheses, learning plans and personal evolution views. No unreviewed inferred claims as facts.

## Changes policy
Small vertical slices with failing-first tests; versioned schema/index migration; explicit rollback of derived outputs; preserve user-owned aliases/follows/queues; production switches only after acceptance evidence. Preserve frozen test corpora. Never bypass Notion mirror export workflow.

## Initial work items
- Capability inventory table linked to code/tests.
- Authoritative contract amendments reviewed and exported.
- Incremental mutation/deletion fixtures.
- Retrieval/generation bounded-resource regressions.
- Typed explicit edge and direct-passage navigation proof of concept.
- Independent user-task and TalkBack evaluation.

## Definition of done
Every completed requirement includes code reference, test evidence and approval status. Claims of "implemented" and "verified" must be recorded separately.
