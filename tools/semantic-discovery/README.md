## Current LiteRT verification (2026-10-25)

Use the published EmbeddingGemma 2 Text 270M INT4 bundle, including its authoritative
SentencePiece tokenizer, without conversion/export. The optional pack is
164626432 bytes (about 165 MB). The app has no ONNX dependency. Experiments below
and under historical-onnx are prior-runtime evidence.

After installing the debug APK, stage-litert-gemma.ps1 downloads through hf,
verifies pinned size/SHA256 and stages isolated emulator cache (emulator only).
inspect-litert-tokenizer.py regenerates token/offset references read-only from the
published bundle; Python dependencies are flatbuffers and sentencepiece 0.2.2.
The verifyGemmaTokenizer Gradle task accepts the gemmaModelBundle property.
See native/discovery-tokenizer/README.md for bridge build details and licenses.

Run each selected class separately through tools/run-accessibility-tests.ps1
with an explicit Device: LiteRtGemmaDeviceTest and GemmaDiscoveryJourneyTest.
Production acquisition uses GemmaAcquisitionDeviceTest with explicit DeployModel
and an unlocked screen. Never clear private data to test a migration. Headless
inference is opt-in for native-only classes. Accepted emulator/S25 evidence and
limits are in litert-migration-2026-10-25.json.

## Historical ONNX experiments

# Semantic discovery feasibility evidence

## Active Gemma INT8 Android verification

The app now uses pinned EmbeddingGemma 2 INT8 through ONNX Runtime Android,
with native Hugging Face Tokenizers 0.22.1. The older classes named
`ShippingDiscoveryInferenceTest`, `ShippingTopicInferenceTest`,
`DiscoveryPolicyInferenceTest` and `TopicFormationScalabilityDeviceTest` retain
historical **MiniLM baseline** fixtures; they do not qualify Gemma. Their historical
commands and measurements below remain reproducible, with that scope.

Build prerequisites on this Windows workspace: Rust/cargo (tested with rustc
1.98.1), PowerShell 7, the existing JDK/Android SDK, and NDK 29.0.13846066.
`tools/build-discovery-tokenizer.ps1` builds the locked crate for all four Android
ABIs at API26 and the Windows JVM test host. Gradle wires these outputs into normal
builds; model binaries are not bundled. ARM64 ELF LOAD alignment was checked at
16 KiB; runtime evidence covers Pixel 8 Pro/API37 and x86_64/API36; API26 runtime remains inconclusive.

```powershell
android sdk install 'ndk;29.0.13846066'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:verifyGemmaTokenizer '-PgemmaTokenizerJson=C:/path/to/pinned/tokenizer.json'
pwsh -NoProfile -File tools/semantic-discovery/prepare-gemma-device.ps1 -Device DEVICE_SERIAL -ModelDirectory C:/path/to/pinned/Gemma-pack
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device DEVICE_SERIAL -Class com.example.notionreader.ui.GemmaDeviceIntegrationTest
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device DEVICE_SERIAL -Class com.example.notionreader.ui.GemmaDiscoveryJourneyTest
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device DEVICE_SERIAL -DeployModel -Class com.example.notionreader.ui.GemmaAcquisitionDeviceTest
```

The staging script verifies all five current INT4 files before any device write, accepts
flat or standard Hub `onnx/` paths, and touches only its isolated public test-pack
directory under app-owned `cache/gemma-benchmark-q4`, via a temporary public transfer file and app-side hash verification. Tests prefer the verified installed `no_backup/discovery-model/current` pack. `-DryRun` checks local bytes without device mutation. Parity checks the
precision-specific frozen JSON's 12 token/UTF-16 cases and three real public vector references, tests
explicit context overflow, and reports scoped post-call memory samples. The
journey performs real embeddings and uses isolated stores for six purpose-built
Notes: indexing, subjects, semantic/named connections, query ranking, actual-rate
embedding estimates, accessible evidence navigation and Back focus, without
changing the user's library or listening state. Its screenshot/report contain
only those fixtures. Acquisition is an explicit network test through the actual
application's Settings; it installs the optional pack using the default network
policy, preserving it for normal app use.

