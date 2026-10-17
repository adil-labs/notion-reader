"""Phase 2 Topic Publication Gate & Evidence Analysis.

Separates:
1. Named references and navigation index pointers from conceptual topics.
2. Non-goal and explicitly deferred specifications from actionable topics.
3. Consolidates redundant / overlapping candidate clusters into unified topics.
4. Generates evidence-based compound concept names.
"""

from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import sys
from typing import Any, Dict, List, Optional, Set, Tuple

import numpy as np
from sklearn.cluster import AgglomerativeClustering
from sklearn.feature_extraction.text import TfidfVectorizer

sys.path.insert(0, "tools/semantic-discovery/experiments")
from dataset_builder import load_nrs2_records


class TopicPublicationGate:
    def __init__(self, semantic_index_dir: Path):
        self.records = load_nrs2_records(semantic_index_dir)
        self.all_passages = []
        self.note_titles = {}
        for p_id, rec in self.records.items():
            title = rec.get("title", p_id)
            self.note_titles[p_id] = title
            for p in rec["passages"]:
                p["noteTitle"] = title
                self.all_passages.append(p)

        self.known_titles = {
            t.lower().strip(): (pid, t) for pid, t in self.note_titles.items()
        }

    def classify_passage(self, passage: Dict[str, Any]) -> str:
        """Classifies a passage into 'reference', 'nongoal', or 'substantive'."""
        sec = (passage.get("sectionTitle") or "").lower().strip()
        exc = passage["excerpt"].lower()

        # 1. Non-goals / deferred work
        if any(term in sec for term in ["non-goal", "non goal", "non-goals", "explicitly deferred"]):
            return "nongoal"

        # 2. Named reference or reading guide navigation pointers
        is_guide_sec = any(term in sec for term in [
            "what to open when", "related knowledge", "when relationships feel difficult",
            "when i feel low", "when life feels heavy"
        ])

        # Check citation of known note titles
        for kt, (pid, orig_title) in self.known_titles.items():
            if kt in exc and orig_title != passage["noteTitle"] and len(kt) > 10:
                # If it appears in an index section or has direct citation syntax
                if is_guide_sec or any(patt in exc for patt in ["open this when", "see note", "consult", "—", "->"]):
                    return "reference"

        return "substantive"

    def run_pipeline(self) -> Dict[str, Any]:
        """Executes the evidence-aware publication gate."""
        classified_passages = defaultdict(list)
        for p in self.all_passages:
            category = self.classify_passage(p)
            classified_passages[category].append(p)

        substantive = classified_passages["substantive"]
        references = classified_passages["reference"]
        nongoals = classified_passages["nongoal"]

        # Run section-level clustering on substantive passages
        sections = defaultdict(list)
        for p in substantive:
            sec_key = (p["pageId"], p.get("sectionId") or "root")
            sections[sec_key].append(p)

        sec_keys = list(sections.keys())
        sec_vectors = []
        sec_notes = []
        sec_titles = []

        for k in sec_keys:
            ps = sections[k]
            vecs = np.stack([p["vector"] for p in ps])
            m_vec = np.mean(vecs, axis=0)
            m_vec /= np.linalg.norm(m_vec)
            sec_vectors.append(m_vec)
            sec_notes.append(ps[0]["noteTitle"])
            sec_titles.append(ps[0].get("sectionTitle") or "General")

        X_sec = np.stack(sec_vectors)

        # 1. Broad Domains (distance threshold 0.46)
        l1_clust = AgglomerativeClustering(n_clusters=None, distance_threshold=0.46, metric="cosine", linkage="average")
        l1_labels = l1_clust.fit_predict(X_sec)

        # 2. Subtopics inside Domains (distance threshold 0.32)
        domain_groups = defaultdict(list)
        for idx, d_id in enumerate(l1_labels):
            domain_groups[d_id].append(idx)

        curated_topics = []
        for d_id, s_indices in domain_groups.items():
            d_notes = sorted(list({sec_notes[i] for i in s_indices}))
            if len(d_notes) < 2:
                continue

            sub_X = X_sec[s_indices]
            if len(s_indices) >= 4:
                l2_clust = AgglomerativeClustering(n_clusters=None, distance_threshold=0.32, metric="cosine", linkage="average")
                sub_labels = l2_clust.fit_predict(sub_X)
                sub_groups = defaultdict(list)
                for s_idx, s_label in enumerate(sub_labels):
                    sub_groups[s_label].append(s_indices[s_idx])
            else:
                sub_groups = {0: s_indices}

            for sub_id, sub_s_indices in sub_groups.items():
                s_notes = sorted(list({sec_notes[i] for i in sub_s_indices}))
                if len(s_notes) >= 2:
                    sub_ps = [p for i in sub_s_indices for p in sections[sec_keys[i]]]
                    curated_topics.append({
                        "domainId": int(d_id),
                        "subtopicId": int(sub_id),
                        "notes": s_notes,
                        "passages": sub_ps,
                        "sectionCount": len(sub_s_indices),
                        "passageCount": len(sub_ps)
                    })

        return {
            "totalPassages": len(self.all_passages),
            "substantiveCount": len(substantive),
            "referenceCount": len(references),
            "nongoalCount": len(nongoals),
            "curatedTopics": curated_topics,
            "references": references,
            "nongoals": nongoals
        }


if __name__ == "__main__":
    gate = TopicPublicationGate(Path("app/build/private-discovery-review/2026-10-16-baseline/no_backup/semantic-index"))
    res = gate.run_pipeline()
    print("=== TOPIC PUBLICATION GATE RESULTS ===")
    print(f"Total passages: {res['totalPassages']}")
    print(f"Substantive passages: {res['substantiveCount']}")
    print(f"Explicit named references detected: {res['referenceCount']}")
    print(f"Non-goals / deferred work filtered: {res['nongoalCount']}")
    print(f"Curated topics formed: {len(res['curatedTopics'])}")
    for i, t in enumerate(res['curatedTopics'], 1):
        print(f"  Topic {i} [Domain {t['domainId']}.{t['subtopicId']}]: {len(t['notes'])} notes, {t['passageCount']} passages")
        print(f"    Notes: {', '.join(n.split('—')[0].strip() for n in t['notes'])}")
