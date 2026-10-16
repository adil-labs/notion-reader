"""Standalone feasibility probe. Never reads Notion or app private data.

Usage: python tools/semantic-discovery/evaluate.py --output-dir app/build/discovery-feasibility
Downloads publisher artifacts at an immutable revision; reports host evidence only.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import time
import urllib.request
import shutil

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
MODEL_SHA256 = "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474"
FILES = ["onnx/model_qint8_arm64.onnx", "tokenizer.json", "tokenizer_config.json", "config.json", "sentence_bert_config.json", "1_Pooling/config.json", "README.md", "vocab.txt"]


def fetch(directory):
    manifest = {}
    for name in FILES:
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            temporary = target.with_suffix(target.suffix + ".partial")
            urllib.request.urlretrieve(f"https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/{REVISION}/{name}", temporary)
            temporary.replace(target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if name == FILES[0] and digest != MODEL_SHA256:
            raise ValueError("Publisher model checksum mismatch")
        manifest[name] = {"bytes": target.stat().st_size, "sha256": digest}
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, default=Path(__file__).parent / "evaluation-feasibility-v1.json")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest = fetch(args.output_dir)
    tokenizer = Tokenizer.from_file(str(args.output_dir / "tokenizer.json"))
    tokenizer.enable_truncation(max_length=256)
    started = time.perf_counter()
    session = ort.InferenceSession(str(args.output_dir / FILES[0]), providers=["CPUExecutionProvider"])
    cold = time.perf_counter() - started

    def embed(text):
        encoded = tokenizer.encode(text)
        ids = np.asarray([encoded.ids], dtype=np.int64)
        mask = np.asarray([encoded.attention_mask], dtype=np.int64)
        feeds = {"input_ids": ids, "attention_mask": mask, "token_type_ids": np.asarray([encoded.type_ids], dtype=np.int64)}
        output = session.run(None, {i.name: feeds[i.name] for i in session.get_inputs()})[0]
        pooled = (output * mask[:, :, None]).sum(axis=1) / mask.sum(axis=1, keepdims=True)
        vector = pooled[0] / np.linalg.norm(pooled[0])
        assert vector.shape == (384,) and np.isfinite(vector).all()
        return vector

    corpus = json.loads(args.corpus.read_text())
    docs = corpus["documents"]
    vectors = np.asarray([embed(doc["text"]) for doc in docs])
    rows = []
    timings = []
    for item in corpus["queries"]:
        started = time.perf_counter()
        scores = vectors @ embed(item["text"])
        timings.append(time.perf_counter() - started)
        order = sorted(range(len(docs)), key=lambda i: (-float(scores[i]), docs[i]["id"]))
        rows.append({**item, "ranking": [{"id": docs[i]["id"], "score": float(scores[i])} for i in order]})
    calibration = [row for row in rows if row["split"] == "calibration"]
    candidates = sorted({match["score"] for row in calibration for match in row["ranking"]})
    def precision(rows, threshold):
        selected = [(match["id"] in row["relevant"]) for row in rows for match in row["ranking"][:5] if match["score"] >= threshold]
        return sum(selected) / len(selected) if selected else 0
    qualifying = [value for value in candidates if precision(calibration, value) >= .8]
    threshold = min(qualifying) if qualifying else 1.0
    held = [row for row in rows if row["split"] == "held-out"]
    recall = np.mean([len(set(row["relevant"]) & {m["id"] for m in row["ranking"][:5]}) / len(row["relevant"]) for row in held])
    ndcg = np.mean([sum((m["id"] in row["relevant"]) / math.log2(i+2) for i, m in enumerate(row["ranking"][:5])) / sum(1/math.log2(i+2) for i in range(min(5, len(row["relevant"])))) for row in held])
    report = {"scope": "host CPU synthetic evidence; not Android/API26/ARM64/native-memory/playback evidence", "revision": REVISION, "artifacts": manifest, "ort_version": ort.__version__, "cold_session_seconds": cold, "query_p50_seconds": float(np.percentile(timings, 50)), "query_p95_seconds": float(np.percentile(timings, 95)), "threshold": threshold, "calibration_precision": precision(calibration, threshold), "held_out_precision": precision(held, threshold), "held_out_recall_at_5": float(recall), "held_out_ndcg_at_5": float(ndcg), "results": rows}
    assets = args.output_dir / "probe-assets"
    assets.mkdir(exist_ok=True)
    shutil.copyfile(args.output_dir / FILES[0], assets / "model_qint8_arm64.onnx")
    probes = []
    probe_texts = ["Hello, world!", "Café naïve résumé — 中文。", "Running runners can't stop.", "", "  MULTIPLE\tspaces\n"] + [d["text"] for d in docs] + [q["text"] for q in corpus["queries"]]
    for text in probe_texts:
        encoded = tokenizer.encode(text)
        probes.append({"text": text, "input_ids": encoded.ids, "attention_mask": encoded.attention_mask, "token_type_ids": encoded.type_ids, "embedding": embed(text).tolist()})
    (assets / "reference.json").write_text(json.dumps({"probes": probes, "documents": docs, "queries": corpus["queries"], "threshold": threshold}, ensure_ascii=False), encoding="utf-8")
    (args.output_dir / "host-report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({k:v for k,v in report.items() if k not in ("artifacts", "results")}, indent=2))


if __name__ == "__main__":
    main()