Run these classes separately. A combined CSV class request was observed to execute
only one class; the wrapper correctly returned INCOMPLETE against the full expected
count. Never treat that run as a suite pass. The tracked Gradle option
`android.injected.androidTest.leaveApksInstalledAfterRun=true` is essential:
without it this AGP version uninstalls the tested app, including pre-existing local
state, after tests. The first physical integration run exposed that destructive
behavior; later runs proved preservation with a pre-existing sentinel. See decisions
§94 for the incident and evidence. Do not remove this option or run raw Gradle
connected tests.

For exact-formation calibration, the existing `evaluatePublicExactFormation` task
accepts explicit `publicFormationDistance` and `publicMembershipThreshold`
properties. Separate output filenames retain parameter identity rather than
replacing the original baseline report. WOS publisher-label metrics remain proxy
diagnostics, not independent human relevance acceptance.

## Labeled WOS-JTF pipeline diagnostic

`wos_corpus.py` adapts 200 source-dev and 300 source-test scientific title/abstract
records from revision `f246712b46ef607586a774be262b186665b04630` of
`marcelsun/wos_hierarchical_multi_label_text_classification`. Its `--download`
option fetches the two exact public source files into an external evaluation
directory. The adapter rejects changed source bytes,
selects by deterministic content hash, rejects duplicate prose, and freezes
`corpus.json`, `corpus.lock.json`, `gold-labels.json` and `evaluation-lock.json`.
Publisher labels remain outside passage, vector and discovery inputs.

```powershell
python tools/semantic-discovery/wos_corpus.py --directory C:/evaluation/wos --download
# Run exportPublicCorpusPassages, public_vectors.py, exportPublicFormationInput,
# evaluatePublicExactFormation, formation_vectors.py, graph_formation.py and
# evaluatePublicGraphProjection as described below, using that directory.
python tools/semantic-discovery/wos_score.py --directory C:/evaluation/wos --reports C:/evaluation/wos/exact/exact-formation.json --output C:/evaluation/wos/scores.json
```

Scoring verifies the corpus/gold lock, every gold Note's source content and split,
and each pipeline report's corpus identity. Pair precision/recall/F1 compares
unique co-member Note pairs against shared publisher labels, separately for
depth-zero labels 0–5 and depth-one labels 6–51 (pinned `JTF/depth2label.pt`).
No predicted pairs gives null precision, not perfect precision. Source splits
share subject families: this is a diagnostic, **not family-held-out acceptance**.
Journal/citation classifications are proxy grouping labels, not human judgments
of passage relevance, app labels, subject subsumption or useful connections.

`bge_vectors.py` checks pinned BGE-small ONNX/tokenizer bytes and exact publisher
token parity with shipping windows, uses publisher CLS pooling and normalization,
then exports the same artifact format for shipping formation/projection. The
FP32 challenger versus INT8 MiniLM comparison is not quantization-controlled.
`gemma2_vectors.py` uses pinned community EmbeddingGemma 2 text-only ONNX
exports (`--precision fp32`, `int8` or `q4`), publisher clustering instruction and tokenizer, and the export's projected
sentence embedding. Source passage text stays fixed; tokenization changes explicitly.
No vision/audio encoder is loaded. All weights/tokenizer files must match
source-pinned checksums. Standard `hf download` nested paths and flat asset paths
are both supported. Only publisher-supported MRL sizes
128/256/512/768 are allowed, followed by normalization. Publisher PyTorch parity
and Android resource/quality qualification remain separate work.

For a quantization-only comparison, use the same export revision, source passages,
768 dimensions, two CPU threads, clustering instruction and graph/projection
settings as the FP32 run. Do not independently tune thresholds and attribute the
resulting change solely to quantization. The INT8 files are
`onnx/model_quantized.onnx` and its `_data` file; Q4 uses `onnx/model_q4.onnx`
and its `_data`. Shared tokenizer/config files must pass the same pinned hashes.

