import unittest
import onnx
from prepare_gemma_qnn_model import text_only_graph, cpu_normalization_graph
import numpy as np
import onnxruntime as ort


class TextOnlyGraphTest(unittest.TestCase):
    def test_text_merge_is_bypassed_and_unreachable_modalities_are_removed(self):
        h = onnx.helper
        graph = h.make_graph([
            h.make_node('Identity', ['ids'], ['text'], name='/model/embed_tokens/Gather_Quant'),
            h.make_node('Add', ['text', 'modality'], ['merged'], name='/model/multimodal_merge/Reshape_embeds'),
            h.make_node('Identity', ['merged'], ['output'])
        ], 'fixture', [h.make_tensor_value_info(n, onnx.TensorProto.FLOAT, [1]) for n in ['ids', 'modality']],
            [h.make_tensor_value_info('output', onnx.TensorProto.FLOAT, [1])])
        result = text_only_graph(h.make_model(graph))
        self.assertEqual(['ids'], [i.name for i in result.graph.input])
        self.assertEqual(2, len(result.graph.node))
        self.assertEqual(['text'], list(result.graph.node[-1].input))

    def test_an_unknown_export_is_rejected_instead_of_guessed(self):
        model = onnx.helper.make_model(onnx.helper.make_graph([], 'unknown', [], []))
        with self.assertRaises(ValueError):
            text_only_graph(model)

    def test_large_projection_values_keep_valid_normalization_without_fp16_overflow(self):
        h = onnx.helper
        f = onnx.TensorProto.FLOAT
        graph = h.make_graph([
            h.make_node('Identity', ['last_hidden_state'], ['mean'], name='/model/pooling/Div_mean'),
            h.make_node('ReduceL2', ['mean', 'axes'], ['norm'], name='/model/pooling/ReduceL2', keepdims=1),
            h.make_node('Max', ['norm', 'epsilon'], ['clipped'], name='/model/pooling/norm/Max'),
            h.make_node('Div', ['mean', 'clipped'], ['sentence_embedding'], name='/model/pooling/Div_norm')
        ], 'large-values', [h.make_tensor_value_info('last_hidden_state', f, [1, 2])],
            [h.make_tensor_value_info('sentence_embedding', f, [1, 2])], initializer=[
                h.make_tensor('axes', onnx.TensorProto.INT64, [1], [-1]),
                h.make_tensor('epsilon', f, [], [1e-12])])
        model = h.make_model(graph, opset_imports=[h.make_opsetid('', 18)], ir_version=9)
        fixed = cpu_normalization_graph(model)
        self.assertEqual(f, fixed.graph.output[0].type.tensor_type.elem_type)
        self.assertTrue(any(n.domain == 'ai.onnx.ml' and n.op_type == 'Normalizer' for n in fixed.graph.node))
        session = ort.InferenceSession(fixed.SerializeToString(), providers=['CPUExecutionProvider'])
        actual = session.run(None, {'last_hidden_state': np.array([[400., 300.]], dtype=np.float32)})[0]
        np.testing.assert_allclose(actual, [[.8, .6]], atol=1e-7)


if __name__ == '__main__':
    unittest.main()
