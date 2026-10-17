import unittest
from pathlib import Path
import sys

sys.path.append(str(Path(__file__).resolve().parents[1] / "experiments"))
from dataset_builder import (
    canonical_page_id,
    partition_notes_by_family,
    build_passage_connections,
    exposed_note_ids,
    build_topic_labeling_cases,
)


class DatasetBuilderTest(unittest.TestCase):
    def test_topic_and_pair_exposures_are_canonicalized_without_promoting_ratings(self):
        cases = [{"kind": "topic", "content": {"memberPageIds": ["AA-BB"],
                  "members": [{"pageId": "CC-DD"}]}},
                 {"kind": "potentialMissedConnection", "content": {
                  "left": {"pageId": "EE-FF"}, "right": {"pageId": "CC-DD"}}}]
        self.assertEqual({"aabb", "ccdd", "eeff"}, exposed_note_ids(cases))
        topic = {"caseId": "topic", "kind": "topic", "content": {"members": []}}
        result = build_topic_labeling_cases({}, [topic], {}, target_count=1)
        self.assertIsNone(result[0]["humanRating"])
        self.assertEqual("historical_exposed_unrated", result[0]["provenance"])

    def test_fresh_unrated_pairs_remain_available_in_held_out(self):
        records = {}
        for page in ("a", "b"):
            records[page] = {"title": page, "passages": [{
                "id": page + "passage", "pageId": page, "sectionTitle": "Body",
                "excerpt": "This is a long enough body passage for evaluating useful source evidence.",
                "vector": [1.0, 0.0]}]}
        result = build_passage_connections(records, set(), {"a", "b"}, [], {}, target_count=20)
        self.assertEqual(1, len(result["held_out"]))
        self.assertIsNone(result["held_out"][0]["humanRating"])
        self.assertEqual([], result["previously_exposed"])

    def test_exposed_note_families_cannot_become_held_out(self):
        records = {key: {"title": title, "passages": []} for key, title in
                   [("a", "Forest Ecology"), ("b", "Forest Canopy"),
                    ("c", "Android Playback"), ("d", "Ocean Biology")]}
        calibration, held_out = partition_notes_by_family(records, exposed_pages={"a"})
        self.assertTrue({"a", "b"} <= calibration)
        self.assertFalse({"a", "b"} & held_out)

    def test_historical_pairs_are_followup_even_when_both_endpoints_are_held_out(self):
        historical = {"caseId": "old", "kind": "connection", "content": {
            "left": {"pageId": "AA-BB"}, "right": {"pageId": "CC-DD"}}}
        rating = {"decision": "keep", "usefulness": "High"}
        result = build_passage_connections({}, set(), {"aabb", "ccdd"},
                                           [historical], {"old": rating}, target_count=0)
        self.assertEqual([], result["held_out"])
        self.assertEqual([], result["calibration"])
        self.assertEqual(rating, result["previously_exposed"][0]["humanRating"])

    def test_cross_split_historical_pairs_do_not_import_held_out_families_into_calibration(self):
        historical = {"caseId": "old", "kind": "connection", "content": {
            "left": {"pageId": "a"}, "right": {"pageId": "b"}}}
        result = build_passage_connections({}, {"a"}, {"b"}, [historical], {}, target_count=0)
        self.assertEqual([], result["calibration"])
        self.assertEqual([], result["held_out"])
        self.assertIsNone(result["previously_exposed"][0]["humanRating"])
        self.assertEqual("historical_exposed_unrated", result["previously_exposed"][0]["provenance"])

    def test_changed_case_id_does_not_make_exposed_pair_fresh(self):
        records = {}
        for page in ("a", "b"):
            records[page] = {"title": page, "passages": [{
                "id": page + "passage", "pageId": page, "sectionTitle": "Body",
                "excerpt": "This is a long enough body passage for evaluating useful source evidence.",
                "vector": [1.0, 0.0]}]}
        historical = {"caseId": "old", "kind": "connection", "content": {
            "left": {"pageId": "a"}, "right": {"pageId": "b"}}}
        result = build_passage_connections(records, {"a", "b"}, set(), [historical], {}, target_count=20)
        self.assertEqual([], result["calibration"])

    def test_canonical_page_id_normalization(self):
        self.assertEqual("1234abcd", canonical_page_id(" 1234-ABCD "))
        self.assertEqual("a1b2c3d4", canonical_page_id("A1B2-C3D4"))

    def test_partition_notes_by_family_prevents_leakage(self):
        records = {
            f"note_{i}": {
                "title": f"Forest Ecology Part {i % 3}" if i < 6 else f"Android Architecture {i}",
                "passages": []
            }
            for i in range(12)
        }
        calib, held_out = partition_notes_by_family(records, calib_ratio=0.4, seed=42)

        # Invariant 1: Disjoint sets (no leakage)
        self.assertEqual(0, len(calib.intersection(held_out)))
        # Invariant 2: Union covers all notes
        self.assertEqual(set(records.keys()), calib.union(held_out))
        # Invariant 3: Both splits are nonempty
        self.assertTrue(len(calib) > 0)
        self.assertTrue(len(held_out) > 0)


if __name__ == "__main__":
    unittest.main()
