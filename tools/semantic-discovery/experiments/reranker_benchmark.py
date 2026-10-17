"""Evaluates compact cross-encoder reranker (ms-marco-MiniLM-L6-v2) on benchmark passage pairs.

Measures:
- Pairwise ranking quality (AUC on human judgments, Precision@K).
- Bidirectional scoring asymmetry (|score(A, B) - score(B, A)|).
- Elimination of false-positive semantic matches (high cosine, low cross-attention).
- Abstention on overlong pairs (>512 tokens).
- Warm CPU inference latency (p50, p95) and memory footprint.
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


def compute_ranking_metrics(
    pairs: List[Dict[str, Any]],
    scores: Dict[str, float],
    limit: int = 10,
) -> Dict[str, Any]:
    """Computes ranking quality, distinct notes, and AUC on judged pairs."""
    scored_pairs = [p for p in pairs if p["caseId"] in scores and scores[p["caseId"]] is not None]
    ordered = sorted(scored_pairs, key=lambda p: -scores[p["caseId"]])
    selected = ordered[:limit]

    # Evaluate against human ratings
    decisions = Counter()
    positive_scores = []
    negative_scores = []

    for p in scored_pairs:
        cid = p["caseId"]
        hr = p.get("humanRating")
        if hr and hr.get("decision"):
            dec = hr["decision"].casefold()
            decisions[dec] += 1
            if dec in ("keep", "keepdeduplicate"):
                positive_scores.append(scores[cid])
            elif dec in ("suppress", "withhold"):
                negative_scores.append(scores[cid])

    # Compute Mann-Whitney U / ROC-AUC on judged subset
    auc = None
    if positive_scores and negative_scores:
        concordant = sum((pos > neg) + 0.5 * (pos == neg) for pos in positive_scores for neg in negative_scores)
        auc = concordant / (len(positive_scores) * len(negative_scores))

    # Measure Note diversity
    seen_pages = Counter()
    for p in selected:
        seen_pages[p["left"]["pageId"]] += 1
        seen_pages[p["right"]["pageId"]] += 1

    return {
        "selectedCount": len(selected),
        "selectedTopDecisions": dict(Counter(
            (p.get("humanRating") or {}).get("decision", "unrated") for p in selected
        )),
        "distinctNotesInTopK": len(seen_pages),
        "maxAppearancesPerNote": max(seen_pages.values(), default=0),
        "judgedPositiveCount": len(positive_scores),
        "judgedNegativeCount": len(negative_scores),
        "rocAucOnJudgedPairs": round(auc, 4) if auc is not None else None,
    }


def run_reranker_benchmark(
    benchmark_dataset_path: Path,
    model_assets_dir: Path,
    output_report_path: Path,
) -> Dict[str, Any]:
    """Runs the cross-encoder reranker benchmark on all connection pairs."""
    with open(benchmark_dataset_path, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    export_path = model_assets_dir / "onnx" / "model_qint8_arm64.onnx"
    tokenizer_path = model_assets_dir / "tokenizer.json"

    if not export_path.is_file() or not tokenizer_path.is_file():
        raise FileNotFoundError(f"Model or tokenizer missing in {model_assets_dir}")

    tokenizer = Tokenizer.from_file(str(tokenizer_path))

    # Initialize ONNX session
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1  # Standard single-thread baseline for mobile feasibility

    mem_before = get_process_memory()
    start_init = time.perf_counter_ns()
    session = ort.InferenceSession(str(export_path), options, providers=["CPUExecutionProvider"])
    init_time_ms = (time.perf_counter_ns() - start_init) / 1e6
    mem_after_init = get_process_memory()

    results_by_split = {}
    all_latencies_ms = []
    asymmetries = []
    abstentions = 0

    pairs_data = dataset["tasks"]["passageConnections"]
    for split_name in ("calibration", "held_out"):
        split_pairs = pairs_data.get(split_name, [])
        reranker_scores = {}
        cosine_scores = {}
        predictions = []

        for pair in split_pairs:
            cid = pair["caseId"]
            excerpt_a = pair["left"]["excerpt"]
            excerpt_b = pair["right"]["excerpt"]
            cos = pair["cosine"]
            cosine_scores[cid] = cos

            # Bidirectional encodings
            enc_ab = tokenizer.encode(excerpt_a, excerpt_b)
            enc_ba = tokenizer.encode(excerpt_b, excerpt_a)
            len_ab = len(enc_ab.ids)
            len_ba = len(enc_ba.ids)

            # Strict budget: abstain if either direction exceeds 512 tokens
            if max(len_ab, len_ba) > 512:
                abstentions += 1
                reranker_scores[cid] = None
                predictions.append({
                    "caseId": cid,
                    "score": None,
                    "abstention": "exceeds_512_tokens",
                    "lengths": [len_ab, len_ba],
                })
                continue

            # Run inference both ways
            pair_start = time.perf_counter_ns()
            logits = []
            for enc in [enc_ab, enc_ba]:
                inputs = {
                    "input_ids": np.array([enc.ids], dtype=np.int64),
                    "attention_mask": np.array([enc.attention_mask], dtype=np.int64),
                    "token_type_ids": np.array([enc.type_ids], dtype=np.int64),
                }
                out = session.run(None, inputs)[0]
                logits.append(float(out[0, 0]))

            elapsed_ms = (time.perf_counter_ns() - pair_start) / 1e6
            all_latencies_ms.append(elapsed_ms)

            mean_score = sum(logits) / 2.0
            diff = abs(logits[0] - logits[1])
            asymmetries.append(diff)
            reranker_scores[cid] = mean_score

            predictions.append({
                "caseId": cid,
                "meanLogit": round(mean_score, 4),
                "logitsAB": round(logits[0], 4),
                "logitsBA": round(logits[1], 4),
                "asymmetry": round(diff, 4),
                "latencyMs": round(elapsed_ms, 2),
                "cosine": cos,
            })

        # Calculate comparative ranking metrics
        limit = min(10, len(split_pairs))
        cosine_metrics = compute_ranking_metrics(split_pairs, cosine_scores, limit=limit)
        reranker_metrics = compute_ranking_metrics(split_pairs, reranker_scores, limit=limit)

        results_by_split[split_name] = {
            "pairCount": len(split_pairs),
            "abstentionCount": sum(1 for p in predictions if p.get("score") is None),
            "cosineRankMetrics": cosine_metrics,
            "rerankerRankMetrics": reranker_metrics,
            "predictions": predictions,
        }

    mem_after_eval = get_process_memory()

    sorted_latencies = sorted(all_latencies_ms)
    p50_ms = sorted_latencies[len(sorted_latencies) // 2] if sorted_latencies else 0
    p95_ms = sorted_latencies[int(len(sorted_latencies) * 0.95)] if sorted_latencies else 0

    report = {
        "benchmark": "CrossEncoderRerankerBenchmark",
        "model": "cross-encoder/ms-marco-MiniLM-L6-v2",
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
            "totalPairsEvaluated": len(all_latencies_ms),
            "warmPairLatencyP50Ms": round(p50_ms, 2),
            "warmPairLatencyP95Ms": round(p95_ms, 2),
            "meanPairLatencyMs": round(statistics.mean(all_latencies_ms), 2) if all_latencies_ms else 0,
        },
        "orderSensitivity": {
            "meanAsymmetry": round(statistics.mean(asymmetries), 4) if asymmetries else 0,
            "maxAsymmetry": round(max(asymmetries), 4) if asymmetries else 0,
        },
        "resultsBySplit": results_by_split,
    }

    output_report_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


if __name__ == "__main__":
    b_path = Path("app/build/experiments/benchmark_dataset.json")
    m_dir = Path("app/build/discovery-feasibility/auxiliary-reranker")
    o_path = Path("app/build/experiments/reranker_results.json")
    res = run_reranker_benchmark(b_path, m_dir, o_path)
    print("Reranker benchmark complete:")
    print(f"  Init time: {res['initializationMs']} ms")
    print(f"  Pair Latency: p50={res['latency']['warmPairLatencyP50Ms']} ms, p95={res['latency']['warmPairLatencyP95Ms']} ms")
    print(f"  Memory Delta: {res['memory']['modelWorkingSetDeltaMb']} MB")
    for s in ["calibration", "held_out"]:
        c_auc = res['resultsBySplit'][s]['cosineRankMetrics']['rocAucOnJudgedPairs']
        r_auc = res['resultsBySplit'][s]['rerankerRankMetrics']['rocAucOnJudgedPairs']
        print(f"  {s}: Cosine AUC={c_auc} -> Reranker AUC={r_auc}")
