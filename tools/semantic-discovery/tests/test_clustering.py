import unittest
from pathlib import Path
import sys
import numpy as np

sys.path.append(str(Path(__file__).resolve().parents[1] / "experiments"))
from clustering_benchmarks import (
    run_baseline_anchor_clustering,
    run_agglomerative_clustering,
    extract_ctfidf_keywords,
)


class ClusteringBenchmarksTest(unittest.TestCase):
    def setUp(self):
        np.random.seed(42)
        dim = 384
        c1 = np.random.randn(dim); c1 /= np.linalg.norm(c1)
        c2 = np.random.randn(dim); c2 /= np.linalg.norm(c2)

        self.passages = []
        for i in range(6):
            noise = np.random.randn(dim); noise /= np.linalg.norm(noise)
            v = 0.9 * c1 + 0.1 * noise; v /= np.linalg.norm(v)
            self.passages.append({
                "id": f"p_eco_{i}",
                "pageId": f"page_eco_{i % 3}",
                "sectionTitle": "Forest Wildlife",
                "excerpt": f"Trees shelter woodland wildlife and forest birds number {i}.",
                "vector": v.tolist(),
            })
        for i in range(6):
            noise = np.random.randn(dim); noise /= np.linalg.norm(noise)
            v = 0.9 * c2 + 0.1 * noise; v /= np.linalg.norm(v)
            self.passages.append({
                "id": f"p_droid_{i}",
                "pageId": f"page_droid_{i % 3}",
                "sectionTitle": "Android Compose",
                "excerpt": f"Android jetpack compose renders reactive user interface components {i}.",
                "vector": v.tolist(),
            })

    def test_agglomerative_separates_distinct_topics(self):
        res = run_agglomerative_clustering(self.passages, distance_threshold=0.35, min_notes=2)
        self.assertGreaterEqual(res["visibleTopicsCount"], 2)
        self.assertIsNotNone(res["silhouetteScore"])
        self.assertGreater(res["silhouetteScore"], 0.5)

    def test_ctfidf_extracts_distinct_keywords(self):
        res = run_agglomerative_clustering(self.passages, distance_threshold=0.35, min_notes=2)
        labels = extract_ctfidf_keywords(res["clusters"])
        self.assertGreaterEqual(len(labels), 2)
        for l in labels:
            self.assertTrue(len(l["topKeywords"]) > 0)
            self.assertTrue("&" in l["generatedLabel"] or len(l["generatedLabel"]) > 3)


if __name__ == "__main__":
    unittest.main()