```powershell
hf download onnx-community/embeddinggemma-2-ONNX onnx/model_q4.onnx onnx/model_q4.onnx_data onnx/model_quantized.onnx onnx/model_quantized.onnx_data tokenizer.json tokenizer_config.json config.json --revision daa72c51243991dfcaf9f9137d2c573d8f7790c0 --local-dir C:/evaluation/gemma2-model
python tools/semantic-discovery/gemma2_vectors.py --baseline-dir C:/evaluation/wos/minilm --model-dir C:/evaluation/gemma2-model --output-dir C:/evaluation/wos/gemma2-q4 --precision q4
# Repeat with --precision int8 and a separate output directory. Then run the
# shared native preparation, graph and projection with minSimilarity 0.9 and
# publicMembershipThreshold 0.9, followed by wos_score.py on all three reports.
```

Check identical ordered row/token identities before comparing same-passage cosine
with FP32. Vector agreement alone is not semantic acceptance; score the resulting
published groups against the same frozen gold sidecar. Model sizes count graph
and external weights; tokenizer/config bytes are shared additional assets. Host
embedding/artifact times exclude model download/session initialization and are
not Android speed or memory qualifications.

Host graph projection accepts `-PpublicMembershipThreshold=0.75` for explicit
experiments, records the value, and rejects nonfinite/out-of-range thresholds or
ones below the shipping evidence floor. Runtime thresholds do not change.

## Public 500-Note baseline capture

`public_corpus.py` selects twenty substantive public encyclopedia articles from
each of twenty-five category families. It captures literal section paragraphs,
canonical page/revision IDs, attribution links and license provenance. Whole
families are assigned to calibration or held-out before discovery output is seen.
The category strata are not gold subject labels. English encyclopedia prose is a
baseline, not representative personal-Note style or human quality acceptance.

Use an output directory on a drive with enough space; full API responses and
paragraph content stay outside tracked source. The final lock contains public
source metadata and content hashes. Capture resumes from its API cache and honors
rate-limit backoff. It refuses to freeze fewer than 500 Notes or overwrite an
existing lock. A partial `capture-progress.json` is diagnostic, never a complete
evaluation corpus.

```powershell
python -m unittest discover -s tools/semantic-discovery -p test_public_corpus.py
python tools/semantic-discovery/public_corpus.py --output-dir C:\evaluation\public-500-v1 --lock tools/semantic-discovery/public-500-v1.lock.json
python tools/semantic-discovery/public_corpus.py --verify --output-dir C:\evaluation\public-500-v1 --lock tools/semantic-discovery/public-500-v1.lock.json
```

The offline verifier checks literal content, per-Note/source hashes, family
partitions and the whole-corpus fingerprint against the frozen lock. Preserve the
captured corpus/API cache as the replay artifact; public revision links alone do
not freeze subsequently changed transcluded templates or parser output. Exact
duplicate prose/source IDs are rejected; cross-family near-duplicate and conceptual
leakage still need an explicit audit. Sampled paragraphs omit tables/lists and do
not represent full-article coverage. All captured Notes are unrated, with zero
human judgments; provisional/category annotations cannot establish precision,
recall, hierarchy subsumption or label usefulness. Model/algorithm comparisons
must bind results to this exact fingerprint and keep Android gates separate.

After offline verification, export model inputs with the shipping Kotlin
tokenizer/chunker rather than reproducing paragraph/window logic in Python:

```powershell
.\gradlew.bat :app:exportPublicCorpusPassages "-PpublicCorpusDirectory=C:\evaluation\public-500-v1" "-PpublicCorpusLock=tools/semantic-discovery/public-500-v1.lock.json" "-PpublicCorpusVocabulary=app/build/discovery-feasibility/vocab.txt"
```

The task requires a matching external/embedded 500-Note lock, checks the canonical
corpus fingerprint and family partitions, and refuses private/unpinned sources.
It writes `passages.jsonl` plus `passage-manifest.json` under the corpus directory.
The manifest binds corpus, vocabulary and passage-file hashes, records the
shipping tokenizer/chunker and token limit, and keeps human/Android gates
unmeasured. Comparison runners must verify those hashes before consuming inputs.
Each passage also carries the exact token IDs generated by the shipping tokenizer,
including its special tokens; Python inference must not retokenize or truncate.
Original paragraph ordinals become stable block IDs; section titles remain context,
and no fabricated heading chunks qualify as body evidence. This exporter does not
run embeddings or clustering. A partial capture cannot produce a completed export.

Produce one shared MiniLM vector artifact for the algorithm ablation:

