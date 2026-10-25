# Topic-Naming Experiment Harness (Synthetic Isolated Prototype)

This directory contains an isolated, throwaway Python standard-library prototype to evaluate topic-naming capabilities and passage-ID attribution on a fixed loopback Ollama instance (`ministral-3:8b`).

See [the first-run report](report.md) for observed outputs, failures, primary review
and retained raw evidence. This prototype has not passed Android acceptance.

> [!WARNING]
> **No Android Inference Evidence**: This harness runs on Windows with desktop GPU acceleration (NVIDIA RTX 5060 Laptop GPU). Desktop/server LLM execution is **not** evidence for feasible performance, memory bounds, or accuracy on a constrained on-device Android model. This experiment is an exploratory algorithmic prototype, not an Android production baseline.

---

## 1. Scope & Invariants

- **Isolated & Throwaway**: No Android codebase integration, no production dependencies, no Gradle touches.
- **Python Standard Library Only**: Requires only Python 3.10+ stdlib (`urllib.request`, `json`, `argparse`, `ctypes`, etc.). Zero `pip` dependencies.
- **Fixed Ollama Endpoint**: Fixed loopback address `http://127.0.0.1:11434`.
- **Model Target**: `ministral-3:8b` (must already be installed locally; no downloads or external API calls permitted).
- **Reviewed comparison**: `--model gemma3:270m` uses the same cases and prompt;
  install the model explicitly before running. The harness itself never downloads.
- **Execution Constraints**:
  - Serialized requests (concurrency = 1).
  - Maximum generated tokens: $\le 256$ (`num_predict: 256`).
  - Request timeout: 90 seconds.
  - Temperature: 0.0 and seed 42 (repeatability is measured, not guaranteed).
- **Quality Notice**: **JSON validity is not semantic quality.** Syntactically valid JSON topic outputs must still undergo manual human review for specificity, concise scope, and avoidance of hallucinated associations.

---

## 2. Benchmark Cases (`cases.json`)

The benchmark comprises 12 synthetic cases (4 calibration, 8 held-out), plus 2 designated stability repetitions:

| Case ID | Split | Category | Description | Ground Truth / Intent |
|---|---|---|---|---|
| `calib_01_coherent_technical` | Calibration | `coherent_topic` | 3 passages on SQLite WAL mode & checkpoints | Topic: *SQLite WAL Mode Concurrency* (`p1`, `p2`, `p3`). Repeated for stability. |
| `calib_02_boilerplate_abstain` | Calibration | `boilerplate` | Cookie notices, copyright, legal disclaimer | **Must abstain** (`topic: null`, no supporting IDs). |
| `calib_03_misleading_heading` | Calibration | `misleading_heading` | Financial earnings headings over espresso PID boiler text | Must ignore misleading heading; Topic: *Espresso Boiler PID Temperature Stability*. |
| `calib_04_membership_distractor` | Calibration | `distractor_separation` | 3 sourdough fermentation passages + 1 bike brake pad passage | Topic: *Sourdough Fermentation and Hydration* (`p1`, `p2`, `p3`). Distractor `p4` rejected. |
| `heldout_01_unrelated_shared_vocab` | Held-out | `polysemy_shared_vocab` | River bank erosion vs central bank reserve requirements | **Must abstain**; homonym "bank" must not trigger a fabricated connection. |
| `heldout_02_contradiction_common_sub` | Held-out | `contradiction_common_subject` | Contradictory trial results on caffeine & slow-wave delta sleep | Must name common subject without taking sides; Topic: *Caffeine Effects on Slow-Wave Deep Sleep*. Repeated for stability. |
| `heldout_03_mixed_topics_abstain` | Held-out | `mixed_topics` | Disjoint topics (soil pH, quantum crypto, French poetry) | **Must abstain**; reject umbrella categories. |
| `heldout_04_duplicates_near_identical` | Held-out | `duplicates` | Near-identical syndicated passages on `git rebase --onto` | Topic: *Git Rebase Onto Workflow* (`p1`, `p2`, `p3`). |
| `heldout_05_coherent_medical_clinical` | Held-out | `coherent_topic` | Cobalamin (B12) neuropathy and biomarkers | Topic: *Vitamin B12 Deficiency Neuropathy and Diagnosis*. |
| `heldout_06_misleading_heading_arch` | Held-out | `misleading_heading` | "General Notes" heading over Kubernetes HPA metrics scaling | Must extract specific subject: *Kubernetes Horizontal Pod Autoscaler Scaling Metrics*. |
| `heldout_07_boilerplate_single_signal` | Held-out | `distractor_separation` | Two legal notices surrounding one Postgres backup sentence | Topic: *PostgreSQL pg_dump Logical Backups* (isolates single signal `p2`, rejects `p1`, `p3`). |
| `heldout_08_multitopic_umbrella` | Held-out | `distractor_separation` | 2 Compose recomposition skipping passages + 1 Room SQL migration | Topic: *Jetpack Compose Recomposition Stability* (`p1`, `p2`). Distractor `p3` rejected. |

