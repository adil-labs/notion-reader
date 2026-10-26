# Generative Discovery evaluation

Purpose-built, public-text evaluation tooling for the local Gemma 4 E2B / LiteRT-LM proposal. It does not load private Notes, download models, choose semantic judgments automatically or establish release acceptance.

Run self-tests:

```powershell
python -m unittest discover -s tools/semantic-discovery/generative -p test_evaluate.py
```

`cases.json` contains 34 cases, 88 passages and **provisional agent-authored reference concepts**, including 29 held-out concepts and 74 expected held-out memberships. Each family occurs in one partition. Development cases may inform prompts; held-out outputs must be scored under a frozen prompt/policy without iterative tuning. Reference concept names are not exact-match label requirements.

The user authorizes the agent to semantically judge actual generated output. Review topics against supporting passages, including polarity/contradiction, specificity, duplicate evidence, cross-note support and omitted concepts. Keep fixture-author expectations provisional until reviewed. Explicitly record whether the reviewer also authored the fixtures. Never label an agent review “human”.

## Run format

Pass `--cases cases.json --run <run.json>` to `evaluate.py`. A run contains:

- `identity`: at least nonempty `model`, `prompt`, `corpus`; accepted reports additionally record artifact/runtime/backend, schema/policy, settings, code commit and corpus SHA256.
- `outputs`: exactly one entry per selected case, each with `case_id` and `topics`. Each topic has unique case-local `id`, nonempty `label` and `passage_ids`. An explicit empty topics list represents abstention; a missing case is incomplete coverage and fails.
- `reviews`: one adjudicated review per rated proposal, with `case_id`, `proposal_id`, nonempty `reviewer`, `provenance` (`human`, `agent` or `provisional`), `reference_id` (or null for an unmatched/false concept), boolean `topic_supported` / `label_supported` / `label_useful_specific`, and `supported_passage_ids`. A grounded but overly generic label must not pass the useful-specific-label gate. Preserve detailed rationale/raw reviewer notes in the accepted report or review record. Multiple reviewers must be adjudicated rather than double-counted.

Reports separate human, agent and provisional precision/recall, with separate development/held-out partitions so development success cannot conceal held-out failures. References declare their own provenance: an agent review does not silently promote provisional reference coverage into a human recall denominator. Duplicate predictions cannot inflate concept recall. An unmatched reference is a missed concept. Unrated predictions do not count as correct; a report with incomplete rating coverage cannot establish acceptance. Undefined denominators return null, never a misleading perfect score.

Structural invalidity is reported for every output, independent of ratings. A fabricated/revoked source cannot receive a useful-topic score even with an erroneous positive review. Semantic support still comes from explicit review, not citation existence. These evaluator checks do not replace production token/output/source-version validators.

## Failing-first proof

On 2026-10-25, removing only the shipping evaluator's eligible-passage predicate made both `testFabricatedCitationCannotReceiveAUsefulTopicScore` and `testRevokedPassageInvalidatesOutput` fail (`invalid_topics` became zero). Restoring it passed the entire suite. The separate agent-provenance test failed against the two-provenance implementation before agent accounting was added. Missing-module errors during initial setup were not counted as regression proof.

## Reproduce the reviewed comparisons

The first frozen formation trial failed the quality gate; see `docs/discovery-v2/generative-evaluation.md`. Recompute its agent scores without altering the original corpus:

```powershell
python tools/semantic-discovery/generative/evaluate.py --cases tools/semantic-discovery/generative/evidence/heldout-agent-reviewed-cases.json --run tools/semantic-discovery/generative/evidence/s25-heldout-v3-agent-judgments.json
python tools/semantic-discovery/generative/evaluate.py --cases tools/semantic-discovery/generative/evidence/heldout-agent-reviewed-cases.json --run tools/semantic-discovery/generative/evidence/s25-shipping-baseline-heldout-agent-judgments.json
python -m unittest discover -s tools/semantic-discovery/generative -p 'test_*.py'
python tools/semantic-discovery/generative/proof_evaluator.py
```

