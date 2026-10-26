import copy
import unittest

from evaluate import evaluate


class EvaluationContractTest(unittest.TestCase):
    def setUp(self):
        # Test-only annotations exercise accounting; they are not corpus judgments.
        self.cases = {"cases": [{"id": "a", "family": "f", "split": "development",
            "passages": [{"id": "p1", "eligible": True}, {"id": "p2", "eligible": True}],
            "references": [{"id": "r1", "provenance": "human"}]}]}
        self.run = {"identity": {"model": "test", "prompt": "test", "corpus": "test"},
            "outputs": [{"case_id": "a", "topics": [{"id": "t1", "label": "Retries",
                "passage_ids": ["p1"]}]}],
            "reviews": [{"case_id": "a", "proposal_id": "t1", "reviewer": "test-only",
                "provenance": "human", "reference_id": "r1", "topic_supported": True,
                "label_supported": True, "label_useful_specific": True, "supported_passage_ids": ["p1"]}]}

    def score(self):
        return evaluate(self.cases, self.run)

    def testSupportedOutputHasExplicitDenominators(self):
        result = self.score()["human"]
        self.assertEqual(1.0, result["topic_precision"])
        self.assertEqual(1.0, result["concept_recall"])
        self.assertEqual(1, result["rated_topics"])

    def testFabricatedCitationCannotReceiveAUsefulTopicScore(self):
        self.run["outputs"][0]["topics"][0]["passage_ids"] = ["invented"]
        self.run["reviews"][0]["supported_passage_ids"] = []
        result = self.score()
        self.assertEqual(1, result["invalid_topics"])
        self.assertEqual(0.0, result["human"]["topic_precision"])

    def testOmittingAConceptReducesRecall(self):
        self.cases["cases"][0]["references"].append({"id": "r2", "provenance": "human"})
        self.assertEqual(.5, self.score()["human"]["concept_recall"])

    def testUnsupportedLabelReducesLabelPrecision(self):
        self.run["reviews"][0]["label_supported"] = False
        self.assertEqual(0.0, self.score()["human"]["label_precision"])

    def testWrongMembershipReducesMembershipPrecision(self):
        self.run["outputs"][0]["topics"][0]["passage_ids"].append("p2")
        self.assertEqual(.5, self.score()["human"]["membership_precision"])

    def testUnratedOutputsAreNotCountedAsCorrect(self):
        self.run["reviews"] = []
        result = self.score()
        self.assertEqual(1, result["unrated_topics"])
        self.assertIsNone(result["human"]["topic_precision"])
        self.assertEqual(0.0, result["human"]["concept_recall"])

    def testProvisionalJudgmentsDoNotBecomeHumanEvidence(self):
        self.cases["cases"][0]["references"][0]["provenance"] = "provisional"
        self.run["reviews"][0]["provenance"] = "provisional"
        result = self.score()
        self.assertEqual(0, result["human"]["rated_topics"])
        self.assertIsNone(result["human"]["concept_recall"])
        self.assertEqual(1.0, result["provisional"]["topic_precision"])

    def testRevokedPassageInvalidatesOutput(self):
        self.cases["cases"][0]["passages"][0]["eligible"] = False
        self.run["reviews"][0]["supported_passage_ids"] = []
        self.assertEqual(1, self.score()["invalid_topics"])

    def testAgentReviewIsScoredWithoutBeingRelabeledAsHuman(self):
        self.cases["cases"][0]["references"][0]["provenance"] = "agent"
        self.run["reviews"][0]["provenance"] = "agent"
        result = self.score()
        self.assertEqual(0, result["human"]["rated_topics"])
        self.assertEqual(1.0, result["agent"]["topic_precision"])

    def testFamilyCannotLeakBetweenPartitions(self):
        other = copy.deepcopy(self.cases["cases"][0])
        other.update(id="b", split="heldout")
        self.cases["cases"].append(other)
        with self.assertRaisesRegex(ValueError, "family"):
            self.score()

    def testDuplicateProposalIdsAreRejected(self):
        self.run["outputs"][0]["topics"].append(self.run["outputs"][0]["topics"][0])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.score()

    def testReviewCannotInventAReferenceConcept(self):
        self.run["reviews"][0]["reference_id"] = "fake"
        with self.assertRaisesRegex(ValueError, "reference"):
            self.score()

    def testMissingCasesAreNotSilentlyScoredAsACompleteRun(self):
        self.run["outputs"] = []
        with self.assertRaisesRegex(ValueError, "coverage"):
            self.score()

    def testDuplicateReviewsRequireAdjudicationInsteadOfDoubleCounting(self):
        self.run["reviews"].append(self.run["reviews"][0])
        with self.assertRaisesRegex(ValueError, "review"):
            self.score()

    def testSameConceptCannotBeCountedTwiceForRecall(self):
        topic = copy.deepcopy(self.run["outputs"][0]["topics"][0])
        topic["id"] = "t2"
        self.run["outputs"][0]["topics"].append(topic)
        review = copy.deepcopy(self.run["reviews"][0])
        review["proposal_id"] = "t2"
        self.run["reviews"].append(review)
        self.assertEqual(1.0, self.score()["human"]["concept_recall"])

    def testDevelopmentScoresCannotHideAHeldoutFailure(self):
        other = copy.deepcopy(self.cases["cases"][0])
        other.update(id="b", family="other", split="heldout")
        self.cases["cases"].append(other)
        self.run["outputs"].append({"case_id": "b", "topics": []})
        result = self.score()
        self.assertEqual(1.0, result["partitions"]["development"]["human"]["concept_recall"])
        self.assertEqual(0.0, result["partitions"]["heldout"]["human"]["concept_recall"])

    def testUnknownReferenceProvenanceCannotSilentlyDisappear(self):
        self.cases["cases"][0]["references"][0]["provenance"] = "unverified"
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.score()

    def testEvaluationDoesNotRewriteItsInputCorpus(self):
        before = copy.deepcopy(self.cases)
        self.score()
        self.assertEqual(before, self.cases)

    def testGroundedButGenericLabelsDoNotPassTheUsefulSpecificLabelGate(self):
        self.run["reviews"][0]["label_useful_specific"] = False
        self.assertEqual(0.0, self.score()["human"]["label_precision"])


if __name__ == "__main__":
    unittest.main()
