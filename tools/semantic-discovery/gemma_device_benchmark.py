"""Summarize real ORT node attribution without calling CPU fallback acceleration."""
import argparse
import json
import math
from pathlib import Path


def profile_summary(events, requested_provider):
    providers = {}
    cpu_matrix = 0
    for event in events:
        args = event.get('args', {})
        provider = args.get('provider')
        if event.get('cat') != 'Node' or not provider:
            continue
        bucket = providers.setdefault(provider, {'events': 0, 'durationMicroseconds': 0})
        bucket['events'] += 1
        bucket['durationMicroseconds'] += max(0, event.get('dur', 0))
        if provider == 'CPUExecutionProvider' and args.get('op_name') in {'MatMulNBits', 'MatMulInteger'}:
            cpu_matrix += 1
    if not providers:
        raise ValueError('No attributed ORT node events: acceleration is unverified')
    total = sum(p['durationMicroseconds'] for p in providers.values())
    selected = providers.get(requested_provider, {})
    return {'providers': providers, 'requestedProvider': requested_provider,
            'acceleratorObserved': requested_provider != 'CPUExecutionProvider' and bool(selected),
            'cpuQuantizedMatrixEvents': cpu_matrix,
            'requestedProviderDurationFraction': selected.get('durationMicroseconds', 0) / total if total else None,
            'scope': 'Summed profiled node duration; not wall time or hardware utilization'}


def attach_profile_evidence(report, directory):
    rows = report.get('configurations', report.get('attempts'))
    if rows is None:
        raise ValueError('Unknown benchmark report')
    for row in rows:
        if 'failure' in row:
            row['executionComplete'] = False
            row['profileEvidence'] = {'acceleratorObserved': False,
                                      'scope': 'Failed execution; partial trace cannot establish usable acceleration'}
            continue
        if 'attempts' in report:
            norm, cosine = row.get('rawL2Norm'), row.get('cosineWithHostInt8')
            if not (isinstance(norm, (int, float)) and math.isfinite(norm) and norm > 0 and
                    isinstance(cosine, (int, float)) and math.isfinite(cosine) and .95 <= cosine <= 1.00001):
                raise ValueError('Native success without a valid nonzero reference-consistent embedding')
            if 'warmMilliseconds' in row:
                times = row['warmMilliseconds']
                agreements = row.get('warmCosinesWithSamePrecisionCpu', [])
                if not (len(times) == len(agreements) and len(times) > 0 and
                        all(isinstance(v, (int, float)) and math.isfinite(v) and v > 0 for v in times) and
                        all(isinstance(v, (int, float)) and math.isfinite(v) and .9995 <= v <= 1.00001 for v in agreements)):
                    raise ValueError('Timed outputs lack valid same-precision CPU agreement')
        if 'case' in row:
            times = row.get('milliseconds', [])
            agreements = row.get('cosinesWithSamePrecisionCpu', [])
            if not (len(times) == len(agreements) == 3 and row.get('profileFile') and
                    all(isinstance(v, (int, float)) and math.isfinite(v) and v > 0 for v in times) and
                    all(isinstance(v, (int, float)) and math.isfinite(v) and .9995 <= v <= 1.00001 for v in agreements)):
                raise ValueError('Vulkan probe success requires three valid timed outputs and a profile')
        row['executionComplete'] = True
        if 'profileFile' in row:
            events = json.loads((directory / Path(row['profileFile']).name).read_text())
            requested = row.get('requestedProvider', 'CPUExecutionProvider' if row.get('backend') == 'cpu' else 'QNNExecutionProvider')
            row['profileEvidence'] = profile_summary(events, requested)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    kind = parser.add_mutually_exclusive_group()
    kind.add_argument('--qnn', action='store_true')
    kind.add_argument('--vulkan', action='store_true')
    args = parser.parse_args()
    filename = 'gemma-vulkan-probe.json' if args.vulkan else 'gemma-qnn-probe.json' if args.qnn else 'gemma-benchmark.json'
    report = json.loads((args.directory / filename).read_text())
    attach_profile_evidence(report, args.directory)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
