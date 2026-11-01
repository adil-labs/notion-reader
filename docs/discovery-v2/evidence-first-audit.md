# Evidence-first incremental Discovery: initial audit

Status: **in progress, 2026-11-02**. The user subsequently authorized direct
implementation instead of a separate prototype and later authorized production
activation before outstanding measurements. This investigation is not a
governing specification, a completed baseline, or evidence of acceptance. The
existing production pipeline remains enabled while replacement wiring is incomplete.

## Contract boundary

The Product, UI and Integration sources must be reviewed together before
production changes. The prior Integration amendment ("Sole Discovery pipeline:
cluster seeds, generated concepts and retrieved Notes") requires existing
section-aware grouping as seed discovery and a response citing exactly the
offered evidence. The requested replacement removes mandatory grouping for an
ordinary update, allows a selected subset of citations, adds successful
abstention, and requires an independently evaluated support decision. These
changes are now addressed by the repository-owned Evidence-first incremental
Discovery amendments in the three governing specifications. The earlier
generative and cluster-seeded amendments are superseded where they conflict.

## Verified seams

- `DiscoveryIndexing.reconcileInBackground` snapshots cache revisions, removes
  ineligible index records, and invokes topic invalidation. For a compatible
  model, it rebinds vectors by the hash of exact passage input, preserving them
  in passage progress before replacing an old record. Metadata changes can
  update an existing record without embedding. Cache generation and current
  source revision guard commits. This is an implementation inspection, not a
  fresh timing or reuse measurement.
- `TopicCoordinator.topicSourceSignature` includes the sorted complete source
  revision map and hierarchical engine version. `buildGeneration` reads
  eligible records and runs checkpointed hierarchical formation before labeling.
  This is the specific seam to compare with incremental proposals; the global
  signature is not evidence that every vector is regenerated.
- `DiscoveryRuntime` owns the inference mutex, bulk gate, foreground-query
  priority, exact-input memo and label cancellation/terminal cleanup. The replacement
  must not create a competing production worker or native gate.
- `TopicLabelRequest` is bound to a canonical topic and whole-group fingerprint.
  `validateTopicLabel` requires the citation set to equal the offered set and
  validates structure and freshness, not semantic entailment. `TopicLabelRun`
  has no successful abstention variant. The replacement needs a distinct proposal
  identity and versioned response contract without weakening the shipping one.
- `TopicRetrievalCoordinator` retains scanned current revisions, processes
  bounded windows and persists progress by canonical topic ID. It hydrates only
  complete, current generations. Its `matchTopicRecord` is a cosine-threshold
  selection, not a proof of support; the controller default is 0.85. It does not
  force seed members into results. The sidecar removes obsolete references if a
  withdrawal replacement write fails.
- `StructuralKnowledgeRepository` exposes page, ancestor, section, explicit
  relationship and per-page sync-completeness queries. Missing or partial
  inventory must not be interpreted as evidence of absence.

## Evidence already available, with limits

Decision §130 reports a prior agent-reviewed fixed-group experiment with
57/64 membership precision (89.0625%), below its acceptance gate. The §131
name-only diagnostic used already-seen inputs and was calibration rather than
fresh acceptance. Neither is a baseline comparison of the requested pipeline.
Decision §135 records 1,378 passing JVM tests and device checks for an earlier
structural round; these are historical, not a fresh verification of this work.

## Next evidence required

Complete the end-to-end lifecycle and test inventory, freeze a purpose-built or
public evaluation corpus and its provenance, define independently reviewable
topic and support judgments, and run both algorithms over the same inputs.
Measure native work only from actual executions. Retain explicit missed topics,
abstentions, errors and incomplete scans in the denominator. Collect comparative
evidence and report applicable acceptance gates independently from activation.
Physical-device and human TalkBack observations remain separate from host tests
and agent judgments.
