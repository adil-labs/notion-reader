# Discovery subject hierarchy and connection catalog — proposal

Date: 2026-10-17. Status: user-approved design; implementation in progress.
This is subordinate to the live governing specifications and their generated
mirrors. Execution is tracked in the 2026-10-17 subject-hierarchy implementation plan.

## Intended outcome and scope

Keep the shipping MiniLM embedding pipeline. Discover meaningful specific subjects,
organize them under evidence-supported major subjects, explain each Note membership
with its strongest substantive passage and section, and expose strong relationships
across the library independently of topic publication. Retain overlapping membership,
Emerging subjects, unnamed internal candidates and ungrouped Notes.

The requested evaluation sequence isolates formation changes using identical
MiniLM vectors, then compares embedding challengers on the selected pipeline.
No production model replacement, auxiliary LLM or reranker is included.

## Findings from current sources

The Product contract's Automatic topics and Unified Discovery stages sections
already govern broad/focused subjects, independent naming, passage membership,
resumable work and one production engine. They do not establish a navigable
parent–child subject graph or a complete connection catalog.

`DiscoveredTopic` has no parent relationships. `TopicMembership` already stores
page identity, passage, score and revision; its passage carries section provenance.
Extend that evidence model only where the hierarchy requires it rather than
duplicating existing fields.

`TopicCoordinator.connections` rejects some pairs through `crosses`, retains five
matches per seed passage, and prunes accumulated pairs using
`selectDiscoveryConnections`. That selector defaults to twenty connections and
two uses per Note. Selection also occurs before snapshot persistence. Consequently,
changing only the homepage display limit cannot recover a complete catalog.

Decision record §76 reports provisional held-out membership precision of 11/13,
not independently reviewed acceptance. Earlier synthetic/device functional results
do not establish new hierarchy quality or physical-device performance.

## Approaches considered

1. **Recommended: extend the existing sole engine in stages.** Separate persisted
   subject relationships and connection evidence from their UI projections. Reuse
   formation, embeddings, identity and source-removal infrastructure. This isolates
   hierarchy/membership improvements from model and clustering changes.
2. **Replace formation with a neighborhood graph immediately.** Potentially useful
   for narrow subject recall, but changes the baseline before evaluation and adds
   Android implementation/resource risk. Restrict HNSW/Leiden to evaluation first.
3. **Build a display-only hierarchy over existing topics.** Smaller change, but
   similarity alone does not establish semantic subsumption and cannot fix lost
   connection evidence. This does not satisfy the requested outcome.

## Proposed product semantics

A major subject is itself an evidence-backed subject, with an independently
supported source label. Persist parent–child edges separately from subject identity.
Allow multiple parents and variable depth; reject self-links and cycles. No Note
is forced into a parent, and no arbitrary count or fixed depth controls discovery.
Related-subject links are distinct from parent–child links and Note connections.

Parent publication requires substantive source evidence supporting the broader
subject and the proposed child relationship. A centroid similarity, shared Note,
common title prefix or author heading alone cannot prove subsumption. Uncertain
relationships remain unpublished, with specific topics accessible independently.
This proposal deliberately does not assign an uncalibrated numeric parent score
the meaning of semantic proof. Before implementation, the governing amendment must
settle the admissible evidence rule with positive and counterexample fixtures;
unsupported automatic hierarchy is a blocking semantic gap.

For each subject, evaluate eligible body passages across the library. Persist one
best supporting passage per canonical Note–subject membership, its current source
revision and section provenance, with deterministic tie-breaking. Bounded section
context may clarify meaning but must not turn unrelated body text into evidence.
Parent membership is independently validated, never inherited automatically from
a child's membership. Independent narrow themes must survive consolidation.

Retrieve Note connections without requiring different or published topic sets.
Keep Named references and Similar passages distinct under their current evidence
rules. Preserve the strongest validated paired evidence for each canonical Note
pair and evidence kind, so an explicit reference cannot erase a useful semantic
pair or vice versa. Subject differences may support browsing/ranking but do not
establish the relationship or determine candidate admission.

The catalog contains all validated relationships from completed eligible coverage,
within the existing derived-storage budget. Resource limits pause work and report
incomplete coverage; they never silently discard valid relationships and claim
completion. The Discover preview remains a small deterministic, diverse projection.
It does not constrain catalog persistence. A complete catalog does not imply an
unbounded in-memory list or an all-pairs matrix.

