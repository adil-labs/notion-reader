# Knowledge data model and provenance

Status: conceptual schema. Adapt to current file stores and IDs rather than replacing them unconditionally.

| Record | Required fields | Ownership |
|---|---|---|
| Page | notionPageId, parentType/id, title, dataSource/database, revision, eligibility | Notion-derived |
| Block | notionBlockId, pageId, parentBlockId, type, order, text/content hash | Notion-derived |
| Section | stable local section key, pageId, heading path, block span | deterministic derived |
| Passage | stable anchor, pageId, sectionKey, block references, text, revision, eligible | derived source evidence |
| Embedding | passage anchor, vector, model/tokenizer/preprocess identity | rebuildable derived |
| ExplicitEdge | source/target Notion IDs, kind, link target, observed revision | Notion-derived |
| Topic | canonical existing topic ID, seed evidence fingerprint, display alias, generated label/definition identity, status | Discovery + user alias |
| TopicMembership | topic ID, supporting passage ID/page ID/revision, score, retrieval-policy identity, complete flag | derived |
| InferredEdge | source passage, target passage/concept, kind, score, method version, supporting evidence, state | derived |
| UserState | note position, follows, aliases, queue items, listening progress | user-owned |

## Critical distinctions
- **Physical location**: parent-child/database membership, never inferred.
- **Explicit references**: hyperlinks, mentions, database relations; presence alone does not imply conceptual dependence.
- **Semantic topic membership**: content-grounded retrieval, not the original seed cluster's members.
- **Suggested relation**: inferred and uncertain; do not claim causal or prerequisite relationship from cosine similarity.

## Identity & lifecycle
Use Notion stable page/block IDs for Notion entities. Preserve existing canonical topic IDs when meaningful concept continuity is established. Content/section anchors carry revision and source offsets; support best-effort rebasing on edits, otherwise signal unavailable. Never silently remap a queue item to an unrelated page.

Every derived edge and membership must carry a current eligible source reference and policy/model identity. On removal, deletion, revoked permission or reset, remove/quarantine stale references from persisted, pending, pinned and displayed results before acknowledgement. Prevent late native inference results from republishing invalid evidence. Tombstones and revision keys are internal, never proof of continued access.

## Storage and migration
SQLite indexed relations/FTS is an option, **not yet mandated**; current stores and embeddings may remain if they satisfy query and atomicity needs. Migrate additively with versioned reads/writes, rollback-safe backups, bounded storage and no duplicate catalog. Preserve user-owned state and old cached Notes. Define fixture-driven compatibility tests before any actual schema migration.
