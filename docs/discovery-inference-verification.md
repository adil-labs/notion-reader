# Discovery inference and evidence verification

Derived implementation audit; the governing contracts retain precedence.

## Review corrections (2026-11-07)

Partial-index scheduling now invokes guarded admission instead of unconditionally
rejecting it. Revalidation compares the candidate with its current catalog subset
and checks every revision against eligible sources. Completed subset membership
is persisted without claiming complete source coverage. Pending early-stop
checkpoints transfer unchecked tails into canonical evidence. The membership
scheduler prioritizes unresolved Notes, then exhaustively continues known tails
in bounded pages, reusing prior decisions. Proposal output is limited to one or
two citations, with the prompt/schema hashes changing accordingly.

The original cold support-first call counts measure initial membership discovery;
they do not include the later exhaustive tail reconciliation. This policy moves
that additional cost after publication rather than eliminating it.

## Execution and reuse

`DiscoveryIndexing` reconciles cached semantic revisions and passage checkpoints
before embedding. The semantic index retrieves candidates; similarity does not
authorize membership. `topicCandidatePage` ranks threshold-eligible passages
deterministically strongest first and returns bounded pages with a continuation
cursor, retaining results beyond the first page.

`DiscoveryController` and `EvidenceFirstScheduler` drive proposal, independent
citation verification and corpus replay. `generateProposal` checks persisted exact
results before invoking its runtime. `verifyRetrievedPassage` checks durable support
before inference. Supported, unsupported, uncertain and unchecked remain distinct.

Bounded per-Note verification is implemented (governing amendments, 2026-11-07;
implementation round §243). `topicCandidatePage` still ranks threshold-eligible
passages deterministically strongest first. `verifyExistingConceptPage` now:

- checks persisted exact-input support before invoking the verifier;
- submits the strongest eligible candidate first;
- stops on the first completed `SUPPORTED` decision when any eligible candidate
  remains unchecked;
- continues on `UNSUPPORTED` and `UNCERTAIN`, retaining every encountered
  uncertainty rather than discarding it;
- never treats a cosine score as support and never invents a per-Note score
  cutoff or a top-one-only rule.

The result distinguishes termination from evidence. Termination is exactly
`SUPPORTED_EARLY` or `EXHAUSTED` (`ConceptScanTermination`); there is no
`UNCERTAIN` termination value. A supported early stop records
`ConceptScanCompletion.SUPPORTED_EARLY` plus the concept's work key; an
exhausted scan records `EXHAUSTED`, whether it found support on the final
candidate or only unresolved evidence. An interrupted scan keeps a cursor and no
completion claim. Support and uncertainty are persisted separately in the
assessments, so one scan can be `SUPPORTED_EARLY` while carrying both a supported
witness and previously encountered uncertainty. The durable record also stores
how many candidates were retrieved and examined in the pages actually read.
Those counts are not a count of the unseen tail: only the fact that the Note has
an unchecked tail is persisted (`CanonicalConceptEvidence.uncheckedRevisions`).

Membership and passage coverage are separate:

- `matchingComplete` means every accepted source Note reached a terminal,
  *resolved* assessment under the current policy: either a verified member or an
  exhausted retrieval/negative result. It is derived from
  `scannedRevisions`/`uncertainRevisions`, never from the set of Notes that
  support the concept. A Note with only unresolved evidence keeps it false.
- `passageCoverageComplete` is true only when `matchingComplete` is true and no
  Note has an unchecked tail. Discovery presents and describes coverage as
  incomplete while any tail remains.
- A verified positive member is published normally; its unchecked tail is never
  presented as unsupported, absent or checked.

Both callers use the same bounded verifier: pending proposal corpus verification
(`retrievePendingConceptPage` / `scanPendingConceptNote`) and existing canonical
membership expansion (`scanExistingConceptPage` / `advanceDurableConceptScan`).
Early support advances to the Note's next concept instead of forcing the
unchecked passage tail, and a supported Note is eligible for canonical
membership without exhaustive passage coverage.

