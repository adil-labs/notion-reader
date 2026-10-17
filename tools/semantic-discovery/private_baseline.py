"""Freeze local evaluation inputs; never infers ratings or reads credentials/history."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

from review_evidence import exposure_identity


def summarize(review, judgments=None, previous=()):
    cases = review['cases']
    ids = [c['caseId'] for c in cases]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate review case IDs')
    ratings = judgments['cases'] if judgments is not None else []
    rated_ids = [c['caseId'] for c in ratings]
    if judgments is not None and (len(rated_ids) != len(set(rated_ids)) or set(rated_ids) != set(ids)):
        raise ValueError('Judgments must identify every frozen case exactly once')
    by_id = {c['caseId']: c for c in ratings}
    known = {identity for r in previous for c in r['cases'] if (identity := exposure_identity(c)) is not None}
    result = []
    for case in cases:
        rating = by_id.get(case['caseId'])
        identity = exposure_identity(case)
        result.append({
            'caseId': case['caseId'], 'kind': case['kind'], 'identity': identity,
            'exposure': 'previouslyReviewed' if identity in known else 'unknownMembership' if identity is None else 'notFoundInSuppliedReviews',
            'referenceType': case['content'].get('evidenceKind', 'unclassifiedLegacy') if case['kind'] != 'topic' else None,
            'rating': None if rating is None else {
                'usefulness': rating.get('usefulness'),
                'passageSupport': rating.get('passageSupport', rating.get('support')),
                'labelClarity': rating.get('labelClarity', rating.get('label')),
                'coherence': rating.get('coherence'), 'decision': rating.get('decision'),
            },
        })
    tasks = {}
    for kind in sorted({c['kind'] for c in cases}):
        selected = [c for c in result if c['kind'] == kind]
        rated = [c for c in selected if c['rating'] is not None]
        tasks[kind] = {
            'cases': len(selected), 'rated': len(rated),
            'decisions': dict(Counter(c['rating']['decision'] for c in rated)),
            'dimensions': {key: dict(Counter(c['rating'][key] or 'notRated' for c in rated))
                           for key in ['usefulness', 'passageSupport', 'labelClarity', 'coherence']},
            'referenceTypes': dict(Counter(c['referenceType'] for c in selected if c['referenceType'] is not None)),
        }
    return {'ratingStatus': 'humanRatedDiagnostic' if judgments is not None else 'unrated',
            'precisionEstimate': None, 'qualityGateEstablished': False,
            'caveat': 'Convenience diagnostic cases with prior exposure; no inferred human ratings, population precision, missed-connection recall or independent held-out claim.',
            'tasks': tasks, 'cases': result}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--history', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    private = repo / 'app' / 'build' / 'private-discovery-review'
    paths = [p.resolve() for p in args.history + [args.baseline, args.output]]
    for path in paths:
        if not path.is_relative_to(private) or subprocess.run(['git', 'check-ignore', '-q', str(path)], cwd=repo).returncode:
            raise SystemExit('All evaluation inputs/outputs must remain under ignored private-review storage')
    output = args.output.resolve()
    if output.exists():
        raise SystemExit('Refusing to overwrite a frozen comparison')
    sources = paths[:-1]
    output.mkdir(parents=True)
    previous = []
    sets = []
    for number, source in enumerate(sources):
        target = output / 'inputs' / str(number)
        target.mkdir(parents=True)
        for name in ['review.json', 'human-judgments.json', 'snapshot.json']:
            if (source / name).is_file():
                shutil.copyfile(source / name, target / name)
        review = json.loads((target / 'review.json').read_text(encoding='utf-8'))
        ratings_file = target / 'human-judgments.json'
        ratings = json.loads(ratings_file.read_text(encoding='utf-8')) if ratings_file.is_file() else None
        summary = summarize(review, ratings, previous)
        (target / 'baseline-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
        sets.append({'input': str(target.relative_to(output)), 'source': str(source),
                     'role': 'currentFrozenBaseline' if source == args.baseline.resolve() else 'exposedHistoricalDiagnostic',
                     'ratingStatus': summary['ratingStatus'], 'cases': len(review['cases']),
                     'files': {f.name: digest(f) for f in target.iterdir() if f.is_file()}})
        previous.append(review)
    baseline_source = args.baseline.resolve() / 'no_backup'
    record_hashes = {str(p.relative_to(baseline_source)): digest(p) for p in baseline_source.rglob('*') if p.is_file()}
    if not record_hashes:
        raise SystemExit('Baseline has no cached/vector snapshot provenance')
    shutil.copytree(baseline_source, output / 'baseline-no_backup')
    code = list((repo / 'app' / 'src' / 'main').rglob('*.kt'))
    code += [repo / 'app' / 'build.gradle.kts', repo / 'tools' / 'semantic-discovery' / 'artifacts.json']
    manifest = {
        'scope': 'PRIVATE frozen baseline before auxiliary inference. Never commit/upload.',
        'shippingChange': False, 'sets': sets, 'baselineRecordHashes': record_hashes,
        'gitHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
        'worktreeSourceHashes': {str(p.relative_to(repo)): digest(p) for p in code},
        'protocol': 'Fixed exact passage pairs; reranker uses mean raw logit of both orders; overlength abstains. No private threshold calibration. Legacy references unclassified. Current unrated cases are not acceptance evidence.',
        'additionalHeldOutCases': 'Not yet supplied/rated; must be frozen separately before evaluation.',
    }
    (output / 'baseline-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'frozenSets': len(sets), 'caseCounts': [s['cases'] for s in sets],
                      'ratingStates': [s['ratingStatus'] for s in sets], 'qualityGateEstablished': False}))


if __name__ == '__main__':
    main()
