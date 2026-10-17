"""Constructs reproducible evaluation benchmark from local Notes snapshot.

Strict Privacy Rules:
- All data stays under ignored local directories (e.g. app/build/).
- Never log raw passage text or private Note titles to console or public files.
- Prevents data leakage by partitioning at the Note-family level.
"""

from collections import Counter, defaultdict
import glob
import hashlib
import json
import os
from pathlib import Path
import struct
from typing import Any, Dict, List, Optional, Set, Tuple

import numpy as np


MAGIC = 0x4E525332  # NRS2
FORMAT = 2
PREFIX_BYTES = 12


def canonical_page_id(page_id: str) -> str:
    """Normalize page UUID to canonical lowercase hex without hyphens."""
    return page_id.strip().lower().replace("-", "")


def load_nrs2_records(semantic_index_dir: Path) -> Dict[str, Dict[str, Any]]:
    """Loads all NRS2 binary records from semantic-index directory."""
    records = {}
    for record_path in semantic_index_dir.glob("*.record"):
        with open(record_path, "rb") as f:
            data = f.read()
            if len(data) < PREFIX_BYTES:
                continue
            magic, fmt, header_len = struct.unpack(">III", data[:12])
            if magic != MAGIC or fmt != FORMAT:
                continue
            header_bytes = data[12:12 + header_len]
            header = json.loads(header_bytes.decode("utf-8"))
            page_id = canonical_page_id(header["pageId"])
            dim = header["identity"]["dimensions"]
            passages_meta = header["passages"]
            count = len(passages_meta)

            vector_bytes = data[12 + header_len: 12 + header_len + count * dim * 4]
            vectors = np.frombuffer(vector_bytes, dtype=">f4").reshape(count, dim).astype(np.float32)

            passages = []
            for i, p_meta in enumerate(passages_meta):
                passages.append({
                    "id": p_meta["id"],
                    "pageId": page_id,
                    "sectionId": p_meta.get("sectionId"),
                    "sectionTitle": p_meta.get("sectionTitle"),
                    "excerpt": p_meta.get("excerpt", ""),
                    "start": p_meta.get("start", 0),
                    "end": p_meta.get("end", 0),
                    "vector": vectors[i],
                })

            records[page_id] = {
                "pageId": page_id,
                "revision": header.get("revision", ""),
                "title": header.get("metadata", {}).get("title", ""),
                "identity": header.get("identity", {}),
                "passages": passages,
            }
    return records


def load_document_cache(cache_dir: Path) -> Dict[str, Dict[str, Any]]:
    """Loads document cache JSON files for structural context."""
    cache = {}
    for json_path in cache_dir.glob("*.json"):
        try:
            with open(json_path, "r", encoding="utf-8") as f:
                doc = json.load(f)
                page_id = canonical_page_id(doc.get("pageId", json_path.stem))
                cache[page_id] = {
                    "pageId": page_id,
                    "title": doc.get("title", ""),
                    "sections": doc.get("sections", []),
                }
        except Exception:
            continue
    return cache


def load_existing_review_and_judgments(review_dir: Path) -> Tuple[List[Dict[str, Any]], Dict[str, Dict[str, Any]]]:
    """Loads reviewed cases and human judgments if present."""
    cases = []
    judgments = {}
    review_file = review_dir / "review.json"
    judgments_file = review_dir / "human-judgments.json"

    if review_file.is_file():
        try:
            with open(review_file, "r", encoding="utf-8") as f:
                cases = json.load(f).get("cases", [])
        except Exception:
            pass

    if judgments_file.is_file():
        try:
            with open(judgments_file, "r", encoding="utf-8") as f:
                for c in json.load(f).get("cases", []):
                    case_id = c.get("caseId")
                    if case_id:
                        judgments[case_id] = {
                            "caseId": case_id,
                            "usefulness": c.get("usefulness"),
                            "support": c.get("support", c.get("passageSupport")),
                            "label": c.get("label", c.get("labelClarity")),
                            "coherence": c.get("coherence"),
                            "decision": c.get("decision"),
                        }
        except Exception:
            pass

    return cases, judgments


def note_family(page_id, record):
    words = [word for word in record["title"].strip().lower().split() if len(word) > 3]
    return words[0] if words else page_id[:4]


def exposed_note_ids(reviewed_cases):
    pages = set()
    for case in reviewed_cases:
        content = case.get("content", {})
        if case.get("kind") in ("connection", "potentialMissedConnection"):
            pages.update(canonical_page_id(content.get(side, {}).get("pageId", "")) for side in ("left", "right"))
        elif case.get("kind") == "topic":
            pages.update(canonical_page_id(member.get("pageId", "")) for member in content.get("members", []))
            pages.update(canonical_page_id(page) for page in content.get("memberPageIds", []))
    return pages - {""}


