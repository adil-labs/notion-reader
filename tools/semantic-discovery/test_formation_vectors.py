import json
from pathlib import Path
import tempfile
import unittest
import numpy as np

from formation_vectors import promote, POLICY
from public_vectors import file_hash, verify_artifact


class FormationVectorsTest(unittest.TestCase):
    def fixture(self, root):
        raw = root / "vectors.f32"
        np.asarray([[.6, .8]], dtype="<f4").tofile(raw)
        rows = root / "rows.json"
        rows.write_text(json.dumps([{"passageId": "opportunity", "pageId": "a", "opportunityIndex": 0,
                                    "input": "Literal source", "family": "systems", "partition": "held_out",
                                    "memberPassageIds": ["a:1", "a:2"]}]))
        manifest = {"complete": True, "notes": 1, "opportunities": 1, "eligiblePassages": 2,
                    "formationOpportunityPolicy": POLICY, "identity": {"dimensions": 2, "modelSha256": "m"},
                    "sourceVectorManifestSha256": "source", "rawVectorsFile": raw.name, "rawVectorsSha256": file_hash(raw),
                    "rowsFile": rows.name, "rowsSha256": file_hash(rows)}
        (root / "opportunity-manifest.json").write_text(json.dumps(manifest))
        return manifest

    def test_native_prepared_vectors_and_all_member_provenance_survive_the_graph_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.fixture(root)
            output = root / "graph"
            manifest = promote(root, output, expected_notes=1)
            vectors, rows = verify_artifact(output, manifest)
            self.assertEqual(POLICY, manifest["formationOpportunityPolicy"])
            self.assertEqual(["a:1", "a:2"], rows[0]["memberPassageIds"])
            np.testing.assert_array_equal(vectors.view(np.uint32), np.asarray([[.6, .8]], dtype=np.float32).view(np.uint32))

    def test_lost_provenance_or_partial_native_corpus_cannot_publish_a_graph_input(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = self.fixture(root)
            output = root / "graph"
            with self.assertRaises(ValueError):
                promote(root, output)
            manifest["eligiblePassages"] = 3
            (root / "opportunity-manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                promote(root, output, expected_notes=1)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
