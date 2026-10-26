# System architecture — local-first structured knowledge

Status: target logical design, not a commitment to a graph database or new background service.

## Flow
```text
Notion API (authoritative permitted pages/blocks/properties/relations)
   -> authenticated sync and eligibility checks
   -> local source cache (Page, Block, Section, explicit edges, revisions)
   -> full-text index + passage embeddings
   -> canonical Discovery topic catalog + derived relationship edges
   -> shared knowledge query service
   -> Browse | Explore | Search | Read | Listen
```

## Boundaries
1. **Notion adapter:** fetches accessible pages, blocks and relation metadata; owns external pagination/rate-limit strategy, not conceptual classification.
2. **Source repository:** records canonical source IDs, source revisions, block hierarchy, sections, explicit edges, tombstones and current eligibility. Offline UI reads this first.
3. **Evidence/index repository:** supplies passage anchors, lexical search, embedding/model provenance and derived invalidation.
4. **Discovery:** existing section-aware seed formation, local Gemma-generated concept label/definition, independent membership retrieval; one canonical catalog.
5. **Knowledge relationships:** typed explicit and inferred edges, each with provenance and source references. Inferred edges are suggestions, not Notion facts.
6. **Query service:** unifies cached hierarchy navigation, hybrid retrieval, topic memberships and contextual results; never triggers generation while opening a Note.
7. **Reader & player:** Compose/TalkBack, queues and TTS, independent from model availability.

## Ownership and consistency
Only Notion source sync updates representations of Notion-authored facts. User-owned state is stored independently and retained through derived-index resets. Model outputs cannot mutate source structure. Derived publications use versioned evidence and atomic generations; withdrawals invalidate all visible/pinned/offline paths. Rebuild derived indexes without deleting authoritative cached Notes or reading/listening state.

## Scheduling
One process-owned coordinator and serialized native inference ownership, reusing current DiscoveryRuntime and foreground priority. Immediate reads/player > interactive query > eligibility withdrawal & indexing > opportunistic formation/reconciliation. Batch, checkpoint and yield with cancellation and memory/power gates. Do not add a competing inference worker.

## Failures
Network/offline: show cached data with freshness indication. Lost permission/deletion: withdraw related evidence; avoid stale links. Model unavailable: show valid saved concepts if allowed by existing contract; do not invent labels or hidden fallback topic formation. Storage denied: preserve last valid published generation and disclose incomplete work. Partial retrieval is explicitly pending, never falsely complete.

## Security and privacy
Keep credentials and fetched user content within existing integration boundaries; no external LLM upload. Check authorization/eligibility before indexing, retrieval, result display and playback. Avoid sensitive text in diagnostic logs.

## Open decisions
Whether hierarchy is fully captured today; precise local storage migration; section-anchor compatibility; quantitative scheduler budgets; whether/when partial retrieval may be displayed. Inventory and approval required before implementation.
