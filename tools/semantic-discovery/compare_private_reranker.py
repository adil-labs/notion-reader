"""Local fixed-evidence diagnostic comparison. No network or private-content output."""
import argparse
from collections import Counter
import ctypes
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

from private_baseline import digest
from probe_reranker import verify_model


def ranking_summary(cases, scores, ratings, limit):
    ordered = sorted((c for c in cases if scores.get(c['caseId']) is not None),
                     key=lambda c: (-scores[c['caseId']], c['caseId']))
    selected = ordered[:limit]
    decisions = Counter((ratings.get(c['caseId']) or {}).get('decision', 'unrated') for c in selected)
    degree = Counter(page.strip().lower().replace('-', '') for c in selected
                     for page in [c['content']['left']['pageId'], c['content']['right']['pageId']])
    positive = [scores[c['caseId']] for c in ordered if (ratings.get(c['caseId']) or {}).get('decision', '').casefold() == 'keep']
    negative = [scores[c['caseId']] for c in ordered if (ratings.get(c['caseId']) or {}).get('decision', '').casefold() == 'suppress']
    auc = sum((a > b) + .5 * (a == b) for a in positive for b in negative) / (len(positive) * len(negative)) if positive and negative else None
    return {'selectedCaseIds': [c['caseId'] for c in selected], 'selected': len(selected),
            'humanDecisions': dict(decisions), 'distinctNotes': len(degree),
            'maximumAppearancesPerNote': max(degree.values(), default=0),
            'definiteDecisionAuc': auc, 'aucPositiveCases': len(positive), 'aucNegativeCases': len(negative),
            'caveat': 'Fixed judged candidate pool only; conditional/deprioritized cases are not binary negatives. No production-feed, recall, population-precision or independent held-out claim.'}


