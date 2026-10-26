# Discovery v2 — adopted generative direction and requirement disposition

Date: 2026-10-25. The owning Product, UI and Integration Notion contracts now contain **Local generative Discovery v2**. This document explains their implementation direction; it does not assert implementation or release acceptance. The original deterministic proposal is historical design input where it conflicts with this direction.

## Pipeline and ownership

Current approved direction (2026-10-26): cached eligible body evidence → existing section-aware grouping/memberships over compatible EmbeddingGemma vectors → local Gemma 4 E2B labels → source-safe publication in the same canonical topic catalog. Label-only is now the target, not merely a control. Full LLM formation is superseded, not deferred as a hidden or selectable production alternative.

Use the [sole-pipeline design](sole-grouping-labeling-design.md) and [current implementation plan](../superpowers/plans/2026-10-26-sole-grouping-llm-labeling.md). Preserve IDs/follows/aliases, existing grouping continuity and one serialized process-owned execution path. The model cannot change memberships or reduce the number of topics by renaming. The table below records the former formation proposal's dispositions; where it assigns LLM concept formation or new retrieval/reconciliation work, this latest direction supersedes that scope. Existing source/quality/privacy/accessibility requirements remain binding.

## Requirement disposition

Every R01–R49 retains its identifier. “Retain” preserves the existing requirement under the amended governing contract. “Revise” supplies the new interpretation; it does not mean the requirement was verified.

| Requirement | Disposition | Implementation / verification phase |
|---|---|---|
| R01 | Retain meaningful concepts | 1, 4, 8 |
| R02 | Retain specificity and generic-label rejection | 1, 4 |
| R03 | Retain repeated-heading isolation | 1, 4 |
| R04 | Retain body-first semantic grounding | 1, 4 |
| R05 | Retain cross-wording concept recognition | 1, 4, 6 |
| R06 | Retain independently supported subtopics | 1, 4, 5 |
| R07 | Retain multi-topic passage memberships | 4, 5 |
| R08 | Retain valid one-Note Emerging topics | 4, 5 |
| R09 | Retain abstention on weak membership | 4, 5 |
| R10 | Revise: deterministic weighted scorer is optional diagnostic research, not a production prerequisite | 1, 4, 8 |
| R11 | Revise: evaluate the six quality dimensions with reviewed evidence; no mandatory six computed features | 1, 4, 8 |
| R12 | Defer configurable feature weights unless a measured scorer is adopted; version all active generation/publication policy | 4, 5 |
| R13 | Retain mandatory source/structure gates; do not claim they prove semantic support | 4, 5 |
| R14 | Retain substantive concepts, mechanisms and trade-offs | 1, 4 |
| R15 | Revise: corpus statistics may diagnose boilerplate; no required TF/DF implementation before generation | 1, 4 |
| R16 | Retain duplicate independence control | 1, 4, 6 |
| R17 | Retain bounded decision/rejection reason codes; no generated chain-of-thought | 4, 5, 7 |
| R18 | Revise: calibrate active prompt/retrieval/publication policy using development data; freeze before held-out review | 1, 4, 8 |
| R19 | Retain headings as context rather than proof | 1, 4 |
| R20 | Revise: evaluate generic-heading failures; structural lexicon/statistics are optional implementation aids | 1, 4 |
| R21 | Revise: local Gemma 4 E2B proposes distinctive grounded paraphrased labels via LiteRT-LM | 2, 3, 4 |
| R22 | Retain misleading/generic/title-family label rejection | 1, 4 |
| R23 | Retain specific supported concepts | 1, 4 |
| R24 | Retain evidence-safe identity and user customization preservation | 5, 7 |
| R25 | Revise: prioritize content additions/edits; reading activity is not a new input | 6 |
| R26 | Retain matching existing concepts before forming new ones | 6 |
| R27 | Retain retrieval of older eligible evidence | 6 |
| R28 | Retain unchanged embedding reuse | 3, 6 |
| R29 | Retain bounded affected updates instead of whole-library regeneration | 6 |
| R30 | Retain durable unmatched/provisional pool | 6 |
| R31 | Retain fair low-priority resumable reconciliation | 6, 7 |
| R32 | Retain resource bounds; include prompts, generated output, engine residency and swapping | 2, 3, 6, 8 |
| R33 | Retain durable resume, cancellation, power and foreground-query priority | 2, 3, 7, 8 |
| R34 | Retain playback and responsiveness protection | 2, 3, 7, 8 |
| R35 | Retain local-only sources, embeddings and generation | 2–8 |
| R36 | Retain major subjects/subtopics under existing explicit hierarchy evidence rules | 5, 7 |
| R37 | Retain independently validated relationships; generated hierarchy deferred | 5, 8 |
| R38 | Retain separation of topic validity and explicit-interest presentation | 5, 7 |
| R39 | Revise: explicit interests only; new recent-activity ranking deferred | 7 |
| R40 | Retain discoverability of older valid topics | 6, 7 |
| R41 | Retain distinctness/duplicate checks without label-only consolidation | 4, 5 |
| R42 | Retain Established/Emerging and internal Provisional/Suppressed distinctions | 4, 5, 7 |
| R43 | Retain real consented/public and purpose-built adversarial cases | 1, 8 |
| R44 | Retain human-rated topic/label/membership precision and missed-topic measurement | 1, 4, 8 |
| R45 | Retain same-source/same-embedding baseline comparison | 1, 4, 8 |
| R46 | Retain resource metrics; add generated tokens, native cancellation and engine reloads | 2, 6, 8 |
| R47 | Revise: inspect current supporting passages and bounded decision reasons; optional scores are not confidence | 4, 7 |
| R48 | Retain screen-reader access, lazy catalog and stable focus | 7, 8 |
| R49 | Retain source withdrawal across all saved, pending, pinned and late-result paths | 3, 5, 7 |

