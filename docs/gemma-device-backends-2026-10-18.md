# Gemma quantization and Android backend benchmark

The user approved this bounded benchmark on 18 October 2026: compare the pinned
INT8 and INT4 exports on Pixel 8 Pro using identical public passages, repeated
warm runs, session creation, sampled memory, thermal state and vector agreement;
inspect provider attribution and explore S25 NPU/GPU support. Production model,
settings and private Notes are not changed by these probes.

## Reproduction and interpretation

- Model repository: `onnx-community/embeddinggemma-2-ONNX`, revision
  `daa72c51243991dfcaf9f9137d2c573d8f7790c0`. Five-file packs are verified against
  the tracked quantization manifest before staging; device copies are hashed.
- Graph plus weights: INT8 314,220,093 bytes; INT4 174,519,542 bytes. Shared
  tokenizer/configuration adds 32,177,140 bytes. Both are weight-quantized
  `MatMulNBits` exports with floating-point activations, not fully INT8/INT4 compute.
- ONNX Runtime Android 1.31.0. CPU uses two intra-op threads. XNNPACK is examined
  with its recommended separate two-thread pool/one ORT thread, plus a matched
  two-ORT-thread control because full CPU fallback otherwise changes thread budget.
- Three public inputs are generated from the frozen reference: 68-token short,
  220-token medium and a repeated longer public passage. Actual instructed token
  counts are in reports. No input is silently truncated.
- Two sweeps run in reverse configuration order. Each input has one untimed
  warmup and two timed repeats per sweep. Timed sessions have profiling disabled;
  a separate session profiles one medium inference. Session creation is measured
  separately; this is not filesystem-cold or process-cold loading.
- PSS, Java/native allocation and thermal status are sampled after/between calls;
  these are not peak memory, energy or thermal qualification. Normal background
  applications can interfere. Small warm samples do not support tail-latency claims.
- `gemma_device_benchmark.py` attributes actual ORT Node events and exposes CPU
  matrix work. Selected provider names alone do not prove GPU/NPU use. Summed node
  duration is not wall time or hardware utilization. Raw vectors are public fixtures.

```powershell
pwsh -NoProfile -File tools/semantic-discovery/prepare-gemma-device.ps1 -Device SERIAL -ModelDirectory C:/path/to/pack -Precision q4
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device SERIAL -HeadlessInference -Class com.example.notionreader.ui.GemmaBackendBenchmarkTest
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device SERIAL -HeadlessInference -Class com.example.notionreader.ui.GemmaXnnpackMatchedThreadsBenchmarkTest
```

Pull `files/gemma-backend-benchmark` and `files/gemma-xnnpack-matched` from the
application's external-files directory before the next run overwrites reports.
Run the profile summarizer on each pulled directory with an explicit `--output`.
Run classes separately through the repository wrapper. Never uninstall or clear
phone data to obtain a benchmark result.

## S25 QNN preparation

The connected S25 identifies as SM-S931B / SM8750. The Pixel identifies as Tensor
G3; Qualcomm QNN is not its NPU backend. The stock Android ORT package does not
provide the separate QNN backend libraries needed for this experiment.

The research pack pins Maven `com.qualcomm.qti:onnxruntime-android-qnn:2.7.0`
(SHA256 `ecd36df222e2a947ceb13bdd6fec181d1f1841b40379e21ce519830f2fbd562a`)
and `com.qualcomm.qti:qnn-runtime:2.51.0`
(`6388face1d5aa5e8958d745e5a547e92b5bec6fc332dfb1b438b1e17ffa02861`).
They are staged only in isolated app cache, not added to production dependencies.

The dynamic Gemma exports need fixed input shapes for QNN. The preparation tool
fixes batch=1, sequence=220 and empty modality dimensions=0; it preserves weights
and first proves original/fixed host CPU cosine at least .999999 for each precision.
This is a probe for one shape, not production support for arbitrary Note lengths.

```powershell
python tools/semantic-discovery/prepare-qnn-device.py --device S25_SERIAL --directory C:/path/to/qnn-research
# Use a research Python environment containing onnx, onnxruntime and numpy:
python tools/semantic-discovery/prepare-gemma-qnn-models.py --device S25_SERIAL --model-directory C:/path/to/pack --output-directory C:/path/to/fixed-models
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device S25_SERIAL -HeadlessInference -Class com.example.notionreader.ui.GemmaQnnProbeTest
```

The QNN probe records exposed EP/hardware devices, per-precision/backend failures,
first inference and host-vector agreement; profiles are required before claiming
actual accelerator coverage. Registration and exposing an NPU alone do not prove
Gemma executes on it.

