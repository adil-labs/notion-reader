import copy
import hashlib
import json
import unittest
from score_name_queries import score


class NameQueryCalibrationTest(unittest.TestCase):
    def setUp(self):
        self.corpus = {"cases": [{"passages": [{"id": p, "eligible": True} for p in ("p0", "p1", "p2")],
            "references": [{"id": "a", "passage_ids": ["p0", "p1"]}, {"id": "b", "passage_ids": ["p2"]}]}]}
        self.origin = json.dumps({"outputs": [{"topics": [{"id": "a", "label": "Alpha"}, {"id": "b", "label": None}]}]}).encode()
        self.judgments = {"reviews": [{"proposal_id": "a", "reference_id": "a"}, {"proposal_id": "b", "reference_id": "b"}]}
        self.report = {"status": "completed", "nameOnlyQueries": {"corpusSha256": "corpus",
            "originSha256": hashlib.sha256(self.origin).hexdigest(), "queries": [
                {"topicId": "a", "query": "Alpha", "candidates": [
                    {"passageId": "p0", "score": .90}, {"passageId": "p1", "score": .84}, {"passageId": "p2", "score": .86}]},
                {"topicId": "b", "skipped": "No name"}]}}

    def score(self, report=None):
        return score(self.corpus, self.judgments, self.origin, report or self.report)

    def testAFalseRetrievedMatchAndAMissingNameRemainInTheirDenominators(self):
        result = self.score()
        row = result["thresholds"][0]
        self.assertEqual(2, row["retrievedMemberships"])
        self.assertEqual(1, row["referenceSupportedMemberships"])
        self.assertEqual(.5, row["precision"])
        self.assertEqual(.5, row["subjectRecall"])
        self.assertFalse(result["acceptance"])

    def testNoRetrievedMatchesCannotBecomePerfectPrecision(self):
        row = self.score()["thresholds"][-1]
        self.assertEqual(0, row["retrievedMemberships"])
        self.assertIsNone(row["precision"])
        self.assertEqual(0.0, row["subjectRecall"])

    def testDroppingAnUnfavourableCandidateCannotBecomeAnImprovedScore(self):
        report = copy.deepcopy(self.report)
        report["nameOnlyQueries"]["queries"][0]["candidates"].pop()
        with self.assertRaises(ValueError):
            self.score(report)

    def testChangingTheGeneratedQueryOrItsOriginIsRejected(self):
        for mutation in ("query", "origin"):
            report = copy.deepcopy(self.report)
            if mutation == "query":
                report["nameOnlyQueries"]["queries"][0]["query"] = "Desired reference wording"
            else:
                report["nameOnlyQueries"]["originSha256"] = "different-source"
            with self.assertRaises(ValueError):
                self.score(report)


if __name__ == "__main__":
    unittest.main()