Durable replay never generates a support decision. `replayConceptScanPage` and
`replayPendingNoteScan` replay with `TopicSupportRun.Unavailable`, compare the
derived supported/uncertain sets, early-stop keys and completion claim against
the checkpoint, and reject a truncated or contradictory record. There is one
current checkpoint contract: `ExistingConceptScanRecord.formatVersion =
CONCEPT_SCAN_FORMAT_VERSION` (4) and `PendingNoteScanRecord.formatVersion =
PENDING_NOTE_SCAN_FORMAT_VERSION` (5); `existingConceptScanScope` and
`pendingNoteScanBinding` carry matching version prefixes. A file from an older
format is not deserialized or reinterpreted: the store rejects it on write and
ignores it on read, so the scan is rebuilt from current sources while its
exact-input support assessments stay in the support store and are reused without
inference. No legacy migration or compatibility branch exists.

A completed commit advances the catalog generation, so the scheduler's existing
one-bounded-restart path may rebuild a completed scan's scope; that restart still
reuses support.

`CanonicalMembershipScheduler` scans remaining Notes after an early stop. A
scanned Note with an unchecked tail is a distinct `TAIL` work kind, not
membership work: the scheduler reaches `Idle` for membership, then runs the tail
as an exhaustive pass (never early-stopping, reusing the support cache) after at
most two membership scheduler steps, including steps that only return `Pending`.
Tail restart is owned by the durable checkpoint, not an in-memory first-turn
flag: a partial multi-page tail resumes its cursor across automatic continuation,
unrelated catalog generations and process recreation; a completed
`SUPPORTED_EARLY` checkpoint with a pending tail restarts exhaustively; a
completed `EXHAUSTED` checkpoint commits without rescanning. `matchingComplete`
is derived only when every current Note has a resolved outcome. The durable
concept-scan scope excludes the global catalog generation and the live catalog
epoch; a stale completed tail is rescanned with `restart = true`, and a bounded
tail commit replay resumes its cursor across steps. The membership
active-work scope excludes retry counters and the global catalog generation, so
automatic continuation preserves an in-flight `AwaitingValidation` scan or replay;
explicit retry only clears blocked work. The proposal-catalog identity used to
release a blocked proposal page covers canonical readiness, the canonical
query/verifier/wording, seed members and continuity. The evidence-first
proposal scheduler still skips an admitted proposal without repeating admission;
automatic continuation resumes available pages without reopening a block, while
the explicit Retry action may reopen one.

Membership expansion and new-subject formation are independent bounded jobs. An
incomplete, blocked or uncertain membership check must not starve the other job
(§248). A page-level `UNCERTAIN` result for one existing subject also does not
suppress proposals for a different subject; each new proposal still needs its own
supported citations and the normal admission checks. The scheduler keeps the
uncertainty in membership coverage rather than treating it as proof that the
Note has no other subject. The full state model — termination versus verifier
decisions, derived coverage facts, typed and idempotent admission, deferral
versus blocked work and scheduling fairness — is specified in the
[work-state model](discovery-v2/discovery-work-state-model.md) (§249).

### Early Emerging admission with partial source coverage

Admission is now a typed decision (`ProposalAdmissionCoverage`), not the broad
`blocksConceptAdmission()` predicate. `SOURCE_SCAN` and an unresolved
`EXISTING_CONCEPT_CATALOG` are coverage facts, not evidence invalidity:
`allowsEmerging(citationsSupported)` requires every required citation to be
independently verified, the catalog generation to be consistent, and the
duplicate check to be complete. It does not require all cached Notes to be
indexed. `allowsEstablished` also requires complete source coverage.

`EXISTING_CONCEPT_UNCERTAIN` and `STALE_SOURCE` remain evidence-invalidating and
block publication. A ticketless or inconsistent catalog still blocks, so a
provisional catalog cannot admit until a consistent generation and duplicate
check are available. A later proposal with the same canonical name/definition
returns the existing topic id instead of creating a duplicate; membership
expansion adds later Notes to that topic.

Early topics persist their `SOURCE_SCAN` reason and remain
`matchingComplete = false` with incomplete passage coverage. A topic with
verified members but incomplete coverage is presented as Emerging, not Pending;
Established still requires the existing complete conditions. Indexing and
membership expansion continue independently. The Discover/Topics status now
offers a visible Retry processing action when scheduling has deferred work.

