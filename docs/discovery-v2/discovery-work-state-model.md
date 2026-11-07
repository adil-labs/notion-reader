# Discovery work-state model and scheduling contract

**Status:** adopted 2026-11-07. This document specifies how the sole evidence-first
Discovery pipeline represents work state, coverage and admission. The governing
semantics live in
[Product & Architecture Contract §Discovery work-state separation, scheduling fairness and coverage facts](../notion/product-and-architecture-contract.md);
this file explains the state model that implements them. Where they differ, the
contract wins.

It supersedes the state-machine sketch in the 2026-11-07 refactoring design
discussion. It does not replace [architecture](architecture.md),
[incremental processing](incremental-processing.md) or
[state and migration](state-migration.md); those keep their existing scope.

## 1. The one rule

Evidence validity, work progress and coverage completeness are three separate
facts and must never be represented by one ambiguous value.

| Fact | Question it answers | Owner |
| --- | --- | --- |
| Evidence validity | Is this passage currently, independently verified support for this concept? | support store |
| Work progress | Where has this bounded job reached, and can it continue? | scheduler / checkpoint |
| Coverage completeness | Which eligible Notes and passages have been individually resolved? | derived coverage facts |

A Note can therefore be a verified member, have an unresolved `UNCERTAIN`
decision, and have an unchecked passage tail at the same time. None of those
facts contradicts the others, and no combination may be collapsed into a single
"done" flag. Verified support is never retracted by a later inconclusive
observation; uncertainty is retained alongside it.

## 2. Work units

Each unit is bounded, resumable, and owned by the existing process-scoped
coordinator. The design adds no second pipeline and no new engine.

| Unit | Selects | Must not |
| --- | --- | --- |
| Source indexing | unindexed or stale cached Notes | decide topic validity |
| Proposal formation | indexed evidence bundles for Notes without a published outcome | write the catalog |
| Admission | a verified proposal | run inference to re-decide support |
| Note–concept membership | Notes not yet terminal for an existing concept | collect extra proofs after support |
| Passage-tail reconciliation | unchecked tails left by support-first scanning | mark an unscanned Note scanned |
| Connection retrieval | explicit references and semantic neighbours | establish membership |

Source indexing incompleteness may limit which evidence is available. It must
never become an implicit global lock on the other units.

## 3. Note–concept scan state

Scan termination and verifier decisions are independent. Termination answers
"why did this bounded scan stop"; decisions answer "what did the verifier say".
They are **two different fields**, never one enum value.

Termination values (the complete set):

- `EXHAUSTED` — no further eligible candidate needs submitting under the current
  retrieval policy. The scan may have found support on its final candidate, or
  may have only unsupported/uncertain decisions; neither changes termination.
- `SUPPORTED_EARLY` — a current candidate was independently supported and further
  eligible candidates were deliberately not submitted. The Note keeps an
  unchecked tail.

There is **no** `UNCERTAIN` termination value. Uncertainty is evidence state for a
Note–concept pair, derived from the support store:

- at least one current `SUPPORTED` assessment → verified membership;
- unresolved `UNCERTAIN` assessments → unresolved evidence;
- only `UNSUPPORTED` assessments within an exhausted scan → not supported by the
  inspected candidates, which is not a claim of absence under every method;
- no examined eligible evidence → unexamined.

Termination and evidence are independent, so a single scan can be
`SUPPORTED_EARLY` and carry both a supported witness and previously encountered
uncertainty. The Kotlin types are `ConceptScanTermination { EXHAUSTED,
SUPPORTED_EARLY }` for runtime termination and `ConceptScanCompletion {
EXHAUSTED, SUPPORTED_EARLY }` for the persisted completion. Assessments are
persisted separately and are the source of truth for support and uncertainty.

