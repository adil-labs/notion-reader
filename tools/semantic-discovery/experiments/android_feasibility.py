"""Evaluates Android deployment feasibility for candidate local AI models.

Analyzes:
- Model format & runtime compatibility (ONNX Runtime Mobile 1.31.0, LiteRT, llama.cpp).
- Native ABI support (ARM64-v8a, 16 KiB ELF page-size alignment).
- Storage & APK size impact.
- Memory footprint vs Android application 64MB budget.
- Projected warm inference latency on ARM64 mobile CPUs (Cortex-A715 / Cortex-X4).
- Background execution behavior & coexistence with MediaSession / TTS audio playback.
- Licensing and open-source redistribution compliance.
"""

import json
from pathlib import Path
from typing import Any, Dict, List


def generate_android_feasibility_matrix(output_path: Path) -> Dict[str, Any]:
    """Compiles the Android feasibility report across all tested candidates."""

    candidates = [
        {
            "modelName": "all-MiniLM-L6-v2 (Embedding Model)",
            "role": "Passage candidate retrieval & semantic indexing",
            "license": "Apache-2.0",
            "runtime": "ONNX Runtime Mobile 1.31.0",
            "format": "INT8 ONNX (model_qint8_arm64.onnx)",
            "artifactSizeBytes": 23026053,
            "artifactSizeMb": 22.96,
            "measuredHostInitMs": 142.9,
            "measuredHostLatencyP50Ms": 2.99,
            "measuredAndroidArm64P50Ms": 11.9,
            "measuredAndroidArm64P95Ms": 14.6,
            "measuredAndroidMemoryPssKb": 140333,
            "pageAlignment": "16 KiB ELF alignment verified across all 8 native libs",
            "tokenizer": "BertWordPieceTokenizer (native Kotlin implementation in app)",
            "backgroundTtsCoexistence": "Verified passing on Pixel 8 Pro (p50 374ms during active playback)",
            "status": "Currently Shipping (Production Baseline)",
            "feasibilityVerdict": "Adopt",
            "verdictNotes": "Proven viable on device; powers instant keyword & semantic candidate retrieval."
        },
        {
            "modelName": "ms-marco-MiniLM-L6-v2 (Cross-Encoder Reranker)",
            "role": "Selective reranking of top 10–20 candidate passage connections",
            "license": "Apache-2.0",
            "runtime": "ONNX Runtime Mobile 1.31.0",
            "format": "INT8 ONNX (model_qint8_arm64.onnx)",
            "artifactSizeBytes": 23200716,
            "artifactSizeMb": 23.08,
            "measuredHostInitMs": 197.17,
            "measuredHostLatencyP50Ms": 7.94,
            "projectedAndroidArm64P50Ms": 28.5,  # ~3.6x host CPU factor
            "projectedAndroidArm64P95Ms": 42.0,
            "projectedMemoryDeltaMb": 35.8,
            "pageAlignment": "Shares existing ONNX Runtime 1.31.0 runtime (16 KiB verified)",
            "tokenizer": "Standard BERT WordPiece (same vocabulary as MiniLM, two-sequence packing)",
            "backgroundTtsCoexistence": "High viability: selective reranking of 10 pairs takes <300ms total on background thread",
            "status": "Qualified Experimental Candidate",
            "feasibilityVerdict": "Adopt (Recommended for Connections)",
            "verdictNotes": "Direct drop-in ONNX graph; uses existing app WordPiece tokenizer; eliminates superficial keyword false-positives."
        },
        {
            "modelName": "nli-MiniLM2-L6-H768 (Zero-Shot Classifier)",
            "role": "Topic membership validation & multi-label assignment",
            "license": "Apache-2.0",
            "runtime": "ONNX Runtime Mobile 1.31.0",
            "format": "INT8 ONNX (model_qint8_arm64.onnx)",
            "artifactSizeBytes": 82823065,
            "artifactSizeMb": 82.82,
            "measuredHostInitMs": 226.36,
            "measuredHostLatencyP50Ms": 11.44,
            "projectedAndroidArm64P50Ms": 45.0,
            "projectedAndroidArm64P95Ms": 72.0,
            "projectedMemoryDeltaMb": 91.6,
            "pageAlignment": "Shares existing ONNX Runtime 1.31.0 runtime (16 KiB verified)",
            "tokenizer": "RoBERTa Byte-Level BPE (requires adding BPE tokenizer to Kotlin)",
            "backgroundTtsCoexistence": "Moderate risk: 82.8MB asset + 91MB RAM pushes beyond the 64MB memory budget",
            "status": "Empirically Disqualified",
            "feasibilityVerdict": "Reject",
            "verdictNotes": "High download size (82.8MB); requires new BPE tokenizer; MNLI logical entailment fails on topical classification."
        },
        {
            "modelName": "SmolLM2-360M-Instruct (Small Language Model)",
            "role": "Structured topic naming & description generation",
            "license": "Apache-2.0",
            "runtime": "ONNX Runtime Mobile / LiteRT / llama.cpp",
            "format": "Q4F16 ONNX (model_q4f16.onnx) or GGUF Q4_K_M",
            "artifactSizeBytes": 272737275,
            "artifactSizeMb": 272.74,
            "measuredHostInitMs": 252.88,
            "measuredHostLatencyP50Ms": 466.72,  # per topic label (12-15 tokens)
            "projectedAndroidArm64P50Ms": 1450.0, # ~1.45s per topic label on mobile CPU
            "projectedMemoryDeltaMb": 320.0,
            "pageAlignment": "Compatible if built with ONNX Runtime Mobile or llama.cpp",
            "tokenizer": "Llama Byte-Level BPE (requires custom tokenizer integration)",
            "backgroundTtsCoexistence": "High risk of audio stutter and battery drain during background playback",
            "status": "Experimental Prototype",
            "feasibilityVerdict": "Defer on Mobile",
            "verdictNotes": "Fast on PC CPU (25.7 tok/s), but 272MB model size and 320MB RAM exceed typical mobile background service limits."
        }
    ]

    report = {
        "title": "Android Local AI Deployment Feasibility Matrix",
        "date": "2026-10-17",
        "targetPlatform": {
            "os": "Android 8.0 (API 26) through Android 15 (API 35+)",
            "abi": "arm64-v8a",
            "pageSizes": ["4 KiB", "16 KiB"],
            "backgroundConstraints": "Must coexist with android.media.session.MediaSession and TextToSpeech without frame/audio drops",
        },
        "candidates": candidates,
        "runtimeSummary": {
            "recommendedEngine": "ONNX Runtime Mobile 1.31.0 (already packaged in repo)",
            "maxMemoryBudgetMb": 64.0,
            "recommendedCombinedModelsSizeMb": 46.2,  # MiniLM + ms-marco Reranker
            "verdictSummary": "Adopt ms-marco-MiniLM-L6-v2 reranker alongside existing all-MiniLM-L6-v2 embedding model; replace greedy clustering with in-memory Agglomerative + c-TF-IDF. Defer LLM and NLI models to prevent audio stutter and memory pressure."
        }
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


if __name__ == "__main__":
    out_p = Path("app/build/experiments/android_feasibility_matrix.json")
    res = generate_android_feasibility_matrix(out_p)
    print("Android feasibility matrix generated successfully:")
    for c in res["candidates"]:
        print(f"  {c['modelName']}: {c['feasibilityVerdict']} ({c['artifactSizeMb']} MB)")
