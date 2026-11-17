# Discovery Inference Optimization Implementation Plan

**Goal:** Publish useful topics sooner on the S25 while reducing fresh Gemma calls and preserving independently verified evidence.

**Architecture:** Keep the existing process-owned scheduler, runtime, and canonical catalog. Start with observability and exact model-input reuse. Evaluate approximate grouping and verification scheduling against the measured baseline before changing production behavior.

**Stack:** Kotlin, LiteRT, existing file-backed Discovery stores, JUnit 4, and the repository Android test wrapper.

**Specifications:** [Product & Architecture Contract](../../notion/product-and-architecture-contract.md), [Discovery work-state model](../../discovery-v2/discovery-work-state-model.md), and [decision §259](../../decisions.md#259-continue-after-rejected-discovery-citations-and-offer-a-bounded-local-first-pass-2026-11-17).

## Current baseline and constraints

The current pipeline already offers a two-proposal local first pass before
cross-Note formation on larger corpora, verifies each selected citation, skips
the rest of a candidate after a saved rejection, and stops first-pass
membership verification after one supported passage. Exact support decisions
are persisted. The S25 admitted a new concept roughly 52 seconds after the
first inventory event in one run. Engine open (4.77 s), conversation creation
(2.67 s) and native generation (6.91 s) were consecutive phases of the cold
proposal alone. That is an observation, not a repeated latency baseline.

Similarity may retrieve or prioritize candidates. Published membership and
proposal citations still need independent support. A flag does not authorize
similarity-only publication under the current contract. Keep withheld candidates
unchecked and resumable. Preserve the reset, eviction, source revision,
model identity, cancellation, foreground priority and catalog commit guards.

## P0. Establish a comparable S25 baseline

- [x] Run a frozen 100-Note snapshot with current production wiring and pinned
  APK, model, backend and settings. Use the same consented or purpose-built
  corpus for each comparison. Seed the benchmark in an isolated test context.
  Record starting indexed coverage and derived stores. Separate a cold run
  from a repeat run with reused state and repeat the paired runs at least three
  times in each condition. Keep screen, charging, playback and thermal
  conditions comparable. Three 150 s paired runs completed; one further pair
  crashed natively in the GPU path. A long uninterrupted GPU window is not yet
  a stable environment (decision §260).
- [x] Reuse numeric work summaries and native inference diagnostics. Count
  logical requests, cache hits and actual native calls separately for
  embedding, proposal generation and support. Record opens, conversation
  creation, execution, release and gate wait. In `DiscoveryProposalEvidence.kt`
  add proposal response cache-hit and native-request counters if the capture
  cannot already reconcile them. Keep phase sums separate from elapsed time.
- [x] In `DiscoveryController.kt`, verify that the benchmark distinguishes
  duplicate reuse, new admission and first visible publication. The current
  first-publication metric checks visibility in the admission callback, which
  can miss later projection or Refresh. If the trace cannot observe visibility,
  measure the first new topic on the Discover surface separately. Record
  launch-to-first-visible-topic, indexing coverage, useful topics per minute,
  and calls during a fixed foreground window. Compare total runtime only for
  runs that reach the same terminal work state. Record runs without a new topic
  as such.
- [x] Save the device report with run identity, counts, timing and quality
  denominators. Capture numeric diagnostics without Note text or generated labels.
  Reports, manifests and the native crash capture are under
  `build/discovery-benchmark/`; the harness is tracked in `tools/`.

## P1. Reuse exact proposal inputs before Gemma

- [x] Add failing-first cases in the proposal generation tests. A local pass
  and a full pass that offer the same ordered current evidence with a different
  formation key should share one model call. Changing evidence order, passage
  revision or body, or generator identity should miss. A validated abstention
  can be reused for identical inputs. Cancellation, invalid output and storage
  failure must remain retryable under their current rules.
- [x] Update `DiscoveryProposalEvidence.kt` at the cache boundary before
  invoking the runtime. The current `discoveryProposalWorkKey` includes
  `proposalKey`, while the wire prompt emits the evidence in input order with
  positional aliases. Introduce a separately versioned model-response lookup
  keyed by ordered current evidence references and bodies plus generator
  identity. Preserve the existing work key for support, scheduling and
  admission. The old work-keyed lookup was removed rather than kept: Discovery
  checkpoints are derived state with one current format, and an incompatible
  older store is rebuilt, not migrated. Revalidate cached output against the
  current request and return the current proposal's completeness; a cached
  response carries no admission authority.
- [x] Compare exact cache hits and fresh calls on the S25 corpus. At a fixed
  150 s window reuse raised logical requests but did not lower native calls;
  a terminal-state comparison remains open. No approximate grouping was tested
  or shipped because distinct-evidence generation still dominates.
- [ ] If repeated generation remains significant, run a report-only near-duplicate experiment
  on ordinary formation windows. Prefer overlap in current evidence and
  preserve distinct anchors. Compare representatives against skipped
  alternatives and include an abstention fallback. Ship approximate grouping
  only if it saves calls with acceptable loss of independent useful subjects.
  Keep canonical duplicate checks in `TopicCoordinator.kt`.

## P1. Evaluate faster verification ordering

- [x] Replay observed Note–concept candidates with labeled decisions. Measure
  whether reranking or an initial passage budget reaches the first independently
  supported passage sooner than the current ordering. Account for early support
  and cached decisions already saving work. Report missed and delayed supported
  Notes separately from unsupported ones. Candidate order is already
  score-descending and the scan pages one candidate at a time, so reranking is
  the current order and a budget can only defer. A 300 s S25 repeat sample saw
  2 supported scans both at rank 0 and 4 exhausted scans with 1 check each;
  budgets 1-3 delayed nothing. Small sample; more supported scans needed.
  Decision §263.
- [ ] If the result supports a change, test and implement it around
  `ExistingConceptScan.kt` and `CanonicalMembershipScheduler.kt`. Keep Gemma
  as independent verifier; defer lower-priority candidates with a continuation.
  Test that a later supported passage still gets its turn, that a high-score
  false positive stays out of membership, and that coverage stays incomplete
  until the relevant work is resolved. Any retrieval ordering change must be
  reflected in the checkpoint/retrieval identity.
- [x] Keep similarity-only acceptance in offline evaluation. Publishing those
  memberships would require an explicit product-contract amendment and a
  separate validation plan. The current retrieval threshold is not a calibrated
  acceptance threshold. No similarity-only acceptance was shipped.

## P2. Batch engine work if transitions dominate

- [x] Use `DiscoveryRuntime.kt`, `GemmaTopicLabeler.kt` and
  `LiteRtTopicLabelEngine.kt` to attribute cost to engine opening versus
  conversation setup. Batching requests while retaining the engine can avoid
  opens; it still creates a conversation per current support request. Measured
  on the S25: proposal open 38.3-39.4 s, conversation 21.2-23.3 s, generation
  31.4-35.2 s for six requests; support retained the engine across five
  requests with two opens. Proposal requests reopened every time.
- [ ] If engine reopens are material, test a bounded batch of already-eligible
  same-engine work within the existing window. Keep the single inference gate,
  end-of-window release, cancellation and foreground preemption. Measure
  opens per request, memory, query latency and cancellation latency on the S25.
  A two-evidence-step batch was tested and produced zero reuse (4 attempts /
  5 opens cold), so it was reverted; the retrieval embedding, not only the
  membership step, ends the engine window. Decision §262.
- [ ] Keep checkpoint simplification out of this performance round. Revisit
  only after evidence establishes that a path is unused or redundant and
  restart, withdrawal and source-change tests can prove equivalence.

## Verification and decision gates

- [x] Run failing-first regressions, then `:app:testDebugUnitTest`,
  `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin`. Run the
  targeted `SupportFirstDeviceTest` through `tools/run-accessibility-tests.ps1`,
  plus the comparable 100-Note benchmark for behavioral or performance claims.
  All passed; the benchmark and its crash limits are recorded in decisions
  §260-§261.
- [ ] Report call reduction separately for cold and repeat runs. Treat 50–70%
  fewer native Gemma calls, under 10 s to first useful topic and at least 80%
  usefulness as hypotheses. Under 10 s is a warm-path stretch target given the
  observed 14.35 s of cold proposal phases before verification. Define usefulness
  as a distinct, specific, evidence-grounded concept a reviewer would keep,
  divided by all newly published topics reviewed. Also report missed useful
  subjects so suppressing outputs cannot inflate the result. Count a run with
  no publication separately. Source validity remains an invariant; report the
  checked-citation denominator and any violation as a failure.