| Sequence | Termination | Membership | Unchecked tail |
| --- | --- | --- | --- |
| Supported | `SUPPORTED_EARLY` | verified | yes |
| Unsupported → Supported | `SUPPORTED_EARLY` | verified | yes |
| Uncertain → Supported | `SUPPORTED_EARLY` | verified, uncertainty retained | yes |
| Supported on the final candidate | `EXHAUSTED` | verified | no |
| Unsupported → end | `EXHAUSTED` | not supported | no |
| Uncertain → Unsupported → end | `EXHAUSTED` | unresolved | no |
| No eligible passages | `EXHAUSTED` | no verified membership | no |
| Interrupted after one check | pending | not finalized without prior support | preserved |

A terminal scan is committed as coverage for every concept it reached, so
restarting never replays a finished page merely to rediscover its uncertainty.
A terminal scan with unresolved evidence is not "pending"; it is a finished scan
whose membership coverage stays incomplete.

## 4. Coverage facts

Coverage is derived from validated progress. Stored booleans are summaries that
must agree with their sources; they are not independent truths.

- **Source indexing coverage:** which current cached Notes have a complete, valid
  semantic index.
- **Membership coverage:** for one concept, which eligible indexed Notes have a
  terminal, *resolved* assessment under that concept's retrieval policy.
  `matchingComplete` is this fact. The inputs are `scannedRevisions` and
  `uncertainRevisions`, never the set of Notes that support the concept.
- **Passage coverage:** for one concept, which member Notes still contain
  intentionally unchecked eligible candidates. `passageCoverageComplete` is
  `matchingComplete` plus no unchecked tail.

The durable per-concept coverage fields are:

| Field | Source | Invariant |
| --- | --- | --- |
| `scannedRevisions` | validated checkpoint `pageId → revision` | a terminal scan for that Note |
| `supported` | support store | current independently verified evidence |
| `uncheckedRevisions` | early-stop checkpoint | subset of `scannedRevisions` |
| `uncertainRevisions` | `UNCERTAIN` assessments | subset of `scannedRevisions`, disjoint from `uncheckedRevisions` |
| `matchingComplete` | derived | every accepted source scanned and no unresolved Note |

Consequences:

- The set of Notes that *support* a concept is not the set of Notes that have
  been *checked*. Equality between those sets cannot express coverage.
- A fully checked corpus is complete even when most Notes do not support the
  concept.
- Exhausting retrieval without a strong candidate counts as a resolved
  assessment under that policy only; it does not assert semantic absence from the
  Note by any other method.
- A Note with only unresolved evidence is incomplete membership coverage. It is
  terminal work, so it is not rescanned merely to repair the summary, and it must
  not be silently reclassified as checked.
- A Note that holds both verified support and unresolved evidence remains a
  member. Both facts are retained and coverage stays incomplete.

## 5. Admission

Admission is a typed decision, not a chain of independent booleans. The
decision is one of:

- allow as Emerging, with validated evidence attached;
- attach to an existing equivalent topic instead of creating a new one;
- defer, naming the dependency that is not yet trustworthy;
- reject, naming the evidence defect;
- stale, because the source or catalog identity changed.

Rules:

- Source coverage may be incomplete when Emerging is allowed. The topic reports
  incomplete coverage and never claims an exhaustively scanned corpus.
- Established keeps the stronger complete-coverage conditions.
- A catalog that cannot establish duplicate safety defers. It never silently
  admits a second copy of a concept.
- Admission is idempotent under retries and restarts. The same input produces
  the same topic identity, and a duplicate resolution never discards the new
  supporting Note: membership reconciles it against the existing topic.
- An admission decision is provisional until the catalog owner rechecks its
  guards at commit time.

## 6. Durable checkpoints and replay

A stored checkpoint is untrusted input.

1. There is exactly one current checkpoint contract. Discovery checkpoints are
   derived state: an incompatible older file is discarded and rebuilt from
   current sources and still-valid support decisions. No legacy migration,
   deserializer or format-version compatibility branch exists.
2. A completed checkpoint has a terminal reason; a pending checkpoint has a
   continuation cursor. A file that cannot state which is rejected.
3. An early-stop checkpoint must replay at least one current, independently
   verified supporting assessment.
4. A checkpoint claiming exhaustion must replay exhaustion under the same
   retrieval policy and candidate ordering.
5. Missing, truncated or malformed records never masquerade as completed
   coverage.