def memory():
    if os.name != 'nt':
        return {'status': 'unmeasuredOnThisHost'}
    class Counters(ctypes.Structure):
        _fields_ = [('cb', ctypes.c_ulong), ('pageFaults', ctypes.c_ulong)] + [
            (name, ctypes.c_size_t) for name in ['peakWorkingSet', 'workingSet', 'peakPagedPool',
                'pagedPool', 'peakNonPagedPool', 'nonPagedPool', 'pagefile', 'peakPagefile']]
    info = Counters(); info.cb = ctypes.sizeof(info)
    kernel = ctypes.windll.kernel32
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    psapi = ctypes.windll.psapi
    psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(Counters), ctypes.c_ulong]
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(info), info.cb):
        return {'status': 'measurementFailed'}
    return {'workingSetBytes': info.workingSet, 'processLifetimePeakWorkingSetBytes': info.peakWorkingSet,
            'scope': 'Whole host Python process including libraries; not model-only or Android memory'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--assets', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    baseline = args.baseline.resolve(); assets = args.assets.resolve()
    if not baseline.is_relative_to(repo / 'app/build/private-discovery-review') or subprocess.run(
            ['git', 'check-ignore', '-q', str(baseline)], cwd=repo).returncode:
        raise SystemExit('Comparison must remain in ignored private-review storage')
    if not assets.is_relative_to(repo / 'app/build/discovery-feasibility'):
        raise SystemExit('Only isolated local model assets are allowed')
    output = baseline / 'reranker-comparison.json'
    if output.exists():
        raise SystemExit('Refusing to overwrite frozen predictions')
    manifest = json.loads((baseline / 'baseline-manifest.json').read_text(encoding='utf-8'))
    for item in manifest['sets']:
        for name, expected in item['files'].items():
            path = baseline / item['input'] / name
            if not path.resolve().is_relative_to(baseline) or digest(path) != expected:
                raise SystemExit('Frozen review input changed')
    for name, expected in manifest['baselineRecordHashes'].items():
        path = baseline / 'baseline-no_backup' / name
        if not path.resolve().is_relative_to(baseline) or digest(path) != expected:
            raise SystemExit('Frozen baseline record changed')
    candidates_file = Path(__file__).with_name('auxiliary-model-candidates.json')
    candidate = next(c for c in json.loads(candidates_file.read_text())['candidates']
                     if c['model'] == 'cross-encoder/ms-marco-MiniLM-L6-v2')
    export = next(f for f in candidate['selectedMetadata'] if f['path'] == 'onnx/model_qint8_arm64.onnx')
    model_hash = verify_model(assets / export['path'], export)
    tokenizer = Tokenizer.from_file(str(assets / 'tokenizer.json'))
    ort.disable_telemetry_events()
    options = ort.SessionOptions(); options.intra_op_num_threads = 1
    before = memory(); started = time.perf_counter_ns()
    session = ort.InferenceSession(str(assets / export['path']), options, providers=['CPUExecutionProvider'])
    initialization_ms = (time.perf_counter_ns() - started) / 1e6
    initialized = memory()
    sets = []
    for item in manifest['sets']:
        directory = baseline / item['input']
        review = json.loads((directory / 'review.json').read_text(encoding='utf-8'))
        summary = json.loads((directory / 'baseline-summary.json').read_text(encoding='utf-8'))
        ratings = {c['caseId']: c['rating'] for c in summary['cases']}
        pairs = [c for c in review['cases'] if c['kind'] != 'topic']
        predictions = []
        for case in pairs:
            started = time.perf_counter_ns()
            a, b = (case['content'][side]['excerpt'] for side in ['left', 'right'])
            encodings = [tokenizer.encode(a, b), tokenizer.encode(b, a)]
            counts = [len(e.ids) for e in encodings]
            if max(counts) > 512:
                predictions.append({'caseId': case['caseId'], 'score': None, 'abstention': 'fullPairExceeds512Tokens', 'tokenCounts': counts})
                continue
            scores = []
            for e in encodings:
                result = session.run(None, {name: np.array([values], dtype=np.int64) for name, values in
                    [('input_ids', e.ids), ('attention_mask', e.attention_mask), ('token_type_ids', e.type_ids)]})[0]
                if result.shape != (1, 1) or not np.isfinite(result).all():
                    raise SystemExit('Invalid reranker output; no successful comparison published')
                scores.append(float(result[0, 0]))
            predictions.append({'caseId': case['caseId'], 'score': sum(scores) / 2, 'orderedLogits': scores,
                                'orderDifference': abs(scores[0] - scores[1]), 'tokenCounts': counts,
                                'elapsedMsIncludingPairTokenization': (time.perf_counter_ns() - started) / 1e6})
        baseline_scores = {c['caseId']: c['content'].get('cosine') for c in pairs}
        if any(v is not None and not math.isfinite(v) for v in baseline_scores.values()):
            raise SystemExit('Invalid baseline cosine')
        limit = sum(c['kind'] == 'connection' for c in pairs)
        sets.append({'input': item['input'], 'role': item['role'], 'ratingStatus': item['ratingStatus'],
                     'fixedPairs': len(pairs), 'limit': limit,
                     'historicalDisplayedCaseIds': [c['caseId'] for c in pairs if c['kind'] == 'connection'],
                     'cosineRankStage': ranking_summary(pairs, baseline_scores, ratings, limit),
                     'rerankerRankStage': ranking_summary(pairs, {p['caseId']: p['score'] for p in predictions}, ratings, limit),
                     'predictions': predictions})
    report = {'scope': 'PRIVATE local fixed-passage diagnostic; never commit/upload. No network or shipping changes.',
              'baselineManifestSha256': digest(baseline / 'baseline-manifest.json'),
              'model': candidate['model'], 'revision': candidate['revision'], 'modelSha256': model_hash,
              'tokenizerSha256': digest(assets / 'tokenizer.json'), 'licenseMetadata': candidate['license'],
              'runtime': {'ort': ort.__version__, 'numpy': np.__version__, 'machine': platform.machine(), 'threads': 1},
              'initializationMs': initialization_ms, 'memoryBeforeSession': before,
              'memoryAfterSession': initialized, 'memoryAfterEvaluation': memory(), 'sets': sets,
              'recommendation': 'defer', 'qualityGateEstablished': False,
              'reason': 'Exposed diagnostic pairs, legacy reference types unaudited, current baseline unrated; no independent held-out or Android listening evidence. This evaluates only conditional pair ordering.'}
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'sets': len(sets), 'pairs': [s['fixedPairs'] for s in sets],
                      'abstentions': [sum(p['score'] is None for p in s['predictions']) for s in sets],
                      'recommendation': 'defer', 'qualityGateEstablished': False}))


if __name__ == '__main__':
    main()
