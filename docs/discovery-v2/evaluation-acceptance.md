# Discovery v2 — validation, quality, performance and accessibility

**Status:** validation proposal; no current v2 pass claims. Applies to all 49 requirements. Release requires evidence rather than inference from code, unit tests or model benchmark names.

## 1. Evaluation questions

1. Does a candidate actually represent a **coherent, specific and useful** concept, beyond being an embedding cluster?
2. Are member passages truly relevant, independently explanatory and correctly sourced?
3. Are labels non-generic, supported, accurate and not misleading title/heading artifacts?
4. Are distinct subjects retained while redundant topics are suppressed?
5. Does incremental matching preserve useful historic relationships and stable IDs?
6. Does the new engine reduce real work on representative Android devices without disrupting TTS, TalkBack or manual search?
7. Does source deletion/permission loss retire all evidence on every execution lifecycle path?

## 2. Gold data, annotation and leakage rules

Use three datasets:
- **Synthetic adversarial fixtures** with known outcomes and controlled revisions; commit only invented text.
- **Public/consented representative corpus** with distinct domains, large/small Notes, different heading conventions, multilingual snippets, lists/prose/code, deliberate duplicates and overlapping subtopics.
- **User's private local corpus**, only if explicitly permitted for a device-run evaluation; never upload private Note text, vectors, source IDs or excerpts to GitHub, cloud analytics or external services. Store aggregate scores and sanitized observations only.

Create topic candidate/reference pairs and positive/negative membership judgments. Have two independent human annotators on a subset if feasible, reconcile disagreements with a rubric, record uncertainty rather than forcing labels. Random split must be by **Note family / template / near-duplicate group**, not individual passages from the same Note; hold out domains and languages. Freeze a gold set before tuning weights and thresholds. Preserve current v1 results on the same indexed snapshots/model identity. Historical source experiments are not automatically independent held-out proof.

Annotation rubric (0/1/2 + abstain):
- Concept coherence: unrelated / partially related / same definable concept.
- Specificity: generic container / broad but supportable / clearly distinguishable.
- Explanatory substance: name-drop / some real content / mechanisms-definitions-examples with explanatory value.
- Label quality: misleading or generic / partly faithful / precise and literally sourced.
- Membership support: unrelated / partial or ambiguous / source passage genuinely about candidate.
- Novelty/duplication: redundant / related subtopic / independently useful concept.
- Overall publishability: no / uncertain / yes. Allow quality high with just one substantive Note.

## 3. Mandatory adversarial fixtures

| ID | Fixture | Expected outcome |
|---|---|---|
| G01 | “Implementation” heading in unrelated Kubernetes, guitar, Android and finance Notes | No global “Implementation” topic |
| G02 | Same 12 headings but one real body concept across three Notes | Specific body-backed concept accepted if label valid |
| G03 | Different headings, paraphrases of RabbitMQ acknowledgment and redelivery | Same concept retrieved; no heading-equality dependence |
| G04 | Shared broad “Architecture” with two narrow independent mechanisms | Preserve independently supported subtopics; don't collapse |
| G05 | One deep Note with original substantive explanation | Emerging topic permitted |
| G06 | Twelve copied/near-duplicate paragraphs in independent Note IDs | Support saturates; no false twelve-vote confidence |
| G07 | Shared Note-title prefix and templated sections | No title-family label or topic |
| G08 | “Related Notes” section consisting only of links | Ineligible as substantive concept evidence |
| G09 | Contradictory/negated statements sharing many words | No blind near-duplicate or same-concept proof |
| G10 | Multi-topic Note containing unrelated passages | Independent memberships, relevant passage per topic |
| G11 | Two topics share Notes but cite disjoint substantive passages | Retain both, not deduplicate by Note-set alone |
| G12 | Revoked/deleted Note is anchor, label source, candidate pool item and checkpoint | No stale text or membership visible or reintroduced |
| G13 | Only one Note edited among 1,000 cached Notes | Reuse unchanged embeddings, bounded affected work |
| G14 | Recently opened but unchanged Note | No new embeddings/validity change; ranking only if approved |
| G15 | New Note matches an old ungrouped passage | Older evidence retrieved; candidate may publish |
| G16 | Failed top-k recall, power pause, process restart and resumed reconciliation | Honest incomplete coverage, eventual recovered match |
| G17 | Mixed languages/code/short formulas | No spurious rejection from English regex; Unknown/abstain when uncertain |
| G18 | Model identity or scoring policy changes mid-run | Reject stale scores/checkpoints and rebuild safely |
| G19 | Parent–child shared words but no explicit relationship evidence | No unsupported hierarchy edge |
| G20 | Topic split/merge with renamed/followed identity | Correct continuation, no duplicate follow, explicit unresolved case |