Support keys cover concept work identity, name/definition, exact body, source
Note/passage/revision and verifier artifact/tokenizer/template/prompt/schema/policy/
backend. They also include topic ID. After admission, an exact pending assessment
can be revalidated and durably saved under the canonical ID. An existing canonical
decision takes precedence; refused persistence never becomes completed work.

Admission can reuse a current process-local corpus replay result after durable
citation revalidation. The callback is never persisted as proof. Restart takes the
source/storage validation path again. `TopicCoordinator` owns guarded catalog writes;
`hydrateCanonicalTopic` reads saved evidence without inference.

A supported citation can establish Emerging while coverage remains incomplete.
`CanonicalMembershipScheduler` continues bounded checks and guarded updates.
Unchecked Notes are not marked scanned; unsupported or uncertain passages do not
become members. Admission can shrink the unexplained-anchor list, so the proposal
scheduler resets its numeric offset once per admission and skips the exact admitted
proposal within the current source scope. Durable canonical records and verified
support remain authoritative after restart, not this process-local skip set.

## Synthetic evidence

`DiscoverySupportScaleTest` exercises shipping candidate paging and verification
with 20,000 passages, 2,000 Notes and 35 concepts. Each Note has two retrievable
passages for one concept: the strongest is supported and the second is
unsupported. Eight other passages are below threshold. Page size is one, forcing
continuation. The test runs the same fixture through two arms:

- **exhaustive:** the pre-change all-candidate algorithm, using the same shipping
  pager and verifier;
- **support-first:** the shipping bounded algorithm.

| Pass | Exhaustive fresh | Support-first fresh | Support-first reused |
| --- | ---: | ---: | ---: |
| Cold | 4,000 | 2,000 | 0 |
| Unchanged warm | 0 | 0 | 2,000 |
| One Note revision changed | 2 | 1 | 1,999 |
| Verifier policy changed | 4,000 | 2,000 | 0 |

Both arms verify 2,000 positive Note memberships from 2,000 supported decisions
per cold pass. Exhaustive also records 2,000 unsupported decisions; support-first
records zero because the weaker passage is unchecked, not unsupported. The
support-first cold pass has 2,000 early-stopped scans, zero fully exhausted scans
with candidates, and 2,000 Notes with an unchecked tail. It reaches the same
2,000 verified members with 1.0 fresh call per member instead of 2.0.

The fixture has exactly two eligible candidates per matching Note; 34 of the 35
concepts retrieve no candidates for each Note. This shape is a synthetic upper
bound for the first-support saving, not a quality or workload distribution. The
scale double preserves destructive invalidation. File-backed reopening and
canonical handoff have separate tests. These counts do not measure model accuracy,
indexing, proposal generation, cancellation or Android latency, and they do not
show that production ranking usually puts a supported passage first.

## Audit metrics

Numeric, content-free events are emitted only in debug builds. `ReaderApplication`
supplies observers for the native boundary (`DiscoveryInference`) and the work
boundary (`DiscoveryWork`); release builds pass no observer and take no timing
samples. `tools/semantic-discovery/capture_inference.py` records one explicitly
selected device's events and samples `dumpsys meminfo` as `DiscoveryMemory`;
`tools/semantic-discovery/inference_metrics.py` summarizes the captured log. No
prompt, response or source text is logged, and the capture keeps only known numeric
keys.

### Emitted at the native inference boundary

| Event | Written at | Meaning |
| --- | --- | --- |
| `embeddingAttempts`, `embeddingSuccesses`, `embeddingNanos` | one submission | a native embedding call was attempted, returned a valid 768-dimensional normalized finite vector, and its duration |
| `embeddingProbe*` | backend selection probes | fixed public probes; excluded from `inferenceAttemptsPerAdmittedTopic`, included in the native-time total |
| `proposalAttempts`, `proposalSuccesses`, `proposalInvalidOutputs`, `proposalNanos` | proposal submission | input-limit rejection happens before submission and does not increment attempts |
| `supportAttempts`, `supportSuccesses`, `supportInvalidOutputs`, `supportNanos` | support submission | one candidate support check |
| `labelAttempts`, `labelSuccesses`, `labelInvalidOutputs`, `labelNanos` | topic-label submission | label runtime |

