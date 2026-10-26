# Knowledge query service, hybrid ranking and related passages

Status: proposed interface contract; retrieval weighting is experimental.

## Query categories
- **Browse:** authoritative page/database/subpage/heading traversal with breadcrumbs and current eligibility.
- **Exact lookup:** page ID, link/mention, heading or user alias.
- **Lexical search:** titles and source body passages (e.g., SQLite FTS if compatible with current storage).
- **Semantic search:** embedding retrieval over eligible passages using existing model and preprocessing identity.
- **Topic view:** concept definition + source-backed memberships + coverage status.
- **Related knowledge:** explicit references first, then inferred passage similarity with brief *evidence-backed* explanation only when warranted.

## Merge/rank policy
Apply permission, freshness and revision gates *before* ranking. Deduplicate by canonical page/passage anchor, not display title. Prefer high-confidence exact and user-authored references for navigation; lexical/semantic signals form candidates, and calibrated relevance sorts eligible results. Keep explicit relationship type visible and never convert score to probability. Do not silently synthesize claims such as "prerequisite" or "causes" from proximity.

## Response contract
Every result provides title, source location, matched passage/section, match/relation type, and navigation target. Report no matches, partial indexing, incomplete retrieval and unavailable model distinctly. Result paging must not be presented as full corpus coverage. A preview must not include withdrawn/private evidence even when the prior snapshot was pinned.

## Efficiency and resilience
Opening a Note or displaying cached search results does not perform synchronous generation. Foreground semantic queries preempt background native naming, using existing shared scheduler. Cache results by query, source generation, retrieval/model policy; invalidate selectively. Bounded top-K display is distinct from background exhaustive matching.

## Evaluation
Test lookup task completion, time/effort, MRR/nDCG or relevance-at-3 where human-reviewed ranking labels exist, query-language diversity, explicit-reference navigation, empty results, duplicated pages, source withdrawal and stable accessibility. Tune weights on development data; freeze before held-out assessment.
