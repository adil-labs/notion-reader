# Discovery v2 — R01–R49 traceability and proof obligations

**Status:** proposed design coverage, **not implementation coverage**. All 49 requirements appear below. “Planned proof” means tests to implement/measure, not that any test currently passes. This matrix explicitly closes specification gaps identified in the conversation: R01, R04, R05, R09, R12, R14, R16, R17, R22, R24, R32, R33, R34, R37, R41, R44, R46, R47 and R49.

References: **A** [architecture](architecture.md), **S** [evidence/scoring](evidence-scoring.md), **I** [incremental](incremental-processing.md), **M** [state/migration](state-migration.md), **E** [evaluation](evaluation-acceptance.md), **P** [implementation](implementation-plan.md).

| ID | Design owner | Required proof / fixture |
|---|---|---|
| R01 | A/S | Human rubric: publish coherent reusable concept, not merely similar paragraphs (E) |
| R02 | S | G01/G02: generic headings never qualify without specific body-backed concept |
| R03 | S | Repetition of identical headings contributes zero independent support (G01) |
| R04 | S | Heading-invariant body coherence and negative same-heading fixture |
| R05 | A/I/S | G03: different terminology/heading matches supported concept, retrieval recall measured |
| R06 | A/S | G04: independently grounded narrow subtopics retained |
| R07 | A/I | G10: one Note multiple topics, different source passages |
| R08 | A/S | G05: one deep Note can be Emerging |
| R09 | A/S/I | G09/G10: no weak or chained false membership, abstain on ambiguity |
| R10 | S/P | Pure deterministic scorer repeatable on identical evidence |
| R11 | S/E | Six separate measures and ablation evaluation |
| R12 | S/M | Policy normalization, configurable internal weights, frozen scoring version |
| R13 | S | Invalid hard-gate candidate cannot be “rescued” by high Q |
| R14 | S/E | Definition/mechanism/cause/condition/example/trade-off positive and negative fixtures |
| R15 | S/E | Corpus TF/DF, structural lexicon and body semantic checks (G01/G02) |
| R16 | S/E | G06 exact/near-duplicate collapsing, diminishing support |
| R17 | S/M | Accept/provisional/suppress codes and source-backed explanation inspected |
| R18 | S/E | Train/holdout split, calibration version, human scoring report |
| R19 | A/S | Heading-only support is always invalid |
| R20 | S | Generic lexicon + DF with rare generic / common domain-heading counterexamples |
| R21 | S | Literal distinctive name supported by body passages; no generative inference |
| R22 | S/E | G07: no misleading title prefix, generic or broad label |
| R23 | S/A | G04: specific supported subtopic preferred without suppressing valid parent |
| R24 | M | G20 split/merge with stable identity, custom label and follow preservation |
| R25 | I | Add/edit/open priority order; open unchanged requires no content recompute |
| R26 | A/I | Existing-topic match happens before unmatched formation |
| R27 | I/E | G15: old ungrouped Note considered on new trigger |
| R28 | I | G13/G14: zero inference for unchanged exact inputs |
| R29 | I | G13: no mandatory global reclustering per small edit; work measured |
| R30 | I/M | Versioned bounded unmatched pool, safe revocation and reconsideration |
| R31 | I | G16: idle reconciliation recovers missed link while status honest |
| R32 | I/E | Measured CPU/comparison/RSS/storage/thermal per-device budgets and yield |
| R33 | I/M | Power, pause, WorkManager checkpoint/restart, foreground priority |
| R34 | I/E | Concurrent TTS/search stress test; no playback regression |
| R35 | A/M/E | Network/privacy audit; no source text or embeddings off-device |
| R36 | A/M | Independently supported major and narrow subjects, no forced membership |
| R37 | A/M | G19 evidence-backed edges; no unsupported hierarchy/cycles |
| R38 | A/S | Activity never enters Q or candidate admission |
| R39 | A/S/I | Personal relevance presentation-only and governance approval |
| R40 | A/I | Old topic still reachable without recent activity |
| R41 | S/M | G11: deduplicate same theme, preserve independent disjoint evidence |
| R42 | A/M | Internal provisional/suppress; valid one-Note Emerging, multi-Note Established |
| R43 | E | G01–G20 + public/consented and synthetic data |
| R44 | E | Useful Precision@k, topic recall, label accuracy and membership precision/recall |
| R45 | E | Paired v1 vs v2 on same source, model, device and gold labels |
| R46 | I/E | Time, comparisons, model calls, peak native/RSS, battery, storage instrumented |
| R47 | S/M/E | Score breakdown + source provenance + accessible developer inspection |
| R48 | A/E | TalkBack-first topic/progress/details plus manual device testing |
| R49 | M/I/E | G12/G18: complete purge of revoked/revised evidence, late callback protection |

## Unresolved numeric and semantic decisions (explicit, not silent)

1. Full feature formulas/normalizers/quality bands (25/20/20/15/10/10 and 75/55 are unvalidated candidates).
2. Per-model vector calibration, top-k value, recall fallback, non-English handling and generic-heading frequency breakpoints.
3. Source-of-truth amendments required for the new production engine and optional activity-based relevance; currently **not approved by these documents**.
4. Device-specific work budgets, storage ceilings, privacy controls and human-quality release thresholds.
5. Topic user-alias merge/split behavior where old identity legitimately matches multiple branches; define user-visible resolution before production.

**Tracking instruction:** implementation owners mark each Rxx with linked test name, commit, device/corpus version and latest outcome (PASS/FAIL/BLOCKED). Do not change a design “planned proof” into a test pass without observed results.