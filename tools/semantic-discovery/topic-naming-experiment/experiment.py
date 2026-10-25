#!/usr/bin/env python3
"""
Synthetic Topic-Naming Experiment Harness (Isolated Prototype)

Evaluates short specific topic naming and passage-ID attribution using
Ollama (ministral-3:8b) against a synthetic 12-case benchmark with an
extractive proxy baseline.

Constraints:
- Python standard library only (no external dependencies).
- Fixed loopback Ollama endpoint: http://127.0.0.1:11434
- Fixed model: ministral-3:8b
- Serialized requests with <= 256 generated tokens and 90s timeout.
- Host RTX 5060 inference is NOT evidence for a small on-device Android model.
- JSON validity is NOT semantic quality; manual review is mandatory.

CLI modes:
  --self-check : Validates parser, schema, error handling, unknown passage ID rejection.
  --dry-run    : Evaluates proxy baseline and builds prompts without network access.
  --run        : Executes live serialized inferences against loopback Ollama.
"""

import argparse
import ctypes
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

DEFAULT_ENDPOINT = "http://127.0.0.1:11434"
DEFAULT_MODEL = "ministral-3:8b"
REVIEWED_MODELS = {DEFAULT_MODEL, "gemma3:270m"}
REQUEST_TIMEOUT_SECONDS = 90.0
MAX_OUTPUT_TOKENS = 256

SYSTEM_PROMPT = """You are a precise document topic-naming engine.
Given a bounded set of numbered passages, your job is to:
1. Determine if the passages share a single, coherent, specific subject.
2. If coherent, produce a concise and specific topic name (3 to 6 words) and list the IDs of the supporting passages that directly substantiate this topic. Distractor or unrelated passages must NOT be listed in supporting_passage_ids.
3. If the passages are unrelated, contradict each other without a common subject, are purely boilerplate, or represent polysemous unrelated uses of a shared word, you MUST abstain.

Respond ONLY with valid JSON matching this schema:
{
  "abstain": true | false,
  "topic": "Concise specific topic name" | null,
  "abstain_reason": "Brief explanation if abstaining" | null,
  "supporting_passage_ids": ["p1", "p2"]
}
Do not include any explanation or markdown formatting outside of the JSON block."""


# ---------------------------------------------------------------------------
# Memory Measurement (Stdlib Windows best-effort)
# ---------------------------------------------------------------------------
def get_system_memory_snapshot() -> Dict[str, Any]:
    """Capture host memory via Win32 GlobalMemoryStatusEx if on Windows, else record unmeasured."""
    if sys.platform == "win32":
        try:
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            stat = MEMORYSTATUSEX()
            stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat)):
                return {
                    "status": "measured_windows_api",
                    "memory_load_percent": stat.dwMemoryLoad,
                    "total_phys_bytes": stat.ullTotalPhys,
                    "avail_phys_bytes": stat.ullAvailPhys,
                    "used_phys_bytes": stat.ullTotalPhys - stat.ullAvailPhys,
                }
        except Exception as e:
            return {"status": "measurement_failed", "error": str(e)}
    return {
        "status": "unmeasured",
        "notes": "Memory unmeasured unless reliably available via host OS stdlib."
    }


# ---------------------------------------------------------------------------
# Extractive Proxy Baseline (Honest heuristic baseline, not production)
# ---------------------------------------------------------------------------
STOPWORDS = {
    "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any", "are",
    "as", "at", "be", "because", "been", "before", "being", "below", "between", "both", "but",
    "by", "could", "did", "do", "does", "doing", "down", "during", "each", "few", "for", "from",
    "further", "had", "has", "have", "having", "he", "her", "here", "hers", "herself", "him",
    "himself", "his", "how", "i", "if", "in", "into", "is", "it", "its", "itself", "just",
    "me", "more", "most", "my", "myself", "no", "nor", "not", "now", "of", "off", "on", "once",
    "only", "or", "other", "ought", "our", "ours", "ourselves", "out", "over", "own", "same",
    "she", "should", "so", "some", "such", "than", "that", "the", "their", "theirs", "them",
    "themselves", "then", "there", "these", "they", "this", "those", "through", "to", "too",
    "under", "until", "up", "very", "was", "we", "were", "what", "when", "where", "which",
    "while", "who", "whom", "why", "with", "would", "you", "your", "yours", "yourself"
}

