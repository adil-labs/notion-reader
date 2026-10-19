import copy
import io
import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch
import urllib.error

from public_corpus import extract_sections, sample_sections, validate_corpus, freeze_manifest, verify_frozen, fetch


class PublicCorpusTest(unittest.TestCase):
    def note(self, n=1, family="biology", partition="held_out"):
        return {"id": str(n), "title": "Public article", "family": family, "partition": partition,
                "source": {"pageId": n, "revisionId": 100 + n, "url": f"https://en.wikipedia.org/w/index.php?oldid={100+n}",
                           "license": "CC-BY-SA-4.0", "kind": "public"},
                "sections": [{"title": "Body", "paragraphs": [{"ordinal": 0, "text": f"Literal source prose {n}."}]}],
                "judgmentOrigin": "unrated"}

    def test_section_provenance_survives_inline_markup_and_navigation_is_excluded(self):
        sections = extract_sections('<p>Literal <b>body</b> prose.<sup>1</sup></p><h2>Methods<span class="mw-editsection">edit</span></h2>'
                                    '<p>Actual methods.</p><table><tr><td><p>Unrelated navigation.</p></td></tr></table>')
        self.assertEqual(["Body", "Methods"], [s["title"] for s in sections])
        self.assertEqual("Literal body prose.", sections[0]["paragraphs"][0]["text"])
        self.assertEqual("Actual methods.", sections[1]["paragraphs"][0]["text"])
        self.assertNotIn("navigation", str(sections))

    def test_sampling_gives_later_sections_opportunity_and_keeps_original_ordinals(self):
        sections = [{"title": str(s), "paragraphs": [{"ordinal": p, "text": f"{s}:{p}"} for p in range(9)]} for s in range(4)]
        sampled = sample_sections(sections, 8)
        self.assertEqual(4, len(sampled))
        self.assertEqual([0, 1], [p["ordinal"] for p in sampled[-1]["paragraphs"]])

    def test_frozen_manifest_cannot_promote_unrated_cases_to_human_quality(self):
        notes = [self.note()]
        manifest = freeze_manifest(notes, expected=1)
        self.assertEqual("unmeasured", manifest["humanQualityGate"])
        self.assertEqual(0, manifest["humanJudgments"])
        self.assertEqual(1, manifest["unratedNotes"])
        changed = copy.deepcopy(notes)
        changed[0]["sections"][0]["paragraphs"][0]["text"] += " Changed."
        self.assertNotEqual(manifest["corpusSha256"], freeze_manifest(changed, expected=1)["corpusSha256"])

    def test_duplicate_sources_missing_revisions_and_split_family_leakage_are_rejected(self):
        for notes in ([self.note(), self.note()], [self.note(), self.note(2, partition="calibration")]):
            with self.assertRaises(ValueError):
                validate_corpus(notes, expected=2)
        note = self.note()
        del note["source"]["revisionId"]
        with self.assertRaises(ValueError):
            validate_corpus([note], expected=1)

    def test_a_partial_capture_cannot_be_frozen_as_the_500_note_corpus(self):
        with self.assertRaises(ValueError):
            freeze_manifest([self.note()], expected=500)

    def test_replay_verification_rejects_changed_text_and_partition_metadata(self):
        notes = [self.note()]
        manifest = freeze_manifest(notes, expected=1)
        verify_frozen(notes, manifest)
        for key, value in [("title", "Changed title"), ("partition", "calibration")]:
            changed = copy.deepcopy(notes)
            changed[0][key] = value
            with self.assertRaises(ValueError):
                verify_frozen(changed, manifest)

    def test_rate_limit_retry_honors_server_backoff_and_cached_results_do_not_refetch(self):
        error = urllib.error.HTTPError("https://en.wikipedia.org/w/api.php", 429, "rate limited",
                                       {"Retry-After": "125"}, None)
        with tempfile.TemporaryDirectory() as folder, patch("public_corpus.urllib.request.urlopen",
                side_effect=[error, io.BytesIO(b'{"query":{"ok":true}}')]) as network, patch("public_corpus.time.sleep") as sleep:
            first = fetch({"action": "query"}, Path(folder))
            self.assertEqual(first, fetch({"action": "query"}, Path(folder)))
            self.assertEqual(2, network.call_count)
            sleep.assert_any_call(125)
            self.assertTrue(error.closed, "Rate-limit response must close before waiting/retrying")


if __name__ == "__main__":
    unittest.main()
