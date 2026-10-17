import unittest
from private_baseline import summarize


def pair(case_id='one', kind='connection'):
    return {'caseId': case_id, 'kind': kind, 'content': {
        'left': {'pageId': 'AA-BB'}, 'right': {'pageId': 'CC-DD'}, 'cosine': 0.7}}


class PrivateBaselineTest(unittest.TestCase):
    def test_unrated_cases_do_not_become_failed_or_successful_predictions(self):
        result = summarize({'cases': [pair()]})
        self.assertEqual('unrated', result['ratingStatus'])
        self.assertIsNone(result['cases'][0]['rating'])
        self.assertEqual(0, result['tasks']['connection']['rated'])
        self.assertIsNone(result['precisionEstimate'])
        self.assertFalse(result['qualityGateEstablished'])

    def test_ratings_preserve_independent_dimensions_and_conditional_decisions(self):
        ratings = {'cases': [{'caseId': 'one', 'usefulness': 'Medium', 'support': 'Partial',
                             'label': None, 'coherence': None, 'decision': 'conditional'}]}
        result = summarize({'cases': [pair()]}, ratings)
        self.assertEqual('Partial', result['cases'][0]['rating']['passageSupport'])
        self.assertEqual({'conditional': 1}, result['tasks']['connection']['decisions'])
        self.assertEqual('unclassifiedLegacy', result['cases'][0]['referenceType'])
        self.assertIsNone(result['precisionEstimate'])

    def test_display_changes_do_not_make_a_previously_reviewed_pair_fresh(self):
        result = summarize({'cases': [pair()]}, previous=[{'cases': [pair('older', 'potentialMissedConnection')]}])
        self.assertEqual('previouslyReviewed', result['cases'][0]['exposure'])

    def test_duplicate_case_ids_are_rejected(self):
        with self.assertRaises(ValueError):
            summarize({'cases': [pair(), pair()]})

    def test_partial_or_unmatched_judgments_are_rejected(self):
        for ratings in [[], [{'caseId': 'other'}], [{'caseId': 'one'}, {'caseId': 'one'}]]:
            with self.assertRaises(ValueError):
                summarize({'cases': [pair()]}, {'cases': ratings})


if __name__ == '__main__':
    unittest.main()