6. A changed source, query, policy, concept or verifier invalidates the affected
   checkpoint only; unrelated valid decisions survive.
7. Replay performs zero fresh inference.
8. An older checkpoint is rebuilt from still-valid cached support decisions
   without deleting them.
9. Writes stay atomic and bounded.

## 7. Scheduling states

Every unit is in exactly one of five states.

| State | Meaning | Reawakening |
| --- | --- | --- |
| Ready | eligible to process | scheduler selects it |
| Running | bounded operation underway | completion, interruption, cancellation |
| Deferred | temporary runtime ineligibility | playback, foreground or resource condition changes |
| Blocked | a specific unresolved dependency prevents progress | that dependency changes, or explicit retry |
| Completed | finished for a specific input identity | a relevant input changes |

Deferred and Blocked are different. A blocked unit records the **complete
dependency identity** that blocked it; it reopens only when that identity
changes, or on an explicit user retry.

| Work | Dependency identity used for block invalidation |
| --- | --- |
| Membership | source revision of the Note; the relevant concept identity (id, workKey, name, definition), verifier, concept query key/vector/policy, retrieval policy, and the concept's scanned/unchecked/uncertain state for that Note |
| Tail reconciliation | the same identity, with the unchecked-tail state |
| Proposal formation | model/verifier/generator identity, cache revision, indexed revision, source revisions, and the canonical **proposal-catalog identity**: canonical readiness; each topic's id, canonical workKey/name/definition/verifier, query key/vector/policy, label/projection workKeys, and seed members; and continuity (canonicalKey, page/source ids, replacement, anchor). A connection-only generation bump does not change it. |

An unrelated catalog generation change must not reopen a membership block whose
concept/source/query/verifier identity is unchanged. A proposal block is released
only when the topic catalog itself changes, because proposal work re-checks
existing concepts and duplicate safety against it; connection progress alone does
not reopen it. A verifier change invalidates the affected checkpoints; if the
verifier identity of an existing canonical concept changes, that concept requires
an explicit derived-state rebuild rather than silent reuse (see §11).

Four actions are distinct and must not be inferred from counters:

- automatic continuation: resumes Ready and Deferred work only;
- explicit retry: deliberately reopens eligible recoverable failures;
- input invalidation: resets only work whose dependency identities changed;
- scheduler reset: rebuilds scheduling state without deleting valid decisions.

Automatic continuation changes no work dependency: active continuation state,
blocked decisions and durable checkpoints are preserved. The membership active-work
scope contains the model identity, verifier, retrieval policy, sources and the
topic/concept/query identities; it deliberately excludes the global catalog
generation and any retry counter, because those change for unrelated commits and
wake-ups while the durable checkpoint/replay validates any relevant change.
Explicit retry clears blocked work only; it does not discard valid active work.
A dependency-identity change invalidates only the work whose identity changed.

Automatic continuation must not discard durable progress. The durable scan
checkpoint — including a multi-page tail cursor — is resumed, and an
`AwaitingValidation` active scan can commit without being re-scanned. Reopening
blocked work is reserved for an explicit retry or a dependency-identity change.
A verifier-identity change on an existing canonical concept is exposed as a
distinct, non-retryable recovery condition whose instruction is the explicit
derived-state rebuild; it is never presented as an ordinary Retry that cannot
succeed.

Tail restart is decided by the durable checkpoint, not by an in-memory
first-turn flag:

- a partial checkpoint (`next != null`) resumes from its cursor;
- a completed `SUPPORTED_EARLY` checkpoint with an unchecked tail restarts
  exhaustively, because it is not a finished tail pass;
- a completed `EXHAUSTED` checkpoint commits without rescanning;
- a completed checkpoint whose scope is stale is rescanned with `restart = true`
  (for a tail too), rather than repeating the stale commit;
- a bounded tail commit replay resumes its replay cursor across steps instead of
  restarting from the first page.

The durable scan scope excludes both the global catalog generation and the live
catalog epoch; those advance for unrelated catalog activity. The epoch remains
bound into each support request at runtime, not into the reusable checkpoint
identity.