```powershell
python -m unittest discover -s tools/semantic-discovery -p test_public_vectors.py
python tools/semantic-discovery/public_vectors.py --corpus-dir C:\evaluation\public-500-v1 --model app/build/discovery-feasibility/onnx/model_qint8_arm64.onnx --vocabulary app/build/discovery-feasibility/vocab.txt --output-dir C:\evaluation\public-500-v1\minilm
```

The runner checks the shipping export hash, 500-Note coverage, pinned production
model/vocabulary hashes, token window bounds and family partitions. Single-input
ONNX CPU inference uses two threads and double-precision mean pooling over real
tokens followed by L2 normalization, matching the provider's pooling policy.
Host inference is not an Android numerical/performance acceptance result.
`vector-manifest.json` commits last and references content-addressed float32 NPY
vectors and ordered passage metadata. Failed inference preserves the prior
complete manifest. `verify_artifact` rejects altered vectors, reordered rows,
nonfinite/nonunit vectors, missing rows and incomplete artifacts. Both formation
methods must consume the same verified artifact and report its identity/hashes;
this stage does not decide a winning algorithm or model.

Run the lexical leakage audit before held-out quality claims:

```powershell
python -m unittest discover -s tools/semantic-discovery -p test_corpus_leakage.py
python tools/semantic-discovery/corpus_leakage.py --corpus-dir C:\evaluation\public-500-v1 --lock tools/semantic-discovery/public-500-v1.lock.json --output C:\evaluation\public-500-v1\leakage.json
```

The audit binds the frozen corpus fingerprint and accounts for every unordered
Note pair. Declared heuristics are NFKC/casefold five-word shingles, Jaccard >=.65
or containment >=.85 with at least 200 smaller-Note shingles, plus copied normalized
paragraphs of at least forty words. Cross-partition risks flag the entire affected
held-out family for exclusion from quality claims; they do not silently move the
frozen partition or become human judgments. Near-duplicate/paragraph pair evidence,
affected families and excluded Note IDs are explicit. Absence of a lexical match
does not prove semantic family independence or detect all paraphrases. `--progress`
can audit an in-progress capture diagnostically but always leaves the gate
incomplete. The full mode requires a matching verified 500-Note lock. Human quality
remains unmeasured even if no lexical risk is detected.

`graph_formation.py` implements the host-only HNSW/Leiden candidate baseline with
pinned dependencies in `requirements-graph.txt`. Use an isolated environment on a
drive with space. Its unit fixtures exercise real Faiss/igraph/leidenalg binaries:

```powershell
python -m pip install -r tools/semantic-discovery/requirements-graph.txt
python -m unittest discover -s tools/semantic-discovery -p test_graph_formation.py
```

Fixed exploratory defaults are k=15, minimum positive cosine .65, HNSW M=32,
efConstruction=200, efSearch=128, one thread, seed=42 and RBConfiguration Leiden
resolution=1 until stable. Union/max edge symmetrization preserves isolated
vertices as singleton communities. A deterministic calibration-only sample probes
approximate neighborhoods against exact cosine, crediting equally good ties and
rejecting self/duplicate/missing neighbors. Parameters and library versions are
reported; these defaults have not been selected by human quality judgments.

The CLI intentionally refuses raw passage vector artifacts as an exact-engine
ablation: shipping formation uses eligible within-section passage-direction
opportunities. Export those through the shared shipping preparation function:

```powershell
.\gradlew.bat :app:exportPublicFormationInput "-PpublicCorpusDirectory=C:\evaluation\public-500-v1" "-PpublicVectorDirectory=C:\evaluation\public-500-v1\minilm" "-PpublicFormationDirectory=C:\evaluation\public-500-v1\native-formation"
python tools/semantic-discovery/formation_vectors.py --native-dir C:\evaluation\public-500-v1\native-formation --output-dir C:\evaluation\public-500-v1\formation-vectors
python tools/semantic-discovery/graph_formation.py --vector-dir C:\evaluation\public-500-v1\formation-vectors --output C:\evaluation\public-500-v1\graph-formation.json
.\gradlew.bat :app:evaluatePublicExactFormation "-PpublicCorpusDirectory=C:\evaluation\public-500-v1" "-PpublicVectorDirectory=C:\evaluation\public-500-v1\minilm" "-PpublicFormationDirectory=C:\evaluation\public-500-v1\native-formation"
.\gradlew.bat :app:evaluatePublicGraphProjection "-PpublicCorpusDirectory=C:\evaluation\public-500-v1" "-PpublicVectorDirectory=C:\evaluation\public-500-v1\minilm" "-PpublicFormationDirectory=C:\evaluation\public-500-v1\native-formation" "-PpublicGraphReport=C:\evaluation\public-500-v1\graph-formation.json"
```

