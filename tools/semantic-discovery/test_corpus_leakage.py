import unittest

from corpus_leakage import audit_leakage


class CorpusLeakageTest(unittest.TestCase):
    def note(self, identity, family, split, paragraphs):
        return {"id": identity, "family": family, "partition": split,
                "sections": [{"title": "Body", "paragraphs": [{"text": p} for p in paragraphs]}]}

    def test_shared_substantial_prose_flags_the_entire_held_out_family_without_creating_human_truth(self):
        text = " ".join(f"coastal{i}" for i in range(100))
        notes = [self.note("a", "systems", "calibration", [text]),
                 self.note("b", "ecology", "held_out", [text.upper()]),
                 self.note("c", "ecology", "held_out", [" ".join(f"forest{i}" for i in range(100))])]
        result = audit_leakage(notes)
        self.assertEqual(["b", "c"], result["excludedHeldOutNoteIds"])
        self.assertEqual(["ecology"], result["contaminatedHeldOutFamilies"])
        self.assertEqual(3, result["pairsConsidered"])
        self.assertEqual("unmeasured", result["humanQualityGate"])

    def test_contained_notes_are_detected_even_with_different_paragraph_boundaries(self):
        words = [f"subject{i}" for i in range(500)]
        notes = [self.note("a", "systems", "calibration", [" ".join(words[:250])]),
                 self.note("b", "ecology", "held_out", [" ".join(words)])]
        result = audit_leakage(notes)
        self.assertEqual(1, len(result["nearDuplicatePairs"]))
        self.assertGreaterEqual(result["nearDuplicatePairs"][0]["containment"], .99)
        self.assertEqual(0, len(result["sharedParagraphPairs"]))

    def test_unrelated_prose_does_not_get_a_semantic_independence_or_quality_pass(self):
        notes = [self.note(str(i), str(i), "held_out" if i % 2 else "calibration",
                           [" ".join(f"family{i}word{j}" for j in range(100))]) for i in range(4)]
        result = audit_leakage(notes)
        self.assertEqual(6, result["pairsConsidered"])
        self.assertEqual([], result["excludedHeldOutNoteIds"])
        self.assertFalse(result["completeCorpus"])
        self.assertEqual("incomplete", result["leakageGate"])
        self.assertEqual("unmeasured", result["humanQualityGate"])

    def test_duplicate_id_or_a_family_split_cannot_be_a_reliable_audit(self):
        a = self.note("a", "family", "calibration", ["some text"])
        for b in [self.note("a", "other", "held_out", ["other text"]),
                  self.note("b", "family", "held_out", ["other text"])]:
            with self.assertRaises(ValueError):
                audit_leakage([a, b])


if __name__ == "__main__":
    unittest.main()