def partition_notes_by_family(records: Dict[str, Dict[str, Any]], calib_ratio: float = 0.4, seed: int = 42,
                              exposed_pages: Optional[Set[str]] = None) -> Tuple[Set[str], Set[str]]:
    """Partitions Note IDs into Calibration and Held-Out sets without cross-family leakage."""
    rng = np.random.RandomState(seed)
    page_ids = sorted(records.keys())

    families = defaultdict(list)
    for pid in page_ids:
        key = note_family(pid, records[pid])
        families[key].append(pid)

    exposed = {canonical_page_id(page) for page in (exposed_pages or set())}
    calib_set = {pid for members in families.values()
                 if any(canonical_page_id(pid) in exposed for pid in members) for pid in members}
    held_out_set = set()
    target_calib_count = int(len(page_ids) * calib_ratio)

    family_keys = sorted(families.keys())
    rng.shuffle(family_keys)

    for k in family_keys:
        members = families[k]
        if any(pid in calib_set for pid in members):
            continue
        if len(calib_set) + len(members) <= target_calib_count or len(calib_set) == 0:
            calib_set.update(members)
        else:
            held_out_set.update(members)

    assert len(calib_set.intersection(held_out_set)) == 0, "Leakage: Overlapping Note IDs in partitions"
    return calib_set, held_out_set


def build_passage_connections(
    records: Dict[str, Dict[str, Any]],
    calib_pages: Set[str],
    held_out_pages: Set[str],
    reviewed_cases: List[Dict[str, Any]],
    existing_judgments: Dict[str, Dict[str, Any]],
    target_count: int = 120,
) -> Dict[str, List[Dict[str, Any]]]:
    """Generates balanced passage-connection pairs across partitions, including reviewed pairs."""
    pairs_by_split = {"calibration": [], "held_out": [], "previously_exposed": []}
    incorporated_case_ids = set()
    exposed_pairs = set()

    # First, incorporate existing judged connection cases into appropriate partition
    for case in reviewed_cases:
        if case.get("kind") not in ("connection", "potentialMissedConnection"):
            continue
        case_id = case["caseId"]
        content = case.get("content", {})
        left = content.get("left", {})
        right = content.get("right", {})
        left_pid = canonical_page_id(left.get("pageId", ""))
        right_pid = canonical_page_id(right.get("pageId", ""))

        if not left_pid or not right_pid:
            continue

        rating = existing_judgments.get(case_id)
        entry = {
            "caseId": case_id,
            "kind": case.get("kind"),
            "cosine": float(content.get("cosine", 0.0)),
            "evidenceKind": content.get("evidenceKind", "SimilarPassages"),
            "left": {
                "pageId": left_pid,
                "title": records.get(left_pid, {}).get("title", left.get("title", "")),
                "sectionTitle": left.get("section"),
                "passageId": left.get("passageId", ""),
                "excerpt": left.get("excerpt", ""),
            },
            "right": {
                "pageId": right_pid,
                "title": records.get(right_pid, {}).get("title", right.get("title", "")),
                "sectionTitle": right.get("section"),
                "passageId": right.get("passageId", ""),
                "excerpt": right.get("excerpt", ""),
            },
            "humanRating": rating,
            "provenance": "historical_human_reviewed" if rating is not None else "historical_exposed_unrated",
            "exposure": "previously_exposed",
        }

        # Exposure survives changed IDs, labels, ranking and reference classification.
        # Follow-up evidence is never silently imported into either evaluation split.
        pairs_by_split["previously_exposed"].append(entry)
        incorporated_case_ids.add(case_id)
        exposed_pairs.add(tuple(sorted((left_pid, right_pid))))

    # Next, sample additional pairs to meet target quota per partition
    def generate_additional_pairs(pages: Set[str], current_list: List[Dict[str, Any]], target: int):
        needed = max(0, target - len(current_list))
        if needed == 0:
            return
        page_list = sorted(pages)
        eligible = []
        for pid in page_list:
            if pid not in records:
                continue
            for p in records[pid]["passages"]:
                text = p["excerpt"].strip()
                if len(text.split()) >= 8 and len(text) >= 40:
                    eligible.append(p)
        if len(eligible) < 2:
            return

        all_vectors = np.array([p["vector"] for p in eligible], dtype=np.float32)
        sim_matrix = np.dot(all_vectors, all_vectors.T)
        candidates = []
        n = len(eligible)
        for i in range(n):
            p1 = eligible[i]
            for j in range(i + 1, n):
                p2 = eligible[j]
                if p1["pageId"] == p2["pageId"]:
                    continue
                if tuple(sorted((canonical_page_id(p1["pageId"]), canonical_page_id(p2["pageId"])))) in exposed_pairs:
                    continue
                cos = float(sim_matrix[i, j])
                candidates.append((cos, p1, p2))

        candidates.sort(key=lambda x: -x[0])
        for cos, p1, p2 in candidates:
            if len(current_list) >= target:
                break
            ordered_pages = sorted([p1["pageId"], p2["pageId"]])
            ordered_passages = sorted([p1["id"], p2["id"]])
            case_id = hashlib.sha256(f"connection:{ordered_pages[0]}:{ordered_passages[0]}:{ordered_pages[1]}:{ordered_passages[1]}".encode()).hexdigest()[:16]
            if case_id in incorporated_case_ids:
                continue
            incorporated_case_ids.add(case_id)

            current_list.append({
                "caseId": case_id,
                "kind": "high_similarity" if cos >= 0.65 else ("medium_similarity" if cos >= 0.50 else "distractor_negative"),
                "cosine": cos,
                "evidenceKind": "SimilarPassages",
                "left": {
                    "pageId": p1["pageId"],
                    "title": records[p1["pageId"]]["title"],
                    "sectionTitle": p1["sectionTitle"],
                    "passageId": p1["id"],
                    "excerpt": p1["excerpt"],
                },
                "right": {
                    "pageId": p2["pageId"],
                    "title": records[p2["pageId"]]["title"],
                    "sectionTitle": p2["sectionTitle"],
                    "passageId": p2["id"],
                    "excerpt": p2["excerpt"],
                },
                "humanRating": None,
                "provenance": "newly_sampled_candidate",
            })

    generate_additional_pairs(calib_pages, pairs_by_split["calibration"], int(target_count * 0.4))
    # Also protect callers that supply partitions without first accounting for exposure.
    exposed = exposed_note_ids(reviewed_cases)
    exposed_families = {note_family(pid, record) for pid, record in records.items()
                       if canonical_page_id(pid) in exposed}
    fresh_held_out = {pid for pid in held_out_pages if canonical_page_id(pid) not in exposed
                     and (pid not in records or note_family(pid, records[pid]) not in exposed_families)}
    generate_additional_pairs(fresh_held_out, pairs_by_split["held_out"], int(target_count * 0.6))
    return pairs_by_split


