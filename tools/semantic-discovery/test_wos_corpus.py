import unittest
import tempfile
from pathlib import Path
import hashlib
from wos_corpus import adapt, read_pinned_rows


class WosCorpusTest(unittest.TestCase):
    def testModifiedValidSourceCannotClaimThePinnedRevision(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'source.json'
            original = b'{"token":"Original text","label":[1,6]}\n'
            path.write_bytes(original)
            expected = hashlib.sha256(original).hexdigest()
            self.assertEqual('Original text', read_pinned_rows(path, expected)[0]['token'])
            path.write_bytes(b'{"token":"Changed text","label":[1,6]}\n')
            with self.assertRaises(ValueError):
                read_pinned_rows(path, expected)
    def testLabelsNeverEnterDiscoveryInputs(self):
        rows = [{'token': 'A source title. Substantive source prose.', 'label': [1, 20]}]
        notes, gold = adapt(rows, 'test', 1)
        self.assertNotIn('label', notes[0])
        self.assertEqual([1, 20], gold[notes[0]['id']]['labels'])
        self.assertEqual('A source title. Substantive source prose.', notes[0]['sections'][0]['paragraphs'][0]['text'])

    def testSelectionIsDeterministicAndRejectsInsufficientUniqueText(self):
        distinct = [{'token': 'First source. Body.', 'label': [1, 6]},
            {'token': 'Second source. Body.', 'label': [2, 7]}]
        self.assertEqual(adapt(distinct, 'test', 1), adapt(list(reversed(distinct)), 'test', 1))
        rows = [{'token': 'Same source. Body.', 'label': [1]}] * 2
        with self.assertRaises(ValueError):
            adapt(rows, 'test', 2)


if __name__ == '__main__':
    unittest.main()
