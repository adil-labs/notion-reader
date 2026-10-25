# Small Language Model (SLM) Survey for Topic-Name Generation

**Survey Date:** 2026-10-25  
**Domain Scope:** Offline topic-name generation for Notion Reader from bounded selected passages with passage IDs and abstention.  
**Execution Context:** Standalone generative text models evaluated first on host Windows development environments before potential porting to Android CPU via ONNX Runtime.  
**Task Constraints:** No network leakage (notes remain local); no draft-only models, embeddings, classifiers, or API-only services; strict verification of physical ONNX assets in public repositories.

---

## 1. Executive Summary & Context

Notion Reader requires automated discovery and labeling of topical clusters across personal notes. A topic-name generator takes bounded, user-selected text segments and produces concise, descriptive topic headings, optionally citing supporting passage IDs and abstaining when inputs are unrelated distractors.

Initial host prototyping on Windows revealed critical practical dynamics:
* **Ministral 8B**: Produced coherent, human-readable labels but demonstrated excessive abstention when exposed to noisy distractor passages. At 8B parameters, it exceeds realistic mobile CPU/RAM budgets.
* **Gemma 3 270M**: Evaluated against the identical multi-part JSON schema prompt, it failed 100% of test cases (14/14) by copying schema placeholder tokens literally instead of extracting substantive names. This demonstrated that compact SLMs (<500M) require either simplified prompt formats (e.g. plain-text, single-turn completion) or specific instruction fine-tuning to reliably adhere to structured extraction contracts.

This survey systematically inspects the open weights landscape for standalone text-generation models, prioritizing **$\le$1B parameters** for mobile feasibility, while tracking **1B to 3B models** as quality and reference baselines. Base-only models without instruction tuning are excluded from immediate consideration.

---

## 2. Shortlist Comparison Table

All candidates in this table have physically verified ONNX weights in public repositories with inspectable git trees and pinned commits.

| Model | Parameter Count | Publisher / Exporter | Pinned Git Commit | Primary Quantization Format | Download Size (Quantized) | Runtime Target | IFEval (Base) |
|---|---|---|---|---|---|---|---|
| **Qwen2.5-0.5B-Instruct** | 0.49B | Alibaba / `onnx-community` | `22942cb7` | `uint8` / `q4` | ~512 MB (`uint8`) / ~786 MB (`q4`) | Standard ORT CPU | 50.1 |
| **SmolLM2-360M-Instruct** | 0.36B | Hugging Face / `onnx-community` | `fe7c7db4` | `uint8` / `bnb4` | ~363 MB (`uint8`) | Standard ORT CPU | 63.8 |
| **SmolLM2-135M-Instruct** | 0.135B | Hugging Face / `onnx-community` | `b8a5c0f1` | `uint8` / `bnb4` | ~136 MB (`uint8`) | Standard ORT CPU | 53.0 |
| **H2O-Danube3-500M-Chat** | 0.50B | H2O.ai (Official Publisher) | `c202f976` | `quantized_int8` / `uint8` | ~514 MB (`uint8`) | Standard ORT CPU | *N/A* |
| **Gemma 3 270M IT** | 0.27B | Google / `onnx-community` | `2dbbfdb1` | `q4` / `quantized_int8` | ~545 MB (`int8_data`) | Standard ORT CPU | *N/A* |
| **MiniCPM5-1B** | 1.10B | OpenBMB / `skjortan` | `86b33cfa` | `q4` / `q4f16` | ~1.35 GB (`q4`) | Standard ORT CPU | *N/A* |
| **Llama-3.2-1B-Instruct** | 1.23B | Meta / `onnx-community` | `14007543` | `q4` (external data) | ~1.69 GB (`q4_data`) | Standard ORT CPU | 59.5 |
| **Llama-3.2-1B-Instruct (GenAI)** | 1.23B | Meta / Microsoft ORT | `e983c740` | `cpu-int4-rtn-block-32` | ~1.24 GB (`int4_data`) | ORT GenAI Custom Ops | 59.5 |
| **Qwen2.5-1.5B-Instruct** *(Ref)* | 1.54B | Alibaba / `onnx-community` | `d54957e3` | `uint8` | ~1.58 GB (`uint8`) | Standard ORT CPU | 66.8 |
| **Llama-3.2-3B-Instruct** *(Ref)* | 3.21B | Meta / `onnx-community` | `cab364e7` | `q4` (external data) | ~2.35 GB (`q4_data`) | Standard ORT CPU | 77.4 |

