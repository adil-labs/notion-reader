# Phase 1 native structural knowledge

This implementation companion is subordinate to the seven governing contracts.
It adds factual data and a query port; it adds no screen, inference, topic catalog,
Notion write operation or new playback command. See the [audit](phase1-structural-audit.md)
and [API evidence](phase1-api-evidence.md).

## Records and identity

`domain/structure` contains versioned page records, typed containers, ordered blocks,
sections, source anchors and explicit relationships. Canonical Notion UUIDs own
identity. Page movement, title edits and sibling reordering do not replace IDs.
Sections use heading block IDs or the existing page intro key. Reader chunk IDs
are supplied only for blocks that the shipping reader actually speaks; a source
block anchor remains available for structural-only content.

Page metadata keeps typed page/block/database/data-source/workspace parents,
creation/edit times, source revision, accessibility and traversal completeness.
Containers are stored separately from pages. Data-source ownership is distinct
from database membership; a database mention or child-database link is never
converted into membership. Unknown parent types and unsupported block types
remain observable as their native type. Original synced blocks may contribute
native children; duplicate synced blocks retain a reference and do not duplicate
speech or recursively expand another page.

Page containment edges point from parent page to child page. Their provenance
identifies the observing child metadata or child-page block. Block containment
edges point from child block to parent block/page. Other edges point from the
observing page/block to their explicitly named target. Queries aggregate edges
observed in a page record and preserve occurrence/provenance rather than treating
an edge as a semantic similarity or learning prerequisite.

Every edge retains its owning source page, optional block/property identity,
source identity, revision and extraction time. The codec also preserves optional
text ranges. URL classification requires a recognized Notion host or relative
reference; an external URL containing a UUID remains external. Unresolved,
inaccessible, malformed and external references have separate resolution states.

## Persistence and recovery

`FileStructuralKnowledgeStore` uses atomic per-page/container JSON replacement in
an application no-backup directory. Schema version 1 is separate from reader,
semantic and user-owned state. No legacy semantic record or topic ID migrates.
Unknown/corrupt source-state versions fail closed. Unknown/corrupt individual
page payloads are excluded; indexes can be rebuilt from eligible records.

An atomic content-free source-state file owns eligibility, epoch, opaque removal
markers and pending page identities. It is the authority; a disposable index is
not. Switching credentials first changes source identity and minimum eligible
epoch, then unlinks old files. Returning to an earlier credential cannot admit an
orphaned old payload. Cache invalidation similarly changes eligibility before
unlinking. A whole-cache clear advances the minimum epoch. An in-flight fetch
must match the exact identity and epoch captured before its request to publish.

Complete page snapshots replace their own page only. Factual equality ignores
observation timestamps and detects parent moves, block ordering and same-count
relationship changes. Partial collection preserves an existing snapshot and
queues retry. A first partial snapshot is usable with an incomplete status and
remains durably pending. No absent search result, incomplete traversal, network
error or ambiguous 404 is proof of confirmed deletion. Only observed trash is
recorded as confirmed trash; Notion 404 remains missing-or-unshared.

Durable work stores page identities, not opaque API cursors. Cancellation leaves
unfinished work available after recreation; recovery restarts traversal. Failed
items rotate behind other queued work. Permission/authentication failures do not
publish private labels from stale responses. Reader positions, aliases, interests,
queues, topic IDs and embeddings are outside this store's ownership.

## Resource bounds

Default storage is capped at 16 MiB, 200 page files and 200 container files;
individual page payloads are at most 4 MiB, containers 64 KiB, state 1 MiB and
pending work 1,000 identities. The extractor caps depth at 10 and examined blocks
at 5,000 (including duplicates). Target metadata resolution has a 25-request
budget per page. API pagination caps at 500 requests and rejects missing or
repeating continuation cursors. A limit is incomplete/failed work, not evidence
of absence. Writes use the shared NOTES admission gate including staging space;
safety invalidation remains possible when download admission is refused.

These bounds establish explicit stopping points, not Android memory/performance
acceptance. No representative Android scale, memory or battery claim follows
from JVM tests. Structural collection does not run a generative model.

## Query port

`StructuralKnowledgeRepository` exposes page/container metadata, page ancestors
and direct children, ordered blocks, sections, incoming/outgoing and unresolved
relationships, section/block anchors and synchronization status. Container
parents can connect a data-source member to its containing page without calling
the container a page. A child-page reference can return an explicitly UNFETCHED
metadata placeholder until its own metadata is collected. Queries do not expose
file names or JSON codecs to UI code.

## Integration and verification status

Phase 1 structural knowledge is implemented, integrated with the process composition root and verified.
All 1,353 JVM tests pass; debug APK assembly and Android test Kotlin compilation succeed. Targeted instrumented
pipeline testing (`StructuralPipelineIntegrationTest`) passes via the repository accessibility wrapper.
Failing-first regression proofs (`tools/proof-structural-store.ps1` and `tools/proof-structural-isolation.ps1`)
verify that the storage eligibility/epoch guards and the reader isolation boundaries detect regressions.
The completed before/after matrix and capability evidence are recorded in the [audit](phase1-structural-audit.md)
and in `docs/decisions.md` §134.