## Generation and publication policy

- Bundle entries contain opaque passage IDs, canonical source/revision identity and current body evidence. IDs are references, never instructions. No model-selected durable topic IDs.
- Output has a closed schema with label, concept description and supporting passage IDs. Reject unknown IDs, stale revisions, malformed/oversized output and unsupported semantics; abstain when uncertain. At most one bounded format-repair attempt; no semantic retry loop.
- Exact token/output limits and runtime settings are selected from Phase 2 device measurements, then frozen in a versioned policy. No guessed global limits or implied infinite context.
- Distinguish structural validity from semantic judgment. A citation existing is not evidence that a label follows from it. Human-reviewed held-out quality remains a release gate.
- Labels may paraphrase current evidence. Generated descriptions stay internal evaluation/proposal data; this direction does not authorize chatbot answers or generated Note-connection explanations.
- Persist source, embedding, generator artifact/tokenizer/template, prompt/schema/policy and reset epoch identities. A prompt update invalidates generation without needlessly invalidating compatible embeddings.
- Continuity relies on surviving evidence. Splits/merges do not silently redirect interests; explicit names/aliases survive independently where no safe successor exists.
- Publish complete atomic generations only. Revoke evidence synchronously with eligibility and reject late results. Preserve only eligible prior output on resource/I/O failure.

## Acquisition, UI and migration

The generative pack is a separately accounted optional explicit download. Preserve the embedding pack and its search role. Missing generation leaves valid saved topics readable and compatible semantic search available, reports new generation unavailable and never starts a hidden fallback formation algorithm. Startup does not delete an incompatible installation or download a replacement. Changing backend does not itself authorize a pack download.

Preserve current WorkManager/power/pause/storage rules, one serialized execution gate and search/playback priority. Rendering reads saved generations; opening details never generates. Preserve lazy viewport composition, inspected-order pinning until Refresh, focus restoration and non-spamming status. Show embedding preparation versus generation and incomplete/unavailable/wait states without invented estimates.

## Acceptance and rollout

The user subsequently authorized the implementation agent to perform semantic judgments. The **Agent-reviewed semantic evaluation** amendment permits explicitly agent-reviewed evidence for the authorized debug trial using the same numerical targets and sample minima. Agent judgments never become human judgments; independent human general-release quality and TalkBack speech/listening remain separate. Fixture authorship and output-review independence must be disclosed.

The existing quality/playback/privacy/TalkBack gates remain binding. On 2026-10-26 the user approved a provisional **256-MiB added Java/native memory gate for generation**, preserving the **128-MiB embedding-specific gate**, and added a separate device-pressure/Android 17 termination gate. These are application engineering limits, not Google-certified safe thresholds. The 120-second active-processing, one-second cancellation and two-second warm-search p95 gates remain unchanged. Model size is not the same measurement as added execution memory. Record initialization, mapped weights and total PSS separately to avoid hiding device pressure in the idle baseline.

Check ActivityManager.MemoryInfo before expensive work and defer/cancel safely under pressure; measure engine close/unload, repeated batches, long bundles, backend changes, backgrounding and playback contention. Native allocation profiling supplements sampled Java/native/PSS observations. On supported APIs inspect ApplicationExitInfo, including Android 17 REASON_OTHER descriptions containing MemoryLimiter:AnonSwap. Track anonymous RSS plus swap separately from PSS and do not infer survival from an absent Java crash stack. The owning Notion amendment records the full acceptance requirement.

Compare old formation, old formation plus generated labels, and retrieval plus generated formation on identical sources/embeddings. Freeze family-separated held-out data before tuning. Unrated examples remain unrated. Stop new publication on regression, never restore revoked evidence, and do not keep a second production worker as rollback.

See the [task-by-task plan](../superpowers/plans/2026-10-25-llm-discovery-v2.md), [runtime report](gemma4-e2b-runtime-report.md) and [baseline](generative-baseline.md).
