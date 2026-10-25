# Discovery v2 — evidence, generic headings, scoring and publication

**Status:** algorithm proposal; no weights, thresholds or feature functions are validated on production Notes. This document specifies proposed **observable** behavior for R01–R24 and R41 rather than claiming machine comprehension.

## A. Non-negotiable evidence model

**Ground truth:** a current, accessible, local, body passage tied to (canonical Note ID, section ID, source block/chunk ID, offsets, current source revision, embedding model+tokenizer identity). Keep the full excerpt in the indexed source store; derived records should reference it rather than duplicate private text.

**Eligible:** substantive body text, including explanatory paragraphs or sufficiently meaningful standalone statements. Heading-only and navigation/link-only blocks are not body evidence. The shipping substantiveDiscoveryEvidence/isBodyEvidence filters are a compatibility baseline, **not a complete usefulness detector**.

**Not independent:** repeated section headings, copies, boilerplate, near-duplicate paraphrases, title siblings, shared metadata Area/Topics, or embedding context prefixes.

**Topic definition:** a candidate names a distinguishable issue, method, concept, mechanism, relationship or substantive field; its included member passages must be valid body support. A “generic heading found often” is neither support nor a candidate topic by itself. General subjects are allowed only when supported independently as concepts; narrow child topics must survive.

## B. Body-first coherence and heading-leak prevention

The existing SemanticChunker produces inputs including title and section context. For topic quality, do **not** score “same heading” as body agreement.

1. Retain existing contextual embeddings for efficient initial retrieval only.
2. Evaluate title/heading-independent **body evidence** for every publication candidate. First use normalized body tokens, discriminatory body terms, source diversity and supporting passage semantics; reject solely heading-driven matches.
3. If the body signal is ambiguous, optionally generate/cached a **body-only embedding** for representative, bounded passages; compare trimmed body vectors. Count resulting inference separately. Do not promise zero new embeddings in this fallback.
4. A stricter alternative is a body-only embedding index if calibration proves it is economical; any switch must be versioned and evaluated.
5. Derive coherence from a robust distribution (e.g., median and lower quartile of distinct representative passage-to-prototype similarities), not a mean susceptible to outliers. Handle single-Note evidence with intra-note distinct passages or abstain when insufficient.
6. Reject unsupported members independently; do not allow one weak paragraph to enter by chain/transitive association. Multi-topic Notes need different passages supporting each relevant subject.

No hard-coded cross-model cosine threshold is authoritative. Prototype against MiniLM-384 and Gemma-768 separately; the current .35 formation distance/.60 membership thresholds are baseline numbers only.

## C. Generic-heading detection (hard gate + specificity feature)

**Normalize** heading with Unicode normalization, case fold, punctuation/whitespace cleanup and safe length bounds. Keep locale and original form for display.

**Structural lexicon:** initial English list: introduction, overview, summary, conclusion, details, content, implementation, architecture, design, approach, background, notes, references, related notes, next steps, benefits, challenges, examples, error handling. Lexicon is *not* a list of forbidden concepts in every context; “Error Handling” could be a valid independently grounded broad subject with specific explanatory evidence, but not be published solely as a label without specificity.

**Corpus frequency:** N = number of eligible indexed distinct Notes; DF(h) = distinct Notes containing normalized heading; suggested smoothed IDF(h) = ln((N+1)/(DF(h)+1)). Treat low IDF as supporting evidence of structural genericity, never automatic rejection of a frequent domain-specific term. Avoid unreliable DF estimates in a tiny/incomplete corpus.

**Separate label from evidence:** a structural/generic candidate heading is a *rejected final label* until replaced by a specific supported phrase; do not discard the underlying candidate solely because its author used a generic heading. Shared headings grant **zero independent evidence support**. Confirm body agreement with body-derived signals.

**Cases:**

| Body + headings | Expected result |
|---|---|
| Twelve unrelated sections all called “Implementation” | No “Implementation” topic; unrelated bodies do not form one topic |
| Four RabbitMQ body passages under “Implementation”/“Reliability”/“Architecture” | May form “RabbitMQ Message Acknowledgment” if exact supported phrase/evidence exists |
| Three Notes all titled “Service Design — ...” with repetitive shared template | Prefix family alone not proof; separate genuinely different subtopics |
| Three differently named Notes explain the same idempotent queue retry | Should match if body evidence genuinely supports it |
| One comprehensive Note explains a narrowly scoped concept | Eligible as Emerging after hard gates and calibrated quality |

## D. Explanatory-substance feature

Extract bounded, language-aware patterns from *body excerpts*: definitions (“X is …”), mechanisms (“X sends/acknowledges Y”), cause/effect (“because/therefore”), conditions (“if/unless/when”), comparisons/trade-offs (“however/versus/at the cost of”), and worked examples (“for example/consider”). Prefer evidence **variety** and distinct grounded statements over keyword repetition or raw length. A phrase hit alone is not factual verification; an explanatory trigger without discernible content receives no automatic full credit.

Do not punish formulas, code examples, bullet explanations, non-English prose or imperative procedures merely because they lack English pattern tokens. If detection is unsupported for a language or text style, mark the feature **unknown** and abstain/review; do not silently assign zero or a perfect score. Include counterexamples (negation, hypothetical statements, empty template verbs, boilerplate “because …” lines).

