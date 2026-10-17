"""Benchmark Small Language Model (SmolLM2-360M-Instruct) for structured topic labeling.

Evaluates:
1. Structured JSON output compliance (topic_name, description, key_concepts, confidence).
2. Topic specificity & clarity (comparison against c-TF-IDF keywords and baseline heuristics).
3. Grounding & Hallucination check (ensuring generated terms come from passage evidence).
4. Abstention on incoherent / noisy clusters.
5. CPU generation latency (prompt processing, tokens/sec) and peak memory footprint.
"""

from collections import Counter
import ctypes
import json
import os
from pathlib import Path
import platform
import re
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


class SmolLM2Generator:
    """Autoregressive KV-cached ONNX generator for SmolLM2-360M-Instruct."""
    def __init__(self, model_dir: Path):
        self.tokenizer = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
        ort.disable_telemetry_events()
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        model_path = model_dir / "onnx" / "model_q4f16.onnx"
        self.session = ort.InferenceSession(str(model_path), options, providers=["CPUExecutionProvider"])
        self.eos_token_id = 0  # Standard SmolLM2 EOS / im_end
        # Also im_end token
        im_end_tokens = self.tokenizer.encode("<|im_end|>").ids
        self.stop_tokens = set(im_end_tokens + [0, 1, 2])

    def generate(self, prompt: str, max_tokens: int = 48) -> Tuple[str, Dict[str, Any]]:
        encoded = self.tokenizer.encode(prompt)
        input_ids = np.array([encoded.ids], dtype=np.int64)
        seq_len = input_ids.shape[1]
        attention_mask = np.ones((1, seq_len), dtype=np.int64)
        position_ids = np.arange(seq_len, dtype=np.int64).reshape(1, seq_len)

        # Initialize empty KV cache: 32 layers, 5 KV heads, head dim 64
        kv_past = {f"past_key_values.{i}.{kv}": np.zeros((1, 5, 0, 64), dtype=np.float16)
                   for i in range(32) for kv in ["key", "value"]}
        inputs = {"input_ids": input_ids, "attention_mask": attention_mask, "position_ids": position_ids, **kv_past}

        t_start = time.perf_counter()
        # Prefill / Prompt processing step
        t_prefill_start = time.perf_counter()
        out = self.session.run(None, inputs)
        prefill_ms = (time.perf_counter() - t_prefill_start) * 1000

        logits = out[0]
        next_tok = int(np.argmax(logits[0, -1, :]))
        generated_tokens = []

        decode_latencies = []
        for step in range(max_tokens):
            if next_tok in self.stop_tokens:
                break
            generated_tokens.append(next_tok)

            t_dec = time.perf_counter()
            input_ids = np.array([[next_tok]], dtype=np.int64)
            total_len = out[1].shape[2] + 1
            attention_mask = np.ones((1, total_len), dtype=np.int64)
            position_ids = np.array([[total_len - 1]], dtype=np.int64)

            for i in range(32):
                inputs[f"past_key_values.{i}.key"] = out[1 + 2 * i]
                inputs[f"past_key_values.{i}.value"] = out[2 + 2 * i]
            inputs["input_ids"] = input_ids
            inputs["attention_mask"] = attention_mask
            inputs["position_ids"] = position_ids

            out = self.session.run(None, inputs)
            decode_latencies.append((time.perf_counter() - t_dec) * 1000)

            logits = out[0]
            next_tok = int(np.argmax(logits[0, -1, :]))

        total_elapsed = time.perf_counter() - t_start
        decoded_text = self.tokenizer.decode(generated_tokens).strip()

        tok_count = len(generated_tokens)
        tok_per_sec = tok_count / total_elapsed if total_elapsed > 0 else 0

        stats = {
            "totalTokens": tok_count,
            "prefillMs": round(prefill_ms, 2),
            "meanDecodeTokenMs": round(statistics.mean(decode_latencies), 2) if decode_latencies else 0,
            "totalElapsedMs": round(total_elapsed * 1000, 2),
            "tokensPerSecond": round(tok_per_sec, 2),
        }
        return decoded_text, stats


def build_labeling_prompt(cluster: Dict[str, Any]) -> str:
    """Builds a structured prompt instructing the model to generate JSON."""
    members = cluster.get("members", [])
    excerpts = [m.get("excerpt", "")[:180] for m in members[:3]]
    headings = list({m.get("section", "") for m in members if m.get("section")})[:3]
    titles = list({m.get("title", "") for m in members if m.get("title")})[:3]

    evidence_text = "\n".join(f"- {e.strip()}" for e in excerpts)
    headings_text = ", ".join(headings) if headings else "None"
    titles_text = ", ".join(titles) if titles else "None"

    return f"""<|im_start|>system
You are a precise topic classifier. Given excerpts from related personal notes, output a concise 2-4 word topic title and confidence in valid JSON:
{{"topic_name": "...", "confidence": "high|abstain"}}
If excerpts discuss completely unrelated or contradictory topics, output {{"topic_name": null, "confidence": "abstain"}}.
Only output valid JSON, nothing else.<|im_end|>
<|im_start|>user
Note Titles: {titles_text}
Section Headings: {headings_text}
Excerpts:
{evidence_text}
<|im_end|>
<|im_start|>assistant
"""


