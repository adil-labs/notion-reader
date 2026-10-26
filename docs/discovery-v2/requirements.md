# Discovery v2 — requirements baseline (R01–R49)

> **Current direction:** The local Gemma 4 E2B / LiteRT-LM replacement and requirement dispositions are defined in [generative-direction.md](generative-direction.md) under the amended governing Notion contracts. Deterministic-only, literal-label-only and activity-ranking passages in this original proposal are superseded where they conflict. This file preserves the original proposal for comparison; it is not the execution authority.

**Status:** proposed specification; quality/latency acceptance not yet measured. This preserves the 49 requested requirements, with Must/Should priorities. A design reference does **not** mean a passing test. See [traceability](traceability.md) for implementation ownership and verification.

## A. Topic quality and identification

| ID | Priority | Requirement |
|---|---|---|
| R01 | Must | Discover meaningful concepts, not merely semantically similar text. |
| R02 | Must | Reject generic topics such as “Implementation”, “Overview”, “Architecture” or “Details” unless a more specific concept can be identified. |
| R03 | Must | Repeated headings across Notes must not independently establish a topic. |
| R04 | Must | Determine semantic coherence primarily from body content rather than shared headings. |
| R05 | Must | Recognize the same concept even when different Notes use different terminology or headings. |
| R06 | Must | Identify specific subtopics instead of merging everything into broad subjects. |
| R07 | Must | Allow one Note to contribute to multiple topics through different passages. |
| R08 | Must | Preserve worthwhile single-Note topics as Emerging rather than rejecting them automatically. |
| R09 | Must | Do not force unrelated or weakly matching passages into an existing topic. |

## B. Evidence and weighted quality

| ID | Priority | Requirement |
|---|---|---|
| R10 | Must | Introduce a deterministic, weighted Concept Quality Scoring Engine. |
| R11 | Must | Measure semantic coherence, specificity, explanatory substance, label evidence, independent support and distinctiveness. |
| R12 | Must | Normalize individual feature scores and make weights configurable internally. |
| R13 | Must | Enforce mandatory evidence gates so strong scores cannot compensate for invalid topics. |
| R14 | Must | Identify definitions, mechanisms, causal relationships, conditions, examples and trade-offs. |
| R15 | Must | Use term frequency, document frequency and semantic evidence to detect generic headings. |
| R16 | Must | Apply diminishing returns to repeated or nearly identical supporting passages. |
| R17 | Must | Provide reasons for accepted, suppressed or provisional topic decisions. |
| R18 | Must | Calibrate weights and thresholds from human-reviewed topics; do not treat initial values as validated. |

**Initial weights only:** coherence 25%, specificity 20%, explanatory substance 20%, label evidence 15%, independent support 10%, distinctiveness 10%. Weights are tunable and must sum to one after normalization. No feature weight asserts probability or validity.

## C. Generic headings and names

| ID | Priority | Requirement |
|---|---|---|
| R19 | Must | Headings are context clues, not proof of shared concepts. |
| R20 | Must | Detect generic headings using structural patterns and corpus statistics. |
| R21 | Must | Generate source-supported, distinctive names without a generative LLM. |
| R22 | Must | Avoid generic, excessively broad, misleading or Note-title-derived labels. |
| R23 | Must | Prefer a specific supported concept over a generic parent heading. |
| R24 | Must | Preserve stable topic identities and user customizations when evidence changes. |

## D. Incremental processing and efficiency

| ID | Priority | Requirement |
|---|---|---|
| R25 | Must | Prioritize recently added, modified and opened Notes. |
| R26 | Must | Match new evidence to existing topics before new-topic formation. |
| R27 | Must | Search older indexed Notes as supporting evidence for recent changes. |
| R28 | Must | Avoid re-embedding unchanged content. |
| R29 | Must | Avoid repeated whole-library clustering after small changes. |
| R30 | Must | Maintain an unmatched-candidate pool for not-yet-publishable evidence. |
| R31 | Should | Support periodic low-priority reconciliation to detect missed relationships. |
| R32 | Must | Bound indexing, matching, scoring and storage by device resource budgets. |
| R33 | Must | Preserve resumability, cancellation, battery policies and foreground-query priority. |
| R34 | Must | Prevent Discovery from degrading TTS or app responsiveness. |
| R35 | Must | Keep Note content and embeddings on-device. |

## E. Organization and personalization

| ID | Priority | Requirement |
|---|---|---|
| R36 | Must | Support major subjects and specific subtopics. |
| R37 | Must | Preserve meaningful inter-topic relationships without incorrect merging. |
| R38 | Must | Calculate intrinsic topic quality separately from personal relevance. |
| R39 | Must | Use recent activity and followed interests for display order, not topic validity. |
| R40 | Must | Keep useful older topics discoverable even if not recently accessed. |
| R41 | Must | Avoid duplicate topics while retaining independently supported related concepts. |
| R42 | Should | Distinguish Established, Emerging, Provisional and Suppressed candidates appropriately. |

## F. Validation, explainability and accessibility

| ID | Priority | Requirement |
|---|---|---|
| R43 | Must | Test real and synthetic Notes with generic headings, overlapping subjects and unrelated concepts. |
| R44 | Must | Measure useful-topic precision, missed topics, label quality and membership accuracy. |
| R45 | Must | Compare v2 against the present engine on identical evidence. |
| R46 | Must | Record execution time, comparisons, memory, embedding calls and storage overhead. |
| R47 | Must | Make scores, reasons and supporting passages inspectable. |
| R48 | Must | Make topics, explanations, progress and controls screen-reader accessible. |
| R49 | Must | Safely remove/replace revisions and revoked evidence so removed text cannot survive in published topics. |

## Non-goals / constraints

No new generative LLM, classifier, cross-encoder, cloud inference, silent whole-workspace fetching, generative naming, automatic queue mutations or unrelated architecture rewrite. Do not claim semantic correctness from a score alone. An unmatched or ambiguous note remains searchable/readable even without a topic. Respect the existing Notion product/reader/queue contracts.

## Acceptance outcomes

- Generic headings recurring in unrelated Notes never form topics based on headings alone.
- Equivalent concepts can match despite different headings; narrow independent concepts survive broad parent terminology.
- One-Note concepts can be Emerging, multi-topic notes retain independent passage evidence, and weak memberships abstain.
- New/changed Note updates use bounded incremental work; older Notes remain available as evidence; full reconciliation is opportunistic.
- Source deletion/revocation promptly withdraws every derived candidate, index, topic, relationship and preview exposure.
- Human-labeled quality improves relative to baseline with no unacceptable recall loss; device cost, TTS and TalkBack gates have recorded evidence.
- Every published topic has verifiable supporting passages and a reasoned publication decision.

For executable cases, thresholds and release gates, see [evaluation](evaluation-acceptance.md); for implementation steps see [plan](implementation-plan.md).