## E. Feature contract and experimental weights

Each feature returns (score in [0,1] or Unknown, explanatory reasons, evidence references, feature_version, model/policy/corpus identity). No NaN/Inf; no silent imputation of missing critical evidence.

| Feature | Weight | Proposed computational measurement |
|---|---:|---|
| C: body semantic coherence | 25% | Robust normalized distribution of relevant distinct passage similarity plus weak-member penalty; body-sensitive, calibrated per embedding identity |
| S: concept specificity | 20% | Body-level class-based TF-IDF / contrastive term distinctiveness against other candidates; penalize structural/container vocabulary and excessively broad candidate prototypes |
| E: explanatory substance | 20% | Bounded pattern classes, explanatory density, variety, grounded phrases; language-specific and confidence-aware |
| L: label evidence | 15% | Literal source heading/phrase attestation, body support, specificity, coverage; a heading alone cannot certify label |
| I: independent support | 10% | Number/diversity of distinct Notes, sections and independently informative passages with near-duplicate collapsing and saturating transforms |
| D: distinctiveness | 10% | Nearest existing-topic similarity + member/source overlap + contrastive body terms; preserve genuinely independent subtopics |

Prototype Q = 100 × (0.25C + 0.20S + 0.20E + 0.15L + 0.10I + 0.10D). Weights configurable in a versioned, immutable policy object; verify weights non-negative, sum >0, normalize before use. Feature normalization mappings are **fit only on training/development fixtures** and frozen for held-out evaluation. Per-provider calibration required. Do not interpret Q as P(topic useful) or assume score portability across models/languages.

### Independent support and duplicate saturation

1. Collapse identical content by normalized body-input hash; near-duplicates by bounded token-window overlap (existing overlap code is a starting point), revision-safe similarity and polarity consistency. Cross-source copies must not masquerade as independent explanations.
2. Compute effective support from unique independently informative passages, sections and canonical Notes. Saturate contributions (e.g., f(n) = 1 − exp(−n/k), k learned on development fixtures) instead of adding linear vote weight.
3. Keep true independent themes: two concepts may reference identical Notes but use disjoint, non-near-duplicate spans; do not merge solely by Note-set overlap.
4. One well-supported Note can still publish Emerging. Note count affects status, not truth.

## F. Label extraction and validation

1. Generate candidates from supported non-structural section headings and bounded literal n-grams in **eligible body passages**, with distinctive body-term ranking and original language preserved.
2. Prefer specificity and verified body attestation. A literal phrase must appear in current source evidence; no generative rewriting and no title-only creation.
3. Reject generic labels, incomplete grammatical edges, misleading title-family prefixes, pronoun-dependent fragments and labels unsupported by the topic's representative evidence.
4. A candidate can be semantically good yet have **no reliable label**: remain provisional with reason MISSING_SUPPORTED_LABEL; do not invent one.
5. Re-evaluate candidate labels on source revision; preserve existing user-chosen display aliases independent of source-derived identity only where safely mapped.

## G. Hard gates, states and reasons

Before Q evaluation/publication require: CURRENT_SOURCE, ELIGIBLE_BODY, BODY_COHERENCE, SPECIFIC_CONCEPT, SUPPORTED_LABEL, VALID_MEMBERSHIPS, NOT_DUPLICATE_OF_EXISTING and SAFE_MODEL_POLICY. Unknown critical evidence → Provisional/ABSTAIN, not acceptance. Broad subject cannot inherit a child's body support without independent parent evidence.

Suggested Q bands **for experiment only**: Q >=75 publish after all gates; 55<=Q<75 provisional; Q<55 suppress. Gate failures classified separately:
- recoverable naming/coverage/unknown language → Provisional;
- demonstrably generic/unrelated/no body evidence → Suppressed;
- source missing/revoked → Purge, not ordinary Suppressed retention.

Publication then distinguishes one distinct supporting Note → Emerging and >=2 → Established; it is not a separate semantic quality criterion. A suppressed candidate must be reconsidered only on new evidence/policy revision, avoiding repeated loop work.

Every decision stores reason codes, per-feature score/Unknown, provenance/source revision, policy, and a scrub-safe display explanation; debugging export must not leak private text.

## H. Personal relevance (separate)

Intrinsic Q does **not** include recency, number of opens, reading duration, click counts, or followed interests. Proposed optional relevance R = 0.45 activity + 0.35 explicit follows + 0.20 recent add/edit match, and presentation order = 0.70Q + 0.30(100R). **These numbers are not approved behavior**: activity-based ranking requires governance/consent and data retention review first. Keep an interest-only or neutral fallback; no activity-derived membership, publication or deletion.

## I. Calibration and safety check

Calibrate each feature scale, thresholds, contribution and overall ranking with human-reviewed positives/negatives. Ablate each feature, test robustness to identical headings/different bodies and identical bodies/different headings, and use per-language and per-model slices. Report unknown/abstain rate, false publication and missed good topic rates. See [evaluation](evaluation-acceptance.md).