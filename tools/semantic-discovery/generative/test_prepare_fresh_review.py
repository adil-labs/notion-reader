import copy
import hashlib
import json
import unittest
from prepare_fresh_review import prepare
from evaluate import evaluate


class FreshReviewTest(unittest.TestCase):
    def setUp(self):
        self.source = {"cases": [{"id": "example", "family": "example", "split": "heldout",
            "passages": [{"id": "p", "note_id": "n", "eligible": True, "body": "Source evidence"}],
            "references": [{"id": "r", "provenance": "provisional", "passage_ids": ["p"]}]}]}
        self.raw = json.dumps(self.source).encode()
        self.digest = hashlib.sha256(self.raw).hexdigest()
        self.report = {"status": "completed", "semantic": {"stage": "completed", "corpusSha256": self.digest,
            "labelIdentity": "actual-model-prompt-policy", "outputs": [{"case_id": "joint-library", "pending": False,
                "topics": [{"id": "t", "published": True, "label": None,
                            "neutralDisplay": "Topic name pending", "passage_ids": ["p"]}]}]}}
        self.manifest = {"corpusSha256": self.digest, "labelPolicy": "policy", "apkSha256": "frozen-apk"}

    def prepare(self, report=None):
        return prepare(self.raw, json.dumps(report or self.report).encode(), self.manifest)

    def testUnnamedGroupsRemainInTheDenominatorWithoutInventingNamesOrReviews(self):
        corpus, run = self.prepare()
        self.assertIsNone(run["outputs"][0]["topics"][0]["generated_name"])
        self.assertEqual([], run["reviews"])
        scores = evaluate(corpus, run)
        self.assertEqual(1, scores["topics"])
        self.assertEqual(1, scores["unrated_topics"])
        self.assertEqual(1, scores["provisional"]["reference_concepts"])

    def testAnEmptyResultStillRetainsEveryExpectedSubjectAsMissed(self):
        report = copy.deepcopy(self.report)
        report["semantic"]["outputs"][0]["topics"] = []
        corpus, run = self.prepare(report)
        scores = evaluate(corpus, run)
        self.assertEqual(1, scores["provisional"]["reference_concepts"])
        self.assertEqual(0.0, scores["provisional"]["concept_recall"])
        self.assertIsNone(scores["provisional"]["membership_precision"])

    def testChangedCorpusAndIncompleteReportsCannotMasqueradeAsFrozenResults(self):
        for key, value in (("corpusSha256", "changed"), ("stage", "running")):
            report = copy.deepcopy(self.report)
            report["semantic"][key] = value
            with self.assertRaises(ValueError):
                self.prepare(report)

    def testAReferenceMatchDoesNotInventObservedMembershipJudgments(self):
        corpus, run = self.prepare()
        corpus["cases"][0]["references"][0]["provenance"] = "agent"
        run["reviews"] = [{"case_id": "joint-library", "proposal_id": "t", "reference_id": "r",
            "provenance": "agent", "reviewer": "explicit-source-review", "topic_supported": True,
            "label_supported": False, "label_useful_specific": False, "supported_passage_ids": []}]
        scores = evaluate(corpus, run)["agent"]
        self.assertEqual(1, scores["claimed_memberships"])
        self.assertEqual(0, scores["supported_memberships"])
        self.assertEqual(0.0, scores["label_precision"])


if __name__ == "__main__":
    unittest.main()
