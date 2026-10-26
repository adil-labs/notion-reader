import unittest
from prepare_label_baseline import prepare

class LabelBaselineTest(unittest.TestCase):
    def setUp(self):
        self.corpus={'cases':[{'id':'c','passages':[{'id':'p','body':'A current body','eligible':True},{'id':'revoked','body':'Private','eligible':False}]}]}
        self.baseline={'outputs':[{'case_id':'c','topics':[{'id':'t','label':'Old heading','passage_ids':['p']}]}]}
    def testPreservesEveryOriginalMembershipAndGroup(self):
        result=prepare(self.corpus,self.baseline)
        self.assertEqual(result['cases'][0]['passages'],[{'id':'p','body':'A current body','eligible':True}])
        self.assertEqual(result['cases'][0]['baseline_topic_id'],'t')
        self.assertEqual(result['cases'][0]['baseline_case_id'],'c')
    def testRejectsWithdrawnSourceInsteadOfSilentlyRelabelingTheRemainingSubset(self):
        self.baseline['outputs'][0]['topics'][0]['passage_ids'].append('revoked')
        with self.assertRaisesRegex(ValueError,'ineligible'): prepare(self.corpus,self.baseline)
    def testRejectsUnknownSource(self):
        self.baseline['outputs'][0]['topics'][0]['passage_ids']=['missing']
        with self.assertRaisesRegex(ValueError,'unknown'): prepare(self.corpus,self.baseline)
    def testRejectsRepeatedMembership(self):
        self.baseline['outputs'][0]['topics'][0]['passage_ids']=['p','p']
        with self.assertRaisesRegex(ValueError,'duplicate'): prepare(self.corpus,self.baseline)
