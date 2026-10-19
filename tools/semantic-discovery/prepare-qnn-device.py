"""Stage pinned ARM64 research libraries in app cache; production APK is untouched."""
import argparse
import hashlib
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ARTIFACTS = {
    ('onnxruntime-android-qnn', '2.7.0'): 'ecd36df222e2a947ceb13bdd6fec181d1f1841b40379e21ce519830f2fbd562a',
    ('qnn-runtime', '2.51.0'): '6388face1d5aa5e8958d745e5a547e92b5bec6fc332dfb1b438b1e17ffa02861',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    def adb(*parts):
        return subprocess.check_output(['adb', '-s', args.device, *parts], text=True)
    if adb('shell', 'getprop', 'ro.soc.manufacturer').strip().lower() not in {'qcom', 'qualcomm', 'qti'}:
        raise ValueError('QNN probe requires Qualcomm device')
    package = 'com.example.notionreader'
    destination = 'cache/gemma-qnn-libs'
    adb('shell', 'run-as', package, 'mkdir', '-p', destination)
    for (artifact, version), expected in ARTIFACTS.items():
        archive = args.directory / f'{artifact}-{version}.aar'
        if not archive.exists():
            urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/com/qualcomm/qti/{artifact}/{version}/{archive.name}', archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise ValueError(f'Unverified archive {archive.name}')
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                if not item.filename.startswith('jni/arm64-v8a/') or not item.filename.endswith('.so'):
                    continue
                name = Path(item.filename).name
                local = args.directory / name
                local.write_bytes(source.read(item))
                temporary = f'/data/local/tmp/gemma-qnn-{name}'
                adb('push', str(local), temporary)
                adb('shell', 'run-as', package, 'cp', temporary, f'{destination}/{name}')
                actual = adb('shell', 'run-as', package, 'sha256sum', f'{destination}/{name}').split()[0]
                if actual != hashlib.sha256(local.read_bytes()).hexdigest():
                    raise ValueError(f'Device checksum mismatch: {name}')
                adb('shell', 'rm', temporary)
                print(f'Verified app-owned QNN fixture: {name}')


if __name__ == '__main__':
    main()
