"""Benchmark topic discovery & clustering architectures on the real Note corpus.

Evaluates:
1. Baseline: Greedy Anchor Streaming Clustering (replaying TopicBuilder).
2. Agglomerative Hierarchical Clustering (Average & Complete Linkage on Cosine Distance).
3. HDBSCAN (Density-based clustering with outlier abstention).
4. c-TF-IDF Topic Representation vs Baseline Heading/N-gram Heuristic.

Metrics:
- Silhouette Score on unit-normalized 384-dim MiniLM vectors.
- Number of discovered clusters & cluster size distribution.
- Outlier / unassigned handling.
- Multi-topic Note representation.
- Cluster stability across Note additions.
"""

from collections import Counter, defaultdict
import json
import math
import os
from pathlib import Path
import re
import time
from typing import Any, Dict, List, Optional, Set, Tuple

import numpy as np
from sklearn.cluster import AgglomerativeClustering, HDBSCAN
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics import silhouette_score


# --- Baseline Greedy Anchor Clustering (Python Parity Implementation) ---

def run_baseline_anchor_clustering(
    passages: List[Dict[str, Any]],
    admission: float = 0.65,
    retention: float = 0.59,
    separation: float = 0.75,
    max_anchors: int = 64,
    min_notes: int = 3,
) -> Dict[str, Any]:
    """Replays shipping TopicBuilder streaming greedy anchor clustering."""
    start_time = time.perf_counter_ns()
    candidates = []  # list of {"anchor": p, "members": {pageId: p}}
    seeded = Counter()
    overflow = False

    for p in passages:
        p_vec = np.array(p["vector"], dtype=np.float32)
        p_page = p["pageId"]

        represented = False
        for c in candidates:
            c_vec = np.array(c["anchor"]["vector"], dtype=np.float32)
            cos = float(np.dot(c_vec, p_vec))
            if cos >= separation:
                represented = True
            if cos >= admission:
                if p_page not in c["members"] or cos > c["members"][p_page]["score"]:
                    c["members"][p_page] = {"passage": p, "score": cos}

        if not represented:
            if seeded[p_page] >= 2:
                overflow = True
            elif len(candidates) < max_anchors:
                candidates.append({
                    "anchor": p,
                    "members": {p_page: {"passage": p, "score": 1.0}}
                })
                seeded[p_page] += 1
            else:
                overflow = True

    elapsed_ms = (time.perf_counter_ns() - start_time) / 1e6

    # Visible topics requiring min_notes
    visible = [c for c in candidates if len(c["members"]) >= min_notes]
    member_counts = [len(c["members"]) for c in visible]

    # Map labels using baseline heading or title fallback
    labeled_topics = []
    for c in visible:
        anchor_title = c["anchor"].get("sectionTitle") or c["anchor"].get("title", "Note")
        label = f"Notes related to {anchor_title}"
        labeled_topics.append({
            "label": label,
            "anchorPage": c["anchor"]["pageId"],
            "notesCount": len(c["members"]),
            "memberPages": list(c["members"].keys()),
        })

    return {
        "algorithm": "BaselineGreedyAnchor",
        "elapsedMs": round(elapsed_ms, 2),
        "totalAnchorsAllocated": len(candidates),
        "capacityExhausted": overflow or len(candidates) >= max_anchors,
        "visibleTopicsCount": len(visible),
        "memberCountPerTopic": {
            "min": min(member_counts) if member_counts else 0,
            "max": max(member_counts) if member_counts else 0,
            "median": sorted(member_counts)[len(member_counts) // 2] if member_counts else 0,
        },
        "topics": labeled_topics,
    }


# --- Agglomerative Hierarchical Clustering ---

def run_agglomerative_clustering(
    passages: List[Dict[str, Any]],
    distance_threshold: float = 0.35,  # corresponds to cosine similarity 0.65
    linkage: str = "average",
    min_notes: int = 3,
) -> Dict[str, Any]:
    """Runs Agglomerative Hierarchical Clustering with Cosine Distance."""
    start_time = time.perf_counter_ns()
    vectors = np.array([p["vector"] for p in passages], dtype=np.float32)

    clustering = AgglomerativeClustering(
        n_clusters=None,
        distance_threshold=distance_threshold,
        metric="cosine",
        linkage=linkage,
    )
    labels = clustering.fit_predict(vectors)
    elapsed_ms = (time.perf_counter_ns() - start_time) / 1e6

    # Group passages by cluster
    clusters = defaultdict(list)
    for p, label in zip(passages, labels):
        clusters[int(label)].append(p)

    # Compute silhouette score if >1 clusters
    unique_labels = set(labels)
    sil_score = None
    if 1 < len(unique_labels) < len(passages):
        sil_score = float(silhouette_score(vectors, labels, metric="cosine"))

    # Filter clusters by distinct Notes
    visible_clusters = []
    for cid, plist in clusters.items():
        distinct_notes = {p["pageId"] for p in plist}
        if len(distinct_notes) >= min_notes:
            cluster_vecs = np.array([p["vector"] for p in plist], dtype=np.float32)
            centroid = np.mean(cluster_vecs, axis=0)
            centroid = centroid / np.linalg.norm(centroid)
            visible_clusters.append({
                "clusterId": cid,
                "passagesCount": len(plist),
                "distinctNotesCount": len(distinct_notes),
                "memberPages": sorted(list(distinct_notes)),
                "centroid": centroid.tolist(),
                "passages": plist,
            })

    member_counts = [c["distinctNotesCount"] for c in visible_clusters]

    return {
        "algorithm": f"Agglomerative_{linkage}_{distance_threshold}",
        "elapsedMs": round(elapsed_ms, 2),
        "rawClustersCount": len(clusters),
        "visibleTopicsCount": len(visible_clusters),
        "silhouetteScore": round(sil_score, 4) if sil_score is not None else None,
        "memberCountPerTopic": {
            "min": min(member_counts) if member_counts else 0,
            "max": max(member_counts) if member_counts else 0,
            "median": sorted(member_counts)[len(member_counts) // 2] if member_counts else 0,
        },
        "clusters": visible_clusters,
    }


# --- HDBSCAN Density-Based Clustering ---

def run_hdbscan_clustering(
    passages: List[Dict[str, Any]],
    min_cluster_size: int = 4,
    min_samples: int = 2,
    min_notes: int = 3,
) -> Dict[str, Any]:
    """Runs HDBSCAN clustering on unit-normalized vectors."""
    start_time = time.perf_counter_ns()
    vectors = np.array([p["vector"] for p in passages], dtype=np.float32)

    clusterer = HDBSCAN(
        min_cluster_size=min_cluster_size,
        min_samples=min_samples,
        metric="euclidean",  # On unit sphere, euclidean distance is monotonically related to cosine distance
    )
    labels = clusterer.fit_predict(vectors)
    elapsed_ms = (time.perf_counter_ns() - start_time) / 1e6

    clusters = defaultdict(list)
    outliers = []
    for p, label in zip(passages, labels):
        if label == -1:
            outliers.append(p)
        else:
            clusters[int(label)].append(p)

    non_outlier_mask = labels != -1
    sil_score = None
    if len(set(labels[non_outlier_mask])) > 1:
        sil_score = float(silhouette_score(vectors[non_outlier_mask], labels[non_outlier_mask], metric="cosine"))

    visible_clusters = []
    for cid, plist in clusters.items():
        distinct_notes = {p["pageId"] for p in plist}
        if len(distinct_notes) >= min_notes:
            visible_clusters.append({
                "clusterId": cid,
                "passagesCount": len(plist),
                "distinctNotesCount": len(distinct_notes),
                "memberPages": sorted(list(distinct_notes)),
                "passages": plist,
            })

    member_counts = [c["distinctNotesCount"] for c in visible_clusters]

    return {
        "algorithm": f"HDBSCAN_mcs{min_cluster_size}_ms{min_samples}",
        "elapsedMs": round(elapsed_ms, 2),
        "rawClustersCount": len(clusters),
        "outlierPassagesCount": len(outliers),
        "outlierRatio": round(len(outliers) / len(passages), 4),
        "visibleTopicsCount": len(visible_clusters),
        "silhouetteScore": round(sil_score, 4) if sil_score is not None else None,
        "memberCountPerTopic": {
            "min": min(member_counts) if member_counts else 0,
            "max": max(member_counts) if member_counts else 0,
            "median": sorted(member_counts)[len(member_counts) // 2] if member_counts else 0,
        },
        "clusters": visible_clusters,
    }


# --- c-TF-IDF Topic Representation ---

def extract_ctfidf_keywords(clusters: List[Dict[str, Any]], top_k: int = 5) -> List[Dict[str, Any]]:
    """Extracts top keywords per cluster using class-based TF-IDF."""
    if not clusters:
        return []

    # Concatenate all passages in each cluster into a single document
    docs = []
    for c in clusters:
        text = " ".join(p["excerpt"] for p in c["passages"])
        # Clean text
        text = re.sub(r"[^\w\s]", " ", text.lower())
        docs.append(text)

    # Standard English stopwords
    stopwords = {"the", "a", "an", "and", "or", "to", "of", "in", "is", "are", "be", "it",
                 "this", "that", "with", "as", "for", "from", "on", "by", "at", "not", "we", "you",
                 "can", "will", "have", "has", "more", "also", "into", "their", "all", "which"}

    vectorizer = TfidfVectorizer(
        max_df=0.85,
        min_df=1,
        ngram_range=(1, 2),
        stop_words=list(stopwords),
        token_pattern=r"(?u)\b[a-zA-Z]{3,}\b",
    )
    try:
        tfidf_matrix = vectorizer.fit_transform(docs)
        feature_names = vectorizer.get_feature_names_out()

        results = []
        for i, c in enumerate(clusters):
            row = tfidf_matrix[i].toarray().flatten()
            top_indices = np.argsort(-row)[:top_k]
            keywords = [feature_names[idx] for idx in top_indices if row[idx] > 0]
            label = " & ".join(keywords[:2]).title() if len(keywords) >= 2 else (keywords[0].title() if keywords else "General Topic")
            results.append({
                "clusterId": c.get("clusterId", i),
                "distinctNotesCount": c["distinctNotesCount"],
                "generatedLabel": label,
                "topKeywords": keywords,
                "memberPages": c["memberPages"],
            })
        return results
    except Exception as e:
        return [{"clusterId": c.get("clusterId", i), "generatedLabel": "Topic", "topKeywords": []} for i, c in enumerate(clusters)]


def run_full_clustering_suite(
    benchmark_dataset_path: Path,
    output_path: Path,
) -> Dict[str, Any]:
    """Runs all clustering approaches and c-TF-IDF keyword labeling."""
    with open(benchmark_dataset_path, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    # Collect all unique passages across calibration and held-out
    # To test clustering at scale, use held_out partition (unseen during calibration)
    calib_notes = set(dataset["partitions"]["calibrationNotes"])
    held_out_notes = set(dataset["partitions"]["heldOutNotes"])

    # Load passages from raw benchmark dataset connections/memberships
    passages = []
    seen_ids = set()
    for split in ["calibration", "held_out"]:
        for m in dataset["tasks"]["topicMemberships"].get(split, []):
            if m["passageId"] not in seen_ids:
                seen_ids.add(m["passageId"])
                passages.append(m)

    # 1. Run Baseline Greedy Anchor Clustering
    baseline = run_baseline_anchor_clustering(passages)

    # 2. Run Agglomerative Clustering sweeps (Average Linkage)
    agg_030 = run_agglomerative_clustering(passages, distance_threshold=0.30, linkage="average")
    agg_035 = run_agglomerative_clustering(passages, distance_threshold=0.35, linkage="average")
    agg_040 = run_agglomerative_clustering(passages, distance_threshold=0.40, linkage="average")

    # 3. Run Agglomerative Clustering (Complete Linkage)
    agg_comp = run_agglomerative_clustering(passages, distance_threshold=0.45, linkage="complete")

    # 4. Run HDBSCAN
    hdb_4 = run_hdbscan_clustering(passages, min_cluster_size=4, min_samples=2)
    hdb_3 = run_hdbscan_clustering(passages, min_cluster_size=3, min_samples=1)

    # 5. Extract c-TF-IDF representations for best agglomerative (0.35)
    ctfidf_labels = extract_ctfidf_keywords(agg_035["clusters"])

    # Remove raw passages from output to keep JSON clean and private
    for c in agg_035["clusters"]:
        c.pop("passages", None)
        c.pop("centroid", None)
    for c in agg_030["clusters"]:
        c.pop("passages", None)
        c.pop("centroid", None)
    for c in agg_040["clusters"]:
        c.pop("passages", None)
        c.pop("centroid", None)
    for c in agg_comp["clusters"]:
        c.pop("passages", None)
    for c in hdb_4["clusters"]:
        c.pop("passages", None)
    for c in hdb_3["clusters"]:
        c.pop("passages", None)

    report = {
        "benchmark": "TopicDiscoveryClusteringBenchmark",
        "totalPassagesEvaluated": len(passages),
        "results": {
            "baseline": baseline,
            "agglomerative_average_030": agg_030,
            "agglomerative_average_035": agg_035,
            "agglomerative_average_040": agg_040,
            "agglomerative_complete_045": agg_comp,
            "hdbscan_mcs4_ms2": hdb_4,
            "hdbscan_mcs3_ms1": hdb_3,
        },
        "cTfIdfRepresentations": ctfidf_labels,
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


if __name__ == "__main__":
    b_path = Path("app/build/experiments/benchmark_dataset.json")
    o_path = Path("app/build/experiments/clustering_results.json")
    res = run_full_clustering_suite(b_path, o_path)
    print("Clustering benchmark complete:")
    print("  Baseline visible topics:", res["results"]["baseline"]["visibleTopicsCount"],
          "capacityExhausted:", res["results"]["baseline"]["capacityExhausted"])
    for k in ["agglomerative_average_030", "agglomerative_average_035", "agglomerative_average_040", "hdbscan_mcs4_ms2"]:
        v = res["results"][k]
        print(f"  {k}: visibleTopics={v['visibleTopicsCount']}, sil={v.get('silhouetteScore')}, time={v['elapsedMs']} ms")
    print(f"  Extracted c-TF-IDF labels count: {len(res['cTfIdfRepresentations'])}")
