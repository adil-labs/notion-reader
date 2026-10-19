import unittest
import copy
from public_corpus import digest
from wos_score import pair_score, labels_at_level, score, verify_gold


class WosScoreTest(unittest.TestCase):
    def testGoldLabelsAndTheirSourceBindingAreFrozen(self):
        notes = [{'id': 'a', 'partition': 'calibration', 'source': {'contentSha256': 'text'}}]
        gold = {'notes': {'a': {'labels': [1, 6], 'sourceSplit': 'dev', 'contentSha256': 'text'}}}
        corpus = {'notes': notes, 'manifest': {'corpusSha256': digest(notes)}}
        lock = {'corpusSha256': digest(notes), 'goldSha256': digest(gold)}
        verify_gold(corpus, gold, lock)
        for field, changed in [('labels', [2, 7]), ('sourceSplit', 'test'), ('contentSha256', 'other')]:
            altered = copy.deepcopy(gold); altered['notes']['a'][field] = changed
            with self.assertRaises(ValueError):
                verify_gold(corpus, altered, lock)
        altered = copy.deepcopy(gold); altered['notes']['a']['sourceSplit'] = 'test'
        with self.assertRaises(ValueError):
            verify_gold(corpus, altered, {**lock, 'goldSha256': digest(altered)})
    def testHierarchyDepthComesFromPublisherIdsNotListPosition(self):
        self.assertEqual({1, 4}, labels_at_level([16, 1, 4, 15], 'broad'))
        self.assertEqual({15, 16}, labels_at_level([16, 1, 4, 15], 'fine'))
    def testPairsAreUniqueAndMissingSubjectsReduceRecall(self):
        labels = {'a': {1}, 'b': {1}, 'c': {2}, 'd': {2}}
        result = pair_score([['a', 'b'], ['a', 'b'], ['a', 'c']], labels)
        self.assertEqual(2, result['predictedPairs'])
        self.assertEqual(2, result['goldPairs'])
        self.assertEqual(.5, result['precision'])
        self.assertEqual(.5, result['recall'])

    def testNoPredictionsDoesNotMasqueradeAsPerfectPrecision(self):
        result = pair_score([], {'a': {1}, 'b': {1}})
        self.assertIsNone(result['precision'])
        self.assertEqual(0, result['recall'])

    def testForeignPipelineMembersCannotSilentlyDisappearFromScoring(self):
        report = {'subjectRelationships': [], 'cases': [{'published': True,
            'topic': {'label': 'Source subject', 'members': [{'pageId': 'foreign'}]}}]}
        with self.assertRaises(ValueError):
            score(report, {'a': {'labels': [1, 6], 'sourceSplit': 'dev'}})


if __name__ == '__main__':
    unittest.main()
