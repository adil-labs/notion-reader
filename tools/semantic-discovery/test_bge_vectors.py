import unittest
import numpy as np
from bge_vectors import cls_pool


class BgeVectorsTest(unittest.TestCase):
    def testPublisherClsPoolingDoesNotAverageBodyTokens(self):
        result = cls_pool(np.array([[[3., 4.], [100., 0.]]]))
        np.testing.assert_allclose(result, [.6, .8], atol=1e-6)

    def testInvalidOutputsAreRejected(self):
        for value in [np.zeros((1, 2, 2)), np.array([[[float('nan'), 1.]]])]:
            with self.assertRaises(ValueError):
                cls_pool(value)


if __name__ == '__main__':
    unittest.main()
