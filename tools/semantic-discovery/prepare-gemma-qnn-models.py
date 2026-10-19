"""Fix only public benchmark shapes, prove CPU parity, stage isolated QNN probes."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
import numpy as np
import onnx
import onnxruntime as ort
from gemma2_vectors import model_files
from prepare_gemma_qnn_model import text_only_graph, cpu_normalization_graph


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--model-directory', required=True, type=Path)
    parser.add_argument('--output-directory', required=True, type=Path)
    args = parser.parse_args()
    reference = json.loads((Path(__file__).parent / 'gemma2-android-reference.json').read_text())['embeddingReferences'][0]
    ids = np.array([reference['tokenIds']], dtype=np.int64)
    shapes = {'batch_size': 1, 'sequence_length': ids.shape[1], 'num_image_tokens': 0,
              'num_audio_tokens': 0, 'num_video_tokens': 0}
    report = []
    def adb(*parts):
        return subprocess.check_output(['adb', '-s', args.device, *parts], text=True)
    for precision in ['int8', 'q4']:
        pinned = model_files(precision)
        for relative, expected in pinned.items():
            file = args.model_directory / relative
            if hashlib.sha256(file.read_bytes()).hexdigest() != expected:
                raise ValueError(f'Unverified source {relative}')
        name = 'model_quantized.onnx' if precision == 'int8' else 'model_q4.onnx'
        source = args.model_directory / 'onnx' / name
        model = cpu_normalization_graph(text_only_graph(onnx.load(str(source), load_external_data=False)))
        for value in list(model.graph.input) + list(model.graph.output) + list(model.graph.value_info):
            for dim in value.type.tensor_type.shape.dim:
                if dim.dim_param in shapes:
                    fixed = shapes[dim.dim_param]
                    dim.ClearField('dim_param'); dim.dim_value = fixed
        directory = args.output_directory / precision
        directory.mkdir(parents=True, exist_ok=True)
        graph = directory / 'model-fixed.onnx'
        onnx.save_model(model, str(graph))
        weights = directory / (name + '_data')
        if not weights.exists():
            weights.hardlink_to(source.with_name(name + '_data'))
        options = ort.SessionOptions(); options.intra_op_num_threads = 2
        vectors = []
        for path in [source, graph]:
            session = ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])
            feed = {'input_ids': ids, 'attention_mask': np.ones_like(ids)}
            feed.update({name: np.empty((0, 512), dtype=np.float32) for name in ['image_features', 'audio_features', 'video_features']
                         if name in {i.name for i in session.get_inputs()}})
            vector = session.run(['sentence_embedding'], feed)[0][0]
            vectors.append(vector / np.linalg.norm(vector))
            del session
        cosine = float(np.dot(*vectors))
        if not np.isfinite(cosine) or cosine < .999999:
            raise ValueError(f'Fixed shape changed {precision} semantics: {cosine}')
        destination = f'cache/gemma-benchmark-{precision}-fixed'
        adb('shell', 'run-as', 'com.example.notionreader', 'mkdir', '-p', destination)
        for file in [graph, weights]:
            temporary = f'/data/local/tmp/gemma-qnn-{file.name}'
            adb('push', str(file), temporary)
            adb('shell', 'run-as', 'com.example.notionreader', 'cp', temporary, f'{destination}/{file.name}')
            expected = hashlib.sha256(file.read_bytes()).hexdigest()
            actual = adb('shell', 'run-as', 'com.example.notionreader', 'sha256sum', f'{destination}/{file.name}').split()[0]
            if actual != expected:
                raise ValueError('Fixed probe device checksum mismatch')
            adb('shell', 'rm', temporary)
        report.append({'precision': precision, 'tokens': ids.shape[1], 'textOnly': True, 'cpuFp32Normalization': True, 'cpuOriginalVsFixedCosine': cosine,
                       'graphSha256': hashlib.sha256(graph.read_bytes()).hexdigest(), 'sourceGraphSha256': pinned['onnx/' + name]})
    (args.output_directory / 'fixed-shape-parity.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
