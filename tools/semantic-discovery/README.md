# Semantic discovery feasibility evidence

These tools are independent of the reader's Gradle settings and never read Notion content or app documents. Model binaries and build output stay in ignored `app/build/discovery-feasibility` and the standalone probe's `build` directory. The publisher revision, hashes, vocabulary fixture and measured reports are tracked text.

## Reproduce

From the repository root (Python 3.14 and JDK 21 were used):

```powershell
python -m pip install -r tools/semantic-discovery/requirements.txt
python tools/semantic-discovery/evaluate.py --output-dir app/build/discovery-feasibility
python tools/semantic-discovery/test_inspection.py
python tools/semantic-discovery/inspect_aar.py app/build/discovery-feasibility/onnxruntime-android-1.31.0.aar
.\gradlew.bat -p tools/semantic-discovery/android-probe assembleDebug
adb -s DEVICE_SERIAL install -r tools/semantic-discovery/android-probe/build/outputs/apk/debug/DiscoveryFeasibility-debug.apk
adb -s DEVICE_SERIAL shell am force-stop com.example.discoveryfeasibility
adb -s DEVICE_SERIAL shell am start -n com.example.discoveryfeasibility/.ProbeActivity
adb -s DEVICE_SERIAL logcat -d -s DiscoveryProbe:I '*:S'
adb -s DEVICE_SERIAL shell run-as com.example.discoveryfeasibility cat files/report.json
```

Use an explicit serial; the probe neither clears device logs nor modifies the reader package. The AAR comes from Maven Central (`com.microsoft.onnxruntime:onnxruntime-android:1.31.0`). The standalone merged manifest removes ONNX's telemetry initializer and network permissions, and code disables telemetry before opening a session. The reader retains its own network permissions for Notion.

## Shipping implementation tests

The standalone probe validates runtime feasibility; it does not replace tests of the shipping Kotlin implementation. Install the current debug reader APK, then:

```powershell
pwsh -NoProfile -File tools/semantic-discovery/prepare-device.ps1 -Device DEVICE_SERIAL
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device DEVICE_SERIAL -Class com.example.notionreader.discovery.ShippingDiscoveryInferenceTest
adb -s DEVICE_SERIAL shell run-as com.example.notionreader cat no_backup/discovery-shipping-quality.json
adb -s DEVICE_SERIAL shell run-as com.example.notionreader cat no_backup/discovery-shipping-benchmark.json
```

These tests fail explicitly if the staged model is absent. They copy public artifacts from /data/local/tmp into an isolated private test directory after Gradle installs the app, recheck both hashes, and never activate the production model. The quality test runs the shipping tokenizer/provider on the held-out corpus and compares the actual Browse keyword behavior. The benchmark runs 100 paragraphs through the shipping chunker/provider, writes 100 Note records with 10,000 passages within the shipping 64 MiB budget, and queries the actual controller five times after warmup. It labels replicated synthetic embeddings explicitly. The p95 target is an assertion; a failed measurement remains red. Remove the isolated model after testing with `adb -s DEVICE_SERIAL shell run-as com.example.notionreader rm -r no_backup/discovery-probe-model` if desired.

Tokenizer golden fixtures explicitly disable publisher padding. The publisher tokenizer.json defaults to 128 positions, which is relevant to padded batch inference, while the shipping provider uses unpadded variable-length inputs. Original padded probe inference uses attention masks and is unchanged; comparing padded IDs after blindly dropping the last ID was a test-fixture defect, corrected without changing the fixed-ID assertions.

## Observed gate, 15 October 2026

### Automatic topics and paired connections

`topic-evaluation.json` is a separate 19-Note, 23-passage synthetic multi-label
corpus with disjoint calibration/held-out theme families, ungrouped distractions,
two-theme Notes and a cross-topic pair with shared passage evidence.
`evaluate-topics.py` is developer tooling only: it exports actual pinned-model
host vectors for JVM topic tests. Python is not an application dependency.

