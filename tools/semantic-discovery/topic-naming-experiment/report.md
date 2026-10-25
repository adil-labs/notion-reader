# Windows topic naming: first exploratory run

Date: 2026-10-25. Recommendation: continue host research; do not integrate this
prototype into Android yet. Generated names show potential, but this run does not
test a small model or establish an improvement over the shipping naming engine.

## Observed evidence

Agy created the harness and 12 synthetic cases after its original broad assignment
was stopped for excessive repository reading. Primary review corrected a SQLite
fixture, nondeterministic proxy ties, malformed-ID crashes, empty/duplicate support
acceptance, endpoint/model enforcement, and missing raw inference metadata. Before
the fixes, empty/duplicate support was accepted and an object-valued ID crashed.
Afterward, self-check and dry-run passed; agy then executed the reviewed harness.

The run made 14 serialized local requests: four calibration cases, eight held-out
cases and two immediate stability repeats. All requests returned responses, but two
failed support-list validation, so the live command exited 1. Both repeats produced
identical response text. This is a single synthetic run, not an accuracy estimate.
Calibration and held-out examples were authored together; they are not independent
external test data. No tuning on these outputs has been performed.

Primary inspection of retained raw outputs found these examples:

| Input subject | Extractive proxy | Generated result |
| --- | --- | --- |
| SQLite WAL | Readers File Wal | SQLite WAL Mode Concurrency Model |
| Espresso temperature control under misleading headings | Boiler Thermal | Thermal control in espresso brewing |
| Contradictory caffeine/sleep passages | Sleep Caffeine Hours | Caffeine timing and slow-wave sleep |
| Kubernetes autoscaling under vague headings | Metrics | Kubernetes Horizontal Pod Autoscaling |
| River bank versus central bank | Bank | Abstained |

Six of the twelve unique cases received useful subject labels on this inspection.
All three intended no-topic cases received abstentions. The three remaining cases
also received abstentions although they contained a supported subset: sourdough
plus a bicycle distractor, one PostgreSQL passage among boilerplate, and Compose
passages plus a Room distractor. The latter two omitted supporting_passage_ids
entirely. These failures remain in the evidence; no parser relaxation or rerun
was used to turn them green. This review is an agent assessment, not a blinded
human usefulness study. It assesses naming from supplied text, not factual truth
of synthetic medical/technical claims or real Note grouping quality.

The prompt itself is ambiguous: it asks whether the passages share a single
subject while also instructing the model to exclude distractors. Therefore these
subset failures cannot be attributed solely to model ability. A follow-up should
separate naming already-validated evidence from selecting a coherent subset, use
an unambiguous instruction, and evaluate on fresh cases. Do not retrospectively
reclassify this run as a pass.

## Runtime and limits

Windows 11, Python 3.14.7, NVIDIA RTX 5060 Laptop GPU (8151 MiB), approximately
47.4 GiB host RAM. Ollama ministral-3:8b reports 8.9B parameters, Q4_K_M,
6,022,236,616 artifact bytes. Full model digest and raw responses are retained in
[evidence/ministral-8b-first-run.json](evidence/ministral-8b-first-run.json).

First request: 43.32 seconds wall time, including 26.12 seconds reported model load.
Subsequent thirteen requests: median 1.136 seconds, range 0.939–1.458 seconds.
These are one batch's host observations, not guaranteed cold/warm performance.
Requests used a 4096-token context, maximum 256 generated tokens, temperature 0,
seed 42 and top_p 0.9. Host-wide before/after memory snapshots do not measure model
or GPU peak memory. Immediate repeats can benefit from prompt caching.

The comparison baseline is an intentionally simple term-frequency proxy. It is
not the production naming algorithm. No private Notes, cloud inference, downloads,
Android execution, or product changes were involved. Recent-first discovery,
cross-Note grouping, topic identity, evidence validation, battery, playback
contention and accessibility were not evaluated. Eight-billion-parameter results
do not qualify a sub-billion or other small Android candidate.

## Gemma 3 270M comparison

At the user's request, downloaded Ollama `gemma3:270m` and added only that model
to the harness allowlist. Before this change the CLI rejected it; afterward the
self-check and dry-run passed. Agy ran the same twelve cases plus two repeats,
with the original prompt, token/context limits and sampling options unchanged.
The original 8B evidence remains intact. This is a direct prompt comparison, not
a tuned evaluation of the smaller model's best achievable performance.

All fourteen requests returned, but all fourteen failed response validation
(command exit 1). Every response copied the literal schema placeholder
`Concise specific topic name` instead of naming its input. Supporting passage IDs
were missing throughout; most responses also paired abstention with the non-null
placeholder. No usable topic names were observed. Two repeat pairs reproduced the
same failures; stability alone does not indicate quality.

First request: 23.939 seconds. Thirteen subsequent requests: median 0.283 seconds,
range 0.182–0.369 seconds. The short, unusable completions are not equivalent useful
work to the 8B outputs, so timing is not a quality-adjusted speed comparison.
Raw evidence, complete model digest and options are retained in
[evidence/gemma270-first-run.json](evidence/gemma270-first-run.json).
This prompt/model combination is unsuitable as tested. A simpler label-only task
would be a separate, explicitly versioned experiment; this run must remain red.

### MiniCPM candidate check

The user also asked whether MiniCPM has relevant candidates. The publisher lists
[MiniCPM4-0.5B](https://huggingface.co/openbmb/MiniCPM4-0.5B) as a standalone small
text model, and [MiniCPM5-1B](https://huggingface.co/openbmb/MiniCPM5-1B) provides
an official GGUF route for local runtimes. The approximately 324M
[MiniCPM5-2B-DSpark](https://huggingface.co/openbmb/MiniCPM5-2B-DSpark) is a draft
checkpoint paired with a larger target, not a standalone alternative. Neither
standalone MiniCPM candidate was downloaded or evaluated in this round.

## Reproduction and next decision

Use the commands in README.md with the recorded model digest. Preserve this first
run before changing the prompt or fixtures. A useful next comparison would use a
genuinely small local model, fresh public/purpose-built passages and the shipping
naming baseline on identical validated evidence. Treat subset selection as a
separate experiment. Windows quality must be promising before any Android trial.
