# Public labeled Discovery experiment — 18 October 2026

The experiment found and fixed a real publication defect: a generic **Body**
section heading became the label of almost every published candidate. Larger
embedding models recovered more publisher-category pairs after calibration, but
also introduced many more category disagreements. These results do not justify
a production model or clustering replacement.

## Input and measurement

We used 500 scientific title/abstract records from the
[WOS-JTF dataset](https://huggingface.co/datasets/marcelsun/wos_hierarchical_multi_label_text_classification),
pinned to revision `f246712b46ef607586a774be262b186665b04630`: 200 source-dev
records and 300 source-test records selected deterministically by content hash.
The actual shipping tokenizer/chunker produced 861 passages. Gold subject IDs
were isolated from all discovery inputs. Exact and graph formation consumed the
same shipping-prepared vectors for each model, and both used shipping downstream
membership, naming and publication functions.

This is a **source-split diagnostic**, not the required family-held-out human
acceptance study. Subject families overlap the splits; grouping uses the combined
unlabeled corpus, and test diagnostics were inspected during exploration. Journal
and citation classifications are useful proxy labels, not human judgments of
app membership, passage relevance, naming, hierarchy or useful connections.

For each split, a predicted pair is an unordered pair of distinct Notes that share
a published subject. A gold pair shares at least one publisher depth-one subject
ID. We count each pair once across overlapping subjects. Precision is correct /
predicted pairs; recall is correct / gold pairs. The test split has **1,391** gold
fine-category pairs. These are not the product's membership-precision and
missed-subject-recall metrics.

## Findings

| Configuration | Correct / predicted test pairs | Pair precision | Pair recall | Pair F1 |
| --- | ---: | ---: | ---: | ---: |
| MiniLM, shipping exact, naming fixed | 2 / 2 | 100% | 0.14% | 0.29% |
| MiniLM, graph 0.50, membership 0.60 | 33 / 41 | 80.5% | 2.37% | 4.61% |
| BGE-small, graph 0.85, membership 0.75 | 123 / 272 | 45.2% | 8.84% | 14.79% |
| EmbeddingGemma 2, graph 0.90, membership 0.90 | 337 / 1,108 | 30.4% | 24.23% | 26.97% |

The graph rows were selected by dev fine-category pair F1 within the explored
model-specific grids. This is a limited exploration, not an exhaustive model or
algorithm winner. All trials, denominators, hashes and model identities are in the
[machine-readable report](../tools/semantic-discovery/wos-evaluation-2026-10-18.json).

At the original 0.65 graph threshold, MiniLM exact and graph paths each produced
494 candidate groups and the same fixed-publication metrics. Simply changing the
formation algorithm did not help at that setting. A lower graph threshold improved
category-pair recall, with added category disagreements.

Model thresholds do not transfer. Cross-Note median cosine was approximately 0.022
for MiniLM, 0.545 for BGE and 0.731 for Gemma. Unchanged MiniLM thresholds caused
BGE to admit nearly every test pair; Gemma exact formation collapsed to one
candidate with no publishable label. Separate threshold calibration was essential.

Host embedding/artifact runs took approximately 9.8 seconds for MiniLM INT8,
42.7 seconds for BGE FP32 and 248.8 seconds for Gemma FP32. These are measured host
runs on this corpus, not Android results or a quantization-controlled speed study.
BGE used publisher CLS pooling and verified identical token windows. Gemma used
the [documented clustering instruction](https://huggingface.co/google/embeddinggemma-2),
its own tokenizer, 768 dimensions and a pinned community ONNX text-only export.
The export's graph plus FP32 external text weights total about 1.08 GB; image/audio
encoders were not downloaded for inference. Publisher PyTorch parity, quantized
quality and Android qualification remain unmeasured. The earlier LiteRT-LM static
gate concerns a different runtime/artifact and is not evidence that this ONNX
export cannot run.

The lexical audit covered all 124,750 unordered source pairs and found no risks
under its declared shingle/paragraph rules. This does not establish semantic
independence. All hierarchy outputs had zero edges: the scientific category
ontology does not provide the literal source assertions required by the current
runtime hierarchy policy. Useful connection quality was not evaluated here.

## Implemented fixes and verification

Generic container labels Body, Abstract, Main text and Full text are withheld.
Naming can instead use an eligible literal source phrase; candidates and their
evidence remain available internally. The same generic-label set is used for
focused candidate formation. Policy 14 invalidates incompatible progress safely.
The original run published 493 candidates all named Body; the fixed exact run
published 457 distinct source labels. Distinct labels are not evidence that those
labels are useful or correct.

A new regression test failed on the original publication policy and passed after
the fix. The independent review also found unchecked source downloads and an
unfrozen gold-label sidecar. Both are now checksum-bound; the scorer additionally
checks every gold Note's source content/split and each report's corpus identity.
Disabling each checksum guard made its named regression test fail; restoring the
guards made both pass.

Verification: 944 JVM tests pass with zero failures/errors/skips; debug assembly
and Android-test compilation pass; 43 relevant Python tests pass. This round
changes domain publication and host evaluation, with no UI/platform flow changes.
It does not add new device, TalkBack or human-relevance acceptance evidence.

Production remains MiniLM with existing formation and membership thresholds.
General subject-hierarchy recall, representative family-held-out evaluation,
independent passage/label/connection judgments, and physical resource/playback
acceptance remain open. Granite was not benchmarked in this round.

## Gemma quantization comparison

The user subsequently requested quantized Gemma. We ran both INT8 and Q4 exports
from the **same** pinned community revision as FP32, on the identical 861 ordered
passages and token IDs. Dimensions (768), task prefix, CPU provider/two threads,
HNSW/Leiden parameters, 0.90 graph threshold and 0.90 membership threshold were
unchanged. Quantized thresholds were not independently retuned. The frozen
corpus/gold lock and every model file/report hash were verified.

| Gemma variant | Correct / predicted test pairs | Pair precision | Pair recall | Pair F1 | Graph + weights |
| --- | ---: | ---: | ---: | ---: | ---: |
| FP32 | 337 / 1,108 | 30.4% | 24.2% | 27.0% | 1,084.6 MB |
| INT8 | 374 / 1,237 | 30.2% | 26.9% | 28.5% | 314.2 MB |
| Q4 | 424 / 1,531 | 27.7% | 30.5% | 29.0% | 174.5 MB |

The gold denominator remains 1,391 fine-category pairs. INT8 preserves precision
closely with greater recall on this sample, at about 71% less model storage.
Q4 uses about 84% less model storage and has the largest pair F1, driven by higher
recall while precision drops. Higher F1 here is not evidence of cleaner grouping.
All variants require a further shared 32.2 MB of tokenizer/config assets. Stored
embeddings remain float32; this experiment quantizes the model weights.

Same-passage cosine agreement with FP32 averaged 0.999937 for INT8 (minimum
0.999680) and 0.985120 for Q4 (minimum 0.947012). Even small vector changes can
change a sparse graph's communities: INT8 added 140 and removed 11 test pairs
relative to FP32; Q4 added 618 and removed 195. Output pair counts therefore cannot
be substituted for vector fidelity or a human usefulness assessment.

Observed host embedding/artifact times were 248.8s FP32, 571.3s INT8 and 252.2s Q4.
These single runs were not isolated/repeated performance benchmarks; other
verification work occurred, including emulator startup. They exclude model
download and session initialization, and establish no Android speed/RAM claim.
Quantization did not automatically make inference faster in this configuration.

INT8 is the closer FP32 substitute; Q4 is the smaller option with more grouping
changes. Neither fixes the broader precision problem or qualifies a production
model switch. See the [hash-bound quantization report](../tools/semantic-discovery/gemma2-quantization-2026-10-18.json).