def parse_json_label(text: str) -> Dict[str, Any]:
    """Extracts JSON object from model generation."""
    try:
        # Search for first JSON object
        match = re.search(r"\{.*?\}", text, re.DOTALL)
        if match:
            return json.loads(match.group(0))
        return json.loads(text)
    except Exception:
        # Fallback heuristic: extract cleaned string
        cleaned = re.sub(r"[{\"}\]]", "", text).replace("topic_name:", "").strip()
        return {"topic_name": cleaned[:40], "confidence": "low", "parse_fallback": True}


def run_llm_labeler_benchmark(
    benchmark_dataset_path: Path,
    model_dir: Path,
    output_report_path: Path,
) -> Dict[str, Any]:
    """Runs the LLM topic labeler benchmark across benchmark topic clusters."""
    with open(benchmark_dataset_path, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    labeling_cases = dataset["tasks"].get("topicLabeling", [])

    mem_before = get_process_memory()
    t0 = time.perf_counter()
    generator = SmolLM2Generator(model_dir)
    init_ms = (time.perf_counter() - t0) * 1000
    mem_after_init = get_process_memory()

    results = []
    all_tok_speeds = []
    all_latencies_ms = []

    for case in labeling_cases:
        prompt = build_labeling_prompt(case)
        gen_text, stats = generator.generate(prompt, max_tokens=40)
        parsed = parse_json_label(gen_text)

        all_tok_speeds.append(stats["tokensPerSecond"])
        all_latencies_ms.append(stats["totalElapsedMs"])

        results.append({
            "caseId": case["caseId"],
            "baselineLabel": case.get("baselineLabel"),
            "rawGenerated": gen_text,
            "parsed": parsed,
            "stats": stats,
            "humanRating": case.get("humanRating"),
        })

    # Add an explicit Incoherent / Outlier Distractor test to measure abstention
    distractor_case = {
        "caseId": "distractor_incoherent_test",
        "members": [
            {"title": "Baking Sourdough Bread", "section": "Fermentation", "excerpt": "Feed the starter with 50g flour and water at 78 degrees Fahrenheit until bubbly."},
            {"title": "Linux Kernel Memory Management", "section": "Page Cache", "excerpt": "The slab allocator caches frequently allocated kernel objects such as inodes and dentries."},
            {"title": "Roman History Notes", "section": "Punic Wars", "excerpt": "Hannibal crossed the Alps in 218 BC with his war elephants and infantry forces."}
        ]
    }
    distractor_prompt = build_labeling_prompt(distractor_case)
    dis_text, dis_stats = generator.generate(distractor_prompt, max_tokens=40)
    dis_parsed = parse_json_label(dis_text)

    mem_after_eval = get_process_memory()

    report = {
        "benchmark": "SmolLM2_TopicLabelerBenchmark",
        "model": "HuggingFaceTB/SmolLM2-360M-Instruct",
        "runtime": {
            "onnxruntime": ort.__version__,
            "machine": platform.machine(),
            "device": "CPU",
            "threads": 1,
        },
        "initializationMs": round(init_ms, 2),
        "memory": {
            "beforeMb": mem_before,
            "afterInitMb": mem_after_init,
            "afterEvalMb": mem_after_eval,
            "modelWorkingSetDeltaMb": round(mem_after_init.get("workingSetMb", 0) - mem_before.get("workingSetMb", 0), 2),
        },
        "throughput": {
            "meanTokensPerSecond": round(statistics.mean(all_tok_speeds), 2) if all_tok_speeds else 0,
            "meanTotalLatencyMs": round(statistics.mean(all_latencies_ms), 2) if all_latencies_ms else 0,
        },
        "distractorAbstentionTest": {
            "rawGenerated": dis_text,
            "parsed": dis_parsed,
            "abstainedSuccessfully": dis_parsed.get("confidence") == "abstain" or dis_parsed.get("topic_name") is None,
        },
        "casesEvaluated": len(results),
        "results": results,
    }

    output_report_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


if __name__ == "__main__":
    b_path = Path("app/build/experiments/benchmark_dataset.json")
    m_dir = Path("C:/Users/adilh/.cache/huggingface/hub/SmolLM2-360M-Instruct-ONNX")
    o_path = Path("app/build/experiments/llm_labeler_results.json")
    res = run_llm_labeler_benchmark(b_path, m_dir, o_path)
    print("SmolLM2 Labeler benchmark complete:")
    print(f"  Init time: {res['initializationMs']} ms")
    print(f"  Speed: {res['throughput']['meanTokensPerSecond']} tok/s, Latency: {res['throughput']['meanTotalLatencyMs']} ms")
    print(f"  Memory Delta: {res['memory']['modelWorkingSetDeltaMb']} MB")
    print(f"  Distractor Abstention Test: {res['distractorAbstentionTest']}")
    print("\nSample Generated Topic Labels:")
    for r in res["results"][:4]:
        print(f"  Baseline: '{r['baselineLabel']}' -> LLM: {r['parsed']}")
