import unittest

import sys
sys.path.append("tools/semantic-discovery/experiments")
from classifier_benchmark import softmax


class ClassifierTest(unittest.TestCase):
    def test_softmax_normalizes_to_one(self):
        logits = [2.0, 1.0, 0.1]
        probs = softmax(logits)
        self.assertAlmostEqual(1.0, sum(probs), places=5)
        self.assertGreater(probs[0], probs[1])
        self.assertGreater(probs[1], probs[2])

    def test_softmax_handles_extreme_logits_without_overflow(self):
        logits = [1000.0, 999.0, 500.0]
        probs = softmax(logits)
        self.assertAlmostEqual(1.0, sum(probs), places=5)
        self.assertTrue(all(p >= 0.0 for p in probs))


if __name__ == "__main__":
    unittest.main()
