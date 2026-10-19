import unittest
from gemma_device_benchmark import profile_summary, attach_profile_evidence
from pathlib import Path


class ProfileEvidenceTest(unittest.TestCase):
    def test_cpu_fallback_is_not_acceleration(self):
        result = profile_summary([{'cat': 'Node', 'dur': 90, 'args': {
            'provider': 'CPUExecutionProvider', 'op_name': 'MatMulNBits'}}], 'XnnpackExecutionProvider')
        self.assertFalse(result['acceleratorObserved'])
        self.assertEqual(1, result['providers']['CPUExecutionProvider']['events'])

    def test_accelerator_presence_does_not_hide_cpu_matrix_work(self):
        result = profile_summary([
            {'cat': 'Node', 'dur': 10, 'args': {'provider': 'XnnpackExecutionProvider', 'op_name': 'Softmax'}},
            {'cat': 'Node', 'dur': 90, 'args': {'provider': 'CPUExecutionProvider', 'op_name': 'MatMulNBits'}}
        ], 'XnnpackExecutionProvider')
        self.assertTrue(result['acceleratorObserved'])
        self.assertEqual(1, result['cpuQuantizedMatrixEvents'])
        self.assertAlmostEqual(.1, result['requestedProviderDurationFraction'])

    def test_empty_or_unattributed_profile_is_not_valid_evidence(self):
        for events in [[], [{'cat': 'Session', 'dur': 100}], [{'cat': 'Node', 'args': {}}]]:
            with self.assertRaises(ValueError):
                profile_summary(events, 'NnapiExecutionProvider')

    def test_failed_execution_cannot_claim_acceleration_from_partial_profile(self):
        report = {'attempts': [{'failure': 'zero embedding', 'profileFile': 'missing.json'}]}
        actual = attach_profile_evidence(report, Path('.'))
        self.assertFalse(actual['attempts'][0]['executionComplete'])
        self.assertFalse(actual['attempts'][0]['profileEvidence']['acceleratorObserved'])

    def test_zero_or_nan_embedding_is_rejected_even_when_native_call_returned(self):
        for norm, cosine in [(0, float('nan')), (1, float('nan')), (0, .99)]:
            with self.assertRaises(ValueError):
                attach_profile_evidence({'attempts': [{'rawL2Norm': norm, 'cosineWithHostInt8': cosine}]}, Path('.'))

    def test_timed_samples_need_their_own_numerical_agreement(self):
        for agreements in [None, [.9999, .9999], [.9999, .4, .9999]]:
            row = {'rawL2Norm': 1, 'cosineWithHostInt8': .9999, 'warmMilliseconds': [1, 2, 3]}
            if agreements is not None:
                row['warmCosinesWithSamePrecisionCpu'] = agreements
            with self.assertRaises(ValueError):
                attach_profile_evidence({'attempts': [row]}, Path('.'))

    def test_vulkan_compatibility_success_requires_three_valid_samples_and_profile(self):
        for times, cosines, profile in [([], [], None), ([1, 2, 3], [1, .4, 1], None),
                                       ([1, 2, 3], [1, 1, 1], None),
                                       ([1, float('nan'), 3], [1, 1, 1], 'missing.json')]:
            row = {'case': 'smoke', 'milliseconds': times, 'cosinesWithSamePrecisionCpu': cosines}
            if profile:
                row['profileFile'] = profile
            with self.assertRaises(ValueError):
                attach_profile_evidence({'configurations': [row]}, Path('.'))


if __name__ == '__main__':
    unittest.main()
