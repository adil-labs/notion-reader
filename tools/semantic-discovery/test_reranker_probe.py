import hashlib
from pathlib import Path
import tempfile
import unittest

from probe_reranker import verify_model


class RerankerArtifactTest(unittest.TestCase):
    def test_matching_artifact_returns_its_verified_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / 'public-fixture'
            model.write_bytes(b'public fixture')
            digest = hashlib.sha256(model.read_bytes()).hexdigest()
            self.assertEqual(digest, verify_model(model, {'bytes': model.stat().st_size, 'lfsSha256': digest}))

    def test_same_size_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / 'public-fixture'
            model.write_bytes(b'public fixture')
            with self.assertRaises(ValueError):
                verify_model(model, {'bytes': model.stat().st_size, 'lfsSha256': '0' * 64})

    def test_wrong_size_is_rejected_even_if_digest_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / 'public-fixture'
            model.write_bytes(b'public fixture')
            digest = hashlib.sha256(model.read_bytes()).hexdigest()
            with self.assertRaises(ValueError):
                verify_model(model, {'bytes': model.stat().st_size + 1, 'lfsSha256': digest})


if __name__ == '__main__':
    unittest.main()
