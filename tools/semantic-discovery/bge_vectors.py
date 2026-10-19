"""Pinned host-only BGE challenger on identical shipping passage text/windows."""
import argparse
import json
from pathlib import Path
import numpy as np
from public_vectors import file_hash, verify_artifact, write_artifact

REVISION = '5c38ec7c405ec4b44b94cc5a9bb96e735b38267a'
MODEL_SHA = '828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35'
TOKENIZER_SHA = 'd241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66'


def cls_pool(hidden):
    value = np.asarray(hidden)[0, 0].astype(np.float64)
    norm = np.linalg.norm(value)
    if not np.isfinite(value).all() or not np.isfinite(norm) or norm <= 0:
        raise ValueError('Invalid CLS vector')
    return (value / norm).astype(np.float32)


def main():
    import onnxruntime as ort
    from tokenizers import Tokenizer
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-dir', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.baseline_dir / 'vector-manifest.json').read_text(encoding='utf-8'))
    _, rows = verify_artifact(args.baseline_dir, manifest)
    model = args.model_dir / 'onnx/model.onnx'
    if not model.exists():
        model = args.model_dir / 'model.onnx'
    tokenizer_path = args.model_dir / 'tokenizer.json'
    if file_hash(model) != MODEL_SHA or file_hash(tokenizer_path) != TOKENIZER_SHA:
        raise ValueError('Pinned challenger model/tokenizer mismatch')
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    tokenizer.no_padding(); tokenizer.no_truncation()
    for row in rows:
        ids = tokenizer.encode(row['input']).ids
        if ids != row['tokenIds']:
            raise ValueError('Challenger tokenizer changes shipping windows: explicit re-chunking ablation required')
    options = ort.SessionOptions(); options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(model), sess_options=options, providers=['CPUExecutionProvider'])

    def embed(row):
        ids = np.asarray([row['tokenIds']], dtype=np.int64)
        feeds = {'input_ids': ids, 'attention_mask': np.ones_like(ids), 'token_type_ids': np.zeros_like(ids)}
        hidden = session.run(None, {i.name: feeds[i.name] for i in session.get_inputs()})[0]
        if hidden.shape != (1, ids.shape[1], 384):
            raise ValueError('Unexpected challenger output')
        return cls_pool(hidden)

    identity = {**manifest['identity'], 'modelSha256': MODEL_SHA, 'modelRevision': REVISION,
        'modelId': 'BAAI/bge-small-en-v1.5', 'tokenizerSha256': TOKENIZER_SHA,
        'runtime': 'onnxruntime:' + ort.__version__, 'pooling': 'publisher-cls-l2-float32',
        'tokenizer': 'publisher-verified-identical-shipping-ids', 'instruction': 'none-symmetric-clustering',
        'scope': 'Host FP32 challenger vs shipping INT8 baseline; not quantization-controlled or Android-qualified'}
    result = write_artifact(rows, identity, args.output_dir, embed)
    print(f"Verified {result['passages']} BGE vectors on identical text and token windows; host only")


if __name__ == '__main__':
    main()
