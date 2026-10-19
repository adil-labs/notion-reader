"""Pinned-export text-only research transform, valid only for non-modal token IDs."""
import onnx
import numpy as np


def text_only_graph(source):
    model = onnx.ModelProto()
    model.CopyFrom(source)
    embeddings = [n for n in model.graph.node if n.name == '/model/embed_tokens/Gather_Quant']
    merges = [n for n in model.graph.node if n.name == '/model/multimodal_merge/Reshape_embeds']
    if len(embeddings) != 1 or len(merges) != 1 or len(embeddings[0].output) != 1 or len(merges[0].output) != 1:
        raise ValueError('Unknown Gemma export: text-only bypass is not defined')
    before, after = merges[0].output[0], embeddings[0].output[0]
    for node in model.graph.node:
        for i, name in enumerate(node.input):
            if name == before:
                node.input[i] = after
    required = {i.name for i in model.graph.output}
    retained = []
    for node in reversed(model.graph.node):
        if required.intersection(node.output):
            retained.append(node)
            required.update(node.input)
    del model.graph.node[:]
    model.graph.node.extend(reversed(retained))
    for field in ['input', 'initializer', 'value_info']:
        values = getattr(model.graph, field)
        keep = [v for v in values if v.name in required]
        del values[:]
        values.extend(keep)
    if any(i.name in {'image_features', 'audio_features', 'video_features'} for i in model.graph.input):
        raise ValueError('Text-only graph still requires modality inputs')
    return model


def cpu_normalization_graph(source):
    """Use CPU-only ONNX-ML L2 normalization for valid non-tiny embedding means.

    This avoids QNN's FP16 ReduceL2 overflow without FP64 Cast partitions that
    this plugin cannot compose. The existing Android provider also normalizes
    the exported vector. Research only; source weights are unchanged.
    """
    model = onnx.ModelProto()
    model.CopyFrom(source)
    means = [n for n in model.graph.node if n.name == '/model/pooling/Div_mean']
    endings = [n for n in model.graph.node if n.name == '/model/pooling/Div_norm']
    if len(means) != 1 or len(endings) != 1:
        raise ValueError('Unknown publisher normalization graph')
    removed = {'/model/pooling/ReduceL2', '/model/pooling/norm/Max', '/model/pooling/Div_norm'}
    nodes = [n for n in model.graph.node if n.name not in removed]
    nodes.append(onnx.helper.make_node('Normalizer', [means[0].output[0]], ['sentence_embedding'],
                                      name='qnn_research_cpu_normalization', domain='ai.onnx.ml', norm='L2'))
    del model.graph.node[:]
    model.graph.node.extend(nodes)
    if not any(o.domain == 'ai.onnx.ml' for o in model.opset_import):
        model.opset_import.append(onnx.helper.make_opsetid('ai.onnx.ml', 1))
    return model
