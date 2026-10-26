import json
import unittest
from validate_label_baseline import validate

class LabelOutputTest(unittest.TestCase):
    def setUp(self):
        self.inputs={'cases':[{'id':'g','passages':[{'id':'p','eligible':True}]}]}
        self.output={'topics':[{'label':'A topic','passage_ids':['p']}]}
    def report(self):
        return {'status':'completed','runs':[{'case_id':'g','rawOutput':json.dumps(self.output)}]}
    def testAcceptsExactlyTheOriginalMembership(self):
        self.assertEqual(validate(self.inputs,self.report())['g']['passage_ids'],['p'])
    def testRejectsChangedMembershipEvenWhenTheLabelIsPlausible(self):
        self.output['topics'][0]['passage_ids']=['different']
        with self.assertRaisesRegex(ValueError,'membership'): validate(self.inputs,self.report())
    def testRejectsMultipleTopicsInsteadOfSilentlyTakingTheFirst(self):
        self.output['topics']*=2
        with self.assertRaisesRegex(ValueError,'single topic'): validate(self.inputs,self.report())
    def testRejectsMissingGroup(self):
        report=self.report();report['runs']=[]
        with self.assertRaisesRegex(ValueError,'coverage'): validate(self.inputs,report)
    def testRejectsDuplicateGroup(self):
        report=self.report();report['runs']*=2
        with self.assertRaisesRegex(ValueError,'duplicate'): validate(self.inputs,report)
