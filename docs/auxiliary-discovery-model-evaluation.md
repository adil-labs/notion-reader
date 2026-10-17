# Auxiliary discovery model evaluation

This is a research protocol, not an amendment to shipping product semantics. The reader continues to use MiniLM embeddings. No classifier or generative model is installed in the reader, and no private content is sent to a service. Adding generated labels would require governing-spec changes before product implementation.

## Separate the jobs

Embeddings retain candidate retrieval and passage evidence. Evaluate auxiliary models against the specific job they were trained for; do not collapse query relevance, entailment, topic naming and useful discovery into one score.

| Candidate | Intended experiment | Important limitation |
| --- | --- | --- |
| cross-encoder/ms-marco-MiniLM-L6-v2 | Query/passage reranking, followed by a separate exploratory passage-pair study | MS MARCO query relevance is not Note-connection usefulness; pair-order behavior must be measured |
| cross-encoder/nli-MiniLM2-L6-H768 | Validate candidate topic labels against individual passages, permitting multiple labels | Entailment is not topical relatedness; complementary or contrasting Notes need not entail one another |
| HuggingFaceTB/SmolLM2-360M-Instruct | Propose short subject labels from bounded representative passages, with abstention | A plausible label cannot establish coherent membership or passage support; generation adds runtime/tokenizer complexity |

Pinned revisions, publisher metadata hashes and file sizes are in tools/semantic-discovery/auxiliary-model-candidates.json. All three cards declare Apache-2.0; this metadata is not a complete redistribution/license audit. The selected publisher ARM64 INT8 files are 23200716 bytes for the reranker and 82823065 bytes for NLI. The instruction model lists a 272737275-byte q4f16 ONNX file; its other export formats have different sizes and runtime requirements. These are individual file sizes, excluding tokenizer/other required assets, not measured resident memory or complete download sizes.

Sources: [reranker](https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2), [NLI](https://huggingface.co/cross-encoder/nli-MiniLM2-L6-H768), [instruction model](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct).

## Qualification before recommendation

1. Verify the immutable model artifact and full tokenizer/config contract, native graph operators, license requirements and required auxiliary files. Do not assume the smallest export is CPU-compatible.
2. Freeze the current MiniLM baseline, source/code/model provenance, labels, full topic memberships, exact passage pairs and existing human judgments before auxiliary inference. Separate historical exposed diagnostics from unrated current cases and additional held-out cases. Keep Topics, Connections and deterministic References as separate evaluation outputs; legacy reference types remain unclassified until audited.
3. Run the offline controlled comparison first, with identical candidate evidence for each applicable arm. Measure conditional label/member/pair quality separately from candidate retrieval coverage. An auxiliary model cannot repair a candidate that the bounded retrieval stage never considered. Reranking uses both passage orders and a preregistered mean raw-logit score, records asymmetry and abstains rather than silently truncating overlong pairs. This is a conditional candidate-pool experiment, not a claim about the shipping feed or unshown misses.
4. Keep public calibration and held-out evaluation separate. Previously reviewed private cases remain exposed diagnostics and must not become public fixtures or training examples. Human usefulness, passage support, topic coherence and label clarity remain separate dimensions.
5. Include same-vocabulary unrelated passages, complementary-but-not-entailing passages, contradictions that are useful to compare, multi-topic Notes, duplicate source families and supported abstention. NLI neutral/contradiction is not an automatic rejection of a useful connection.
6. Only after offline value is demonstrated, execute public-input graph loading on physical ARM64 and supported API/ABI combinations, then measure initialization, bounded job latency, peak/native/process memory, thermal impact, background work and real playback contention. Existing runtime packaging does not qualify a different graph. Test tokenizer/pair packing or chat-template/generation parity separately. Host inference-only timing is not the Android acceptance gate.
7. Deliver adopt, reject or defer per component, supported by per-task usefulness, evidence support, diversity, abstention and uncertainty as well as precision. Twenty convenience examples cannot establish general accuracy. Preserve the baseline fallback, source eligibility, stable topic identity, optional download and session isolation. No silent migration, cloud inference or private-content logging.

## Current evidence

Only the 23MB reranker graph and its tokenizer/config assets were downloaded to ignored host feasibility storage, using the Hugging Face CLI at the pinned revision. probe_reranker.py verifies its published graph checksum/size, explicit graph/input/output shape and finite raw logits, and runs the publisher's public query/document example. The relevant passage outranks the irrelevant passage. reranker-host-smoke.json records host CPU initialization and twenty inference-only timings with their narrow scope and explicit unmeasured gates.

This is a loading/relevance smoke test, not a discovery-quality evaluation. NLI and instruction-model weights have not been downloaded. Android loading, API/ABI qualification, tokenizer parity, usefulness, memory and listening contention are all still unmeasured for these candidates. A model file named arm64 is not proof of an Android ARM64 execution pass.
