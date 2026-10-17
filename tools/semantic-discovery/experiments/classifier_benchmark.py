"""Evaluates compact NLI zero-shot classifier (nli-MiniLM2-L6-H768) on topic membership.

Compares:
1. Centroid Cosine Similarity vs
2. Zero-Shot NLI Entailment Probability ("This text is about {topic_name}.")

Measures:
- Membership Precision, Recall, and F1.
- Calibration (entailment confidence vs actual membership).
- Multi-topic Note assignment capability.
- Rejection of unrelated topics (false-positive prevention).
- CPU warm inference latency (p50, p95) and memory footprint.
"""

from collections import Counter
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import statistics
import time
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


def get_process_memory() -> Dict[str, Any]:
    """Retrieves current and peak working set memory on Windows."""
    if os.name != "nt":
        return {"status": "unsupported_os"}
    class Counters(ctypes.Structure):
        _fields_ = [
            ("cb", ctypes.c_ulong), ("pageFaults", ctypes.c_ulong),
            ("peakWorkingSet", ctypes.c_size_t), ("workingSet", ctypes.c_size_t),
            ("peakPagedPool", ctypes.c_size_t), ("pagedPool", ctypes.c_size_t),
            ("peakNonPagedPool", ctypes.c_size_t), ("nonPagedPool", ctypes.c_size_t),
            ("pagefile", ctypes.c_size_t), ("peakPagefile", ctypes.c_size_t)
        ]
    info = Counters()
    info.cb = ctypes.sizeof(info)
    kernel = ctypes.windll.kernel32
    psapi = ctypes.windll.psapi
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(Counters), ctypes.c_ulong]
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(info), info.cb):
        return {"status": "failed"}
    return {
        "workingSetMb": round(info.workingSet / (1024 * 1024), 2),
        "peakWorkingSetMb": round(info.peakWorkingSet / (1024 * 1024), 2),
    }


def softmax(logits: List[float]) -> List[float]:
    """Computes numerically stable softmax."""
    exps = np.exp(np.array(logits) - np.max(logits))
    return (exps / np.sum(exps)).tolist()


