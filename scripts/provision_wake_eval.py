"""Provision a verified, synthetic wake corpus into an installed Preview AVD.

The PCM files stay local and are copied only into the selected emulator's app-private test directory.
"""
import argparse
import base64
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile


ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / '.runtime/wake192-acoustic/corpus-v1'
PACKAGE = 'vn.tn.assistant.preview'


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def verified_files(corpus):
    manifest_path = corpus / 'manifest.json'
    manifest_bytes = manifest_path.read_bytes()
    rows = json.loads(manifest_bytes.decode('utf-8'))
    if len(rows) != 75:
        raise RuntimeError('Expected the pinned 75-sample wake corpus')
    files = [('manifest.json', manifest_path)]
    seen = set()
    for row in rows:
        name = row.get('file')
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9.-]+', name) or name in seen:
            raise RuntimeError('Invalid or duplicate corpus filename')
        seen.add(name)
        path = corpus / name
        if path.is_symlink() or path.resolve().parent != corpus.resolve() or not path.is_file():
            raise RuntimeError('Corpus sample is missing or outside its directory')
        if row.get('sample_rate') != 16000 or row.get('channels') != 1 or row.get('sample_width') != 2:
            raise RuntimeError('Unexpected PCM format')
        if path.stat().st_size != row.get('bytes') or digest(path) != row.get('sha256'):
            raise RuntimeError('Corpus sample checksum differs: ' + name)
        files.append((name, path))
    return manifest_bytes, files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit Android SDK emulator serial, for example emulator-5570')
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('--serial must identify an Android SDK emulator')

    manifest_bytes, files = verified_files(CORPUS)
    archive = BytesIO()
    with tarfile.open(fileobj=archive, mode='w', format=tarfile.USTAR_FORMAT) as output:
        for name, path in files:
            output.add(path, arcname=name, recursive=False)

    def adb(*parts, data=None):
        result = subprocess.run([args.adb, '-s', args.serial, *parts], input=data,
                                capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError('ADB operation failed with exit code ' + str(result.returncode))
        return result.stdout

    if adb('get-state').decode('utf-8').strip() != 'device':
        raise RuntimeError('The selected emulator is not online')
    if not adb('shell', 'pm', 'path', PACKAGE).startswith(b'package:'):
        raise RuntimeError('TN Assistant Preview is not installed on the selected emulator')

    command = (
        "run-as " + PACKAGE + " sh -c 'set -e; umask 077; "
        "rm -rf files/tn-wake-test; mkdir -p files/tn-wake-test; "
        "base64 -d > files/.tn-wake-provision.tar; "
        "tar -xf files/.tn-wake-provision.tar -C files/tn-wake-test; "
        "rm -f files/.tn-wake-provision.tar'"
    )
    adb('shell', '-T', command, data=base64.b64encode(archive.getvalue()))

    installed_manifest = adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/tn-wake-test/manifest.json')
    if installed_manifest != manifest_bytes:
        raise RuntimeError('The installed corpus manifest differs')
    for name, path in files[1:]:
        installed = adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/tn-wake-test/' + name)
        if len(installed) != path.stat().st_size or hashlib.sha256(installed).hexdigest() != digest(path):
            raise RuntimeError('Installed corpus checksum differs: ' + name)

    print(json.dumps({
        'serial': args.serial,
        'package': PACKAGE,
        'samples': len(files) - 1,
        'bytes': sum(path.stat().st_size for _, path in files),
        'manifest_sha256': hashlib.sha256(manifest_bytes).hexdigest(),
        'provider_calls': 0,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