```powershell
python tools/semantic-discovery/evaluate-topics.py
.\gradlew.bat :app:testDebugUnitTest --tests '*TopicQualityTest'
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device DEVICE_SERIAL -Class com.example.notionreader.discovery.ShippingTopicInferenceTest
pwsh -NoProfile -File tools/semantic-discovery/prove-topic-regressions.ps1
```

Native instrumentation re-embeds the synthetic source through the shipping
chunker/provider/index/controller and reports topic/pair precision, multi-label
recall, disk/memory, initial work and query contention at 10,000 passages. The
large workload explicitly repeats embeddings, and its foreground-query p95 target
stays below two seconds. Failed runs and unmeasured physical TTS contention are
not converted into success by arithmetic unit tests. Do not run Android CLI layout
inspection concurrently with instrumentation: both use Android UI automation.

The original standalone measurements below use the retained `evaluation-feasibility-v1.json` (20 short documents). `evaluation.json` is the shipping pipeline evaluation: realistic titles and a 31-paragraph archive with a relevant final paragraph. Do not attribute the original report's perfect scores to this expanded corpus; shipping instrumentation reports its own measurements.

The first actual shipping 10,000-passage benchmark failed: JSON index 50,902,890 bytes, query p50 34,623 ms / p95 45,492 ms, total PSS 400,212 KiB (rounded captured measurements in `shipping-json-benchmark-red.json`). This is not an inference failure: 100 passage embeddings took approximately 966 ms, and the separate shipping quality gate passed. Each query decoded every JSON vector twice and allocated normalized lists per candidate.

Storage format 2 replaces JSON vectors with atomic `NRS2` records: a 12-byte magic/version/header-length prefix, bounded UTF-8 JSON metadata and passage header, then big-endian IEEE float32 vectors. Header-only enumeration validates filenames and exact declared payload size; one-document reads validate dimensions, finite components, source spans and unit norms. Legacy JSON is rejected and rebuilt from cached source. Scalar cosine and one-pass best-passage selection remove temporary normalized vectors and full match lists. The original failing benchmark remains recorded; optimization success requires the unchanged real-device test target to pass.

- API 26 Google APIs x86_64 emulator: actual native loading and 39 inference calls passed. Cold session 142.9 ms; measured queries p50 17.9 ms / p95 53.0 ms; native heap 55.9 MB, total PSS 85,056 KiB.
- Pixel 8 Pro, API 37 ARM64: actual native loading and 39 inference calls passed. Cold session 159.3 ms; queries p50 11.9 ms / p95 14.6 ms; native heap 55.8 MB, PSS 140,333 KiB.
- Publisher quantized model: 23,026,053 bytes, 384 dimensions, 256-token context. Exact artifact hashes are in `artifacts.json`. Publisher tokenizer IDs include special tokens in `tokenizer-golden.json`; shipping tokenizer returns content tokens, and provider adds CLS/SEP.
- Synthetic corpus: 20 independently authored short documents, six calibration queries, eight held-out queries. Host calibration threshold 0.3867063977081559 gives precision 6/7. Held-out precision, Recall@5 and nDCG@5 are each 1.0 on host, API26 x86_64 and ARM64. These small synthetic scores establish feasibility, not broad real-workspace quality.
- Cross-ISA INT8 outputs are not numerically identical. Original provisional component-error test (0.01) is preserved as **false** on ARM64: maximum error 0.0228334 and minimum cosine 0.988837. API26 x86_64 matches host within 2.22e-16. The same actual Android held-out rankings and threshold precision pass. No product contract required an arbitrary component tolerance; exact tokenizer, pooling, normalization and semantic-quality properties govern adoption.
- All eight native libraries (ARM64, ARMv7, x86, x86_64, runtime and JNI) have 16 KiB ELF LOAD alignment. `test_inspection.py` proves the checker rejects a synthetic 4 KiB library. This does not prove a shipping APK's ZIP alignment or behavior on a real 16 KiB device.
- Standalone all-ABI debug APK is approximately 159 MB including the development-only model asset. Shipping must download the model separately; ABI-specific delivery avoids shipping every native architecture to each device.

## Runtime choice and remaining measurements