A recreated process/scheduler therefore resumes a partial tail rather than
re-examining completed pages. Only a stale/invalid checkpoint triggers a
deliberate restart, and that restart still reuses exact-input support decisions.

## 8. Fairness and priorities

Three logical priorities run inside the existing bounded loop:

| Priority | Work | Rule |
| --- | --- | --- |
| High | admission-ready proposal and its required citation verification | prompt progress, never gated by membership completion |
| Medium | new proposals and previously unexamined Note membership, fairly interleaved | bounded turns, no unconditional loop |
| Low | passage-tail reconciliation | exhaustive pass, runs after at most two consecutive membership turns |

The two scheduler work kinds are explicit:

| Kind | Selected when | Scan mode |
| --- | --- | --- |
| `MEMBERSHIP` | an accepted Note has no terminal scan for a concept | support-first; may stop after verified support |
| `TAIL` | a concept has an unchecked tail for an accepted Note | exhaustive; never stops early, reuses the support cache |

A tail is never membership work: it is the later exhaustive pass over a Note
whose membership was already verified. After two membership scheduler steps
(including steps that only return `Pending`) the scheduler prefers an available
tail, so a steady stream of new Notes cannot starve it. Tail work is resumable
and never marks an unscanned Note scanned. Its durable checkpoint, not an
in-memory "started" flag, decides whether to resume, restart, or commit; see §7.

Fairness is bidirectional:

- A continuously incomplete, blocked or uncertain membership scan never prevents
  an eligible proposal from executing.
- Continuously available proposal work never starves membership expansion
  indefinitely.

Bound turns come from the existing scheduling mechanism. Foreground search, reading, playback and accessibility keep
existing priority over all bulk work.

## 9. Uncertainty scoping

An `UNCERTAIN` result is evidence state for one Note–concept pair.

- It does not make the Note subject-less.
- It does not prove absence, and it does not establish support.
- It does not block a proposal about a different subject from the same Note.
- It does block admission where it applies to the candidate itself
  (`EXISTING_CONCEPT_UNCERTAIN`).
- It stays visible in that concept's coverage as unresolved work.

This scoping is the §248 fix: a page-level uncertainty previously ended the
whole page's proposal work.

## 10. Required scenarios

These become contract tests in the refactored model.

| Scenario | Expected |
| --- | --- |
| Few indexed Notes, one valid citation | Emerging publishes with incomplete coverage |
| Same candidate admitted twice | one canonical topic, same identity |
| Two proposals describe one concept | existing topic reused or reconciled, not duplicated |
| Uncertain → Supported → early stop | verified membership, uncertainty and tail retained honestly |
| 10 Notes support a topic out of 100 | membership can complete after every Note is resolved |
| Support confirmed, then source passage deleted | stale support withdrawn or revalidated |
| Verifier or retrieval policy changes | affected checkpoints incompatible, unrelated ones reused |
| Process killed after checkpoint save | resume without duplicate inference |
| One membership operation stays blocked | proposal scheduler still advances |
| Proposals keep arriving | membership still advances |
| Unchecked tail exists | passage coverage reported incomplete |
| Checkpoint truncated or tampered | rejected or rebuilt, never accepted as complete |
| Everything indexed and resolved | scheduler reaches Idle |
| Model unavailable | deferred, no destructive reset |
| Restart after catalog write | topic and verified members remain available |

## 11. Closed gaps and remaining risk

The implementation defects this specification originally named were closed on
2026-11-07 (see [decisions.md §249](../decisions.md), the §250 correctness pass,
the §251 scheduler-continuity pass, the §252 continuation-scope round, and the §253 epoch/tail-continuation round):

- Catalog-wide coverage is now derived by `reconcileConceptCoverage` from
  terminal Note scans and unresolved evidence. The supported-member key set is no
  longer compared with the source key set.
