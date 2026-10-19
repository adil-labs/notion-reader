"""Publisher-label pair diagnostics, never human app-quality acceptance."""
import argparse
import hashlib
from itertools import combinations
import json
from pathlib import Path
from public_corpus import encoded


def labels_at_level(labels, level):
    # Pinned JTF/depth2label.pt: depth 0 IDs 0..5; depth 1 IDs 6..51.
    if level not in {'broad', 'fine'} or any(v not in range(52) for v in labels):
        raise ValueError('Unknown pinned WOS-JTF hierarchy label')
    return {v for v in labels if (v < 6) == (level == 'broad')}


def verify_gold(corpus, gold, lock):
    from public_corpus import digest
    notes = corpus['notes']
    fingerprint = digest(notes)
    if fingerprint != corpus['manifest']['corpusSha256'] or fingerprint != lock['corpusSha256'] or digest(gold) != lock['goldSha256']:
        raise ValueError('Frozen gold/corpus fingerprint mismatch')
    if set(gold['notes']) != {n['id'] for n in notes}:
        raise ValueError('Gold/corpus Note identities differ')
    for note in notes:
        judgment = gold['notes'][note['id']]
        expected = 'dev' if note['partition'] == 'calibration' else 'test'
        if judgment['sourceSplit'] != expected or judgment['contentSha256'] != note['source']['contentSha256']:
            raise ValueError('Gold source split/content differs from corpus Note')
        labels_at_level(judgment['labels'], 'fine')


def pair_score(groups, labels):
    predicted = {tuple(sorted(pair)) for group in groups for pair in combinations(set(group) & labels.keys(), 2)}
    gold = {pair for pair in combinations(sorted(labels), 2) if labels[pair[0]] & labels[pair[1]]}
    correct = len(predicted & gold)
    precision = correct / len(predicted) if predicted else None
    recall = correct / len(gold) if gold else None
    return {'predictedPairs': len(predicted), 'goldPairs': len(gold), 'correctPairs': correct,
        'precision': precision, 'recall': recall,
        'f1': 2 * correct / (len(predicted) + len(gold)) if predicted or gold else None}


def score(report, gold):
    if any(m['pageId'] not in gold for c in report['cases'] for m in c['topic']['members']):
        raise ValueError('Pipeline report contains Notes outside the frozen gold corpus')
    groups = [[m['pageId'] for m in c['topic']['members']] for c in report['cases'] if c['published']]
    result = {'publishedSubjects': sum(c['published'] for c in report['cases']),
        'distinctLabels': len({c['topic']['label'] for c in report['cases'] if c['published']}),
        'establishedSubjects': sum(c['published'] and len(c['topic']['members']) > 1 for c in report['cases']),
        'subjectRelationships': len(report['subjectRelationships']), 'partitions': {}}
    for split in ['dev', 'test']:
        selected = {k: v for k, v in gold.items() if v['sourceSplit'] == split}
        result['partitions'][split] = {'notes': len(selected)}
        for level in ['broad', 'fine']:
            labels = {k: labels_at_level(v['labels'], level) for k, v in selected.items()}
            result['partitions'][split][level] = pair_score(groups, labels)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--reports', type=Path, nargs='+', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    gold_artifact = json.loads((args.directory / 'gold-labels.json').read_text(encoding='utf-8'))
    gold = gold_artifact['notes']
    corpus = json.loads((args.directory / 'corpus.json').read_text(encoding='utf-8'))
    lock = json.loads((args.directory / 'evaluation-lock.json').read_text(encoding='utf-8'))
    verify_gold(corpus, gold_artifact, lock)
    results = {}
    for path in args.reports:
        report = json.loads(path.read_text(encoding='utf-8'))
        if report['input']['identity']['corpusSha256'] != corpus['manifest']['corpusSha256']:
            raise ValueError('Pipeline report belongs to another corpus')
        results[str(path)] = {**score(report, gold), 'reportSha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    args.output.write_bytes(encoded({'results': results, 'evaluationLock': lock, 'humanQualityGate': 'unmeasured',
        'scope': 'Publisher-label pair diagnostic on source splits, not family-held-out or passage/label usefulness acceptance'}))
    print(json.dumps(results, indent=2))