The existing publisher ARM64 INT8 ONNX artifact avoids maintaining a custom export. [LiteRT's conversion flow](https://developers.google.com/edge/litert/conversion/pytorch/overview) requires a `torch.export`-compatible source model, sample inputs, conversion and postconversion output validation. No LiteRT conversion was measured here. [Multilingual MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2) supports 50 languages and 384-dimensional output, but adds a different 12-layer model/tokenizer and needs independent size, speed and quality evaluation; it is not a drop-in identity match for this index.

Not measured: API26 ARM64 physical hardware, API26 32-bit devices, real 16 KiB execution, 10,000-passage on-disk query latency, long-document extraction/chunking, production Browse keyword baseline, playback contention, runtime memory pressure, TalkBack listening. Production integration must measure those applicable acceptance properties. Current reports cannot establish them.

Licenses: publisher model card declares Apache-2.0; ONNX Runtime v1.31.0 declares MIT. Full license texts are packaged under `app/src/main/assets/licenses`. [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes) distinguishes ELF alignment, ZIP alignment and actual device validation.

## Evidence policy quality and Gemma 2 gate (16 October 2026)

### Explicit private local review

Never run instrumented installation on a user's populated library: Gradle can uninstall its data. For an explicitly authorized developer review, export only cache and derived discovery records to Git-ignored build storage:

```powershell
python tools/semantic-discovery/export-private-review.py --device <serial> --output app/build/private-discovery-review/<new-snapshot>
.\gradlew.bat :app:privateDiscoveryReview '-PdiscoveryReviewDirectory=D:/repos/notion-reader/app/build/private-discovery-review/<new-snapshot>'
python tools/semantic-discovery/render-private-review.py app/build/private-discovery-review/<new-snapshot>/review.json
```

The explicit main is not a normal test or application runtime. It copies the export before replaying the shipping coordinator/store, reports structural failures and exits nonzero on rejection. Snapshot capture is best-effort across atomic files; pause/background indexing before export. Review cases are frozen, purposive held-out samples, separate from public calibration, with pending human judgments. Unshown pairs are only candidates for review. Never upload/commit snapshots, excerpts or judgments. Host elapsed times are not physical-device query benchmarks.

`policy-evaluation.json` extends the original multi-label corpus with independently authored public reference/routing hard negatives, using disjoint calibration/held-out vocabularies. There are 29 Notes and 33 passages. Generate real host vectors with:

```powershell
python tools/semantic-discovery/evaluate-topics.py --corpus tools/semantic-discovery/policy-evaluation.json --output app/src/test/resources/discovery/policy-vectors.json
pwsh -NoProfile -File tools/proof-discovery-quality.ps1
pwsh -NoProfile -File tools/semantic-discovery/prepare-device.ps1 -Device emulator-5554
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device emulator-5554 -Class com.example.notionreader.discovery.DiscoveryPolicyInferenceTest
```

Policy 7 uses unchanged model vectors and fixed production thresholds. Host and API36 x86_64 native membership precision/recall are 1.0 on both splits (2 calibration topics, 3 held-out topics). This small extension does not establish real-library precision or measure every false-link pattern; actual Pixel inspection additionally found checklist/question and long reference-title evidence, covered by explicit regressions. The original mapper-shaped native grouping/connection gate also passes. Human relevance and previously failed performance gates remain open.

`gemma2-gate-report.json` records the actual inspected LiteRT-LM 0.18.0 AAR hash, manifest, native libraries, ELF alignment and pinned converted-model metadata. Re-run the fail-closed static probe:

```powershell
pwsh -NoProfile -File tools/semantic-discovery/probe-gemma2.ps1
```

The nonzero result is expected: ARMv7 and x86 are absent, and native loading, prompt/tokenizer parity, quantized quality and playback contention are unmeasured. Only the 20.9MB development AAR is downloaded; the 484,622,336-byte model artifact is not downloaded or installed. Published LFS checksum is metadata, not a locally verified model hash. Static minSdk24 compatibility is not an API26 execution pass. The app keeps MiniLM/ONNX and its explicit download semantics.