- `CanonicalMembershipScheduler` has explicit `MEMBERSHIP` and `TAIL` work kinds.
  A tail gets one bounded opportunity after two membership scheduler steps, even
  if membership work only returns `Pending`. Tail restart is owned by the durable
  checkpoint: a partial multi-page tail resumes its cursor across automatic
  continuation, unrelated catalog generations and process recreation; a completed
  `SUPPORTED_EARLY` checkpoint with a pending tail restarts exhaustively; a
  completed exhaustive checkpoint commits without rescanning.
- A terminal scan is committed as coverage for each Note–concept pair. It is not
  replayed after restart, and it does not block updates for a different concept
  from the same Note. Termination is exactly `SUPPORTED_EARLY` or `EXHAUSTED`;
  support and uncertainty are separate persisted evidence facts and may coexist
  with either termination.
- Discovery checkpoints use one current format version (`4` for concept scans,
  `5` for pending-Note scans). Older files are rejected and rebuilt; there is no
  compatibility branch.
- Blocked membership work is keyed to its complete dependency identity (source
  revision, relevant concept identity/query/verifier/policy and coverage state);
  blocked proposal work is released by a change to the canonical topic-catalog
  identity, not by a connection-only generation bump.
- The durable scan scope excludes both the global catalog generation and the live
  catalog epoch; a stale completed tail is rescanned with `restart = true`, and a
  bounded tail commit replay resumes its cursor.
- Canonical hydration reuses a cached supporting decision only under an exact
  evidence/verifier identity; an inconclusive canonical observation may fall back
  to the immutable pending witness, and a definite rejection still withdraws.

Remaining risk is calibration, not architecture:

- Live support quality (50 supported / 345 uncertain / 499 unsupported on the
  S25 corpus) limits publication far more than scheduling does. That is a
  prompt/model calibration problem, out of scope for this model.
- The bounded scheduling ratio (two membership scheduler steps before a tail,
  counted even when membership work only returns `Pending`) is a liveness
  guarantee, not a tuned throughput value; it is measured, not fixed.
- A changed support-verifier identity is detected and exposed as a distinct,
  non-retryable `VerifierChanged` condition whose message directs the user to the
  explicit **Rebuild index** action in Settings. It does not silently remain
  blocked, and it does not offer an ordinary Retry that cannot succeed. The
  rebuild clears derived Discovery state (topics, checkpoints, index) while
  preserving cached Notes, credentials, interests and preferences; the current
  verifier then re-derives topics. This is a controlled derived-state rebuild,
  not transparent verifier migration.

## 12. Scope boundaries

This model does not change the Gemma prompt, verifier, schema, embedding model,
similarity thresholds, retrieval policy version or storage engine, and it adds no
database, no second engine and no migration of the former cluster-first catalog.
Verifier uncertainty needs a labeled calibration study first; it must not be
resolved by lowering a cutoff or accepting uncertain citations.

## 13. Central invariants

| ID | Invariant |
| --- | --- |
| I-01 | Every published topic has current, independently verified supporting evidence. |
| I-02 | Embedding similarity never independently establishes membership. |
| I-03 | An Emerging topic may publish before complete source indexing. |
| I-04 | Coverage incompleteness is never presented as completeness. |
| I-05 | A Note can be a verified member while retaining an unchecked tail. |
| I-06 | An uncertain decision is neither supported nor unsupported. |
| I-07 | Scan termination is independent of verifier decisions. |
| I-08 | Stored checkpoints cannot authorize evidence without replay validation. |
| I-09 | Replaying a valid checkpoint never invokes fresh model inference. |
| I-10 | A change invalidates only the derived decisions that depend on it. |
| I-11 | Admission is idempotent under retries and restarts. |
| I-12 | New proposals cannot be starved by perpetual membership work. |
| I-13 | Membership cannot be starved indefinitely by continuous proposals. |
| I-14 | Blocked work resumes only on a valid dependency change or explicit retry. |
| I-15 | Complete corpus coverage does not require every Note to support the topic. |
| I-16 | Source and catalog writes stay atomic and guarded against stale identities. |
| I-17 | Bulk work never blocks reading, playback or accessibility interactions. |

Release-blocking correctness conditions: I-01, I-04, I-06, I-08, I-11, I-12,
I-15, I-16.

