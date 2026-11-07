import unittest

from inference_metrics import summarize


class InferenceMetricsTest(unittest.TestCase):
    def test_counts_latency_cache_and_memory_from_shipping_event_shapes(self):
        report = summarize([
            "123 100 200 D DiscoveryInference: supportAttempts=1",
            "123 100 200 D DiscoveryInference: supportNanos=2000000",
            "D/DiscoveryInference(100): supportSuccesses=1",
            "123 100 200 D DiscoveryWork: {supportFreshDecisions=1}",
            "123 100 200 D DiscoveryWork: {supportReused=3}",
            "123 100 200 D DiscoveryWork: {topicsAdmitted=1}",
            "123 D DiscoveryMemory: pssKb=123 rssKb=456",
        ])
        self.assertEqual(.75, report["supportCacheHitRate"])
        self.assertEqual(1, report["inferenceAttemptsPerAdmittedTopic"])
        self.assertEqual(2, report["latencyMilliseconds"]["supportNanos"]["mean"])
        self.assertTrue(report["nativeDurationSamplesMatchAttempts"]["support"])
        self.assertEqual(456, report["memoryKiB"]["rssKb"]["max"])

    def test_missing_and_truncated_evidence_cannot_look_like_complete_inference(self):
        empty = summarize([])
        self.assertIsNone(empty["supportCacheHitRate"])
        self.assertIsNone(empty["inferenceAttemptsPerAdmittedTopic"])
        partial = summarize(["D DiscoveryInference: proposalAttempts=1"])
        self.assertFalse(partial["nativeDurationSamplesMatchAttempts"]["proposal"])
        invalid = summarize(["D DiscoveryInference: supportNanos=garbage"])
        self.assertEqual(1, invalid["malformedMetricLines"])
        self.assertEqual({}, invalid["latencyMilliseconds"])

    def test_report_never_contains_arbitrary_logged_text(self):
        report = summarize(["D DiscoveryWork: private note contents", "D Other: supportAttempts=99"])
        self.assertNotIn("private", str(report))
        self.assertEqual({}, report["counters"])


if __name__ == "__main__":
    unittest.main()