`baseline-runner` compiles the actual shipping domain/tokenizer/metadata sources rather than a translated approximation. Its `prepare <cases> <embedding-bundle> <host-tokenizer-dll> <output>` operation verifies the production embedding artifact and prepares eligible source records. Stage its output as `baseline-inputs.json` and the pinned embedding bundle into the isolated probe's external files. Run the public Android probe with `-Case embedding -Backend CPU -CpuThreads 2`; pull `baseline-embedded.json`, then invoke the host runner's `replay <embedded-records> <output>` operation. The captured shipping policy is version16, formation .10 / membership .90 / retention .89. The prepared/embedded/full snapshot gzip files and SHA256 manifest are retained under evidence.

Prepare the fixed-membership label-only control reproducibly:

```powershell
python tools/semantic-discovery/generative/prepare_label_baseline.py --cases tools/semantic-discovery/generative/cases.json --baseline tools/semantic-discovery/generative/evidence/s25-shipping-baseline-proposals.json --split heldout --output build/generative-discovery/baseline-label-inputs.json
```

Stage that file into the isolated probe external directory and run `run-runtime-probe.ps1 -Device <serial> -Backend GPU -Case baseline-labels -PromptVersion 5 -SkipStage -OutputDirectory build/generative-discovery/label-only -TimeoutSeconds 900` after building/installing the updated probe. This describes existing groups only, with prompt/input hashes frozen in `label-only-frozen-manifest.json`. Validate exactly one label with the original membership set before review; do not accept changed membership as a naming result. The formation holdout is now seen, so this followup is descriptive rather than fresh unseen adoption evidence.


Validate the captured label-only output without trusting its completion flag alone:

```powershell
python tools/semantic-discovery/generative/validate_label_baseline.py --inputs tools/semantic-discovery/generative/evidence/baseline-label-inputs.json --report tools/semantic-discovery/generative/evidence/s25-gpu-label-only-v5.json
python tools/semantic-discovery/generative/evaluate.py --cases tools/semantic-discovery/generative/evidence/heldout-agent-reviewed-cases.json --run tools/semantic-discovery/generative/evidence/s25-label-only-v5-agent-judgments.json
```

The control completed 39/39 with 33/39 useful labels and unchanged 90 memberships. See the evaluation document for limitations, unsupported wording and 141-second generation cost; these results do not accept the replacement formation pipeline.


## Actual production label port on S25

Build `runtime-probe` after the app native-tokenizer build. Its generated-source task copies actual shipping domain and selected data files verbatim; the variant Kotlin API compiles them, with the JNI tokenizer packaged separately. It does not compile Reader UI/composition/credentials or operate Reader storage.

Run `run-runtime-probe.ps1 -Device <serial> -Backend GPU -Case production-label -SkipStage -OutputDirectory build/generative-discovery/production-port-normal-final`. For cancellation add `-CancelAfterMillis 500` and a separate output directory. The CPU probe uses the real six-thread production adapter: pass `-Backend CPU -CpuThreads 6`. The staged pinned model is checksum-verified before execution. Results contain prompt/schema identities, actual port outcomes, exact cited IDs, elapsed time measured after awaiting completion, native-terminal latency, full port-completion latency and before/after-close memory snapshots. This path does not measure sampled peak allocation, thermal behavior or whole-worker integration.

Initial empty-preface template failure and invalid-timing reports are retained as failures, not normalized into success. Accepted smoke artifacts are `s25-production-port-gpu-normal.json`, `s25-production-port-gpu-cancel500.json` and `s25-production-port-cpu6-cancel500.json`, with exact source/APK identity in `production-label-port-manifest.json`. These repeated tiny inputs do not establish fresh semantic/scale/device-pressure acceptance.
