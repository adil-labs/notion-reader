"""Generate public INT4 device parity vectors without changing historical INT8 evidence."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

from gemma2_vectors import model_files, normalized_embedding


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path(__file__).with_name('gemma2-q4-android-reference.json'))
    args = parser.parse_args()
    reference = json.loads(Path(__file__).with_name('gemma2-android-reference.json').read_text(encoding='utf-8'))
    for name, expected in model_files('q4').items():
        path = args.model_dir / name
        if not path.exists():
            path = args.model_dir / Path(name).name
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Pinned Q4 pack mismatch: ' + name)
    graph = args.model_dir / 'onnx/model_q4.onnx'
    if not graph.exists():
        graph = args.model_dir / 'model_q4.onnx'
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(graph), sess_options=options, providers=['CPUExecutionProvider'])
    reference['graphSha256'] = model_files('q4')['onnx/model_q4.onnx']
    reference['weightsSha256'] = model_files('q4')['onnx/model_q4.onnx_data']
    reference['precision'] = 'INT4'
    reference['runtime'] = 'onnxruntime:' + ort.__version__
    for case in reference['embeddingReferences']:
        ids = np.asarray([case['tokenIds']], dtype=np.int64)
        feeds = {'input_ids': ids, 'attention_mask': np.ones_like(ids),
                 'position_ids': np.arange(ids.shape[1], dtype=np.int64)[None, :]}
        feeds.update({name: np.zeros((0, 512), dtype=np.float32)
                      for name in ['image_features', 'video_features', 'audio_features']})
        value = session.run(['sentence_embedding'], {i.name: feeds[i.name] for i in session.get_inputs()})[0]
        case['vector'] = normalized_embedding(value, 768).tolist()
    args.output.write_text(json.dumps(reference, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Generated INT4 public parity references: ' + str(args.output))


if __name__ == '__main__':
    main()