## Architecture, storage and navigation

Keep pure evidence rules and relationship models in domain; resumable coordination
in application; private indexed persistence in data; state rendering in UI. The
existing application-bound service owns the single bulk worker, off-main, with
query priority and playback/background/resource pauses.

Use a separate pageable connection candidate/catalog store rather than expanding
the monolithic topic snapshot indefinitely. Version work state against current
source, model, policy and reset identities. Publish a consistent generation manifest
only after referenced topic/edge/catalog data is durable. Failed writes preserve
the previous valid generation. Migration rebuilds previously pruned connections;
old cursors cannot establish complete coverage for the new retrieval policy.

Invalidate changed evidence before reuse. Eviction/access loss removes affected
labels, memberships, edges and excerpts from stored and inspected views; preserve
only continuity explicitly allowed by the current privacy contract. Include the
new store and checkpoints in the existing combined 64 MiB derived-data budget and
index/model/full-reset paths. Preserve interests and frozen listening state.

Proposed navigation is Discover → Subjects → subject details → child subject or
Note; Discover → All connections → filtered/pageable connection list → existing
comparison details. Specific subjects remain directly discoverable. Define stable
Back/focus restoration, reading order, loading/empty/unavailable states, inspected
generation Refresh and immediate source removals in the UI contract before code.
Only explicit Play may create a listening session.

## Governing amendment boundary

Amend live Notion Product first for subject/edge evidence, graph semantics,
independent connection admission and catalog completeness. Review UI for the new
routes, accessible evidence and focus, and Integration for versioned edge/catalog
storage, atomic generation publication, migration and invalidation. Regenerate all
mirrors with `tools/export-notion-doc.ps1 -All` and inspect their diffs. This
hand-authored proposal cannot amend those sources. Do not introduce Notion tables
that the exporter cannot render or partially overwrite a domain-model code block.

The cross-subject higher evidence standard must be calibrated separately from
the current similarity threshold, with explicit evidence-kind denominators. The
proposed amendment must define acceptance for hierarchy and connection usefulness;
existing membership and retrieval thresholds do not measure these new properties.

## Evaluation and verification deliverables

Build a reproducible developer corpus of 500 public, consented or purpose-built
Notes with source provenance, revision manifest and family-separated calibration
and held-out partitions frozen before tuning. Do not require private-library
submission. Preserve human, provisional and unrated judgments separately.

Evaluate missed broad/narrow subjects, overlap, single-Note subjects, duplicate
topics, heading contamination, membership precision, supported label usefulness,
parent subsumption and cycles, related-subject links, and connection usefulness
by evidence kind. Report actual denominators, false positives and false negatives.
Automated fixtures establish correctness properties, not human semantic quality.

Compare exact current formation with HNSW/Leiden on identical pinned MiniLM
vectors and identical partitions; report neighborhood approximation misses as well
as clustering quality and resources. Run BGE-small-en-v1.5 and the attachment's
Granite Small English R2 candidate only after verifying their current official
artifacts, licensing, tokenizer and Android feasibility. Do not treat model claims
in the attachment as verified specifications. Benchmark models on the selected
pipeline, not on different algorithms or independently tuned held-out sets.

Use failing-first regression proofs for strongest-passage membership, heading-only
false evidence, retained narrow subjects, hierarchy invalidation/cycles, same-topic
connections, ungrouped connections, catalogs larger than preview limits,
interrupted resume, storage refusal, stale generations and reset/session isolation.
Test shipping stores/selectors, not retyped evaluator implementations.

Run the repository JVM suite and debug assembly; explicitly compile androidTest
when changed. Use targeted accessibility classes through the repository wrapper
for new navigation/focus and a real device probe for the user-visible flows.
Measure the existing 10,000-passage, >2,000 uneven-section Android gates, combined
storage, cancellation/resume and playback coexistence. Human TalkBack and
independent quality gates stay pending until actually observed.

## Review and execution status

The user approved this proposal and explicitly requested implementation. The live
Product amendment selects conservative literal body statements of subtopic/type/
kind/part relationships as initial parent evidence; headings and centroid similarity
alone do not qualify. Product, UI and Integration mirrors were regenerated after
amendment. The written implementation plan tracks execution and remaining gates;
approval and this document are not evidence that every feature or gate is complete.
