import unittest

import sys
sys.path.append("tools/semantic-discovery/experiments")
from reranker_benchmark import compute_ranking_metrics


class RerankerTest(unittest.TestCase):
    def test_ranking_metrics_auc_and_counts(self):
        pairs = [
            {"caseId": "p1", "left": {"pageId": "a"}, "right": {"pageId": "b"}, "humanRating": {"decision": "keep"}},
            {"caseId": "p2", "left": {"pageId": "c"}, "right": {"pageId": "d"}, "humanRating": {"decision": "suppress"}},
            {"caseId": "p3", "left": {"pageId": "e"}, "right": {"pageId": "f"}, "humanRating": {"decision": "keep"}},
        ]
        # Perfect ranking: positive items have higher score than negative item
        scores = {"p1": 2.5, "p2": -1.2, "p3": 1.8}
        metrics = compute_ranking_metrics(pairs, scores, limit=3)

        self.assertEqual(3, metrics["selectedCount"])
        self.assertEqual(1.0, metrics["rocAucOnJudgedPairs"])
        self.assertEqual(2, metrics["judgedPositiveCount"])
        self.assertEqual(1, metrics["judgedNegativeCount"])

    def test_ranking_metrics_inverted_auc(self):
        pairs = [
            {"caseId": "p1", "left": {"pageId": "a"}, "right": {"pageId": "b"}, "humanRating": {"decision": "keep"}},
            {"caseId": "p2", "left": {"pageId": "c"}, "right": {"pageId": "d"}, "humanRating": {"decision": "suppress"}},
        ]
        # Inverted ranking: positive item has lower score
        scores = {"p1": -2.0, "p2": 3.0}
        metrics = compute_ranking_metrics(pairs, scores, limit=2)
        self.assertEqual(0.0, metrics["rocAucOnJudgedPairs"])


if __name__ == "__main__":
    unittest.main()