BOILERPLATE_PATTERNS = [
    re.compile(r"\b(cookie|privacy policy|terms of service|all rights reserved|disclaimer|confidential)\b", re.I)
]

def run_extractive_proxy_baseline(passages: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Extractive Proxy Baseline:
    Simple term-frequency heuristic. Honest fallback proxy, NOT the production baseline.
    1. Checks if all passages are dominated by boilerplate patterns -> abstain.
    2. Identifies non-boilerplate candidate passages.
    3. Finds shared significant unigram/bigram stems across candidate passages.
    4. If no significant shared vocabulary across candidate passages -> abstain.
    5. Returns top extracted salient phrase and IDs of passages containing those terms.
    """
    valid_passages = []
    for p in passages:
        text = p.get("text", "")
        is_bp = any(pat.search(text) for pat in BOILERPLATE_PATTERNS)
        if not is_bp or len(text.split()) > 20:
            valid_passages.append(p)

    if not valid_passages:
        return {
            "method": "extractive_proxy_baseline",
            "abstain": True,
            "abstain_reason": "All passages match boilerplate patterns",
            "topic": None,
            "supporting_passage_ids": []
        }

    # Tokenize each passage
    passage_tokens: Dict[str, Set[str]] = {}
    passage_words: Dict[str, List[str]] = {}
    for p in valid_passages:
        words = [w.lower() for w in re.findall(r"\b[a-zA-Z]{3,}\b", p.get("text", "")) if w.lower() not in STOPWORDS]
        passage_tokens[p["id"]] = set(words)
        passage_words[p["id"]] = words

    # If only 1 passage remains valid
    if len(valid_passages) == 1:
        p0 = valid_passages[0]
        words = passage_words[p0["id"]]
        top_words = [w for w, _ in Counter(words).most_common(3)]
        topic_title = " ".join(top_words).title() if top_words else "Single Passage Extract"
        return {
            "method": "extractive_proxy_baseline",
            "abstain": False,
            "abstain_reason": None,
            "topic": topic_title,
            "supporting_passage_ids": [p0["id"]]
        }

    # Count term frequency across passages (document frequency)
    doc_freq = Counter()
    total_tokens = Counter()
    for pid, tokens in passage_tokens.items():
        doc_freq.update(tokens)
        total_tokens.update(passage_words[pid])

    # Find terms appearing in at least 2 candidate passages
    shared_terms = [term for term, count in doc_freq.items() if count >= 2]

    # If no shared terms across candidate passages, abstain due to lack of coherence
    if not shared_terms:
        return {
            "method": "extractive_proxy_baseline",
            "abstain": True,
            "abstain_reason": "No shared non-stopword terms found across candidate passages",
            "topic": None,
            "supporting_passage_ids": []
        }

    # Sort shared terms by overall occurrence
    shared_terms.sort(key=lambda t: (-total_tokens[t], t))
    top_terms = shared_terms[:3]
    topic_name = " ".join(top_terms).title()

    # Passages supporting this extractive topic
    supporting_ids = [
        pid for pid, tokens in passage_tokens.items()
        if any(term in tokens for term in top_terms)
    ]

    return {
        "method": "extractive_proxy_baseline",
        "abstain": False,
        "abstain_reason": None,
        "topic": topic_name,
        "supporting_passage_ids": supporting_ids
    }


# ---------------------------------------------------------------------------
# Prompt Construction & Parsing
# ---------------------------------------------------------------------------
def format_prompt(case: Dict[str, Any]) -> str:
    lines = ["Here are the bounded candidate passages:\n"]
    for p in case.get("passages", []):
        pid = p.get("id", "")
        heading = p.get("heading", "")
        text = p.get("text", "")
        heading_prefix = f" [Heading: {heading}]" if heading else ""
        lines.append(f"Passage ID: {pid}{heading_prefix}\n\"{text}\"\n")
    lines.append(
        "Analyze these passages. Formulate a short specific topic (3-6 words) with supporting passage IDs, "
        "or abstain if disjoint, conflicting without a common subject, or boilerplate."
    )
    return "\n".join(lines)


def parse_and_validate_response(
    raw_text: str,
    valid_passage_ids: Set[str]
) -> Tuple[Optional[Dict[str, Any]], List[str]]:
    """
    Parses LLM response, enforces schema, and flags unknown passage IDs.
    Returns (parsed_dict, validation_errors).
    """
    errors: List[str] = []
    if not raw_text or not raw_text.strip():
        return None, ["Empty response from model"]

    # Strip markdown code blocks if present
    cleaned = raw_text.strip()
    if cleaned.startswith("```"):
        match = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", cleaned)
        if match:
            cleaned = match.group(1).strip()

    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as jde:
        return None, [f"JSONDecodeError: {str(jde)}"]

    if not isinstance(data, dict):
        return None, ["Root of response is not a JSON object"]

    # Check abstain
    abstain = data.get("abstain")
    if not isinstance(abstain, bool):
        errors.append(f"Field 'abstain' must be boolean, got {type(abstain).__name__}")

    topic = data.get("topic")
    if abstain is False and (not isinstance(topic, str) or not topic.strip()):
        errors.append("When abstain is false, 'topic' must be a non-empty string")
    elif abstain is True and topic is not None and topic != "":
        # Abstaining with non-null topic is suspicious
        errors.append("When abstain is true, 'topic' should be null")

    supporting_ids = data.get("supporting_passage_ids")
    if not isinstance(supporting_ids, list):
        errors.append(f"Field 'supporting_passage_ids' must be a list, got {type(supporting_ids).__name__}")
        supporting_ids = []
    else:
        if any(not isinstance(pid, str) for pid in supporting_ids):
            errors.append("Supporting passage IDs must be strings")
            supporting_ids = [pid for pid in supporting_ids if isinstance(pid, str)]
        if len(set(supporting_ids)) != len(supporting_ids):
            errors.append("Supporting passage IDs must be unique")
        if abstain is False and not supporting_ids:
            errors.append("A named topic requires supporting passage IDs")
        if abstain is True and supporting_ids:
            errors.append("Abstention must not claim supporting passage IDs")
        # Check unknown passage IDs
        unknown_ids = [pid for pid in supporting_ids if pid not in valid_passage_ids]
        if unknown_ids:
            errors.append(f"Unknown supporting passage IDs rejected: {unknown_ids} (valid: {sorted(list(valid_passage_ids))})")

    result = {
        "abstain": bool(abstain),
        "topic": topic if isinstance(topic, str) and topic.strip() else None,
        "abstain_reason": data.get("abstain_reason"),
        "supporting_passage_ids": [pid for pid in supporting_ids if pid in valid_passage_ids],
        "flagged_unknown_ids": [pid for pid in supporting_ids if pid not in valid_passage_ids],
    }
    return result, errors


# ---------------------------------------------------------------------------
# Self-Check Mode
# ---------------------------------------------------------------------------
def run_self_check(cases_path: Path) -> int:
    """
    Self-check suite:
    1. Validates cases.json schema, structure, calibration (4) and held-out (8) counts.
    2. Tests parser with known good response.
    3. Tests parser with known bad responses (malformed JSON, wrong types).
    4. Tests rejection of unknown passage IDs.
    5. Tests extractive proxy baseline on synthetic fixture.
    """
    print("[SELF-CHECK] Commencing internal validation...")
    failures = 0

    # 1. Check cases.json
    if not cases_path.exists():
        print(f"FAIL: cases.json not found at {cases_path}")
        return 1

    try:
        with open(cases_path, "r", encoding="utf-8") as f:
            cases_data = json.load(f)
        cases = cases_data.get("cases", [])
        calib_cases = [c for c in cases if c.get("split") == "calibration"]
        heldout_cases = [c for c in cases if c.get("split") == "held-out"]
        categories = {c.get("category") for c in cases}

        if len(calib_cases) != 4:
            print(f"FAIL: Expected exactly 4 calibration cases, found {len(calib_cases)}")
            failures += 1
        if len(heldout_cases) != 8:
            print(f"FAIL: Expected exactly 8 held-out cases, found {len(heldout_cases)}")
            failures += 1

        required_categories = {
            "coherent_topic", "boilerplate", "misleading_heading", "distractor_separation",
            "polysemy_shared_vocab", "contradiction_common_subject", "mixed_topics", "duplicates"
        }
        missing_cat = required_categories - categories
        if missing_cat:
            print(f"FAIL: Missing required case categories: {missing_cat}")
            failures += 1
        else:
            print(f"PASS: cases.json verified (4 calibration, 8 held-out, all {len(required_categories)} categories represented).")
    except Exception as e:
        print(f"FAIL: Error loading cases.json: {e}")
        return 1

    # 2. Known good response test
    good_json = '{"abstain": false, "topic": "SQLite WAL Concurrency", "abstain_reason": null, "supporting_passage_ids": ["p1", "p2"]}'
    res, errs = parse_and_validate_response(good_json, {"p1", "p2", "p3"})
    if errs or not res or res["topic"] != "SQLite WAL Concurrency" or res["supporting_passage_ids"] != ["p1", "p2"]:
        print(f"FAIL: Good JSON validation failed: errors={errs}, res={res}")
        failures += 1
    else:
        print("PASS: Parser handles valid schema correctly.")

    # 3. Known bad responses (malformed JSON)
    bad_json = '{"abstain": false, "topic": "Unterminated string'
    res, errs = parse_and_validate_response(bad_json, {"p1"})
    if not errs or res is not None:
        print("FAIL: Parser did not flag malformed JSON.")
        failures += 1
    else:
        print("PASS: Parser safely caught malformed JSON.")

    # 4. Unknown passage ID rejection
    hallucinated_json = '{"abstain": false, "topic": "Test Topic", "abstain_reason": null, "supporting_passage_ids": ["p1", "p99_hallucinated"]}'
    res, errs = parse_and_validate_response(hallucinated_json, {"p1", "p2"})
    if not errs or "p99_hallucinated" not in str(errs):
        print(f"FAIL: Parser failed to flag unknown passage ID 'p99_hallucinated': errs={errs}")
        failures += 1
    elif res and "p99_hallucinated" in res["supporting_passage_ids"]:
        print("FAIL: Unknown ID was not stripped from supporting_passage_ids.")
        failures += 1
    else:
        print("PASS: Unknown passage ID rejection and flagging verified.")

    # 5. Extractive baseline unit check
    for invalid_ids in [[], ["p1", "p1"], [{}]]:
        _, invalid_errors = parse_and_validate_response(json.dumps({
            "abstain": False, "topic": "A Test Topic",
            "supporting_passage_ids": invalid_ids
        }), {"p1"})
        if not invalid_errors:
            print(f"FAIL: Invalid support accepted: {invalid_ids!r}")
            failures += 1

    bp_passages = [
        {"id": "p1", "text": "All rights reserved. Terms of service apply."},
        {"id": "p2", "text": "We use cookies to improve user experience. Privacy policy."}
    ]
    bp_res = run_extractive_proxy_baseline(bp_passages)
    if not bp_res["abstain"]:
        print(f"FAIL: Extractive baseline failed to abstain on pure boilerplate: {bp_res}")
        failures += 1
    else:
        print("PASS: Extractive baseline correctly abstains on boilerplate.")

    if failures == 0:
        print("\nALL SELF-CHECKS PASSED (0 failures).")
        return 0
    else:
        print(f"\nSELF-CHECK FAILED ({failures} failure(s)).")
        return 1


# ---------------------------------------------------------------------------
# Dry-Run Mode
# ---------------------------------------------------------------------------
def run_dry_run(cases_path: Path) -> int:
    """
    Dry-run suite:
    - Loads cases.
    - Evaluates extractive proxy baseline on every case.
    - Formats LLM prompts and verifies prompt lengths.
    - Emulates model responses to verify end-to-end report pipeline without network.
    """
    print("[DRY-RUN] Executing harness dry-run (NO NETWORK ACCESS)...")
    with open(cases_path, "r", encoding="utf-8") as f:
        cases_data = json.load(f)

    cases = cases_data.get("cases", [])
    print(f"Loaded {len(cases)} benchmark cases.")

    for i, case in enumerate(cases, 1):
        cid = case["id"]
        split = case["split"]
        prompt = format_prompt(case)
        baseline = run_extractive_proxy_baseline(case["passages"])
        gt = case.get("ground_truth", {})

        print(f"\n--- [{i}/{len(cases)}] {cid} ({split}) ---")
        print(f"  Passages count     : {len(case['passages'])}")
        print(f"  Prompt characters  : {len(prompt)} chars")
        print(f"  Baseline output    : abstain={baseline['abstain']}, topic='{baseline['topic']}', IDs={baseline['supporting_passage_ids']}")
        print(f"  Ground Truth       : should_abstain={gt.get('should_abstain')}, expected_topic='{gt.get('expected_topic')}'")

    print("\n[DRY-RUN] Successfully checked all prompts and extractive baseline heuristics. Ready for execution review.")
    return 0


# ---------------------------------------------------------------------------
# Ollama Client & Execution
# ---------------------------------------------------------------------------
def query_ollama_tags(endpoint: str) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """Query /api/tags to verify Ollama status and retrieve model digest. Read-only, no downloads."""
    url = f"{endpoint.rstrip('/')}/api/tags"
    req = urllib.request.Request(url, headers={"User-Agent": "TopicNamingExperiment/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=10.0) as resp:
            if resp.status == 200:
                body = json.loads(resp.read().decode("utf-8"))
                return body, None
            return None, f"HTTP {resp.status}"
    except Exception as e:
        return None, str(e)


def generate_ollama_completion(
    endpoint: str,
    model: str,
    prompt: str,
    timeout: float = REQUEST_TIMEOUT_SECONDS,
    max_tokens: int = MAX_OUTPUT_TOKENS
) -> Tuple[Optional[Dict[str, Any]], float, Optional[str]]:
    """
    Serialized Ollama request with bounded token limit and timeout.
    Returns (raw_response_dict, latency_ms, error_string).
    """
    url = f"{endpoint.rstrip('/')}/api/generate"
    payload = {
        "model": model,
        "prompt": prompt,
        "system": SYSTEM_PROMPT,
        "stream": False,
        "format": "json",
        "options": {
            "num_predict": max_tokens,
            "num_ctx": 4096,
            "seed": 42,
            "temperature": 0.0,
            "top_p": 0.9,
        }
    }
    data_bytes = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data_bytes,
        headers={"Content-Type": "application/json", "User-Agent": "TopicNamingExperiment/1.0"}
    )

    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 200:
                raw_json = json.loads(resp.read().decode("utf-8"))
                latency_ms = (time.perf_counter() - t0) * 1000.0
                return raw_json, latency_ms, None
            return None, (time.perf_counter() - t0) * 1000.0, f"HTTP Error {resp.status}"
    except urllib.error.URLError as ue:
        latency_ms = (time.perf_counter() - t0) * 1000.0
        return None, latency_ms, f"URLError: {ue.reason}"
    except Exception as e:
        latency_ms = (time.perf_counter() - t0) * 1000.0
        return None, latency_ms, f"Exception: {str(e)}"


# ---------------------------------------------------------------------------
# Live Run Mode
# ---------------------------------------------------------------------------
def run_live_experiment(
    cases_path: Path,
    output_dir: Path,
    endpoint: str = DEFAULT_ENDPOINT,
    model: str = DEFAULT_MODEL
) -> int:
    """
    Executes live serialized experiment against loopback Ollama.
    DO NOT INVOKE WITHOUT PRIMARY REVIEW.
    """
    print(f"[RUN] Starting live experiment against {endpoint} with model {model}...")

    # 1. Verify model identity via /api/tags
    tags_info, tags_err = query_ollama_tags(endpoint)
    if tags_err or not tags_info:
        print(f"ABORT: Unable to contact Ollama endpoint at {endpoint}: {tags_err}")
        return 1

    available_models = tags_info.get("models", [])
    model_record = next((m for m in available_models if m.get("name") == model or m.get("model") == model), None)
    if not model_record:
        print(f"ABORT: Model '{model}' not found in Ollama /api/tags. Available: {[m.get('name') for m in available_models]}")
        print("Note: Automatic downloads are strictly prohibited.")
        return 1

    model_digest = model_record.get("digest", "unknown")
    print(f"Verified model: {model} (digest: {model_digest[:16]}...)")

    # Load cases
    with open(cases_path, "r", encoding="utf-8") as f:
        cases_data = json.load(f)

    cases = cases_data.get("cases", [])
    run_records: List[Dict[str, Any]] = []

    # Prepare cases queue, including stability repetitions
    execution_queue: List[Tuple[Dict[str, Any], bool]] = []
    for c in cases:
        execution_queue.append((c, False))
        if c.get("repeat_for_stability"):
            execution_queue.append((c, True))

    output_dir.mkdir(parents=True, exist_ok=True)
    timestamp_str = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%SZ")
    run_output_file = output_dir / f"topic_naming_run_{timestamp_str}.json"

    initial_memory = get_system_memory_snapshot()

    print(f"Total scheduled executions: {len(execution_queue)} (12 cases + 2 stability repetitions)")

    for idx, (case, is_repeat) in enumerate(execution_queue, 1):
        cid = case["id"] + ("_stability_repeat" if is_repeat else "")
        valid_pids = {p["id"] for p in case["passages"]}
        prompt = format_prompt(case)
        baseline = run_extractive_proxy_baseline(case["passages"])

        # Cold-ish flag for the very first request
        is_first_request = (idx == 1)
        cold_status = "cold-ish only, no hard cold guarantee" if is_first_request else "warm"

        print(f"[{idx}/{len(execution_queue)}] Querying {cid} ({cold_status})...", end="", flush=True)

        raw_resp, latency_ms, err = generate_ollama_completion(
            endpoint=endpoint,
            model=model,
            prompt=prompt,
            timeout=REQUEST_TIMEOUT_SECONDS,
            max_tokens=MAX_OUTPUT_TOKENS
        )

        response_text = raw_resp.get("response", "") if raw_resp else ""
        parsed_result, validation_errors = parse_and_validate_response(response_text, valid_pids)

        record = {
            "execution_index": idx,
            "case_id": case["id"],
            "is_repeat": is_repeat,
            "split": case.get("split"),
            "category": case.get("category"),
            "cold_status": cold_status,
            "latency_ms": latency_ms,
            "prompt_chars": len(prompt),
            "raw_prompt": prompt,
            "raw_response": response_text,
            "raw_ollama_response": raw_resp,
            "parsed_llm_result": parsed_result,
            "validation_errors": validation_errors,
            "extractive_proxy_baseline": baseline,
            "ground_truth": case.get("ground_truth"),
            "error": err
        }
        run_records.append(record)

        if err:
            print(f" ERROR: {err} ({latency_ms:.1f}ms)")
        else:
            topic_disp = parsed_result.get("topic") if parsed_result else "PARSING_FAILED"
            abstain_disp = parsed_result.get("abstain") if parsed_result else "ERR"
            print(f" DONE ({latency_ms:.1f}ms) -> abstain={abstain_disp}, topic='{topic_disp}'")

    final_memory = get_system_memory_snapshot()

    # Compile experiment output report
    experiment_report = {
        "metadata": {
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
            "model": model,
            "model_digest": model_digest,
            "endpoint": endpoint,
            "request_timeout_seconds": REQUEST_TIMEOUT_SECONDS,
            "max_output_tokens": MAX_OUTPUT_TOKENS,
            "generation_options": {"num_predict": MAX_OUTPUT_TOKENS, "num_ctx": 4096, "seed": 42, "temperature": 0.0, "top_p": 0.9},
            "system_prompt": SYSTEM_PROMPT,
            "memory_scope": "Host-wide snapshots only; model/process/GPU peak memory is unmeasured",
            "total_executions": len(execution_queue),
            "disclaimer": (
                "Synthetic Windows prototype. Host RTX 5060 execution is NOT evidence for a tiny "
                "on-device Android model. JSON validity is NOT semantic quality; manual quality review is required."
            ),
            "initial_system_memory": initial_memory,
            "final_system_memory": final_memory,
        },
        "records": run_records
    }

    with open(run_output_file, "w", encoding="utf-8") as out_f:
        json.dump(experiment_report, out_f, indent=2)

    print(f"\n[RUN COMPLETE] Raw results written to: {run_output_file}")
    print("MANDATORY: Perform manual qualitative review of outputs. JSON validity != quality.")
    return 1 if any(r["error"] or r["validation_errors"] for r in run_records) else 0


# ---------------------------------------------------------------------------
# CLI Entrypoint
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="Isolated synthetic topic-naming experiment harness."
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--self-check",
        action="store_true",
        help="Validate parser, schema, error handling, unknown passage ID rejection (NO NETWORK)."
    )
    group.add_argument(
        "--dry-run",
        action="store_true",
        help="Evaluate extractive baseline and build prompts without network calls."
    )
    group.add_argument(
        "--run",
        action="store_true",
        help="Execute serialized requests against loopback Ollama (requires primary review)."
    )

    parser.add_argument(
        "--cases",
        type=Path,
        default=Path(__file__).parent / "cases.json",
        help="Path to cases.json benchmark file."
    )
    parser.add_argument(
        "--results-dir",
        type=Path,
        default=Path("build/agy-tasks/topic-naming/results"),
        help="Directory to save experiment run results."
    )
    parser.add_argument(
        "--endpoint",
        type=str,
        default=DEFAULT_ENDPOINT,
        help=f"Ollama loopback endpoint (default: {DEFAULT_ENDPOINT})."
    )
    parser.add_argument(
        "--model",
        type=str,
        default=DEFAULT_MODEL,
        help=f"Ollama model name (default: {DEFAULT_MODEL})."
    )

    args = parser.parse_args()

    if args.endpoint != DEFAULT_ENDPOINT or args.model not in REVIEWED_MODELS:
        parser.error("This reviewed prototype permits only the fixed loopback endpoint and reviewed models")

    if args.self_check:
        sys.exit(run_self_check(args.cases))
    elif args.dry_run:
        sys.exit(run_dry_run(args.cases))
    elif args.run:
        sys.exit(run_live_experiment(
            cases_path=args.cases,
            output_dir=args.results_dir,
            endpoint=args.endpoint,
            model=args.model
        ))


if __name__ == "__main__":
    main()
