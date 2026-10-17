"""Render an explicit local review without uploading content or inventing human ratings."""
import argparse
import html
import json
from pathlib import Path
import subprocess
from review_evidence import exposure_identity


def render(review, prior_reviews=(), snapshot_context=None):
    escape = lambda value: html.escape(str(value))
    snapshot_context = snapshot_context or {}
    provenance = '<p>Evaluation origin: ' + escape(snapshot_context.get('origin', 'Not recorded in this snapshot.')) + '</p>'
    if snapshot_context.get('coverage'):
        provenance += '<p>Coverage: ' + escape(snapshot_context['coverage']) + '</p>'
    known = {identity for prior in prior_reviews for case in prior['cases']
             if (identity := exposure_identity(case)) is not None}
    incomplete_prior_topics = any(exposure_identity(case) is None for prior in prior_reviews for case in prior['cases'])
    def evidence(item):
        return (f'<h3>{escape(item["title"])}</h3><p>{escape(item["section"])}</p>'
                f'<blockquote>{escape(item["excerpt"])}</blockquote>')
    cards = []
    for number, case in enumerate(review['cases'], 1):
        content = case['content']
        identity = exposure_identity(case)
        if identity in known:
            exposure = 'Previously reviewed Note pair or topic membership; diagnostic follow-up.'
        elif identity is None or (case['kind'] == 'topic' and incomplete_prior_topics):
            exposure = 'Topic membership exposure unknown: a current or prior review omits full membership; not proof of an independent sample.'
        else:
            exposure = 'No matching identity in supplied prior reviews; not proof of an independent sample.'
        body = (f'<h3>{escape(content["label"])}</h3><p>{content["availableCount"]} Notes</p>'
                + ''.join(evidence(member) for member in content['members'])) if case['kind'] == 'topic' else (
                evidence(content['left']) + evidence(content['right']))
        if case['kind'] != 'topic':
            evidence_type = {'NamedReference': 'Named reference', 'SimilarPassages': 'Similar passages'}.get(
                content.get('evidenceKind'), 'Evidence type not classified')
            body = '<p><strong>' + evidence_type + '</strong></p>' + body
        cards.append(f'<section><h2>{number}. {escape(case["kind"])} — {escape(case["caseId"])}</h2>'
                     + '<p>' + exposure + '</p>' + body + '<p><strong>Judgments are recorded separately.</strong> Assess usefulness and passage support separately. '
                     'For topics, also assess label clarity and member coherence. An unshown candidate is not automatically a missed useful connection.</p></section>')
    return ('<!doctype html><html lang="en"><meta charset="utf-8"><title>Private Discovery review</title>'
            '<meta name="viewport" content="width=device-width"><style>body{font:18px/1.6 system-ui;max-width:850px;margin:2rem auto;padding:1rem}'
            'section{border-top:2px solid #777;margin-top:2rem}blockquote{border-left:4px solid #777;padding-left:1rem;white-space:pre-wrap}</style>'
            '<h1>Private Discovery review</h1><p>Local private content. Do not publish or commit. '
            'These frozen local cases are separate from public calibration. Prior feedback and exposure must be audited before any held-out claim. No human precision score has been assigned.</p>'
            + provenance + ''.join(cards) + '</html>')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('review', type=Path)
    parser.add_argument('--prior-review', type=Path, action='append', default=[])
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    paths = [path.resolve() for path in [args.review] + args.prior_review]
    for path in paths:
        if not path.is_relative_to(repo / 'app' / 'build' / 'private-discovery-review'):
            raise SystemExit('Private review must remain under ignored build storage')
        if subprocess.run(['git', 'check-ignore', '-q', str(path)], cwd=repo).returncode:
            raise SystemExit('Private review must be Git-ignored')
    source = paths[0]
    output = source.with_suffix('.html')
    snapshot_file = source.with_name('snapshot.json')
    snapshot_context = json.loads(snapshot_file.read_text(encoding='utf-8')) if snapshot_file.is_file() else {}
    output.write_text(render(json.loads(source.read_text(encoding='utf-8')),
                             [json.loads(path.read_text(encoding='utf-8')) for path in paths[1:]], snapshot_context), encoding='utf-8')
    print(f'Local review rendered: {output}')


if __name__ == '__main__':
    main()
