"""Explicit developer-only snapshot for local review; never uploads data or exports credentials."""
import argparse
import datetime
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if not output.is_relative_to(repo / 'app' / 'build'):
        raise SystemExit('Private review output must remain under ignored app/build')
    if subprocess.run(['git', 'check-ignore', '-q', str(output)], cwd=repo).returncode != 0:
        raise SystemExit('Refusing to write private data outside Git-ignored storage')
    if output.exists():
        raise SystemExit('Choose a new output directory; never overwrite a prior review or judgments')
    # The app is only read. Atomic source records are copied; no model, credentials or session history is exported.
    folders = ['semantic-index', 'discovery-topics', 'discovery-topic-checkpoint', 'document-cache']
    command = ['adb', '-s', args.device, 'exec-out', 'run-as', 'com.example.notionreader', 'tar', '-cf', '-']
    capture = subprocess.run(command + ['no_backup/' + f for f in folders], capture_output=True)
    if capture.returncode:
        raise SystemExit('Snapshot failed; no complete review created. Device snapshot may have changed; retry while indexing is paused/backgrounded.')
    output.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(capture.stdout)) as archive:
        for entry in archive.getmembers():
            target = (output / entry.name).resolve()
            if not target.is_relative_to(output) or entry.issym() or entry.islnk():
                raise SystemExit('Unsafe archive entry rejected')
        archive.extractall(output, filter='data')
    inventory = {str(f.relative_to(output)): {'bytes': f.stat().st_size, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
                 for f in output.rglob('*') if f.is_file()}
    (output / 'snapshot.json').write_text(json.dumps({'capturedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'device': args.device, 'privacy': 'Local explicit evaluation snapshot; private content. Never commit or upload.',
        'consistency': 'Best-effort atomic records; replay must validate cache revisions before publishing.',
        'inventory': inventory}, indent=2), encoding='utf-8')
    print(f'Private snapshot created under ignored storage: {output}; files={len(inventory)}')


if __name__ == '__main__':
    main()