The MiniLM artifact includes checksummed little-endian float32 transport identical
bit-for-bit to NPY values. The JVM adapter checks consumed payload hashes, canonical
passage/row alignment, source provenance, family splits and unit vectors, then calls
the same preparation as the sole engine. Native output retains every eligible
member passage ID, opportunity order and the source artifact identity. The Python
conversion copies the native normalized directions without inference and rejects
missing/duplicate provenance or partial corpus export. It supplies the required
`shipping:SectionHierarchicalEngine:v4` declaration; raw embeddings cannot supply
that declaration by accident. The fixtures do not establish a 500-Note comparison.
The exact task recomputes/checks native opportunity IDs, every member ID and raw
prepared vector bits before observing seed groups from the actual shipping engine.
It also runs normal downstream projection/publication and writes unrated review
cases, committing `exact-formation.json` only after completion. An internal observer
exposes the existing formation phase; normal runtime calls have no observer or
alternate algorithm. Exact candidate groups can overlap because focused candidates
are retained. Graph communities are a partition before downstream projection.
Timing scopes are explicit: exact formation includes shipping preparation/focused
candidates, while the graph timer excludes shared preparation; exact total also
includes downstream work. Do not infer a speed winner from unequal phase scopes.
The graph-projection task validates generation, algorithm identity and complete
partition coverage, then calls the same focused-candidate, center/anchor,
best-passage membership, naming, publication and hierarchy functions as shipping
exact formation. Graph review uses fresh cold-run identities; runtime continuity
and sessions are outside this host comparison. Both reports retain literal
evidence and unrated judgments. This is shared downstream evaluation, not a graph
selector or alternate algorithm wired into Android. Useful-connection quality and
independent human subject/membership/hierarchy judgments still need separate review.

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

Replay defaults to 32 rounds/60 seconds. To observe a longer bounded sweep, add
`'-PdiscoveryReviewRounds=2048' '-PdiscoveryReviewSeconds=600'` (limits: 4096 rounds,
1800 seconds). Each invocation copies the original snapshot into a new replay
directory and leaves frozen cases/judgments unchanged. Progress prints every sixteen
rounds. The final report distinguishes round/time budget exhaustion, persistence
failure and a worker stopping; inspect final grouping/connection coverage and pass
limits before describing that stop as complete. Host replay remains separate from
physical-device latency and human quality acceptance.

The explicit main is not a normal test or application runtime. It copies the export before replaying the shipping coordinator/store, reports structural failures and exits nonzero on rejection. Replay reconstructs the shipping metadata hash from cache-current records and reports exact source-signature agreement. Missing metadata or a differing/legacy signature cannot establish unchanged-input checkpoint resumption. Snapshot capture is best-effort across atomic files; pause/background indexing before export. Review cases are frozen local samples, separate from public calibration. Audit prior exposure before calling a sample held-out; pass earlier review files to the renderer with repeatable `--prior-review <path>` arguments. Exposure compares canonical Note pairs across shown/unshown kinds and topic member sets across label/ID changes. It does not prove statistical independence or assign human precision. Unshown pairs are only candidates for review. Never upload/commit snapshots, excerpts or judgments. Host elapsed times are not physical-device query benchmarks.

Run the public review-renderer regression checks with `python -m unittest discover -s tools/semantic-discovery -p test_private_review.py`.

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

## Isolated physical shipping-search benchmark

```powershell
pwsh -NoProfile -File tools/semantic-discovery/check-benchmark-verdict.ps1
pwsh -NoProfile -File tools/semantic-discovery/run-shipping-benchmark.ps1 -Device <physical-serial>
```