def build_topic_membership_cases(
    records: Dict[str, Dict[str, Any]],
    calib_pages: Set[str],
    held_out_pages: Set[str],
    target_count: int = 120,
) -> Dict[str, List[Dict[str, Any]]]:
    """Builds topic membership judgment cases (positive, multi-topic, negative)."""
    results = {"calibration": [], "held_out": []}

    def generate_memberships(pages: Set[str], limit: int) -> List[Dict[str, Any]]:
        page_list = sorted(pages)
        cases = []
        for pid in page_list:
            note = records[pid]
            passages = [p for p in note["passages"] if len(p["excerpt"].split()) >= 10]
            if not passages:
                continue
            for p in passages[:min(len(passages), max(3, limit // max(1, len(page_list))))]:
                case_id = hashlib.sha256(f"membership:{pid}:{p['id']}".encode()).hexdigest()[:16]
                cases.append({
                    "caseId": case_id,
                    "pageId": pid,
                    "title": note["title"],
                    "passageId": p["id"],
                    "sectionTitle": p["sectionTitle"],
                    "excerpt": p["excerpt"],
                    "vector": p["vector"].tolist(),
                })
        return cases[:limit]

    results["calibration"] = generate_memberships(calib_pages, int(target_count * 0.4))
    results["held_out"] = generate_memberships(held_out_pages, int(target_count * 0.6))
    return results


def build_topic_labeling_cases(
    records: Dict[str, Dict[str, Any]],
    reviewed_cases: List[Dict[str, Any]],
    existing_judgments: Dict[str, Dict[str, Any]],
    target_count: int = 30,
) -> List[Dict[str, Any]]:
    """Builds candidate topic clusters for labeling evaluation."""
    labeling_cases = []
    seen_ids = set()

    # Incorporate reviewed topics
    for case in reviewed_cases:
        if case.get("kind") != "topic":
            continue
        case_id = case["caseId"]
        content = case.get("content", {})
        rating = existing_judgments.get(case_id)
        labeling_cases.append({
            "caseId": case_id,
            "topicId": content.get("topicId", case_id),
            "baselineLabel": content.get("label", ""),
            "members": content.get("members", []),
            "humanRating": rating,
            "provenance": "historical_human_reviewed" if rating is not None else "historical_exposed_unrated",
            "exposure": "previously_exposed",
        })
        seen_ids.add(case_id)

    # Sample additional synthetic/candidate clusters from remaining notes
    page_list = sorted(records.keys())
    for i in range(0, len(page_list), 2):
        if len(labeling_cases) >= target_count:
            break
        p1 = page_list[i]
        p2 = page_list[(i + 1) % len(page_list)]
        members = []
        for pid in [p1, p2]:
            note = records[pid]
            for p in note["passages"][:3]:
                members.append({
                    "pageId": pid,
                    "title": note["title"],
                    "section": p["sectionTitle"],
                    "excerpt": p["excerpt"],
                    "passageId": p["id"],
                })
        case_id = hashlib.sha256(f"labeling:{p1}:{p2}".encode()).hexdigest()[:16]
        if case_id in seen_ids:
            continue
        seen_ids.add(case_id)
        labeling_cases.append({
            "caseId": case_id,
            "topicId": case_id,
            "baselineLabel": f"Notes related to {records[p1]['title']}",
            "members": members,
            "humanRating": None,
            "provenance": "candidate_cluster",
        })

    return labeling_cases


def build_explicit_reference_cases(records: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Identifies explicit Note references in passages using deterministic regex rules."""
    titles = {pid: records[pid]["title"].strip().lower() for pid in records if records[pid]["title"].strip()}
    cases = []

    for pid, note in records.items():
        for p in note["passages"]:
            text_lower = p["excerpt"].lower()
            for target_id, target_title in titles.items():
                if target_id == pid or len(target_title) < 4:
                    continue
                if target_title in text_lower:
                    case_id = hashlib.sha256(f"ref:{pid}:{p['id']}:{target_id}".encode()).hexdigest()[:16]
                    cases.append({
                        "caseId": case_id,
                        "sourcePageId": pid,
                        "targetPageId": target_id,
                        "sourceTitle": note["title"],
                        "targetTitle": records[target_id]["title"],
                        "passageId": p["id"],
                        "excerpt": p["excerpt"],
                        "referenceKind": "exact_title_match",
                    })
    return cases


def build_benchmark_dataset(
    snapshot_dir: Path,
    output_path: Path,
    seed: int = 42,
) -> Dict[str, Any]:
    """Extracts, builds, and saves the complete benchmark dataset."""
    semantic_index_dir = snapshot_dir / "semantic-index"
    cache_dir = snapshot_dir / "document-cache"

    records = load_nrs2_records(semantic_index_dir)
    cache = load_document_cache(cache_dir)
    reviewed_cases, judgments = load_existing_review_and_judgments(snapshot_dir.parent)

    calib_pages, held_out_pages = partition_notes_by_family(records, calib_ratio=0.4, seed=seed,
                                                           exposed_pages=exposed_note_ids(reviewed_cases))
    connections = build_passage_connections(records, calib_pages, held_out_pages, reviewed_cases, judgments, target_count=120)
    memberships = build_topic_membership_cases(records, calib_pages, held_out_pages, target_count=120)
    labeling = build_topic_labeling_cases(records, reviewed_cases, judgments, target_count=30)
    references = build_explicit_reference_cases(records)

    total_judged = sum(1 for c in connections["calibration"] + connections["held_out"] + connections["previously_exposed"] if c["humanRating"] is not None) + sum(1 for l in labeling if l["humanRating"] is not None)

    benchmark = {
        "version": 2,
        "seed": seed,
        "metadata": {
            "totalNotes": len(records),
            "totalPassages": sum(len(r["passages"]) for r in records.values()),
            "calibrationNotes": len(calib_pages),
            "heldOutNotes": len(held_out_pages),
            "connectionPairsCount": len(connections["calibration"]) + len(connections["held_out"]),
            "previouslyExposedConnectionCount": len(connections["previously_exposed"]),
            "membershipCasesCount": len(memberships["calibration"]) + len(memberships["held_out"]),
            "topicLabelingCasesCount": len(labeling),
            "explicitReferenceCasesCount": len(references),
            "judgedCasesCount": total_judged,
        },
        "partitions": {
            "calibrationNotes": sorted(list(calib_pages)),
            "heldOutNotes": sorted(list(held_out_pages)),
        },
        "tasks": {
            "passageConnections": connections,
            "topicMemberships": memberships,
            "topicLabeling": labeling,
            "explicitReferences": references,
        },
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(benchmark, f, indent=2)

    return benchmark["metadata"]


if __name__ == "__main__":
    base_dir = Path("app/build/private-discovery-review/2026-10-16-baseline/no_backup")
    out_file = Path("app/build/experiments/benchmark_dataset.json")
    meta = build_benchmark_dataset(base_dir, out_file)
    print(f"Benchmark dataset built successfully: {meta}")
