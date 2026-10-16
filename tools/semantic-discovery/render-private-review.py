"""Render an explicit local review without uploading content or inventing human ratings."""
import argparse
import html
import json
from pathlib import Path
import subprocess


def render(review):
    escape = lambda value: html.escape(str(value))
    def evidence(item):
        return (f'<h3>{escape(item["title"])}</h3><p>{escape(item["section"])}</p>'
                f'<blockquote>{escape(item["excerpt"])}</blockquote>')
    cards = []
    for number, case in enumerate(review['cases'], 1):
        content = case['content']
        body = (f'<h3>{escape(content["label"])}</h3><p>{content["availableCount"]} Notes</p>'
                + ''.join(evidence(member) for member in content['members'])) if case['kind'] == 'topic' else (
                evidence(content['left']) + evidence(content['right']))
        cards.append(f'<section><h2>{number}. {escape(case["kind"])} — {escape(case["caseId"])}</h2>'
                     + body + '<p><strong>Human judgment: pending.</strong> Assess usefulness and passage support separately. '
                     'For topics, also assess label clarity and member coherence. An unshown candidate is not automatically a missed useful connection.</p></section>')
    return ('<!doctype html><html lang="en"><meta charset="utf-8"><title>Private Discovery review</title>'
            '<meta name="viewport" content="width=device-width"><style>body{font:18px/1.6 system-ui;max-width:850px;margin:2rem auto;padding:1rem}'
            'section{border-top:2px solid #777;margin-top:2rem}blockquote{border-left:4px solid #777;padding-left:1rem;white-space:pre-wrap}</style>'
            '<h1>Private Discovery review</h1><p>Local private content. Do not publish or commit. '
            'These frozen held-out cases are separate from public calibration. No human precision score has been assigned.</p>'
            + ''.join(cards) + '</html>')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('review', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    source = args.review.resolve()
    if not source.is_relative_to(repo / 'app' / 'build' / 'private-discovery-review'):
        raise SystemExit('Private review must remain under ignored build storage')
    if subprocess.run(['git', 'check-ignore', '-q', str(source)], cwd=repo).returncode:
        raise SystemExit('Private review must be Git-ignored')
    output = source.with_suffix('.html')
    output.write_text(render(json.loads(source.read_text(encoding='utf-8'))), encoding='utf-8')
    print(f'Local review rendered: {output}')


if __name__ == '__main__':
    main()