---

## 3. Detailed Evidenced Candidate Records

### 3.1 Qwen2.5-0.5B-Instruct
* **Underlying Architecture:** `Qwen2ForCausalLM` (490M parameters). Uses RoPE, SwiGLU, RMSNorm.
* **Hugging Face Source:** [Qwen/Qwen2.5-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct)
* **ONNX Repository:** [onnx-community/Qwen2.5-0.5B-Instruct](https://huggingface.co/onnx-community/Qwen2.5-0.5B-Instruct)
* **Pinned Revision:** `22942cb7d7ba4cc81bb4673549ca4d4614469b5e`
* **Exact ONNX Paths:**
  * `onnx/model_uint8.onnx` (512,096,636 bytes — self-contained graph)
  * `onnx/model_q4.onnx` (786,400,642 bytes — self-contained graph)
  * `onnx/model_bnb4.onnx`
  * `onnx/model.onnx` (1,993,796,793 bytes — FP32 baseline)
* **Companion Assets:** `config.json`, `generation_config.json`, `tokenizer.json` (7.03 MB), `tokenizer_config.json`, `vocab.json` (2.78 MB), `merges.txt`, `special_tokens_map.json`, `added_tokens.json`.
* **License & Gating:** Apache 2.0; ungated.
* **Runtime Classification:** Standard ONNX Runtime CPU. No custom GenAI operators required.
* **Architectural & Runtime Caveats:** Single-file graph under the 2 GB ONNX limit makes deployment simple (no external data shard paths to resolve). Chat template supports explicit system instructions (`<|im_start|>system...<|im_end|>`).

### 3.2 SmolLM2-360M-Instruct
* **Underlying Architecture:** `LlamaForCausalLM` (360M parameters). Trained on 4T tokens, SFT + DPO aligned.
* **Hugging Face Source:** [HuggingFaceTB/SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct)
* **ONNX Repository:** [onnx-community/SmolLM2-360M-Instruct-ONNX](https://huggingface.co/onnx-community/SmolLM2-360M-Instruct-ONNX)
* **Pinned Revision:** `fe7c7db4c8921c9e3fa1c65cfd296fb3b1b1a8f9`
* **Exact ONNX Paths:**
  * `onnx/model_uint8.onnx` (363,115,262 bytes — self-contained graph)
  * `onnx/model_bnb4.onnx` (366,836,866 bytes)
  * `onnx/model.onnx` (1,448,145,769 bytes — FP32)
* **Companion Assets:** `config.json`, `generation_config.json`, `tokenizer.json` (3.52 MB), `tokenizer_config.json`, `vocab.json`, `merges.txt`, `special_tokens_map.json`.
* **License & Gating:** Apache 2.0; ungated.
* **Runtime Classification:** Standard ONNX Runtime CPU / Transformers.js.
* **Architectural & Runtime Caveats:** Very small memory footprint (~363 MB). Standard Llama operator set supported universally on Android CPU. Fine-tuned specifically for rewriting and text summarization.

### 3.3 SmolLM2-135M-Instruct
* **Underlying Architecture:** `LlamaForCausalLM` (135M parameters).
* **Hugging Face Source:** [HuggingFaceTB/SmolLM2-135M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct)
* **ONNX Repository:** [onnx-community/SmolLM2-135M-Instruct-ONNX](https://huggingface.co/onnx-community/SmolLM2-135M-Instruct-ONNX)
* **Pinned Revision:** `b8a5c0f183b78c55955a5364f610c36668b5e681`
* **Exact ONNX Paths:**
  * `onnx/model_uint8.onnx` (135,658,468 bytes — self-contained graph)
  * `onnx/model_bnb4.onnx` (173,947,195 bytes)
  * `onnx/model.onnx` (538,868,107 bytes — FP32)
* **Companion Assets:** `config.json`, `generation_config.json`, `tokenizer.json` (3.52 MB), `tokenizer_config.json`, `vocab.json`, `merges.txt`, `special_tokens_map.json`.
* **License & Gating:** Apache 2.0; ungated.
* **Runtime Classification:** Standard ONNX Runtime CPU.
* **Architectural & Runtime Caveats:** Lowest footprint candidate (~136 MB). May suffer from reduced capacity to balance multi-passage synthesis and selective abstention simultaneously without prompt simplification.

### 3.4 H2O-Danube3-500M-Chat
* **Underlying Architecture:** Adapted Llama/Mistral architecture (500M parameters, 8k context length, 32k vocabulary).
* **Hugging Face Source & ONNX Repo:** [h2oai/h2o-danube3-500m-chat](https://huggingface.co/h2oai/h2o-danube3-500m-chat) (Official publisher repository!)
* **Pinned Revision:** `c202f976c26875541e738ea978c8158fa536da9a`
* **Exact ONNX Paths:**
  * `onnx/model_uint8.onnx` (514,382,129 bytes — self-contained graph)
  * `onnx/model_quantized.onnx` (514,382,080 bytes)
  * `onnx/model_bnb4.onnx`
  * `onnx/model.onnx` (2,054,879,671 bytes — FP32)
* **Companion Assets:** `config.json`, `generation_config.json`, `tokenizer.json` (1.79 MB), `tokenizer.model` (493 KB), `tokenizer_config.json`, `special_tokens_map.json`.
* **License & Gating:** Apache 2.0; ungated.
* **Runtime Classification:** Standard ONNX Runtime CPU.
* **Critical Prompt/Template Caveat:** The chat template explicitly raises an exception if a `system` message is passed (`raise_exception('System role not supported')`). Prompt templates must fold instructions directly into the first user turn: `<|prompt|>...</s><|answer|>`.

### 3.5 Gemma 3 270M IT
* **Underlying Architecture:** `Gemma3ForCausalLM` / `gemma3_text` (~270M parameters).
* **Hugging Face Source:** [google/gemma-3-270m-it](https://huggingface.co/google/gemma-3-270m-it)
* **ONNX Repository:** [onnx-community/gemma-3-270m-it-ONNX](https://huggingface.co/onnx-community/gemma-3-270m-it-ONNX)
* **Pinned Revision:** `2dbbfdb1b59bd034eb959428c6a7da9dd7ea27f0`
* **Exact ONNX Paths:**
  * `onnx/model_q4.onnx` (~185 KB graph header) + `onnx/model_q4.onnx_data` (~180 MB)
  * `onnx/model_quantized.onnx` + `onnx/model_quantized.onnx_data` (545,028,608 bytes)
  * `onnx/model.onnx` + `onnx/model.onnx_data`
* **Companion Assets:** `chat_template.jinja`, `config.json`, `generation_config.json`, `tokenizer.json` (20.32 MB), `tokenizer.model` (4.69 MB), `tokenizer_config.json`, `special_tokens_map.json`.
* **License & Gating:** Gemma Terms of Use (open weights, commercial use permitted under Google terms).
* **Architectural & Runtime Caveats:**
  * Large 256k vocabulary leads to a massive ~25 MB tokenizer overhead.
  * Splits weights across external data files (`.onnx_data`).
  * In earlier Notion Reader testing, it failed 14/14 tests with structured JSON prompts by echoing placeholder strings. Evaluation requires a simplified, single-output prompt format.

### 3.6 MiniCPM5-1B
* **Underlying Architecture:** `LlamaForCausalLM` (~1.1B parameters).
* **Hugging Face Source:** [openbmb/MiniCPM5-1B](https://huggingface.co/openbmb/MiniCPM5-1B)
* **ONNX Repository:** [skjortan/MiniCPM5-1B-ONNX](https://huggingface.co/skjortan/MiniCPM5-1B-ONNX) (Community exporter)
* **Pinned Revision:** `86b33cfa5405a52ed93f676e8ffa43f14a2d5370`
* **Exact ONNX Paths:**
  * `onnx/model_q4.onnx` (1,353,588,708 bytes — self-contained graph)
  * `onnx/model_q4f16.onnx` (930,792,939 bytes — self-contained graph)
* **Companion Assets:** `config.json`, `generation_config.json`, `tokenizer.json` (9.89 MB), `tokenizer_config.json` (92.5 KB), `special_tokens_map.json`.
* **License & Gating:** Apache 2.0; ungated.
* **Runtime Classification:** Standard ONNX Runtime CPU / WebGPU.
* **Architectural & Runtime Caveats:** Official OpenBMB repository provides no official ONNX text models. This verified community export packages weights into a single file (<1.4 GB) with standard Llama operators.

### 3.7 Llama-3.2-1B-Instruct (Standard & GenAI Variants)
* **Underlying Architecture:** `LlamaForCausalLM` (1.23B parameters, GQA, 128k context).
* **Hugging Face Source:** [meta-llama/Llama-3.2-1B-Instruct](https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct)
* **Standard ORT Repository:** [onnx-community/Llama-3.2-1B-Instruct-ONNX](https://huggingface.co/onnx-community/Llama-3.2-1B-Instruct-ONNX)
  * **Pinned Revision:** `14007543b6dc92de88daf96a9aa85d2f95ace6ef`
  * **ONNX File:** `onnx/model_q4.onnx` (~149 KB) + `onnx/model_q4.onnx_data` (1.69 GB).
* **GenAI Custom-Op Repository:** [onnx-community/Llama-3.2-1B-Instruct-GENAI-ONNX](https://huggingface.co/onnx-community/Llama-3.2-1B-Instruct-GENAI-ONNX)
  * **Pinned Revision:** `e983c740a38fcfa57fb4d124b18b644974c3d966`
  * **ONNX File:** `cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/model.onnx` (105 KB) + `model.onnx.data` (1.24 GB).
  * **Companion Config:** `genai_config.json`.
* **License & Gating:** Llama 3.2 Community License; ungated community conversions.
* **Runtime Difference:** The `GENAI-ONNX` artifact uses Microsoft's `onnxruntime-genai` custom runtime bindings, which include hardware-fused key-value cache and beam search operators. The standard `ONNX` artifact uses vanilla ONNX Runtime `InferenceSession`.

---

## 4. Excluded and Unverified Candidates

| Candidate / Family | Classification | Concrete Evidenced Reason |
|---|---|---|
| **SmolLM2-1.7B-Instruct** | `unverified` | No verified release in `onnx-community` or official `HuggingFaceTB`. Only scattered personal user accounts (e.g., `elbruno`, `webai-community`) without institutional maintenance. |
| **Gemma 2 2B IT** (`google/gemma-2-2b-it`) | `unverified` | No official or `onnx-community` ONNX export exists. Google published LiteRT and Safetensors; Gemma 2's logit soft-capping and sliding-window attention caused exporter failures in standard ONNX opsets. |
| **MiniCPM-2B / MiniCPM-1B (Official)** | `unverified` | Publisher `openbmb` provides PyTorch and GGUF, but no official pure-text ONNX artifacts. Only community conversions exist (`skjortan/MiniCPM5-1B-ONNX`). |
| **Phi-3-mini / Phi-3.5-mini / Phi-4-mini** | `excluded` | Exceeds parameter ceiling (3.82B parameters > 3B). While verified ONNX models exist (`microsoft/Phi-3-mini-4k-instruct-onnx`), memory consumption (>2.2 GB INT4) and compute demands make them unsuitable for mobile CPU background tasks. |
| **Ministral 8B** | `excluded` | Exceeds parameter ceiling (8B parameters > 3B) and empirically exhibited high distractor-induced abstention during host prototyping. |
| **Base Models** (e.g. `SmolLM2-135M-Base`, `Qwen2.5-0.5B-Base`) | `excluded` | Lack chat templates and instruction tuning. They predict raw textual continuation rather than executing topic synthesis and formatting constraints. |
| **Speculative Draft Checkpoints** | `excluded` | Designed solely as residual verification heads for paired large models (e.g., Llama-3.1-Draft), incapable of standalone coherent generation. |

---

## 5. Benchmark Interpretation & Comparability Limits

### 5.1 Reported Primary Benchmarks (Original Checkpoints)

| Model Checkpoint | IFEval (Strict / Loose) | MMLU | GSM8K | Primary Source |
|---|---|---|---|---|
| **SmolLM2-135M-Instruct** | 53.0 / *N/A* | 28.5 | 18.6 | Hugging Face SmolLM2 Paper (arXiv:2502.02737) |
| **SmolLM2-360M-Instruct** | 63.8 / *N/A* | 50.8 | 39.8 | Hugging Face SmolLM2 Paper (arXiv:2502.02737) |
| **Qwen2.5-0.5B-Instruct** | 50.1 / *N/A* | 47.4 | 53.6 | Alibaba Qwen2.5 Technical Report (2024) |
| **Qwen2.5-1.5B-Instruct** | 66.8 / *N/A* | 60.9 | 75.5 | Alibaba Qwen2.5 Technical Report (2024) |
| **Llama-3.2-1B-Instruct** | 52.4 / 59.5 | 49.3 | 44.4 | Meta Llama 3.2 Evaluation Report (2024) |
| **Llama-3.2-3B-Instruct** | 72.6 / 77.4 | 63.4 | 77.7 | Meta Llama 3.2 Evaluation Report (2024) |
| **H2O-Danube3-500M-Chat** | *Not Reported* | 39.5 | 18.2 | H2O Danube3 Technical Report (arXiv:2407.03278) |
| **Gemma 3 270M IT** | *Not Reported* | 38.2 | 12.5 | Google Gemma 3 Technical Report (2025) |

### 5.2 Benchmark Caveats & Comparability Limits
1. **Instruction Following (IFEval) as the Primary Metric:**
   General reasoning metrics like GSM8K or academic trivia like MMLU are poor predictors of topic naming performance. IFEval (verifiable instruction following, formatting adherence, length limits, and negative constraints) is the most relevant proxy for whether a model will produce a short label and follow abstention rules. SmolLM2-360M-Instruct (63.8) and Qwen2.5-0.5B (50.1) stand out in the $\le$500M class.
2. **Checkpoint vs. Quantized Artifact Discrepancy:**
   All publisher-reported benchmarks above reflect unquantized FP16/BF16 PyTorch models running on server GPUs. None of the publishers provide verified IFEval scores on the exact 4-bit/8-bit ONNX graphs running under ONNX Runtime CPU.
3. **Task-Specific Held-Out Evaluation Required:**
   No generic public benchmark tests the exact combination required by Notion Reader:
   * Consolidating 2–5 disjoint passages into a single 2–5 word topic title;
   * Accurately outputting citations (e.g. supporting passage indices);
   * Correctly abstaining when passages share no common theme.
   
   Consequently, **our own held-out evaluation suite is mandatory** to measure true task accuracy.

---

## 6. Recommended 3–5 Model Windows Evaluation Order

We recommend evaluating candidates in this sequence on the host Windows test harness before any mobile work:

### 1. `Qwen2.5-0.5B-Instruct` (UINT8 / Q4 ONNX)
* **Why First:** Best overall architectural stability in the sub-500M parameter tier. Possesses a mature chat template with full system role support, robust multilingual training data, and a compact self-contained single ONNX graph (~512 MB).
* **Primary Test Objective:** Test if it can output structured topic labels + passage citations without placeholder hallucination or runaway token generation.

### 2. `SmolLM2-360M-Instruct` (UINT8 ONNX)
* **Why Second:** Highest reported IFEval score (63.8) among sub-1B models, explicitly fine-tuned with SFT + DPO on text rewriting and summarization datasets. Ultra-lightweight (~363 MB).
* **Primary Test Objective:** Determine if its text-summarization alignment translates directly into tight, non-verbose topic labels.

### 3. `H2O-Danube3-500M-Chat` (Quantized INT8 ONNX)
* **Why Third:** Official publisher ONNX artifact directly from H2O.ai. 
* **Primary Test Objective:** Evaluate instruction adherence when prompts are adapted to fold system instructions into the first user turn (`<|prompt|>`), testing whether chat template restrictions impede abstention signaling.

### 4. `SmolLM2-135M-Instruct` (UINT8 ONNX)
* **Why Fourth:** Absolute lower-bound probe (~136 MB download).
* **Primary Test Objective:** Determine if 135M parameters is fundamentally sufficient to understand semantic overlap and abstention, or if catastrophic schema collapse occurs.

### 5. `Llama-3.2-1B-Instruct` (Q4 / GenAI INT4 ONNX)
* **Why Fifth:** Quality reference anchor. While larger (~1.24–1.69 GB), it establishes the quality ceiling for instruction compliance against which the $\le$500M models can be judged.

---

## 7. Android CPU & ONNX Runtime Feasibility Assessment

1. **Standard ORT vs. ORT GenAI:**
   * Standard ONNX Runtime CPU (`onnxruntime-android`) directly loads models like `SmolLM2-135M/360M`, `Qwen2.5-0.5B`, and `H2O-Danube3-500M` using standard C++/Java `InferenceSession` APIs.
   * `Llama-3.2-1B-Instruct-GENAI-ONNX` requires the `onnxruntime-genai` native library package, which incorporates custom ops and loops outside vanilla ORT.
2. **RAM & Thermal Budget:**
   * A 360M–500M model quantized to 8-bit or 4-bit consumes **~350 MB to ~550 MB resident memory**, fitting safely alongside active background media playback and TTS audio pipelines without triggering Android Low Memory Killer (LMK).
   * Models $\ge$1.5B (1.5 GB–2.5 GB resident) risk memory pressure and thermal throttling on mid-range ARM chipsets during sustained sequential generation.