## 14. State-machine diagrams

These diagrams are normative for the implementation in `application/` and
`domain/discovery/`.

### 14.1 Source indexing

```text
                    ┌──────────── stale source / model change ───────────┐
                    ▼                                                    │
 Pending ──select──▶ Running ──complete──▶ Indexed ──evidence────────────┘
    ▲                  │  │
    │                  │  └─ no eligible work now ──▶ Deferred ──▶ Ready
    │                  └──── dependency/failure ─────▶ Blocked(reason)
    │                                                      │
    └──────────────── explicit retry / dependency change ──┘

 Invariant: a partially indexed Note is Pending or Running, never Indexed.
```

### 14.2 Proposal and admission

```text
 EvidenceReady ──generate──▶ Proposed ──citation replay──▶ CitationsVerified
                                   │                              │
                                   │                              ├─ policy+coverage+duplicate ─▶ AdmissionEligible ──guarded write──▶ Published
                                   │                              ├─ evidence unresolved ──────▶ Deferred(reason)
                                   │                              └─ evidence invalid ─────────▶ Rejected(defect)
                                   ├─ model abstains ─────────────▶ Abstained
                                   └─ source/catalog identity changed ─▶ Stale

 Emerging publication does not require complete corpus membership replay or
 complete source indexing. Established additionally requires complete coverage.
```

### 14.3 Note verification: termination is not a decision

```text
 scan page (strongest passage first)
   │
   ├─ SUPPORTED and more candidates remain ─▶ SUPPORTED_EARLY
   │                                            membership=verified, tail=unchecked
   ├─ every candidate examined, any UNCERTAIN ─▶ EXHAUSTED
   │                                            evidence=unresolved, no tail
   └─ every candidate examined, no support ────▶ EXHAUSTED
                                                membership=not supported, no tail
```

A Note may hold verified support, prior uncertainty and an unchecked tail at the
same time; none of them is discarded. Uncertainty never replaces a termination
value.

### 14.4 Membership versus passage-tail work

```text
 MEMBERSHIP: unscanned Note ─▶ support-first scan ─▶ commit terminal coverage
 TAIL:       unchecked Note ─▶ durable checkpoint decides:
                               partial (next != null) ─▶ resume cursor
                               SUPPORTED_EARLY + tail ─▶ exhaustive restart
                               EXHAUSTED ─────────────▶ commit, no rescan
                             (support cache reused; zero repeat inference)

 Selection: membership first; after two membership scheduler steps (including
 partial/pending ones), prefer a tail. Both commit through the same verifier and
 support store. Automatic continuation or process recreation resumes the durable
 tail cursor; it does not restart completed pages.
```

### 14.5 Durable replay

```text
 Loaded ──identity/source/query match──▶ IdentityValidated ──cached support──▶ Replayed
   │                                              │                              │
   │                                              └─ mismatch ─▶ Rejected(stale) │
   │                                                                             ├─ Accepted
   └─ unreadable/older format ─────────────────────────────────────────────────▶ Rebuilt

 Replay invokes no model. Rejected or rebuilt state never authorizes publication.
```

### 14.6 Scheduler

```text
 Ready ──select──▶ Running ──complete──▶ Completed ──input change──▶ Ready
   ▲                  │
   ├── resource/foreground pause ──▶ Deferred ──condition changes──▶ Ready
   └── dependency failure ─────────▶ Blocked(reason) ──dependency change
                                                       or explicit retry ──▶ Ready

 Automatic continuation resumes Ready and Deferred only.
```

## 15. Relationship to existing documents

- [Architecture](architecture.md) owns the end-to-end pipeline and its data
  contract.
- [Incremental processing](incremental-processing.md) owns triggers, budgets and
  the single serialized work gate.
- [State and migration](state-migration.md) owns record versioning and topic
  identity continuance.
- [Inference verification](../discovery-inference-verification.md) owns the
  support verifier, replay validation and coverage diagnostics.
- [Evaluation and acceptance](evaluation-acceptance.md) owns quality gates. This
  model adds no quality claim and no acceptance result.