### Topic Naming vs. Membership Correctness
Cases deliberately separate **topic naming** from **passage membership**:
- The model must output `supporting_passage_ids` referencing *only* the specific passage IDs that justify the extracted topic.
- Distractor passages bundled in the input must be omitted from `supporting_passage_ids`.
- The parser strictly checks and rejects unknown/hallucinated passage IDs (e.g. IDs not present in the input prompt).

---

## 3. Extractive Proxy Baseline

The harness includes an internal `extractive_proxy_baseline` heuristic:
- **Honest labelling**: This is a simple, term-frequency heuristic proxy for comparison; it is **not** the production baseline.
- **Mechanism**:
  1. Filters out passages dominated by standard boilerplate/legal regexes.
  2. Tokenizes remaining candidate passages, stripping common English stopwords.
  3. Measures document frequency across passages.
  4. If no non-stopword terms appear across multiple candidate passages, it abstains.
  5. Otherwise, extracts the top shared terms as a title-cased topic and attributes supporting IDs to passages containing those terms.

---

## 4. CLI Execution Modes

```powershell
# 1. Internal self-check (validates parser, unknown ID rejection, baseline, schema; NO NETWORK)
python tools/semantic-discovery/topic-naming-experiment/experiment.py --self-check

# 2. Dry-run (evaluates extractive proxy baseline and builds prompts without network access)
python tools/semantic-discovery/topic-naming-experiment/experiment.py --dry-run

# 3. Live run (queries loopback Ollama; requires explicit primary authorization before execution)
python tools/semantic-discovery/topic-naming-experiment/experiment.py --run
```

### Execution Protocol Details (`--run`)
1. **Tags Verification**: Calls `GET /api/tags` on `http://127.0.0.1:11434` to verify `ministral-3:8b` is present and captures its digest. No model downloading is performed.
2. **Cold-ish Flag**: Request #1 is recorded as `cold-ish only, no hard cold guarantee` in the result log.
3. **Memory Capture**: On Windows, captures system physical memory via Win32 `GlobalMemoryStatusEx` before and after the run. If unavailable, marked as unmeasured.
4. **Stability Repetition**: Automatically executes repeat runs for `calib_01_coherent_technical` and `heldout_02_contradiction_common_subject` (14 total requests).
5. **Output Destination**: Stores timestamped raw results under `build/agy-tasks/topic-naming/results/topic_naming_run_<timestamp>.json`.

---

## 5. Output Data Schema

Each execution record in the output JSON contains:
- `execution_index`: 1-based order.
- `case_id`: Identifier with stability suffix if repeated.
- `cold_status`: `cold-ish only, no hard cold guarantee` (index 1) or `warm`.
- `latency_ms`: Elapsed wall-clock time in milliseconds.
- `prompt_chars`: Size of input prompt.
- `raw_prompt`: Exact text transmitted to Ollama.
- `raw_response`: Exact text received from Ollama.
- `parsed_llm_result`: Extracted schema (`abstain`, `topic`, `abstain_reason`, `supporting_passage_ids`, `flagged_unknown_ids`).
- `validation_errors`: Schema or unknown passage-ID errors.
- `extractive_proxy_baseline`: Output of the extractive heuristic.
- `ground_truth`: Expected benchmark behavior and evaluation notes.
