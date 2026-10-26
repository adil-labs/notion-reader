# Notion ingestion, hierarchy and incremental synchronization

Status: proposed coverage/behavior, pending audit of current Notion adapter and cache.

## Inputs
Enumerate *authorized* pages, database/data-source entries, parent metadata, page properties and nested blocks. Traverse paginated block children and retain parent block IDs and ordered siblings. Extract section headings separately from body evidence; do not promote headings to concepts merely through repetition.

## Explicit references
Extract relation properties, page mentions, internal page links and hyperlinks as **different edge types**. Resolve permitted Notion targets by stable ID when available; keep unresolved external URLs distinct and never invent target IDs. Missing permissions, unsupported block types and partial pagination must be represented as incomplete rather than silently absent.

## Change protocol
1. Poll/sync with API rate-limit and backoff controls; detect new, edited, moved, deleted and inaccessible sources.
2. Compare current source/revision and local block/section fingerprints; do not rely on last-edited timestamps as a semantic diff.
3. Recompute affected hierarchy and explicit edges.
4. Regenerate changed passages and embeddings only where text/preprocessing identity changed.
5. Invalidate affected topic matches and inferred edges; re-evaluate existing concepts before proposing new ones.
6. Commit source eligibility and source-derived references consistently; preserve unrelated reading and playback state.
7. Perform occasional full reconciliation to detect missed/moved/withdrawn material; no routine full regeneration of unchanged embeddings.

## Safety invariants
Source withdrawal is immediate for all published, inspected, saved, pending and replayable results. Any stale asynchronous result must fail revision/epoch validity before publication. Network failure does not imply deletion; confirmed deletion or denied permission cannot keep visible evidence. Do not display unauthorized text from retained offline indexes.

## Acceptance cases
Nested child pages; database pages; section reorder; repeated headings; two pages with identical titles; relation without target access; deep pagination; rename/move without body edit; passage edit while playing; delete/permission loss during generation; interrupted sync; offline restart; duplicate webhook/poll observation. Tests assert source IDs, ordering, eligibility, stale withdrawal and absence of needless re-embedding.

## Gap audit
Map each case to actual current classes and tests before coding. Record API limitations as product-visible completeness states.