An attempted call has no success when it failed, was cancelled or returned invalid
output; absence of a success is not a decision.

### Emitted at the work boundary

| Event | Meaning |
| --- | --- |
| `passagesEmbedded` | a bulk-index embedding after the exact-input memo missed |
| `supportReused` / `supportFreshDecisions` | a durable exact-key decision was reused / the verifier was invoked |
| `supportSupported`, `supportUnsupported`, `supportUncertain` | completed decisions, including reused ones; pair them with the reuse counters |
| `supportInvalidAssessments` | a request or decision failed validation |
| `supportCandidatesRetrieved` / `supportCandidatesChecked` | candidates offered in the page / candidates answered by the verifier or a valid durable decision |
| `conceptScanEarlyStopped`, `conceptScanExhausted`, `conceptScanUncertain` | how the bounded Note+concept page concluded |
| `staleResultDiscards` | a result was rejected by a validity guard |
| `schedulerRestarts` | the proposal scheduler rebuilt its scope |
| `topicsAdmitted` | a new canonical topic was written |
| `corpusReplayNanos`, `canonicalAdmissionNanos`, `firstPublishedTopicNanos` | replay, admission and first-new-topic durations |

Scheduler-state events (`evidenceInspect`, `evidenceProcess`, `evidenceStop`,
`evidenceScheduler`, `domainDeferred`) are emitted but are not in the summarizer's
whitelist; they are not inference metrics and are absent from captures.

### Derived summary

`summarize` reports per-key `samples`, `mean`, `min`, `p50`, `p95` and `max` for
durations and memory, plus:

- `supportCacheHitRate` = `supportReused / (supportReused + supportFreshDecisions)`;
- `inferenceAttemptsPerAdmittedTopic` = non-probe attempts / `topicsAdmitted`;
- `totalObservedNativeInferenceMillisecondsIncludingProbes`;
- `nativeDurationSamplesMatchAttempts` per observed kind, which detects a capture
  that split an attempt from its duration at the interval boundary;
- `malformedMetricLines`.

A `null` metric means the denominator was absent (no support decisions, no
admission), not zero cost. A capture that starts mid-call can report one more
success than attempt, or one more duration than attempt. The 300 s capture shows
that on `embeddingProbe`, the 600 s capture on `embedding`; both are flagged by
`nativeDurationSamplesMatchAttempts`, which is the signal that the interval is not
internally closed.

### What these metrics can and cannot establish

- They measure observed work in one debug interval on one device. Missing samples
  are not zero latency, and sums across intervals are not per-run totals.
- Native attempt counts are not semantic quality. `supportSupported` counts the
  verifier's decisions, not their correctness.
- Synthetic fake-inference counts are not native calls, tokens, battery or
  wall-clock time.
- Sampled `dumpsys meminfo` values are context, not the added-memory acceptance
  gate; the contract requires allocation-level evidence.
- A Pixel 8 Pro result is not an S25 result, and neither is an emulator result.
- The summarizer's whitelist silently omits unknown keys, so absence of a key is
  not evidence that the event never occurred.

### Measured so far

Retained captures under ignored `build/discovery-measurements/`:

| Device | Interval | Embedding attempts | Successes | Mean ms | p50 ms | p95 ms | Max ms | Proposal/support attempts | Topics admitted |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Pixel 8 Pro, SDK 37 | 300 s | 877 | 877 | 135.7 | 135.2 | 151.8 | 184.5 | 0 | 0 |
| Pixel 8 Pro, SDK 37 | 600 s | 1,732 | 1,733 | 138.0 | 136.7 | 153.0 | 211.2 | 0 | 0 |
| Pixel 8 Pro, SDK 37 | support-first capture (about 14 min) | 1,712 | 1,711 | 139.2 | 137.8 | 155.4 | 348.0 | 2 proposal / 4 support | 0 |

