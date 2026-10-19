"""Pinned WOS-JTF adapter. Gold labels are isolated from shipping discovery input."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
from public_corpus import digest, encoded, freeze_manifest

REPO = 'marcelsun/wos_hierarchical_multi_label_text_classification'
REVISION = 'f246712b46ef607586a774be262b186665b04630'
SOURCE_HASHES = {
    'dev': '416b77019799756eede0928c412bc3e6e35df2878887579482847fabb308f1a2',
    'test': '3b61f674ae679772fe51b3622fb230df079a8b44332ac2bce91e5c2d023c9287',
}


def read_pinned_rows(path, expected_sha):
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError('Source bytes do not match the pinned dataset revision')
    return [json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]


def adapt(rows, split, count):
    unique = {}
    for row in rows:
        text = row['token'].strip()
        if text:
            unique.setdefault(digest(text), row)
    if len(unique) < count:
        raise ValueError('Insufficient unique source texts')
    notes, gold = [], {}
    for key, row in sorted(unique.items(), key=lambda item: digest('wos-v1:' + item[0]))[:count]:
        text = row['token'].strip()
        identity = digest('wos-jtf:' + key)[:32]
        partition = 'calibration' if split == 'dev' else 'held_out'
        notes.append({'id': identity, 'title': text.split('. ', 1)[0],
            'family': 'wos-source-split-' + split, 'partition': partition,
            'source': {'kind': 'public', 'pageId': identity, 'revisionId': 1,
                'datasetRevision': REVISION, 'contentSha256': key,
                'url': f'https://huggingface.co/datasets/{REPO}/blob/{REVISION}/JTF/JTF_{split}.json',
                'license': 'CC-BY-4.0 (dataset card)'},
            'sections': [{'title': 'Body', 'paragraphs': [{'ordinal': 0, 'text': text}]}],
            'judgmentOrigin': 'unrated'})
        gold[identity] = {'labels': row['label'], 'sourceSplit': split,
            'origin': 'publisher-journal-and-citation-classification', 'contentSha256': key}
    return notes, gold


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--download', action='store_true', help='Fetch exact public source revision before verifying/adapting')
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    notes, gold = [], {}
    for split, count in [('dev', 200), ('test', 300)]:
        file = args.directory / f'JTF_{split}.json'
        if args.download and not file.exists():
            url = f'https://huggingface.co/datasets/{REPO}/resolve/{REVISION}/JTF/JTF_{split}.json'
            with urllib.request.urlopen(url, timeout=90) as response:
                raw = response.read()
            if hashlib.sha256(raw).hexdigest() != SOURCE_HASHES[split]:
                raise ValueError('Downloaded source does not match pinned revision')
            file.write_bytes(raw)
        rows = read_pinned_rows(file, SOURCE_HASHES[split])
        selected, labels = adapt(rows, split, count)
        notes.extend(selected); gold.update(labels)
    manifest = freeze_manifest(notes)
    manifest['samplingPolicy'] = 'wos-jtf-v1-content-hash-200dev-300test'
    manifest['limitations'] = ['Publisher source splits; subject families overlap across splits: not family-held-out acceptance',
        'Journal/citation labels are proxy grouping judgments, not passage relevance or human app-usefulness judgments',
        'Scientific title/abstract corpus; no authored section hierarchy; no representative personal-Note claim']
    (args.directory / 'corpus.json').write_bytes(encoded({'manifest': manifest, 'notes': notes}))
    (args.directory / 'corpus.lock.json').write_bytes(encoded(manifest))
    gold_artifact = {'dataset': REPO, 'revision': REVISION, 'notes': gold}
    (args.directory / 'gold-labels.json').write_bytes(encoded(gold_artifact))
    (args.directory / 'evaluation-lock.json').write_bytes(encoded({'dataset': REPO, 'revision': REVISION,
        'sourceHashes': SOURCE_HASHES, 'corpusSha256': manifest['corpusSha256'], 'goldSha256': digest(gold_artifact),
        'samplingPolicy': manifest['samplingPolicy']}))
    print('Prepared 500 WOS-JTF Notes; isolated publisher labels; source-split diagnostic only')


if __name__ == '__main__':
    main()
