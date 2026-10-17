import unittest

from quality_audit import audit_judgments, compare_runs


class QualityAuditTest(unittest.TestCase):
    def test_editorial_decisions_do_not_become_human_truth(self):
        report = audit_judgments({"totalCandidates": 1, "cases": [
            {"clusterId": 3, "decision": "Rename", "approvedTitle": "Forest ecology"}]})
        self.assertEqual(0, report["confirmedHuman"])
        self.assertEqual(1, report["provisional"])
        self.assertIsNone(report["humanKeepRate"])

    def test_compound_editorial_decisions_remain_provisional(self):
        report = audit_judgments({"cases": [{"clusterId": 2, "decision": "Merge/Refine"}]})
        self.assertEqual(1, report["provisional"])
        self.assertEqual(0, report["confirmedHuman"])

    def test_human_confirmation_requires_case_provenance(self):
        report = audit_judgments({"cases": [
            {"caseId": "a", "decision": "Keep", "judgmentOrigin": "human",
             "reviewerConfirmed": True, "reviewedAt": "2026-10-17"},
            {"caseId": "b", "decision": "Withhold", "judgmentOrigin": "assistant"},
            {"caseId": "c", "decision": None}]})
        self.assertEqual(1, report["confirmedHuman"])
        self.assertEqual(1, report["provisional"])
        self.assertEqual(1, report["unrated"])
        self.assertEqual(1.0, report["humanKeepRate"])

    def test_declared_counts_cannot_hide_missing_or_duplicate_cases(self):
        for document in ({"totalCandidates": 2, "cases": [{"clusterId": 1}]},
                         {"cases": [{"clusterId": 1}, {"clusterId": 1}]}):
            with self.assertRaises(ValueError):
                audit_judgments(document)

    def test_comparison_rejects_changed_corpus_or_leaked_families(self):
        baseline = {"corpusFingerprint": "abc", "calibrationFamilies": ["trees"],
                    "heldOutFamilies": ["ocean"], "metrics": {"published": 2}}
        for changed in (dict(baseline, corpusFingerprint="def"),
                        dict(baseline, heldOutFamilies=["trees"])):
            with self.assertRaises(ValueError):
                compare_runs(baseline, changed)

    def test_previously_exposed_families_cannot_be_called_held_out(self):
        run = {"corpusFingerprint": "abc", "calibrationFamilies": ["trees"],
               "heldOutFamilies": ["ocean"], "exposedFamilies": ["ocean"]}
        with self.assertRaises(ValueError):
            compare_runs(run, run)

    def test_counts_are_not_quality_metrics_and_missing_gates_remain_unmeasured(self):
        run = {"corpusFingerprint": "abc", "calibrationFamilies": ["trees"],
               "heldOutFamilies": ["ocean"], "metrics": {"published": 40}}
        report = compare_runs(run, run)
        self.assertEqual("unmeasured", report["qualityGate"])
        self.assertNotIn("passed", report.values())

    def test_comparison_cannot_echo_private_content_as_metrics(self):
        run = {"corpusFingerprint": "abc", "calibrationFamilies": ["trees"],
               "heldOutFamilies": ["ocean"], "metrics": {"published": 40, "title": "Private"}}
        with self.assertRaises(ValueError):
            compare_runs(run, run)


if __name__ == "__main__":
    unittest.main()