The user's working figure of 526 embedding calls at a 135 ms mean is consistent
with this rate. The first two captures report `null` support-cache and
attempts-per-admission metrics because no proposal or support call and no
admission occurred.

The third capture is the first real Gemma proposal/support observation after the
support-first change. It was run on a Pixel 8 Pro with the installed 2.4 GiB topic
model and 100 cached Notes; the Discovery index was cleared first so the run was
cold. Measurements from `inference_metrics.py` over the captured interval:

| Observation | Value |
| --- | ---: |
| `proposalAttempts` / successes | 2 / 2 |
| `proposalNanos` mean / min / max | 17.09 s / 16.34 s / 17.84 s |
| `supportAttempts` / successes | 4 / 4 |
| `supportNanos` mean / p50 / p95 / max | 1.566 s / 1.499 s / 1.770 s / 1.770 s |
| `supportReused` / `supportFreshDecisions` | 3 / 4 |
| `supportCacheHitRate` | 0.4286 |
| `conceptScanEarlyStopped` events | 3 |
| Candidates retrieved / checked at each early stop | 8 / 1 |
| `schedulerRestarts` | 1 |
| Topics admitted | 0 |

The three early stops are the shipping bounded behavior: the persisted pending
checkpoint at capture time was `formatVersion = 3` (the current contract uses 5), `completion = SUPPORTED_EARLY`,
`retrievedCandidates = 8`, `examinedCandidates = 1`, one supported passage and no
uncertainty. Two early stops reused a decision from the earlier citation check,
so they spent zero additional Gemma support calls. Only 1 of the 100 Notes was
indexed when the capture ended, and the catalog still contained 0 topics; no
admission occurred, so advances-per-admission remains `null`. This is Pixel
evidence, not S25 evidence, and the capture's `nativeDurationSamplesMatchAttempts`
reports `embedding=false` because the interval boundary split one embedding
attempt from its duration.

The tracked `SupportFirstDeviceTest` adds an isolated two-Note real-Gemma probe.
On the same Pixel it observed 4 proposal attempts, 11 support calls with a mean of
1,413 ms, 23 early-stopped concept scans and 23 checked candidates (one per stop),
and a published topic with 2 independently verified members. The tiny fixture
finished with `matchingComplete = true` and `passageCoverageComplete = true`, so
it is not an early-publication measurement; it shows the bounded verifier and
canonical membership working on the real runtime. The test skips when the large
model is not installed.

Extrapolation, not a benchmark: 20,000 cold embeddings at 135–138 ms is about
45–46 minutes of native embedding alone, before storage, scheduling, proposal and
support work. It assumes the mean of an 877–1,732-call capture holds when scaled to
20,000 passages (about 12–23×) and that passage length does not change it. The
Pixel support mean of 1.566 s is not an S25 latency.

### Metrics for the bounded per-Note rule

The shipping work boundary now emits `supportCandidatesRetrieved`,
`supportCandidatesChecked`, `conceptScanEarlyStopped`, `conceptScanExhausted` and
`conceptScanUncertain`. These are per bounded page, not a claim about the unseen
tail. The durable Note record separately stores how many candidates were
retrieved and examined in the pages actually read, plus the set of concepts whose
tail is unchecked.

| Event | Status | Definition / limit |
| --- | --- | --- |
| `supportCandidatesRetrieved`, `supportCandidatesChecked` | emitted per page | offered and examined candidates in the page; sum across pages for a pass lower bound |
| `conceptScanEarlyStopped` | emitted | the page stopped on a verified supported decision with an unchecked tail |
| `supportCandidatesEligible` | not emitted | threshold-eligible total for a Note+concept; required for an exact unchecked count |
| `supportCitationChecks` | not emitted | selected citations checked before admission |
| `supportFirstCandidateSupported` / `Unsupported` / `Uncertain` | not emitted | whether the strongest candidate alone establishes membership |
| `supportChecksAfterFirstSupported` | not emitted and now zero by construction | pre-change evidence only; the rule cannot spend these checks |
| `supportCandidatesUnchecked` | persisted as a Note-level fact, not a passage count | `CanonicalConceptEvidence.uncheckedRevisions` marks the Note; the exact tail length needs the eligible total |
| `supportLaterUnsupportedAfterSupported` | not emitted | explicit bounded audit sample only |
| `conceptsPublishedWithUncheckedCandidates` | not emitted | admission does not require exhaustive passage coverage |

