import json
from pathlib import Path
import tempfile
import unittest
import numpy as np

from public_vectors import mean_pool, write_artifact, verify_artifact, validate_inputs


class PublicVectorsTest(unittest.TestCase):
    def rows(self):
        return [{"pageId": "a", "passageId": "a:p", "input": "Literal A", "family": "ecology", "partition": "held_out"},
                {"pageId": "b", "passageId": "b:p", "input": "Literal B", "family": "systems", "partition": "calibration"}]

    def identity(self):
        return {"corpusSha256": "a" * 64, "passagesSha256": "b" * 64, "vocabularySha256": "c" * 64,
                "modelSha256": "d" * 64, "tokenizerSha256": "e" * 64, "dimensions": 3}

    def test_mean_pool_uses_all_real_tokens_and_normalizes_in_double_precision(self):
        vector = mean_pool(np.asarray([[1, 0, 0], [0, 1, 0]], dtype=np.float32), 3)
        np.testing.assert_allclose(vector, [2 ** -.5, 2 ** -.5, 0], atol=1e-7)
        for hidden in (np.asarray([[float("nan"), 0, 1]]), np.zeros((2, 3))):
            with self.assertRaises(ValueError):
                mean_pool(hidden, 3)

    def test_both_algorithms_can_verify_exactly_the_same_ordered_vectors(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest = write_artifact(self.rows(), self.identity(), Path(folder), lambda _: np.asarray([1, 0, 0]))
            vectors, rows = verify_artifact(Path(folder), manifest)
            self.assertEqual(["a:p", "b:p"], [r["passageId"] for r in rows])
            self.assertEqual((2, 3), vectors.shape)
            self.assertEqual("unmeasured", manifest["humanQualityGate"])
            self.assertEqual("unmeasured", manifest["androidGate"])
            self.assertEqual(self.identity(), manifest["identity"])

    def test_jvm_transport_preserves_float_bits_and_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = write_artifact(self.rows(), self.identity(), root, lambda _: np.asarray([.6, .8, 0], dtype=np.float32))
            vectors, _ = verify_artifact(root, manifest)
            raw = np.fromfile(root / manifest["rawVectorsFile"], dtype="<f4").reshape(vectors.shape)
            np.testing.assert_array_equal(vectors.view(np.uint32), raw.view(np.uint32))
            (root / manifest["rawVectorsFile"]).write_bytes(b"corrupt")
            with self.assertRaises(ValueError):
                verify_artifact(root, manifest)

    def test_corrupt_vectors_or_reordered_rows_cannot_be_used_as_a_paired_comparison(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = write_artifact(self.rows(), self.identity(), root, lambda _: np.asarray([1, 0, 0]))
            index = root / manifest["rowsFile"]
            original = index.read_bytes()
            rows = json.loads(index.read_text())
            index.write_text(json.dumps(rows[::-1]))
            with self.assertRaises(ValueError):
                verify_artifact(root, manifest)
            index.write_bytes(original)
            vector_file = root / manifest["vectorsFile"]
            vectors = np.load(vector_file, allow_pickle=False)
            vectors[0] = [0, 1, 0]
            np.save(vector_file, vectors, allow_pickle=False)
            with self.assertRaises(ValueError):
                verify_artifact(root, manifest)

    def test_failed_inference_cannot_replace_a_previously_complete_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            write_artifact(self.rows(), self.identity(), root, lambda _: np.asarray([1, 0, 0]))
            before = (root / "vector-manifest.json").read_bytes()
            with self.assertRaises(ValueError):
                write_artifact(self.rows(), self.identity(), root, lambda _: np.asarray([float("nan"), 0, 0]))
            self.assertEqual(before, (root / "vector-manifest.json").read_bytes())

    def test_duplicate_ids_and_family_leakage_fail_before_inference(self):
        duplicate = self.rows() + [self.rows()[0]]
        with self.assertRaises(ValueError):
            validate_inputs(duplicate)
        rows = self.rows()
        rows[1]["family"] = rows[0]["family"]
        with self.assertRaises(ValueError):
            validate_inputs(rows)


if __name__ == "__main__":
    unittest.main()
