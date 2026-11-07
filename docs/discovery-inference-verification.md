# Discovery inference and evidence verification

Derived implementation audit; the governing contracts retain precedence.

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

The governing contract now permits bounded per-Note verification (Product and
Integration amendments, 2026-11-07): one independently validated supporting
passage establishes membership, the strongest candidate is submitted first,
further candidates are submitted only while the decision is unsupported or
uncertain, and the Note's remaining candidates stay unchecked reconciliation work
with coverage marked incomplete. **The implementation still verifies every
threshold-eligible candidate for a Note.** The amendment authorizes the cheaper
behavior; it is not implemented, tested or measured here.

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
passages for one concept: one supported and one unsupported. Eight other passages
are below threshold. Page size is one, forcing continuation.

| Pass | Fresh fake support calls |
| --- | ---: |
| Cold | 4,000 |
| Unchanged warm | 0 |
| One Note revision changed | 2 |
| Verifier policy changed | 4,000 |

The scale double preserves destructive invalidation. File-backed reopening and
canonical handoff have separate tests. These counts do not measure model accuracy,
indexing, proposal generation, cancellation or Android latency. Each Note
contributes exactly two candidates, so the synthetic shape bounds a first-support
saving at half of the cold calls (2,000) when the supported candidate ranks first;
the realized saving depends on that ranking, and the test does not report it.

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

The user's working figure of 526 embedding calls at a 135 ms mean is consistent
with this rate; the retained captures are larger. Both captures report `null`
support-cache and attempts-per-admission metrics because no proposal or support
call and no admission occurred, so real proposal and support latency remains
unmeasured.

Extrapolation, not a benchmark: 20,000 cold embeddings at 135–138 ms is about
45–46 minutes of native embedding alone, before storage, scheduling, proposal and
support work. It assumes the mean of an 877–1,732-call capture holds when scaled to
20,000 passages (about 12–23×) and that passage length does not change it.

### Metrics required to audit the bounded per-Note rule

None of these are emitted today. The counterfactual ones must be measured on the
current all-candidate behavior, because the redundant checks they count disappear
when the rule is implemented.

| Proposed event | Boundary | Definition |
| --- | --- | --- |
| `supportCandidatesEligible` | per Note pass | threshold-eligible candidates for that Note and concept |
| `supportCandidatesChecked` | per Note pass | candidates submitted to the verifier |
| `supportCitationChecks` | per proposal | selected citations checked before admission |
| `supportFirstCandidateSupported` / `Unsupported` / `Uncertain` | first check in a Note pass | whether the strongest candidate alone establishes membership |
| `supportChecksAfterFirstSupported` | each check | checks spent on a Note that already has a supporting decision |
| `supportCandidatesUnchecked` | per Note pass | eligible minus checked; must agree with the retained reconciliation work |
| `supportLaterUnsupportedAfterSupported` | explicit audit sample only | a weaker checked candidate is unsupported after a supported one |
| `conceptsPublishedWithUncheckedCandidates` | admission | admissions whose Note passes remain unfinished |

`supportAttempts` covers both proposal-citation checks and retrieved-candidate
checks; the per-Note counters cover only the candidate pass, so their totals will
not equal `supportAttempts`. `supportCitationChecks` makes the proposal-side cost
visible if the one-or-two-citations policy is adopted.

`supportChecksAfterFirstSupported`, measured within a pass, is the direct measure
of the saving: it is the extra check the proposed rule would not make. After the
rule ships it counts only later reconciliation checks, so the pre-change
distribution is the evidence. In the synthetic 2-candidate shape the ceiling is
2,000 of 4,000 cold calls. `supportCandidatesUnchecked` is the coverage cost and
must agree with the persisted reconciliation record; if the two disagree, the
summary is not evidence.

`supportLaterUnsupportedAfterSupported` is the trust-risk measurement: whether a
supported strongest passage commonly coexists with a weaker candidate the verifier
rejects. It is a coarse proxy, because a weaker candidate can be legitimately
unrelated to the concept rather than contradictory, so a high rate is not by itself
evidence of hidden contradictions. It is observable only while every candidate is
still checked, or in an explicit bounded audit sample that continues checking.
After the rule ships without such a sample, that risk is unmeasured, and
post-change counters cannot reconstruct it.

## Open collection and acceptance

Real proposal and support latency and throughput, cold/warm indexing at scale,
interrupted-processing behavior, the per-Note counters above, allocation-level
memory, battery and pressure behavior, and representative quality gates remain
open. The 2026-11-07 captures add device embedding throughput only; they do not
close those gaps. Synthetic, emulator and Pixel results cannot establish S25
timing, resource behavior or human TalkBack acceptance.