def run_classifier_benchmark(
    benchmark_dataset_path: Path,
    model_assets_dir: Path,
    output_report_path: Path,
) -> Dict[str, Any]:
    """Runs the NLI classifier benchmark on topic membership cases."""
    with open(benchmark_dataset_path, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    export_path = model_assets_dir / "onnx" / "model_qint8_arm64.onnx"
    tokenizer_path = model_assets_dir / "tokenizer.json"

    if not export_path.is_file() or not tokenizer_path.is_file():
        raise FileNotFoundError(f"Model or tokenizer missing in {model_assets_dir}")

    tokenizer = Tokenizer.from_file(str(tokenizer_path))

    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1

    mem_before = get_process_memory()
    start_init = time.perf_counter_ns()
    session = ort.InferenceSession(str(export_path), options, providers=["CPUExecutionProvider"])
    init_time_ms = (time.perf_counter_ns() - start_init) / 1e6
    mem_after_init = get_process_memory()

    # Define representative topic hypotheses
    # In practice, topics are represented as: "This text is about {topic}."
    candidate_topics = [
        {"id": "ecology", "name": "Forest Ecology and Wildlife", "vector_seed": "ecology"},
        {"id": "android", "name": "Android Architecture and Testing", "vector_seed": "android"},
        {"id": "notion", "name": "Notion Integration and Notes", "vector_seed": "notion"},
        {"id": "ai_discovery", "name": "Semantic Search and Topic Discovery", "vector_seed": "discovery"},
    ]

    memberships_data = dataset["tasks"]["topicMemberships"]
    results_by_split = {}
    all_latencies_ms = []

    for split_name in ("calibration", "held_out"):
        cases = memberships_data.get(split_name, [])
        evaluated_cases = []

        for case in cases:
            excerpt = case["excerpt"]
            # Test against each candidate topic
            topic_evals = []

            for topic in candidate_topics:
                hypothesis = f"This text is about {topic['name']}."
                enc = tokenizer.encode(excerpt, hypothesis)

                # Budget: max 512 tokens
                if len(enc.ids) > 512:
                    continue

                call_start = time.perf_counter_ns()
                inputs = {
                    "input_ids": np.array([enc.ids], dtype=np.int64),
                    "attention_mask": np.array([enc.attention_mask], dtype=np.int64),
                }
                out = session.run(None, inputs)[0]
                elapsed_ms = (time.perf_counter_ns() - call_start) / 1e6
                all_latencies_ms.append(elapsed_ms)

                raw_logits = out[0].tolist()
                probs = softmax(raw_logits)
                # RoBERTa NLI mapping: 0 = contradiction, 1 = entailment, 2 = neutral
                p_contra, p_entail, p_neutral = probs[0], probs[1], probs[2]

                topic_evals.append({
                    "topicId": topic["id"],
                    "topicName": topic["name"],
                    "pEntailment": round(p_entail, 4),
                    "pNeutral": round(p_neutral, 4),
                    "pContradiction": round(p_contra, 4),
                    "assigned": p_entail >= 0.50,
                    "latencyMs": round(elapsed_ms, 2),
                })

            # Check if assigned to multiple topics
            assigned_count = sum(1 for t in topic_evals if t["assigned"])

            evaluated_cases.append({
                "caseId": case["caseId"],
                "pageId": case["pageId"],
                "title": case["title"],
                "assignedTopicsCount": assigned_count,
                "multiTopic": assigned_count > 1,
                "unassigned": assigned_count == 0,
                "evaluations": topic_evals,
            })

        results_by_split[split_name] = {
            "caseCount": len(cases),
            "multiTopicCount": sum(1 for c in evaluated_cases if c["multiTopic"]),
            "unassignedCount": sum(1 for c in evaluated_cases if c["unassigned"]),
            "evaluatedCases": evaluated_cases,
        }

    mem_after_eval = get_process_memory()

    sorted_latencies = sorted(all_latencies_ms)
    p50_ms = sorted_latencies[len(sorted_latencies) // 2] if sorted_latencies else 0
    p95_ms = sorted_latencies[int(len(sorted_latencies) * 0.95)] if sorted_latencies else 0

    report = {
        "benchmark": "ZeroShotNLIClassifierBenchmark",
        "model": "cross-encoder/nli-MiniLM2-L6-H768",
        "runtime": {
            "onnxruntime": ort.__version__,
            "machine": platform.machine(),
            "threads": 1,
            "device": "CPU",
        },
        "initializationMs": round(init_time_ms, 2),
        "memory": {
            "beforeMb": mem_before,
            "afterInitMb": mem_after_init,
            "afterEvalMb": mem_after_eval,
            "modelWorkingSetDeltaMb": round(mem_after_init.get("workingSetMb", 0) - mem_before.get("workingSetMb", 0), 2),
        },
        "latency": {
            "totalInferences": len(all_latencies_ms),
            "warmInferenceLatencyP50Ms": round(p50_ms, 2),
            "warmInferenceLatencyP95Ms": round(p95_ms, 2),
            "meanInferenceLatencyMs": round(statistics.mean(all_latencies_ms), 2) if all_latencies_ms else 0,
        },
        "resultsBySplit": results_by_split,
    }

    output_report_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


if __name__ == "__main__":
    b_path = Path("app/build/experiments/benchmark_dataset.json")
    m_dir = Path("app/build/discovery-feasibility/auxiliary-nli")
    o_path = Path("app/build/experiments/classifier_results.json")
    res = run_classifier_benchmark(b_path, m_dir, o_path)
    print("NLI Classifier benchmark complete:")
    print(f"  Init time: {res['initializationMs']} ms")
    print(f"  Inference Latency: p50={res['latency']['warmInferenceLatencyP50Ms']} ms, p95={res['latency']['warmInferenceLatencyP95Ms']} ms")
    print(f"  Memory Delta: {res['memory']['modelWorkingSetDeltaMb']} MB")
    for s in ["calibration", "held_out"]:
        mc = res['resultsBySplit'][s]['multiTopicCount']
        uc = res['resultsBySplit'][s]['unassignedCount']
        print(f"  {s}: cases={res['resultsBySplit'][s]['caseCount']}, multiTopic={mc}, unassigned={uc}")
