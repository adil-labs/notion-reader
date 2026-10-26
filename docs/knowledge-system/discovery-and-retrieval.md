# Semantic Discovery and incremental concept retrieval

Status: architectural integration proposal subordinate to current approved [generative direction](../discovery-v2/generative-direction.md) and [cluster-seeded retrieval design](../discovery-v2/cluster-seeded-topic-retrieval-design.md).

## Production concept flow
Eligible body passages -> current section-aware clustering (seeds only) -> Gemma 4 E2B local label and grounded definition -> strict structure/provenance checks -> semantic quality assessment -> embedding of name + definition -> passage-level retrieval over eligible Notes -> one canonical topic catalog. Seed membership is not final retrieved membership; out-of-seed inclusion and no-match are permitted.

## Admission rules
Reject stale/withdrawn evidence, invalid references, malformed bounded schema, generic wording without substantive conceptual meaning, unsupported relationships and duplicates. Deterministic checks do **not** prove semantic entailment. Assess quality on reviewed material, including false positives and missed concepts. Avoid treating arbitrary weights (e.g., 25/25/20/15/10/5) as validated release rules.

## Incrementality
Changes to a Note trigger retrieval against existing concepts first. Unmatched evidence enters a durable bounded formation pool. Changed source revisions remove old matches and rescan affected passages; unchanged topic definitions and query embeddings are reused. Refresh dormant topics fairly; maintain a lower-priority reconciliation pass to prevent permanent misses. Ensure full eligible-source coverage eventually, even when UI shows a partial bounded page.

## Execution, persistence and cost
Reuse current process-owned inference gate and model packaging; generate in batches, release engine before query embeddings, prioritize foreground search/player. Persist work cursor, attempted sources, completion and failure state atomically. Limit scan preparatory allocations as well as record reads. Add per-concept failure backoff/retry budget, reset on material input/model change, and explicit retry affordance; never infinite semantic repair loops. Memory admission and Android device-pressure checks follow currently approved project gates, not guessed vendor guarantees.

## Results
Every topic has evidence of *proposal* distinct from evidence of *current membership*. Expose retrieval incompleteness, unavailable model, no matches and storage failure separately. Similarity is ranking evidence rather than an accuracy probability. Source deletion withdraws references in all persisted/pinned paths. A model cannot rewrite user aliases, follows, queues or physical Note location.

## Verification
Same-source, same-embedding old-versus-new quality evaluation; frozen family-separated holdout; missed-topic, generic heading, false publication, duplicate, membership precision and recall rates; S25 memory, power, cancellation, player contention and TalkBack. Reference numeric gates in existing [evaluation spec](../discovery-v2/evaluation-acceptance.md); do not claim they passed absent current evidence.
