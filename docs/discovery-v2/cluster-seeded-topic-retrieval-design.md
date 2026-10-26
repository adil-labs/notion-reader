# Cluster-seeded topics with semantic Note retrieval

Approved for implementation by the user, 2026-10-26. Incorporates the user's cluster → LLM name → embedding matches proposal and explicit permission for Notes to remain unmatched. Product/UI/Integration Notion sources now contain the approved amendment; export verification precedes production edits. The earlier fixed-group design describes the pre-migration code. Implementation and acceptance are tracked separately.

## Intended behavior

Use existing grouping to discover seed evidence. Local Gemma proposes a concise useful name and a short definition supported by that evidence. Embed the name plus definition and retrieve supporting passages across every eligible indexed Note. A topic shows Notes with meaningful matching passages, not every Note in its seed cluster. It may gain relevant Notes outside that cluster, exclude original seed Notes, overlap other topics or leave Notes unmatched. Do not force coverage, fill a quota or pad results to a requested count. A mixed seed may fail to yield a coherent topic; absence of a useful proposal is an explicit result, never an invented catch-all name.

Keep one canonical catalog, one process-owned coordinator and one serialized native gate. Original cluster wording remains untrusted evidence only, never a displayed/default/fallback name. Explicit aliases remain authoritative presentation but do not silently rewrite the generated retrieval query. Explicit user-authored interests retain their existing separately governed semantics.

## Three distinct identities and evidence records

1. Seed identity and evidence: existing grouping continuity and current source revisions identify the origin. Preserve its body passages for explaining why the concept was proposed. A seed's membership is not an inclusion rule for the final matching-Notes list.
2. Concept identity: retain a canonical topic ID through genuine concept continuity. Persist generated name, definition, exact offered evidence, source fingerprint and complete generator/prompt/schema/policy identity. Rejected/failed/cancelled work is not cached as success. A changed name alone cannot establish a new concept, merge concepts or transfer follows. A materially changed meaning requires explicit continuity evaluation; matching strings are insufficient.
3. Retrieval generation: bind the concept query and embedding identity to a versioned matching policy and current eligible source generation. Persist source-bound matching passage references, scores and completion/cursor state separately from seed memberships. Withdraw stale matches immediately. Newly indexed content can update retrieval without rerunning the LLM when the seed concept is unchanged.

This separation extends the canonical topic model rather than creating a second generated-topic list. Old fixed-group records migrate as seed evidence; do not present those members as new retrieval matches before they have been evaluated. Preserve Notes, compatible vectors, IDs where continuity is genuine, explicit interests, aliases, follows, queues and listening state.

## Generation and retrieval

Extend the bounded local naming schema with a short evidence-grounded definition. A definition should express what makes a passage relevant, preserve negation and distinguish nearby subjects. Validate the closed schema, offered citation identities and source freshness. Deterministic validation does not prove semantic support; actual output needs source-based evaluation. Do not replace the LLM definition with a definition invented by the evaluator or infer it from a desired reference label.

Use the published embedding model and a versioned query construction policy compatible with the indexed space. Compare name-only retrieval as a diagnostic control; it is not assumed sufficient. Do not change embedding task instructions without verifying compatibility or re-embedding when required. Retrieve by source passage and aggregate matching Notes from their actual supporting passages. Similarity is a candidate relevance signal, not a probability or proof. Calibrate a conservative inclusion policy against reviewed positives and hard negatives; allow sparse/empty results. A paged UI can bound a page, but an arbitrary first-twenty search result is not complete topic coverage. Record unfinished scans as unfinished.

Finish a bounded naming batch, close the generator, then embed pending concept queries and retrieve with the same coordinator. Avoid an embedding/generation engine swap for every topic. Foreground search and playback retain priority. Yield and persist valid boundaries on cancellation, source/reset changes, pressure or background restrictions; native termination still precedes engine replacement. Opening a topic reads persisted data and never starts its own native work.

## Presentation and lifecycle

Show the current generated name or explicit alias; unnamed/pending/unavailable/failed states remain neutral. Distinguish seed evidence used to propose the concept from passages supporting the currently matched Notes. Do not count seed citations as automatically verified retrieval memberships. Keep inspected order/focus under existing Refresh semantics while withdrawing unavailable source evidence immediately. A valid saved concept can remain visible with an explicit no-current-matches state; do not silently refill it from seed members.

Cache query embeddings by concept-query and embedding identity. Recompute matching results for changed/removed/new source records without regenerating unaffected concepts. Reset clears derived concept/retrieval state while preserving the existing user-data contract. Optional model removal retains only compatible saved concepts/results and stops new generation; explicit acquisition, checksum, storage inventory and privacy boundaries remain intact.

## Evaluation and implementation sequence

First perform a bounded name-only retrieval diagnostic on the already-seen fresh-v1 corpus, using its actual generated labels and actual persisted embeddings. Retain every ranking and skipped/failed name. Report threshold sweeps as calibration, not fresh acceptance; do not silently choose only favourable subjects or replace generated names with reference labels.

Then amend owning Product, UI and Integration Notion specifications and regenerate/diff mirrors before production behavior changes. Extend the schema/domain/persistence with failing-first regressions; integrate concept-query work into the existing worker; migrate the catalog and UI evidence distinctions; verify native scheduling and destructive lifecycle cases.

Evaluate the implemented name-plus-definition pipeline on new family-separated inputs after freezing code/prompt/policy. Preserve the existing numerical semantic sample sizes and subject-recall, membership, false-publication and useful-label gates unless explicitly amended. The permission to leave Notes unmatched does not erase missed-subject measurement or license low precision. Count generated failures, empty results and false memberships. Agent fixture authorship/review remains distinct from independent human quality/TalkBack observation. Complete representative S25 scale, active-time, latency, native allocation/whole-process pressure, playback/background/cancellation and migration tests before activation.

The fixed-group fresh-v1 run failed membership precision at 57/64 (89.0625%). This motivates an experiment; it does not prove query-driven retrieval will fix the failure. Production activation remains disabled.