Sources checked: [QNN 2.7 implementation](https://github.com/onnxruntime/onnxruntime-qnn/tree/v2.7.0),
[QNN documentation](https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md),
[XNNPACK](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html),
[NNAPI](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html).
Current Maven metadata was checked separately from the evolving main-branch docs.
Android's official NNAPI migration guide says NNAPI was deprecated in Android 15;
it remains a compatibility experiment, not a new strategic dependency.

## Pixel results

The main Pixel/API37 sweep completed all 24 configuration/round combinations.
The wrapper passed 1/1 tests on its second attempt; the first attempt recorded
an empty harness failure before measurement, not an application assertion.
The complete report and six profiles were retrieved after reconnecting the phone.

CPU uses two threads. These are medians of four warm samples per input/precision:

| Instructed tokens | INT8 CPU | INT4 CPU | Lower INT4 latency |
| --- | --- | --- | --- |
| 68 | 1,258 ms | 668 ms | 47% |
| 220 | 2,842 ms | 2,069 ms | 27% |
| 863 | 11,222 ms | 10,285 ms | 8% |

INT4's minimum cosine against INT8 CPU was .985448 across these three public
inputs. This is vector agreement, not topic/connection quality acceptance.
Session creation ranged 268–471 ms for INT8 and 539–770 ms for INT4 in this run.
The reverse sweep was warmer: thermal status changed from 0 to 1. The complete
min/max samples and scoped memory ranges are in the tracked JSON. Do not infer
an isolated quantization effect on memory from sequential-process PSS samples.

All six main profiles attributed all 1,066 executed node events to CPU, including
146 quantized matrix events. XNNPACK and NNAPI therefore supplied no observed
accelerator execution. XNNPACK's recommended one-ORT-thread configuration made
fallback slower. The completed two-ORT-thread control still showed CPU-only
execution (146 CPU quantized matrix events). Its INT8 medians were 976/1,875/7,335
ms and INT4 475/1,532/8,248 ms for 68/220/863 tokens. These are a separate run
with different device conditions, not a causal comparison with the main table.
QNN registration and WebGPU registration were unavailable in the stock package;
they are recorded failures, not GPU/NPU benchmark results.

## S25 research graph and correctness

The debug-only manifest requests optional vendor libraries `libcdsprpc.so` and
`libOpenCL.so`; the release manifest is unchanged. QNN libraries and transformed
models remain isolated in app cache. No production backend or setting is changed.

QNN rejected the publisher's empty modality tensors. The research transform removes
the unused multimodal merge for ordinary text and fixes batch=1, sequence=220.
Host CPU original/transformed cosine is effectively 1.0 for both precisions.
This does not establish equivalence for modal tokens or arbitrary Note lengths.

The initial HTP run produced zero final embeddings despite finite hidden states.
The reference pooling squared norm was 121,085, above the FP16 finite maximum.
Disabling the HTP FP16 precision option did not fix this. An attempted FP64 split
failed graph composition. The working graph keeps final L2 normalization on CPU
using ONNX-ML Normalizer; the matrix work can then execute on HTP correctly.
The unit fixture explicitly tests normalization above the FP16 range.

The probe rejects zero/nonfinite vectors and checks every profiled and timed
output against same-precision CPU (cosine at least .9995). Timed sessions disable
profiling, warm up once, then measure three calls; validation is outside the clock.
A separate profiled session establishes actual provider attribution. GPU is
explicitly selected through its backend library; it is not an enumerated GPU
hardware device in this runtime. Headless execution is opt-in and restricted to
the explicitly allowlisted native test classes, with all ordinary UI test guards retained.

Install `requirements-device-benchmark.txt` into a research Python environment.
The compact tracked result is
`tools/semantic-discovery/gemma-device-backends-2026-10-19.json`.


## S25 measured results

The final wrapper run passed 1/1 tests on its first attempt, with all eight
CPU/HTP/HTP-FP32-option/GPU attempts completing. Three warm samples per row:

| Precision | CPU median | HTP NPU median | GPU median | CPU / NPU warm speed ratio |
| --- | --- | --- | --- | --- |
| INT8 | 1,359 ms | 225 ms | 1,913 ms | 6.0× |
| INT4 | 1,251 ms | 226 ms | 1,813 ms | 5.5× |

These are the transformed fixed-220-token graph on the same S25, not a direct
comparison with the Pixel's unmodified dynamic graph. All warm thermal samples
were status1. Three sequential samples are exploratory, not sustained throughput.
HTP session creation cost 15.46 seconds for INT8 and 17.41 seconds for INT4,
versus CPU 132/362 ms. Setup must be amortized across passages; these are not
filesystem-cold startup measurements. The HTP-FP32-option rows were 223/224 ms
and did not materially differ in this small sample.

HTP profiles showed 73 QNN partition events, 98 CPU events and zero CPU quantized
matrix events for each precision. CPU retains some graph work including final
normalization. GPU showed 170 QNN events but all 146 quantized matrix events
remained on CPU; its partial offload was slower. Node-duration fractions are not
hardware utilization. Every HTP timed vector's same-precision CPU cosine exceeded
.9999989. INT4 versus host INT8 cosine was .990538 for this passage; that separate
quantization comparison is not a measure of NPU numerical error or topic quality.

Final verification: 973 JVM tests, debug assembly, androidTest compilation and
12 Gemma Python tests passed. Native device runs and wrapper self-checks passed;
fresh review found no remaining important issue. Settings file hashes stayed
unchanged on both phones, the Pixel preservation sentinel remained present, and
screen timeout/stay-awake values restored (S25 30000/0, Pixel 30000/15). These
checks do not reverse the earlier data-loss incident documented in decisions §94.


The [Vulkan follow-up](gemma-vulkan-2026-10-19.md) records successful S25
WebGPU/Dawn Vulkan execution and the Pixel Gemma readback failure. The initial
stock WebGPU failure above refers to the Pixel; it must not be generalized to
all Android devices. No production backend changed.