Add negative fixtures where text superficially looks explanatory (“because ...” boilerplate), generic headings are rare, and domain-specific headings are common. Those test robustness of structural IDF rules.

## 4. Metrics and comparison

Use paired runs over exactly the same accepted eligible source corpus, tokenizer/provider identity, cache revisions and annotation set. Report:
- **Topic usefulness Precision@5 / Precision@10** and all-published precision; separately one-Note vs multi-Note.
- **Good-topic recall** (missed annotated concepts) and abstention coverage; report recall loss from top-k and from hard gates separately.
- **Membership precision/recall/F1** on Note–topic and passage–topic evidence; distinct Note count and weak-member contamination.
- **Generic heading false promotion** count (G01, G07); **label precision**, wording source attestation and misleading-label frequency.
- **Duplication**, unjustified merge and unjustified split rates; explicit supported hierarchy edge precision.
- **Stability** across edit, delete, recache, reorder and model/policy change; user follow/rename preservation.
- **Resource profile:** wall time, active CPU/inference time, p50/p95/p99 per changed Note, total cosine operations, embeddings new/reused, CPU/GPU backend, peak RSS/native memory, battery drain, heat/thermal throttling, disk/candidate/checkpoint bytes, foreground query/TTS latency and playback interruption count.
- **Accessibility:** TalkBack focus traversal, headings/labels, action feedback, status not spammed, preserved focus on refresh, Android Back, no forced Play.

Do not equate low silhouette loss or cluster purity with end-user usefulness. Benchmark current single-engine baseline separately from v2; do not claim a new model caused an algorithmic gain when model identity differs.

## 5. Provisional acceptance criteria, to ratify with product owner

The requirements currently specify *what to measure*, not approved pass numbers. Before release, set and record actual numeric pass/fail limits from representative baselines and risk tolerance. Starting candidate limits for discussion (NOT approved release gates):
- Zero generic-heading-only publications in G01/G07 and zero stale-source exposure in G12.
- Target >= 0.80 human-judged Precision@10 and noninferior annotated good-topic recall within 5 percentage points of a trusted exhaustive/reference candidate oracle; report confidence intervals and dataset size.
- Unchanged-content update: zero model inference on unchanged embeddings (excluding separately authorized body-only fallback); opening unchanged content alone: zero topic-content recomputation.
- Incremental median and tail work costs materially below current engine on same device/corpus; a concrete bound for p95 CPU, RSS, disk and battery must be specified after profiling.
- No new TTS audio interruption, missed media action or accessibility blocker attributable to Discovery under concurrent-load device tests.
- Every visible topic links to current source provenance, passes the final-label evidence check and exposes an accessible explanation.

Don't use aggregate averages to hide one failed Must requirement. Report each failed and blocked gate explicitly.

## 6. Test layers and harness

**JVM/domain:** exact pure feature tests, normalized formula property tests, heading frequency/IDF invariants, polarity/dedup, status transitions, source purge, deterministic tie-breaks and equality/resume checks. Seeded random adversarial generation for regression.

**Android instrumentation:** use real configured provider/session and local stores for same-fixture runs. Exercise power mode transitions, WorkManager windows, source invalidation while a worker is active, cache/version corruption and cold process restart. Instrument S25 and at least one slower supported device with separate thermal and model-backend logs.

**TalkBack manual acceptance:** verify linear focus, stable selection, meaningfully announced “Why this topic?” sources/scores, accessible state/progress, zero unexpected TTS playback, and screen-reader-only journey from Discover to actual supporting passage. Automated Compose semantics tests complement, but do not replace, human listening.

**Shadow vs visible:** v2 runs without changing published topics until evaluation accepts it. Keep old and new outcome comparisons internal and private. Explicitly record all exceptions, unknown-feature rates and cost regressions.

## 7. Decision record template

For each evaluation run record: code commit, corpus snapshot hash (not raw source), model identity/provider/backend, policy version and weights, all thresholds, dataset split, sample counts per class/language, expected label/membership sources, confusion counts, p50/p95/p99 resource metrics, accessibility evidence, unresolved assumptions, exact passed/failed criteria and next corrective action.

The test suite passing by itself is **not** certification of private-library usefulness or physical-device latency.

See [requirements](requirements.md), [scoring](evidence-scoring.md), [incremental work](incremental-processing.md), and [implementation](implementation-plan.md).