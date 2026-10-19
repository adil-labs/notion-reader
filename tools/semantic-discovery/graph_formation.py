"""Experimental host HNSW/Leiden seed formation; no production engine/model change."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import numpy as np

from public_corpus import encoded
from public_vectors import verify_artifact, file_hash


def neighbor_recall(vectors, candidates, k):
    """Exact cosine probe with tie-aware boundary credit; self/duplicate IDs are invalid."""
    if not candidates:
        return {"queries": 0, "k": k, "recallAtKWithTies": None}
    expected = min(k, len(vectors) - 1)
    hits = 0
    for query, neighbors in candidates.items():
        if not 0 <= query < len(vectors) or len(neighbors) != expected or len(set(neighbors)) != expected or any(
                n == query or not 0 <= n < len(vectors) for n in neighbors):
            raise ValueError("Incomplete, duplicate, self or unknown neighbor opportunity")
        scores = vectors @ vectors[query]
        scores[query] = -np.inf
        boundary = np.partition(scores, len(scores) - expected)[len(scores) - expected] if expected else 0
        hits += sum(float(scores[n]) >= float(boundary) - 1e-6 for n in neighbors)
    denominator = len(candidates) * expected
    return {"queries": len(candidates), "k": expected, "neighborOpportunities": denominator,
            "acceptedNeighbors": hits, "recallAtKWithTies": hits / denominator if denominator else None}


def graph_formation(vectors, rows, k=15, min_similarity=.65, resolution=1.0, seed=42):
    import faiss
    import igraph
    import leidenalg
    if len(vectors) != len(rows) or not len(rows) or k < 1 or not -1 <= min_similarity <= 1 or resolution <= 0:
        raise ValueError("Invalid graph opportunity/parameter declaration")
    if len({r["passageId"] for r in rows}) != len(rows) or not np.isfinite(vectors).all() or not np.allclose(
            np.linalg.norm(vectors, axis=1), 1, atol=1e-5):
        raise ValueError("Unique normalized shared passage vectors required")
    started = time.perf_counter()
    faiss.omp_set_num_threads(1)
    values = np.ascontiguousarray(vectors, dtype=np.float32)
    index = faiss.IndexHNSWFlat(values.shape[1], 32, faiss.METRIC_INNER_PRODUCT)
    index.hnsw.rng = faiss.RandomGenerator(seed)
    index.hnsw.efConstruction = 200
    index.hnsw.efSearch = 128
    index.add(values)
    count = min(k, len(rows) - 1)
    _, nearest = index.search(values, count + 1)
    neighbors = {i: [int(n) for n in result if n >= 0 and n != i][:count] for i, result in enumerate(nearest)}
    build_seconds = time.perf_counter() - started
    probe_ids = sorted((i for i, r in enumerate(rows) if r["partition"] == "calibration"),
                       key=lambda i: hashlib.sha256(rows[i]["passageId"].encode()).digest())[:256]
    probe = neighbor_recall(values, {i: neighbors[i] for i in probe_ids}, count)
    edges = {}
    for i, targets in neighbors.items():
        if len(targets) != count or len(set(targets)) != count:
            raise ValueError("Incomplete approximate neighborhood")
        for j in targets:
            score = float(values[i] @ values[j])
            if score >= min_similarity and score > 0:
                edge = (min(i, j), max(i, j))
                edges[edge] = max(score, edges.get(edge, 0))
    graph = igraph.Graph(n=len(rows), edges=list(edges), directed=False)
    graph.es["weight"] = list(edges.values())
    partition = leidenalg.find_partition(graph, leidenalg.RBConfigurationVertexPartition,
        weights="weight", resolution_parameter=resolution, seed=seed, n_iterations=-1)
    groups = sorted((sorted(rows[i]["passageId"] for i in group) for group in partition), key=lambda g: g[0])
    flattened = [identity for group in groups for identity in group]
    if len(flattened) != len(rows) or set(flattened) != {r["passageId"] for r in rows}:
        raise ValueError("Graph partition lost a seed opportunity")
    return {"schemaVersion": 1, "algorithm": "faiss-hnsw-leiden-rbconfiguration", "seedOpportunities": len(rows),
            "seedGroups": groups, "edges": len(edges), "calibrationNeighborProbe": probe,
            "parameters": {"k": k, "minSimilarity": min_similarity, "resolution": resolution, "seed": seed,
                           "hnswM": 32, "efConstruction": 200, "efSearch": 128, "threads": 1,
                           "symmetrization": "union-max-positive-cosine", "iterations": "until-stable"},
            "versions": {"faiss": faiss.__version__, "igraph": igraph.__version__, "leidenalg": leidenalg.__version__},
            "hostNeighborhoodSeconds": build_seconds, "hostTotalSeconds": time.perf_counter() - started,
            "timingScope": "HNSW/Leiden and neighbor probe; shared shipping preparation and downstream projection excluded",
            "membershipProjection": "notRun: seed formation only", "humanQualityGate": "unmeasured",
            "androidGate": "unmeasured", "scope": "Exploratory fixed defaults; no winner or production adoption claim"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vector-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--k", type=int, default=15)
    parser.add_argument("--min-similarity", type=float, default=.65)
    parser.add_argument("--resolution", type=float, default=1.0)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    path = args.vector_dir / "vector-manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    vectors, rows = verify_artifact(args.vector_dir, manifest)
    if manifest.get("formationOpportunityPolicy") != "shipping:SectionHierarchicalEngine:v4":
        raise ValueError("Export shared shipping formation opportunities first; raw passages are not an exact-engine ablation")
    report = graph_formation(vectors, rows, args.k, args.min_similarity, args.resolution, args.seed)
    report["input"] = {"vectorManifestSha256": file_hash(path), "identity": manifest["identity"],
                       "vectorsSha256": manifest["vectorsSha256"], "rowsSha256": manifest["rowsSha256"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".partial")
    temporary.write_bytes(encoded(report)); temporary.replace(args.output)
    print(f"Host graph seeds={report['seedOpportunities']}, groups={len(report['seedGroups'])}; quality/Android gates unmeasured")