The separate no-permissions Android package copies shipping domain, cache, binary index, provider and controller sources at build time. It does not install/uninstall the reader, access its UID or use private content. Public model assets must already exist under app/build/discovery-feasibility/probe-assets; runtime model/vocabulary checksums are verified. Build output includes all native ABIs and is a developer artifact, not a distribution recommendation.

The runner always builds incrementally, rejects emulator targets, checks generated copies, records the APK SHA256, and accepts only the current run ID. Reports remain available on failure. The shared verdict validates 100 Notes, 10000 passages, 20 finite query samples and nearest-rank p50/p95; self-checks reject slow, incomplete, invalid-percentile, nonfinite and error reports. Removing the threshold guard makes the slow-report self-check fail.

On Pixel 8 Pro API37 ARM64, two current binary-format runs produced p95 991ms and 1020ms (20 samples each), under the unchanged 2000ms target. The second public report is pixel-binary-benchmark-2026-10-16.json. It uses repeated synthetic embeddings with actual file persistence and exact search, not representative private-library relevance. Cold embedding includes session creation, tokenization and inference; it is not a session-initialization-only measurement. The historical JSON-format performance failure remains in shipping-json-benchmark-red.json. Other devices/API combinations, worst-case metadata filters and 10000 passages concurrent with real playback are not covered by these runs.

Debug reader builds emit numeric-only DiscoveryTiming maps. searchTotalNs includes inference/bulk wait, embedding and retrieval; completed=1 identifies a published successful search. Existing totalNs remains retrieval-only. Incomplete/cancelled/blank/unavailable operations have completed=0 and must not be mixed into successful-query percentiles. Diagnostic observer failures do not change search behavior. No query text, IDs or excerpts are included.

Debug `SpeechTrace` contains static event names plus numeric utterance counters/statuses, never speech text, Note IDs or voice identities. Native start/done/stop/error, enqueue returns and reader cancellations distinguish an interrupted engine request from an app cancellation. A native done callback is stronger evidence than a fake completion; it still does not establish human audible/listening acceptance. Keep traces from private-library probes in ignored local storage.


Connection review exports now include evidenceKind and connectionEvidencePolicyVersion. The renderer shows Named reference or Similar passages; older frozen cases remain explicitly unclassified. Pair exposure ignores classification, so reclassification cannot manufacture held-out evidence.

The benchmark supports -QueryDelaySeconds (0–120) and records a current-run ready marker plus epoch and elapsed query windows. Delay alone is not playback evidence. Window self-checks reject partial/impossible windows and insufficient sample duration. The Pixel foreground benchmark with separate Reader service speech produced p50 374.88ms/p95 456.81ms; see pixel-speech-window-2026-10-16.json for scope. Native speech progressed across the query window without stop/error/cancel; this is not human listening acceptance or a same-process UI measurement. Retained pixel-mixed-window-red-2026-10-16.json failed at p95 3963.25ms with mixed benchmark visibility and incomplete speech coverage. These differing conditions do not support a claim that playback improves query speed. Snapshot memory is not peak memory.


## Auxiliary labeling/classification research

See docs/auxiliary-discovery-model-evaluation.md and auxiliary-model-candidates.json for task-specific, unqualified candidates. This research does not change the reader or upload private Notes. Reproduce the host reranker smoke with existing requirements:

```powershell
hf download cross-encoder/ms-marco-MiniLM-L6-v2 onnx/model_qint8_arm64.onnx config.json tokenizer.json tokenizer_config.json vocab.txt special_tokens_map.json --revision 233902d25c440f23af6f7d6e94d2946bac0bee0a --local-dir app/build/discovery-feasibility/auxiliary-reranker
python tools/semantic-discovery/probe_reranker.py --assets app/build/discovery-feasibility/auxiliary-reranker --report tools/semantic-discovery/reranker-host-smoke.json
python -m unittest discover -s tools/semantic-discovery -p test_reranker_probe.py
```

The published graph size/hash is checked before inference. Removing that guard fails both corruption properties while the valid-artifact property still passes. All probe acceptance checks remain active under Python optimization. Raw logits are not user confidence percentages. Host public-example ordering and inference-only timings do not qualify Android execution or Note discovery quality.

## Trustworthy Discovery review and coverage

Run the recorded-provenance audit without exposing case content:

