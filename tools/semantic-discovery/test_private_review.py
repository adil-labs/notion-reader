import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location('private_review', Path(__file__).with_name('render-private-review.py'))
review_renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review_renderer)


def pair(kind, left, right):
    def evidence(page):
        return {'pageId': page, 'title': 'Public forest example', 'section': 'Ecology', 'excerpt': 'Trees shelter wildlife.'}
    return {'caseId': kind + left, 'kind': kind, 'content': {'left': evidence(left), 'right': evidence(right)}}


class PrivateReviewExposureTest(unittest.TestCase):
    def test_review_discloses_derived_origin_and_incomplete_coverage(self):
        rendered = review_renderer.render({'cases': []}, snapshot_context={
            'origin': 'Host replay, not a new device capture', 'coverage': 'Candidate capacity reached; incomplete'})
        self.assertIn('Host replay, not a new device capture', rendered)
        self.assertIn('Candidate capacity reached; incomplete', rendered)

    def test_reference_types_are_visible_without_reclassifying_legacy_cases(self):
        named = pair('connection', 'a', 'b')
        named['content']['evidenceKind'] = 'NamedReference'
        self.assertIn('Named reference', review_renderer.render({'cases': [named]}))
        self.assertIn('Evidence type not classified', review_renderer.render({'cases': [pair('connection', 'c', 'd')]}))
        named['content']['evidenceKind'] = 'SimilarPassages'
        self.assertIn('Similar passages', review_renderer.render({'cases': [named]}))

    def test_a_previously_unshown_pair_is_still_exposed_when_it_becomes_a_connection(self):
        prior = {'cases': [pair('potentialMissedConnection', 'AA-BB', 'CC-DD')]}
        current = {'cases': [pair('connection', ' ccdd ', 'aabb')]}
        rendered = review_renderer.render(current, [prior])
        self.assertIn('Previously reviewed Note pair or topic membership', rendered)
        self.assertNotIn('frozen held-out cases', rendered)

    def test_a_new_identity_does_not_automatically_establish_an_independent_sample(self):
        rendered = review_renderer.render({'cases': [pair('connection', 'a', 'b')]}, [])
        self.assertIn('not proof of an independent sample', rendered)
        self.assertIn('No human precision score has been assigned', rendered)

    def test_topic_identity_uses_membership_rather_than_a_new_label_or_opaque_id(self):
        members = [{'pageId': 'a'}, {'pageId': 'B-B'}]
        previous = {'kind': 'topic', 'caseId': 'old', 'content': {'members': members, 'availableCount': 2}}
        current = {'kind': 'topic', 'caseId': 'new', 'content': {'members': [{'pageId': 'bb'}, {'pageId': ' A '}], 'availableCount': 2}}
        self.assertEqual(review_renderer.exposure_identity(previous), review_renderer.exposure_identity(current))

    def test_large_topic_exposure_uses_all_members_not_only_six_representatives(self):
        pages = [str(index) for index in range(8)]
        def topic(representatives):
            return {'kind': 'topic', 'caseId': 'large', 'content': {'availableCount': 8,
                    'memberPageIds': pages, 'members': [{'pageId': page} for page in representatives]}}
        self.assertEqual(review_renderer.exposure_identity(topic(pages[:6])),
                         review_renderer.exposure_identity(topic(pages[2:])))

    def test_legacy_truncated_topic_membership_is_unknown(self):
        legacy = {'kind': 'topic', 'caseId': 'large', 'content': {'availableCount': 8,
                  'members': [{'pageId': str(index)} for index in range(6)]}}
        self.assertIsNone(review_renderer.exposure_identity(legacy))


if __name__ == '__main__':
    unittest.main()
