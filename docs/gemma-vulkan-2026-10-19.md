# Gemma Vulkan compatibility probe — 19 October 2026

User scope: test Vulkan on the Pixel 8 Pro and S25; do not use llama.cpp.
All device inference used the existing ONNX Runtime Android 1.31.0 package and
pinned ONNX Gemma INT8/INT4 packs. Production backend/settings are unchanged.

## Method

Native ORT WebGPU uses Dawn with Vulkan on Android, per the
[official provider documentation](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html).
The probe explicitly requests `dawnBackendType=Vulkan` and `validationMode=basic`.
A tiny opset17 matrix graph computes `[1,2] @ [[1,2],[3,4]] = [7,10]`; exact
readback and attributed WebGpu node execution distinguish working GPU compute
from merely enumerating a Vulkan device.

Each phone then runs both precisions of the original dynamic Gemma graph and
the fixed-220 ordinary-text/CPU-final-normalization research graph from the QNN
investigation. All five CPU controls must succeed. Every successful GPU output
must be finite/nonzero and have cosine at least .9995 with its same-case CPU
baseline. One warmup precedes three unprofiled timed `session.run` calls;
validation and tensor creation are outside the clock. Separate profiled sessions
validate outputs and actual node attribution. Timed sessions log warnings only;
verbose diagnostics are restricted to profiling. GPU failures are recorded with
their stage and are never reported as timings or usable acceleration.

The `WEBGPU_DEFAULT` matrix session follows explicit Vulkan in the same process;
it is not an independent fresh-process default-configuration comparison. Do not
attribute the result to a particular provider option without such a control.
An initial probe used unsupported custom context IDs without native instance/
device handles; that configuration error was corrected and its rows excluded.
The earlier verbose-timing S25 diagnostic is also excluded from final numbers.

Each phone has 11 collected cases: 3 matrix controls and 8 Gemma cases. A green
JUnit result means the diagnostic completed with valid CPU controls; it does
not mean every GPU attempt succeeded. JSON `executionComplete` and profile
attribution establish the individual outcome.

## Pixel outcome

Device Vulkan enumeration reports Mali-G715. Tiny matrix Vulkan compute/readback
passed with an attributed WebGpu node. Both original INT8/INT4 and both fixed
research graphs failed at warmup readback: `Failed to wait for the operation:2`.
No Gemma GPU timing or usable acceleration is claimed for this device.

This reproduces the earlier Pixel WebGPU failure and narrows its scope: Vulkan
compute works for a small graph, while Gemma fails in this ORT/driver combination.
It does not establish whether the root defect lies in Dawn, the driver, a shader,
resource pressure or synchronization. Disabling validation or guessing a fallback
is not evidence of a fix. CPU remains the verified Pixel route.

## S25 outcome

Device Vulkan enumeration reports Adreno 830. All 11 cases completed correctly.
The final warning-only timed sessions produced these medians (three warm calls):

| Graph | CPU INT8 | Vulkan INT8 | CPU INT4 | Vulkan INT4 |
| --- | --- | --- | --- | --- |
| Original dynamic export, 220 tokens | 1,529 ms | 339 ms | 1,597 ms | 334 ms |
| Fixed text research graph, 220 tokens | 2,453 ms | 339 ms | 1,796 ms | 333 ms |

The original graph's observed warm ratios are about 4.5×/4.8× CPU-to-GPU.
Vulkan session creation for that graph was 450 ms INT8 and 385 ms INT4, versus
CPU 153/536 ms. These are measured session creation, not filesystem-cold startup.
Every timed Gemma GPU cosine exceeded .9999999999997 against same-case CPU.

Original-graph profiles showed 1,032 WebGpu and 37 CPU events, with zero CPU
quantized matrix events. Fixed research profiles showed 1,010 WebGpu and 2 CPU
events, also zero CPU quantized matrix events. This is actual GPU execution;
node-event duration fractions are not hardware utilization.

The earlier HTP NPU prototype had lower warm latency (~225 ms) but much higher
session setup (15–17 seconds). Those are separate runs and graph/runtime paths,
not a controlled direct comparison. Vulkan is promising for lower setup cost;
choosing a production backend still requires representative workload measurements.

## Reproduction

Use the pinned staging instructions in
[the earlier backend report](gemma-device-backends-2026-10-18.md), including the
fixed research graph and its host CPU parity/checksums, on each phone. QNN runtime
libraries are not needed for this Vulkan class.

```powershell
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device SERIAL -HeadlessInference -Class com.example.notionreader.ui.GemmaVulkanProbeTest
adb -s SERIAL pull /sdcard/Android/data/com.example.notionreader/files/gemma-vulkan-probe C:/path/to/new-result-directory
python tools/semantic-discovery/gemma_device_benchmark.py C:/path/to/new-result-directory --vulkan --output C:/path/to/analyzed.json
```

Pull to a new host directory: pulling into an existing directory can introduce an
extra nested folder. Preserve reports before rerunning. The matrix ONNX graph is
embedded in the tracked test, not an untracked build asset. Raw logs/profiles are
local temporary evidence; compact measurements are tracked in
`tools/semantic-discovery/gemma-vulkan-2026-10-19.json`.

This is a compatibility and short warm-latency probe for one public 220-token
passage. It does not qualify arbitrary Note lengths, Discovery quality, energy,
peak memory, sustained heat, cancellation or background lifecycle. Three samples
and sequential backends do not support tail latency or isolated thermal claims.


## Verification

The final diagnostic wrapper passed 1/1 on the Pixel and 1/1 on the S25, each
on its first attempt. Pixel's four collected Gemma GPU failures remain visible;
those green diagnostic tests must not be read as GPU compatibility passes.
JVM suite (973 tests), debug assembly, androidTest compilation and 13 Gemma Python
tests passed. The new sample/profile validator failed its intended test before
implementation and passed afterward. The actual whitelist checker similarly
rejected the new native class before its explicit addition; ordinary UI/mixed/
unknown selections remain rejected. Wrapper completeness (292 source tests),
signatures, real-output classification, empty failures and keyguard checks passed.
Fresh read-only code review found no remaining important issue.

Both phones retained their settings file hashes; the Pixel preservation sentinel
remained present. Screen timeout/stay-awake values restored to Pixel 30000/15 and
S25 30000/0. Earlier data-loss history in decisions §94 remains unchanged.
