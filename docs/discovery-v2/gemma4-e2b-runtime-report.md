# Gemma 4 E2B / LiteRT-LM feasibility record

Started 2026-10-25, updated 2026-10-26. Status: real S25 CPU/GPU generation, initial memory and GPU native cancellation measured; complete feasibility/acceptance **still open**.

## Verified acquisition identity

The official [Gemma 4 LiteRT-LM guide](https://developers.google.com/edge/litert-lm/models/gemma-4) identifies the published E2B artifact. Publisher API metadata was read at `https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm?blobs=true`:

- Repository: `litert-community/gemma-4-E2B-it-litert-lm`
- Revision: `1caa527c1e88b55f046fcd8d2b4265606680663d`
- File: `gemma-4-E2B-it.litertlm`
- Bytes: `2588147712`
- LFS SHA256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
- Ungated in the metadata response. This is artifact metadata, not a locally verified download digest.

Download implementations must resolve the immutable revision, not `main`. No model conversion/export. The publisher guide lists Apache-2.0 licensing; preserve the actual pinned card/license in acquisition review before integration. This record is not an assertion that every backend/API/ABI accepts this artifact.

## Runtime investigation

The current app uses LiteRT-LM Android 0.18.1. Its [pinned Engine source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Engine.kt) exposes background engine initialization and native lifecycle. Inspect the matching Session/Conversation/config APIs before writing the probe; examples from a newer default branch are not compatibility proof.

Android CLI documentation lookup was performed first: `android docs search "LiteRT LM Gemma 4 E2B Android"`, followed by `android docs fetch kb://android/ai/custom/index`. The returned general LiteRT guide did not answer the generation API question, so official LiteRT-LM sources were consulted next.

## Measurements still required

### Initial S25 observations

The isolated public probe ran the `different-wording` development case on SM-S931B/API 37 through the actual 0.18.1 Android API. The model SHA256 was also checked on device before inference. These are two fresh conversations per backend, one model load per invocation:

| Observation | CPU (2 threads) | GPU |
|---|---:|---:|
| Load ms | 6252 | 12779 |
| First response ms, first / second | 2740 / 2110 | 1014 / 293 |
| Total generation ms, first / second | 8606 / 7911 | 3734 / 3016 |
| Model-loaded process PSS KiB | 559503 | 1888421 |
| Sampled peak process PSS KiB | 1460304 | 1975108 |

Raw public inputs/outputs are tracked in `tools/semantic-discovery/generative/evidence/s25-{cpu,gpu}-different-wording.json`. The agent's explicit semantic review is alongside them. The same agent authored the fixture; no independent human review or broad backend-equivalence claim follows.

Both backends grouped the two body paraphrases correctly and returned the same supported label, “Retry strategy for unavailable servers”. The label could be more specific about exponential delays. Both also wrapped JSON in Markdown fences: strict output-format compliance **failed**. Follow-up native schema-constrained decoding returned plain JSON on both CPU and GPU; the original failure is retained, not normalized into a passing production parse.

The seven-case development batch confirmed semantic weaknesses: at 2,048 context, useful-specific labels were 6/9 and concept recall 8/9. At 1,024 context the valid mulch concept was additionally missed (7/9 recall). See raw runs, explicit agent judgments and evaluator outputs in the evidence directory. The first frozen held-out run is now scored and failed: recall 17/29, useful-specific labels 19/24, only 39 emitted memberships; see generative-evaluation.md.

On 2026-10-26 the user approved provisional 256-MiB generation-specific added-memory acceptance while retaining embedding's 128-MiB gate. It is not Google-certified safety. Device-pressure, native profiling, repeated/long/background/engine-swap/search/playback gates remain independent.

GPU native cancellation was explicitly requested 500 ms into generation in two fresh conversations. Both returned native cancellation exceptions; request-to-terminal upper bounds were 162/164 ms, including snapshot overhead. After engine close, native allocated memory was 15,676,704 bytes, PSS 203,028 KiB, anonymous RSS plus swap 155,176 KiB. Raw evidence: `s25-gpu-native-cancel500.json`. This is real cancellation/release evidence for one small workload, not general pressure or cancellation coverage.

Process PSS includes mapped model pages and is neither the governing additional Java/native allocation metric nor a traced allocation peak. Native generation succeeded under the ordinary 256-MiB managed heap. Overall device memory pressure, cancellation, swapping and quality acceptance remain unproven.

Artifact checksum on device; CPU/GPU load and public-input generation; ABI/API compatibility; exact chat template and token budgeting; context/output bounds; format validity; cold/warm latency; first token; peak managed/native/PSS and model-loaded idle; cancellation; close/unload and embedding-generation swaps; thermal behavior; search/playback contention; repeated process-death/reset/source-revocation scenarios. Publisher benchmarks on another phone do not establish any of these S25 results.

Phase 2 is not passed. No production runtime upgrade or installation has been performed. The immutable artifact was downloaded and verified on host/device, and the isolated developer probe runs on S25. Its missing-model path also produced a real failure rather than invented success. See `execution-ledger.md` for ongoing measurements and remaining implementation phases.


The 27-case held-out GPU batch completed in 45,058 ms summed generation time (excluding load/validation); sampled generation-stage Java/native increase was 222.97 MiB above model-loaded idle, within the provisional 256-MiB sample gate with limited headroom. Sampled process peak PSS was 2,380,446 KiB, a distinct measure. After close, native allocation was 13,808,816 bytes, process PSS 193,515 KiB, anonymous RSS plus swap 147,808 KiB, and lowMemory false. This batch is still small and is not scale/background safety evidence. Full raw snapshots are retained.

Accurate CPU cancellation measurements now exclude diagnostics: two-thread request-to-terminal 1,140/1,559 ms failed; four-thread 457/1,003 ms passed 1/2; six-thread 321/234 ms passed 2/2 for the tested prompt only. The strict one-second requirement was not widened. Production must explicitly call native cancelProcess and observe termination, since coroutine flow cancellation alone does not establish native termination.

A clean Perfetto native allocation capture is retained as `s25-gpu-native-allocation.pftrace.gz` with summary and SQL. It has zero nonzero error/data_loss stats, eight snapshots for generation PID14378, and sampled maximum live malloc 411,945,998 bytes. An earlier capture with dump-at-max plus continuous dumps had 221 malformed-packet errors and was rejected. Sampled malloc differs from allocator snapshots and mapped model residency; this is diagnostic evidence, not a pressure/peak acceptance substitution.


## Shipping label port smoke — 2026-10-26

The isolated probe now compiles actual production domain/data files verbatim, with no Reader composition, UI or credentials. Source-copy hashes match all 63 compiled files. Model SHA256 is checked before execution; APK/source identities are retained in production-label-port-manifest.json. Real runtime adapter, request/result port and strict parser are used, not a retyped generation loop.

The initial attempt failed because rendering an empty preface invokes a bundled template that reads messages[0]. Rendering the complete first user message with the bundle's native renderer fixed it without changing the model or template. Raw failures are retained. A separate probe timing error read counters before awaiting inference; the resulting zero timings were explicitly rejected and the corrected probe rerun.

| Public smoke | First | Second |
| --- | ---: | ---: |
| GPU operation, including first initialization, ms | 9863 | 6864 |
| Tokenized rendered prompt count | 180 | 180 |
| GPU cancel request to native terminal, ms | 135 | 124 |
| GPU cancel request to full port completion/cleanup, ms | 451 | 463 |
| CPU six-thread cancel request to native terminal, ms | 537 | 377 |
| CPU six-thread cancel request to full port completion/cleanup, ms | 661 | 608 |

Both cancellation backends observed native CancellationException 2/2. Both normal GPU calls returned Seed germination requirements with the exact two expected evidence IDs. Agent review supports that name from the moisture/temperature/sprouting passages; repeated execution of one bundle is not independent semantic acceptance. CPU normal-label quality is not established by cancellation results. Close snapshots show resource release for these calls, not sampled peak or sustained-pressure safety. Initializer cancellation, long/context-boundary/native-token accounting, playback/search swaps and background/representative scale remain separate gates.

The pinned API references are [Conversation callbacks, cancellation and rendering](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt) and [Engine lifetime](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Engine.kt). Callback termination is awaited before conversation/engine close even if the cancel call itself fails. Native library diagnostic text is collected only by the purpose-built public probe; shipping factory has no diagnostic logger by default.