`supportAttempts` covers both proposal-citation checks and retrieved-candidate
checks; the per-Note counters cover only the candidate pass, so their totals will
not equal `supportAttempts`. `supportCitationChecks` would make the proposal-side
cost visible if the one-or-two-citations prompt policy changes; the current
proposal instruction already permits a subset of offered evidence and the schema
allows one citation.

`supportChecksAfterFirstSupported` was the direct pre-change measure of the
saving. It is now structurally zero: after a completed supported decision the
loop stops. The synthetic 2-candidate shape spent 2,000 such checks before the
change; that is the measured saving, not an estimate of production ranking.

`supportLaterUnsupportedAfterSupported` remains the trust-risk measurement:
whether a supported strongest passage commonly coexists with a weaker candidate
the verifier rejects. It is a coarse proxy, because a weaker candidate can be
legitimately unrelated to the concept rather than contradictory, so a high rate
is not by itself evidence of hidden contradictions. It is observable only in an
explicit bounded audit sample that continues checking. This implementation does
not run such a sample, so the risk is unmeasured and post-change counters cannot
reconstruct it.

## Open collection and acceptance

Real proposal and support latency and throughput, cold/warm indexing at scale,
interrupted-processing behavior, exact eligible-versus-checked counts, the
continued-check audit sample, allocation-level memory, battery and pressure
behavior, and representative quality gates remain open. The 2026-11-07 captures
add device embedding throughput only; they do not close those gaps. Synthetic,
emulator and Pixel results cannot establish S25 timing, resource behavior or human
TalkBack acceptance.


Review correction verification (§245): 1,723 JVM tests passed with zero failures,
errors or skips; debug assembly and Android-test Kotlin compilation passed.
Two controller-level regressions prove two-member partial-source publication and
preservation of unchecked passage tails at admission. Restoring the old guards
made both fail. The bounded tail continuation and scheduler regressions also failed
before their fixes. Device measurements above predate these corrections.

### Live partial-index Pixel validation (post-§245)

The current debug build was installed on a Pixel 8 Pro (Android 17) with 109
cached Notes and an empty derived Discovery index. At first publication the UI
reported 3 of 109 Notes indexed and 13,839 passages remaining, and showed
“Emerging topics — 1 matching Note; matching incomplete.” Persisted state held one
graph topic with one member, one supported citation, `matchingComplete = false`,
one unchecked revision and `SOURCE_SCAN`/`EXISTING_CONCEPT_CATALOG` incomplete
reasons. The same topic id survived force-stop and relaunch; no duplicate was
created.

The captured interval had 1 `topicsAdmitted`, 1 proposal attempt (11.47 s), 13
support attempts (mean 1.686 s, p95 2.458 s), 20 reused support decisions, 4
supported / 23 uncertain / 6 unsupported decisions, 2 stale-result discards and
365 embeddings (mean 269.2 ms under concurrent indexing). `canonicalAdmissionNanos`
was 1.37 s, `corpusReplayNanos` 29.7 ms and the support cache hit rate 0.606.
Native attempt/duration counts closed for embedding, proposal and support.

This validates early Emerging publication on a real partially indexed Pixel. It
does not establish S25 latency, full 109-Note completion, tail-audit cost after
all sources finish, resource ceilings or human TalkBack acceptance.

### Membership liveness after the live retry

The post-§245 live run exposed a liveness regression: a blocked uncertain Note
was re-opened by automatic continuation because the blocked-page scope included
the retry generation, and the automatic path used the explicit retry API. The
scheduler now retains a blocked page while its own source revision is unchanged;
automatic continuation does not clear it, while the visible Retry action does
(§246). After the fix the same Pixel run resumed continuous embedding
(`passagesEmbedded`) instead of repeating support scans. This is a liveness fix,
not evidence that all 109 Notes or their tails have completed.
