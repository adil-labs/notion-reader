"""Host MiniLM vectors from verified shipping inputs. No downloads or quality verdicts."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import uuid

import numpy as np

from public_corpus import encoded

MODEL_SHA256 = "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474"
MODEL_REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
VOCABULARY_SHA256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def mean_pool(hidden, dimensions):
    values = np.asarray(hidden, dtype=np.float64)
    if values.ndim != 2 or values.shape[1] != dimensions or not len(values) or not np.isfinite(values).all():
        raise ValueError("Invalid model hidden states")
    pooled = (values / len(values)).sum(axis=0)
    norm = np.linalg.norm(pooled)
    if not np.isfinite(norm) or norm <= 0:
        raise ValueError("Invalid embedding norm")
    return (pooled / norm).astype(np.float32)


def validate_inputs(rows):
    seen, families = set(), {}
    if not rows:
        raise ValueError("No passage opportunities")
    for row in rows:
        if not row.get("passageId") or row["passageId"] in seen or not row.get("input"):
            raise ValueError("Missing/duplicate passage evidence")
        seen.add(row["passageId"])
        family, split = row["family"], row["partition"]
        if split not in {"calibration", "held_out"} or family in families and families[family] != split:
            raise ValueError("Invalid/leaked family partition")
        families[family] = split


def verify_artifact(directory, manifest):
    if manifest.get("complete") is not True:
        raise ValueError("Incomplete vector artifact")
    for field, hash_field in (("vectorsFile", "vectorsSha256"), ("rowsFile", "rowsSha256"), ("rawVectorsFile", "rawVectorsSha256")):
        path = directory / manifest[field]
        if path.parent.resolve() != directory.resolve() or file_hash(path) != manifest[hash_field]:
            raise ValueError("Vector artifact path/hash mismatch")
    vectors = np.load(directory / manifest["vectorsFile"], allow_pickle=False)
    rows = json.loads((directory / manifest["rowsFile"]).read_text(encoding="utf-8"))
    validate_inputs(rows)
    if vectors.shape != (len(rows), manifest["identity"]["dimensions"]) or manifest["passages"] != len(rows):
        raise ValueError("Vector/row cardinality mismatch")
    if vectors.dtype != np.float32 or not np.isfinite(vectors).all() or not np.allclose(np.linalg.norm(vectors, axis=1), 1, atol=1e-5):
        raise ValueError("Invalid/non-normalized vectors")
    raw = np.fromfile(directory / manifest["rawVectorsFile"], dtype="<f4")
    if raw.size != vectors.size or not np.array_equal(raw.view(np.uint32), vectors.ravel().view(np.uint32)):
        raise ValueError("JVM transport vector bits differ")
    return vectors, rows


def write_artifact(rows, identity, directory, embed, formation_policy=None):
    validate_inputs(rows)
    directory.mkdir(parents=True, exist_ok=True)
    vectors = np.empty((len(rows), identity["dimensions"]), dtype=np.float32)
    started = time.perf_counter()
    for i, row in enumerate(rows):
        vector = np.asarray(embed(row), dtype=np.float32)
        if vector.shape != (identity["dimensions"],) or not np.isfinite(vector).all() or not np.isclose(np.linalg.norm(vector), 1, atol=1e-5):
            raise ValueError("Invalid model vector; no manifest published")
        vectors[i] = vector
        if (i + 1) % 256 == 0:
            print(f"Embedded {i+1}/{len(rows)} public passages", flush=True)
    temporary = directory / ("vectors-" + uuid.uuid4().hex + ".partial.npy")
    with temporary.open("wb") as stream:
        np.save(stream, vectors, allow_pickle=False)
    vector_hash = file_hash(temporary)
    vector_name = f"vectors-{vector_hash}.npy"
    temporary.replace(directory / vector_name)
    temporary = directory / ("raw-" + uuid.uuid4().hex + ".partial")
    vectors.astype("<f4", copy=False).tofile(temporary)
    raw_hash = file_hash(temporary)
    raw_name = f"vectors-{raw_hash}.f32"
    temporary.replace(directory / raw_name)
    row_bytes = encoded(rows)
    row_hash = hashlib.sha256(row_bytes).hexdigest()
    row_name = f"rows-{row_hash}.json"
    temporary = directory / (row_name + ".partial")
    temporary.write_bytes(row_bytes); temporary.replace(directory / row_name)
    manifest = {"schemaVersion": 1, "complete": True, "identity": identity, "passages": len(rows),
                "vectorsFile": vector_name, "vectorsSha256": vector_hash, "rowsFile": row_name, "rowsSha256": row_hash,
                "rawVectorsFile": raw_name, "rawVectorsSha256": raw_hash,
                "hostBuildSeconds": time.perf_counter() - started, "humanQualityGate": "unmeasured",
                "androidGate": "unmeasured", "scope": "Host CPU artifact; not Android memory/runtime or semantic quality acceptance"}
    if formation_policy is not None:
        manifest["formationOpportunityPolicy"] = formation_policy
    verify_artifact(directory, manifest)
    temporary = directory / "vector-manifest.json.partial"
    temporary.write_bytes(encoded(manifest)); temporary.replace(directory / "vector-manifest.json")
    return manifest


def main():
    import onnxruntime as ort
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--vocabulary", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.corpus_dir / "passage-manifest.json").read_text(encoding="utf-8"))
    passage_file = args.corpus_dir / "passages.jsonl"
    if manifest["notes"] != 500 or manifest["passagesSha256"] != file_hash(passage_file):
        raise ValueError("Missing/partial/corrupt shipping passage export")
    if manifest["vocabularySha256"] != VOCABULARY_SHA256 or file_hash(args.vocabulary) != VOCABULARY_SHA256 or file_hash(args.model) != MODEL_SHA256:
        raise ValueError("Pinned production model/vocabulary mismatch")
    rows = []
    for line in passage_file.read_text(encoding="utf-8").splitlines():
        note = json.loads(line)
        if note["source"]["kind"] != "public" or len(note["passages"]) != len(note["tokenIds"]):
            raise ValueError("Public shipping token provenance required")
        for passage, ids in zip(note["passages"], note["tokenIds"]):
            if passage["pageId"] != note["pageId"] or not 2 <= len(ids) <= 256 or ids[0] != 101 or ids[-1] != 102:
                raise ValueError("Invalid shipping token window; never silently truncate")
            rows.append({"pageId": note["pageId"], "passageId": passage["id"], "input": passage["input"],
                         "tokenIds": ids, "family": note["family"], "partition": note["partition"]})
    if len(rows) != manifest["passages"] or len({r["pageId"] for r in rows}) != 500:
        raise ValueError("Incomplete passage/Note opportunity coverage")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(args.model), sess_options=options, providers=["CPUExecutionProvider"])

    def embed(row):
        ids = np.asarray([row["tokenIds"]], dtype=np.int64)
        feeds = {"input_ids": ids, "attention_mask": np.ones_like(ids), "token_type_ids": np.zeros_like(ids)}
        hidden = session.run(None, {i.name: feeds[i.name] for i in session.get_inputs()})[0]
        if hidden.shape != (1, ids.shape[1], 384):
            raise ValueError("Unexpected model output shape")
        return mean_pool(hidden[0], 384)

    identity = {"corpusSha256": manifest["corpusSha256"], "passagesSha256": manifest["passagesSha256"],
                "vocabularySha256": manifest["vocabularySha256"], "modelSha256": MODEL_SHA256,
                "modelRevision": MODEL_REVISION, "dimensions": 384, "tokenizer": manifest["tokenizer"],
                "runtime": "onnxruntime:" + ort.__version__, "provider": "CPUExecutionProvider", "threads": 2,
                "pooling": "all-real-tokens-double-mean-l2-float32"}
    output = write_artifact(rows, identity, args.output_dir, embed)
    print(f"Verified {output['passages']} shared MiniLM vectors; human/Android gates unmeasured")


if __name__ == "__main__":
    main()
