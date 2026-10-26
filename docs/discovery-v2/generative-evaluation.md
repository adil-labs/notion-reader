# Generative topic evaluation protocol

Current scope (2026-10-26): existing grouping plus LLM naming is the sole topic pipeline, as governed by the latest Product/UI/Integration amendments and sole-grouping-labeling-design.md. Formation-trial language and results below are historical. Fresh acceptance must evaluate the actual shipping grouping/labeling path and its current generator identity; the old formation trial and old label-only outputs do not establish acceptance.

Status: development and first frozen held-out outputs have explicit agent source-based reviews; the first frozen generative formation trial failed acceptance. User authorizes the agent to act as semantic reviewer. Record `agent` provenance; no human judgments are claimed. The agent also authored the initial purpose-built fixtures, so these judgments are not independent human evidence.

## Binding targets

Use the existing Product contract targets: at least 20 reviewed held-out subjects and 50 membership judgments, false publication at most 10%, missed-subject recall at least 80%, membership precision at least 90%, useful specific supported labels at least 80%. Agent-reviewed results may support the authorized debug trial under the new amendment; independent human general-release validation and human TalkBack speech/listening remain separate gates. The 34-case/88-passage corpus contains 29 held-out expected concepts and 74 expected memberships, sufficient candidate coverage for review but not yet actual reviewed model evidence.

Semantic search retains its separate recommendation precision >=80%, held-out Recall@5 >=80%, warm p95 <2 seconds and representative scale gates. Do not use topic metrics as search evidence.

## Reviewer rubric

For every actual generated proposal, read the complete offered evidence bundle and cited passages. Record concept support, label support, each valid/invalid membership, matched reference concept or false publication, and a concise source-based reason. Assess whether the label expresses the body evidence, preserves negation and distinctions, adds unsupported capabilities or causal claims, or merely repeats a generic heading. Source overlap is not sufficient reason to merge concepts. Distinct relevant passages may support overlapping topics. A single canonical Note may support an Emerging concept.

For each reference concept, record recovered or missed and the retrieval/generation/publication failure stage. Review abstentions and empty outputs; do not score only attractive published examples. Near-duplicate source copies do not establish independent conceptual agreement. Model self-confidence and a second model's assertion are not ground truth.

Use development fixtures for prompt/policy changes. Freeze the corpus hash, prompt/schema/policy/runtime/model identities and evaluation partitions before held-out generation. A failed held-out run is recorded as failed; any later tuning needs a fresh holdout or an explicitly contaminated/calibration classification.

## Comparison and reporting

Evaluate baseline formation, baseline plus generated labels, and retrieval plus generated formation on identical eligible source/embedding snapshots. Retain raw public model output and reviewer notes; keep private content out of logs. Report denominators, unreviewed counts, unsupported labels, false concepts, omissions, membership mistakes, duplicate predictions and over-merges/splits separately.

`tools/semantic-discovery/generative/evaluate.py` accounts for explicit judgments and validates coverage/identities/references. Its self-tests detect deliberate scoring errors; they do not establish semantic accuracy of the corpus or model. No quality gain is claimed before actual outputs are reviewed.

## 2026-10-26 — first frozen held-out formation review

The original 34-case corpus remains byte-frozen. `evidence/heldout-agent-reviewed-cases.json` is a separate review snapshot of all 27 held-out cases: 29 reference concepts and 74 expected memberships. The reviewer read the offered bodies and every generated proposal. Provenance is agent, with the fixture author also reviewing; no independent human evaluation is claimed. Raw model outputs remain unchanged.

| Offered-evidence comparison | Reviewed proposals | Recovered reference concepts | Useful specific labels | Supported cited memberships |
| --- | ---: | ---: | ---: | ---: |
| Shipping engine v4 / policy v16 | 39 | 29/29 (100%) | 8/39 (20.5%) | 90/90 |
| Frozen Gemma E2B GPU prompt v3 | 24 | 17/29 (58.6%) | 19/24 (79.2%) | 39/39 |