```powershell
python tools/semantic-discovery/quality_audit.py app/build/private-discovery-review/human-judgments-phase2.json
python -m unittest discover -s tools/semantic-discovery -p test_quality_audit.py
.\gradlew.bat :app:reviewDiscoveryEngine '-PdiscoveryReviewDirectory=app/build/private-discovery-review/2026-10-16-baseline'
```

The review reads cache-current records through the sole engine and writes
complete candidate evidence under the explicitly selected ignored snapshot.
Previously exposed cases are review material, not held-out evidence. Provisional
editorial decisions require per-case human confirmation before they contribute to
human-review statistics. Topic counts never establish quality acceptance.

The synthetic formation benchmark uses public synthetic 384-dimensional
records: 10,000 passages across 2,500 uneven sections. It measures actual Android
elapsed/thread CPU time and sampled additional Java/native allocated memory,
persists each work slice, and resumes through fresh stores and engine instances:

```powershell
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Class com.example.notionreader.discovery.TopicFormationScalabilityDeviceTest
```

The corpus uses eight- and forty-subject variations with section-specific vector perturbations:
2,500 distinct section directions and 10,000 passages in the primitive index
representation. It loads the shipping MiniLM provider before its idle memory
baseline, persists each slice and checks cooperative CPU cancellation within one
second. This controlled synthetic distribution is not independently judged real
library quality; it does not measure process PSS peaks, heat, physical playback or
TalkBack listening. Sampling is not an allocation profiler.

The legacy streaming engine, obsolete clustering/ablation runner, research-only
clustering tests and legacy mutation proof are retired. The sole runtime is used
across builds. Architecture adoption does not declare human or physical acceptance.


## Automatic background recovery verification

On an owned, otherwise empty API36 test emulator with the real installed pack:

```powershell
pwsh -NoProfile -File tools/run-accessibility-tests.ps1 -Device emulator-5554 -Class com.example.notionreader.ui.AutomaticDiscoveryDeviceTest
pwsh -NoProfile -File tools/probe-automatic-discovery-recovery.ps1 -Device emulator-5554
```

The first test observes normal screen-off scheduling and persisted effort changes.
The probe brackets an actual process kill with two wrapper phases; it captures
headless completion before verification instrumentation starts. It does not force
JobScheduler. Both restore their fixtures/settings; the host probe resets battery
simulation. See decisions §95 and `gemma2-android-integration-2026-10-18.json` for
scope, counts, known limitations and the earlier physical-device data-loss incident.


## Quantization and backend device benchmark

See [the benchmark report](../../docs/gemma-device-backends-2026-10-18.md) and
`gemma-device-backends-2026-10-19.json` for pinned models, reproduction and measured
scope. `requirements-device-benchmark.txt` pins the research Python environment.
`gemma_device_benchmark.py` rejects unsupported acceleration claims and validates
timed numerical agreement. QNN uses cache-only libraries and a fixed ordinary-text
research graph; this does not enable a production NPU backend. The wrapper's
`-HeadlessInference` option permits only the explicit native device classes.


Vulkan follow-up: [device findings](../../docs/gemma-vulkan-2026-10-19.md), with
compact results in `gemma-vulkan-2026-10-19.json`. Run `GemmaVulkanProbeTest`
through the wrapper and analyze its pulled folder with `--vulkan`. This uses
ORT WebGPU/Dawn with Vulkan; no llama.cpp is used. Diagnostic completion is
separate from individual GPU compatibility.


INT4 is the current default. `generate-gemma-q4-reference.py --model-dir PATH`
reproduces its pinned public device vectors; INT8 references remain historical.
Staging defaults to Q4; use `-Precision int8` explicitly for old benchmark controls.
`GemmaDeploymentDeviceTest` is an explicit opt-in real installation through the
production download API and then public-reference backend validation; invoke only
when the device owner requested deployment. It uses no Activity and is separately
allowlisted by the headless wrapper. Never clear app data to run these checks.


Model acquisition/deployment classes require `-DeployModel` and exact single-class
selection; default full-suite runs exclude both. The wrapper forwards an explicit
runner opt-in, and each test checks it before accessing the application. Self-check
`tools/check-model-deployment-selection.ps1` verifies the guard and agreement of
actual runner exclusions with source-derived completeness.
