"""Public-text CPU smoke probe. Never reads the reader or private review data."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import statistics
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


def verify_model(model, export):
    digest = hashlib.sha256(model.read_bytes()).hexdigest()
    if model.stat().st_size != export['bytes'] or digest != export['lfsSha256']:
        raise ValueError('Pinned model size/checksum mismatch')
    return digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--android-assets', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    assets = args.assets.resolve()
    if not assets.is_relative_to(repo / 'app' / 'build' / 'discovery-feasibility'):
        raise SystemExit('Probe assets must remain in isolated ignored feasibility storage')
    if args.android_assets and not args.android_assets.resolve().is_relative_to(repo / 'app' / 'build' / 'discovery-feasibility'):
        raise SystemExit('Android probe assets must remain in isolated feasibility storage')
    manifest = json.loads(Path(__file__).with_name('auxiliary-model-candidates.json').read_text(encoding='utf-8'))
    candidate = next(c for c in manifest['candidates'] if c['model'] == 'cross-encoder/ms-marco-MiniLM-L6-v2')
    export = next(f for f in candidate['selectedMetadata'] if f['path'] == 'onnx/model_qint8_arm64.onnx')
    model = assets / export['path']
    model_hash = verify_model(model, export)
    tokenizer = Tokenizer.from_file(str(assets / 'tokenizer.json'))
    # This is the publisher's public query/document example, not Note-pair training data.
    query = 'How many people live in Berlin?'
    passages = [
        'Berlin has a population of 3,520,031 registered inhabitants in an area of 891.82 square kilometers.',
        'New York City is famous for the Metropolitan Museum of Art.',
    ]
    encoded = [tokenizer.encode(query, passage) for passage in passages]
    if not all(e.ids[0] == 101 and e.ids[-1] == 102 and e.ids.count(102) == 2 and
               0 in e.type_ids and 1 in e.type_ids for e in encoded):
        raise ValueError('Unexpected publisher pair encoding')
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    started = time.perf_counter_ns()
    session = ort.InferenceSession(str(model), options, providers=['CPUExecutionProvider'])
    initialization_ms = (time.perf_counter_ns() - started) / 1e6
    inputs = {i.name: i.type for i in session.get_inputs()}
    if set(inputs) != {'input_ids', 'attention_mask', 'token_type_ids'} or not all(t == 'tensor(int64)' for t in inputs.values()):
        raise ValueError('Unexpected graph inputs: ' + str(inputs))

    def score(e):
        result = session.run(None, {
            'input_ids': np.array([e.ids], dtype=np.int64),
            'attention_mask': np.array([e.attention_mask], dtype=np.int64),
            'token_type_ids': np.array([e.type_ids], dtype=np.int64),
        })[0]
        if result.shape != (1, 1) or not np.isfinite(result).all():
            raise ValueError('Invalid classifier output shape/values')
        return float(result[0, 0])

    scores = [score(e) for e in encoded]
    if scores[0] <= scores[1]:
        raise SystemExit('Publisher example relevant passage did not outrank irrelevant passage')
    timings = []
    for sample in range(20):
        started = time.perf_counter_ns()
        score(encoded[sample % 2])
        timings.append((time.perf_counter_ns() - started) / 1e6)
    ordered = sorted(timings)
    report = {
        'status': 'public_example_smoke_passed_not_discovery_quality',
        'scope': 'Host ONNX CPU only; public publisher example; no reader/private content access',
        'model': candidate['model'], 'revision': candidate['revision'], 'modelSha256': model_hash,
        'artifactBytes': model.stat().st_size, 'licenseMetadata': candidate['license'],
        'host': {'system': platform.system(), 'machine': platform.machine(), 'ort': ort.__version__},
        'providers': session.get_providers(), 'threads': 1, 'inputs': inputs,
        'tokenizerSha256': hashlib.sha256((assets / 'tokenizer.json').read_bytes()).hexdigest(),
        'pairTokenCounts': [len(e.ids) for e in encoded],
        'rawLogitsInPublisherExampleOrder': scores,
        'initializationMs': initialization_ms, 'warmSamples': 20,
        'warmInferenceOnlyP50Ms': ordered[9], 'warmInferenceOnlyP95Ms': ordered[18],
        'warmInferenceOnlyMeanMs': statistics.mean(timings),
        'unmeasured': ['Android native loading/ABIs/API26', 'Android tokenizer parity',
                       'Discovery usefulness/precision', 'Note-pair symmetry and support',
                       'memory/thermal/playback contention', 'topic naming or multi-label membership'],
    }
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if args.android_assets:
        target = args.android_assets / 'reranker'
        target.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(model, target / 'model.onnx')
        reference = {
            'model': candidate['model'], 'revision': candidate['revision'],
            'modelSha256': model_hash, 'artifactBytes': model.stat().st_size,
            'tokenizerSha256': report['tokenizerSha256'],
            'absoluteLogitTolerance': 0.1,
            'scope': 'Public publisher example with host-generated token arrays; not Android tokenizer parity or discovery quality',
            'probes': [{'input_ids': e.ids, 'attention_mask': e.attention_mask, 'token_type_ids': e.type_ids,
                        'expectedLogit': scores[i]} for i, e in enumerate(encoded)],
        }
        (target / 'reference.json').write_text(json.dumps(reference, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': report['status'], 'report': str(args.report)}))


if __name__ == '__main__':
    main()
