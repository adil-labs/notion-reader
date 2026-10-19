import unittest
import numpy as np

from graph_formation import neighbor_recall, graph_formation


class GraphFormationTest(unittest.TestCase):
    def test_exact_neighbor_probe_can_detect_an_approximation_miss(self):
        vectors = np.asarray([[1, 0], [.8, .6], [0, 1]], dtype=np.float32)
        self.assertEqual(1, neighbor_recall(vectors, {0: [1]}, 1)["recallAtKWithTies"])
        self.assertEqual(0, neighbor_recall(vectors, {0: [2]}, 1)["recallAtKWithTies"])

    def test_equally_good_ties_do_not_become_false_approximation_misses(self):
        vectors = np.asarray([[1, 0], [1, 0], [1, 0]], dtype=np.float32)
        self.assertEqual(1, neighbor_recall(vectors, {0: [2]}, 1)["recallAtKWithTies"])
        with self.assertRaises(ValueError):
            neighbor_recall(vectors, {0: [0]}, 1)

    def test_graph_preserves_every_seed_including_isolated_and_single_note_subjects(self):
        vectors = np.asarray([[1, 0, 0], [1, 0, 0], [0, 1, 0], [0, 1, 0], [0, 0, 1]], dtype=np.float32)
        rows = [{"passageId": str(i), "partition": "calibration"} for i in range(5)]
        report = graph_formation(vectors, rows, k=2, min_similarity=.8)
        groups = {frozenset(group) for group in report["seedGroups"]}
        self.assertEqual({frozenset(["0", "1"]), frozenset(["2", "3"]), frozenset(["4"])}, groups)
        self.assertEqual(5, report["seedOpportunities"])
        self.assertEqual("unmeasured", report["humanQualityGate"])

    def test_repeated_seeded_runs_keep_the_same_partition_and_report_real_versions(self):
        vectors = np.eye(5, dtype=np.float32)
        rows = [{"passageId": str(i), "partition": "held_out"} for i in range(5)]
        first = graph_formation(vectors, rows, k=2)
        second = graph_formation(vectors, rows, k=2)
        self.assertEqual(first["seedGroups"], second["seedGroups"])
        self.assertIn("faiss", first["versions"])
        self.assertIsNone(first["calibrationNeighborProbe"]["recallAtKWithTies"])
        self.assertEqual("unmeasured", first["androidGate"])


if __name__ == "__main__":
    unittest.main()
