import unittest
import numpy as np
from gemma2_vectors import normalized_embedding, clustering_input, model_files


class Gemma2VectorsTest(unittest.TestCase):
    def testQuantizedRunsHaveDistinctPinnedWeightsAndTheSameTokenizer(self):
        fp32, int8, q4 = [model_files(name) for name in ['fp32', 'int8', 'q4']]
        self.assertIn('onnx/model_quantized.onnx_data', int8)
        self.assertIn('onnx/model_q4.onnx_data', q4)
        self.assertEqual(fp32['tokenizer.json'], q4['tokenizer.json'])
        self.assertEqual(fp32['tokenizer.json'], int8['tokenizer.json'])
        self.assertNotIn('onnx/model.onnx_data', q4)
        with self.assertRaises(ValueError):
            model_files('fp16')
    def testClusteringInstructionPreservesTheEntireShippingPassage(self):
        self.assertEqual('task: clustering | query: Title\nAll source prose.', clustering_input('Title\nAll source prose.'))

    def testOnlyPublisherSupportedDimensionsCanBeTruncatedAndRenormalized(self):
        vector = np.arange(1, 769, dtype=np.float32).reshape(1, 768)
        result = normalized_embedding(vector, 256)
        self.assertEqual((256,), result.shape)
        self.assertAlmostEqual(1., float(np.linalg.norm(result)), places=6)
        with self.assertRaises(ValueError):
            normalized_embedding(vector, 384)
        with self.assertRaises(ValueError):
            normalized_embedding(np.zeros((1, 768)), 768)


if __name__ == '__main__':
    unittest.main()
