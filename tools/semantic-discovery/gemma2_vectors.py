"""Host-only text EmbeddingGemma 2 ONNX comparison; never loads modality encoders."""
import argparse
import json
from pathlib import Path
import numpy as np
from public_vectors import file_hash, verify_artifact, write_artifact

PINNED_FILES = {
    'onnx/model.onnx': 'bc47de15f81208a5c99e5ab10f746d5e33b51ea228b7dc0bef9c133a94f1c1c3',
    'onnx/model.onnx_data': '9fd452bfc6916e5a92f080f8dada35598ca4512b21c65897d4c5974d5f9630a2',
    'tokenizer.json': '4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4',
    'tokenizer_config.json': '17bd5d6e9364ca49a534e1502076593317c298d4a663623091ed45388f004874',
    'config.json': '8d011bfe08b5e345bbe0b81e5c6fd02c381920b345b986047bc2a33ce7b90d1d',
}
QUANTIZED_FILES = {
    'int8': {
        'onnx/model_quantized.onnx': 'd06edd601f851c633a2519304cbeb8dc6170d7ceb61b436625c17fb9b6e74953',
        'onnx/model_quantized.onnx_data': '278a7ff1248c3618e4bd11a607fc54f7bdc7778854230f3956d3f86bd9db4f3b',
    },
    'q4': {
        'onnx/model_q4.onnx': 'f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9',
        'onnx/model_q4.onnx_data': 'c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49',
    },
}


def model_files(precision):
    if precision == 'fp32':
        return dict(PINNED_FILES)
    if precision not in QUANTIZED_FILES:
        raise ValueError('Unsupported Gemma precision')
    return {**{name: sha for name, sha in PINNED_FILES.items() if not name.startswith('onnx/')},
        **QUANTIZED_FILES[precision]}

def clustering_input(text):
    return 'task: clustering | query: ' + text


def normalized_embedding(output, dimensions):
    value = np.asarray(output)
    if dimensions not in {128, 256, 512, 768} or value.shape != (1, 768):
        raise ValueError('Expected publisher sentence embedding and supported MRL dimension')
    value = value[0, :dimensions].astype(np.float64)
    norm = np.linalg.norm(value)
    if not np.isfinite(value).all() or not np.isfinite(norm) or norm <= 0:
        raise ValueError('Invalid Gemma embedding')
    return (value / norm).astype(np.float32)


def main():
    import onnxruntime as ort
    from tokenizers import Tokenizer
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-dir', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--dimensions', type=int, default=768, choices=[128, 256, 512, 768])
    parser.add_argument('--precision', choices=['fp32', 'int8', 'q4'], default='fp32')
    args = parser.parse_args()
    def asset(name):
        nested = args.model_dir / name
        return nested if nested.exists() else args.model_dir / Path(name).name
    lock = {'repo': 'onnx-community/embeddinggemma-2-ONNX',
        'revision': 'daa72c51243991dfcaf9f9137d2c573d8f7790c0', 'files': {}}
    selected = model_files(args.precision)
    graph_name = next(name for name in selected if name.endswith('.onnx'))
    weights_name = graph_name + '_data'
    for name, expected in selected.items():
        path = asset(name)
        if file_hash(path) != expected:
            raise ValueError('Gemma model/tokenizer bytes differ from pinned download')
        lock['files'][name] = {'bytes': path.stat().st_size, 'sha256': expected}
    manifest = json.loads((args.baseline_dir / 'vector-manifest.json').read_text(encoding='utf-8'))
    _, rows = verify_artifact(args.baseline_dir, manifest)
    tokenizer = Tokenizer.from_file(str(asset('tokenizer.json')))
    tokenizer.no_padding(); tokenizer.no_truncation()
    rows = [{**row, 'tokenIds': tokenizer.encode(clustering_input(row['input'])).ids} for row in rows]
    if any(not 1 <= len(row['tokenIds']) <= 8192 for row in rows):
        raise ValueError('Gemma context overflow: never silently truncate source text')
    options = ort.SessionOptions(); options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(asset(graph_name)), sess_options=options, providers=['CPUExecutionProvider'])
    outputs = [o.name for o in session.get_outputs()]
    if 'sentence_embedding' not in outputs:
        raise ValueError('Export must include publisher projection/pooling; hidden states are not sentence embeddings')

    def embed(row):
        ids = np.asarray([row['tokenIds']], dtype=np.int64)
        feeds = {'input_ids': ids, 'attention_mask': np.ones_like(ids),
            'position_ids': np.arange(ids.shape[1], dtype=np.int64)[None, :]}
        # The unified export accepts precomputed modality features; text-only has none.
        feeds.update({name: np.zeros((0, 512), dtype=np.float32)
            for name in ['image_features', 'video_features', 'audio_features']})
        names = [i.name for i in session.get_inputs()]
        if set(names) - feeds.keys():
            raise ValueError('Unsupported text ONNX input contract: ' + str(names))
        output = session.run(['sentence_embedding'], {name: feeds[name] for name in names})[0]
        return normalized_embedding(output, args.dimensions)

    identity = {**manifest['identity'], 'modelId': 'google/embeddinggemma-2',
        'modelRevision': lock['revision'],
        'publisherCardRevision': '914f7f89142e33e77833254d9c9b90c3cef7303b',
        'exportRepo': lock['repo'], 'exportRevision': lock['revision'],
        'modelSha256': lock['files'][graph_name]['sha256'],
        'externalWeightsSha256': lock['files'][weights_name]['sha256'],
        'graphFile': graph_name, 'modelFiles': lock['files'],
        'tokenizerSha256': lock['files']['tokenizer.json']['sha256'],
        'tokenizer': 'publisher-Gemma2-same-source-passages-new-tokenization',
        'runtime': 'onnxruntime:' + ort.__version__, 'dimensions': args.dimensions,
        'instruction': 'task: clustering | query: ', 'precision': args.precision.upper(),
        'pooling': 'exported-sentence_embedding-projection-MRL-l2',
        'scope': 'Community text-only ONNX export; source text fixed; publisher PyTorch parity and Android unmeasured'}
    result = write_artifact(rows, identity, args.output_dir, embed)
    print(f"Verified {result['passages']} Gemma2 text embeddings; host export only")


if __name__ == '__main__':
    main()