**Decision: the frozen generative formation candidate fails adoption.** Recall is below 80%, label quality is below 80%, and its 39 emitted membership judgments fall below the required 50. Reviewing 74 expected memberships does not manufacture 74 predicted-membership precision observations. Grounded concepts are generously counted as recovered even when a broad label needs improvement; duplicate or narrower fragments recover a reference at most once. The membership metric does not penalize omitted memberships, and source-supported proposal precision does not establish final-publication usefulness. These are tiny purpose-built offered-evidence bundles, not retrieval, real-library, publication-policy or scale acceptance.

The LLM emits no topic in 11 cases, missing 12 concepts: antibacterial treatment limits, DNS TTL, jitter, pressure/boiling, both rare Bloom-filter/B-tree concepts, spaced retrieval, snapshot isolation, drainage/root oxygen, starter fermentation, permeable runoff and write-ahead recovery. Empty native output despite explicit offered evidence puts these omissions at generation, not retrieval. Seven extra proposals fragment recovered references across training overload, habit cues, erosion mechanisms, version vectors and photosynthesis. Version-vector output additionally claims conflict resolution where its citation only distinguishes concurrent conflicts from obsolete values. The broad environmental requirement, Hydration and Cueing labels also lose useful subject specificity.

The shipping engine preserves the reference concepts in its source memberships but frequently chooses generic headings or truncated phrases: New note, Other, log durably before, while preserving exact and grow until memory. Its repeated groups count once for recall. These findings support investigating generated naming and improved concept formation; they do not justify adopting this formation prompt or treating label-only behavior as the target architecture.

Raw per-proposal reasons and reference omission reviews: `s25-heldout-v3-agent-judgments.json` and `s25-shipping-baseline-heldout-agent-judgments.json`; generated score reports are saved beside them. The original frozen manifest and prompt remain unchanged. Subsequent changes may use this partition only as explicitly seen calibration; a fresh unseen family partition is required for another adoption decision.

A separate label-only descriptive comparison was frozen before its first model output. It preserves each shipping group's memberships and asks for one label from its body evidence. Its inputs/prompt/model/config hashes are in `evidence/label-only-frozen-manifest.json`. Preparation rejects unknown, duplicate and withdrawn evidence rather than silently changing the groups. The source-withdrawal/identity checks were observed failing without the guard, then all 23 evaluator/preparation tests passed with it. Label-only generation is an evaluation baseline, not the replacement architecture.


## Fixed-membership label-only result

The separately frozen GPU prompt-v5 run completed 39/39 original groups. The reusable `validate_label_baseline.py` verified exactly one result per group, exact original passage sets and no added/split/omitted group. Its guards were observed failing for changed memberships, extra topics, missing groups and duplicate groups before implementation; all 28 evaluator/control tests pass after restoration. The input regeneration command reproduces the frozen SHA256 byte-for-byte.

Agent source review judged 33/39 labels useful and specific (84.6%), compared with 8/39 original labels. Reference recovery stays 29/29 and cited memberships 90/90 because formation is deliberately unchanged; these figures are inherited, not LLM formation achievements. Duplicate groups and split concepts remain. Errors include unsupported Ownership Transfer on lease-expiry evidence, three broad Retry Strategy names that omit jitter, generic Version Vector Concepts, and broad Stormwater Runoff Reduction Methods rather than permeability/infiltration. Descriptive improvement on seen purpose-built families does not satisfy fresh held-out adoption or independent-human release acceptance.

Summed native generation was 141,105 ms for 39 calls, excluding model load/validation. Sampled generation-stage allocation increase was 235.54 MiB over model-loaded idle; this fits only the provisional sample gate, with about 20 MiB headroom and no background-pressure guarantee. The tiny workload already takes substantial active generation time; this implementation strategy has not met the representative 10,000-passage/2,000-section/120-second acceptance gate. No extrapolated scale pass is claimed.

Raw report, frozen input identity, per-proposal agent rationale and score report are preserved under `evidence/s25-{gpu-label-only-v5,label-only-v5-agent-judgments,label-only-v5-scores}.json`. The next formation experiment must use development/calibration evidence and a fresh unseen family partition before an adoption claim; label-only is not substituted for the target retrieval-plus-generative-formation pipeline.
