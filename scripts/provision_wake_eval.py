"""Provision the synthetic corpus or public CC0 non-wake recordings into a Preview AVD.

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
import tempfile
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / '.runtime/wake192-acoustic/corpus-v1'
PACKAGE = 'vn.tn.assistant.preview'
CC0_SOURCES = Path(__file__).with_name('wake_cc0_negative.json')


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


def cc0_recordings(directory):
    sources = json.loads(CC0_SOURCES.read_text(encoding='utf-8'))
    if not sources or len({row['file'] for row in sources}) != len(sources):
        raise ValueError('Expected a nonempty list of unique CC0 sample names')
    rows, files = [], []
    for index, row in enumerate(sources):
        name = row['file']
        if (not re.fullmatch(r'[a-z0-9-]+\.pcm', name) or row['license'] != 'CC0-1.0'
                or not row['source'].startswith('https://upload.wikimedia.org/wikipedia/commons/')
                or not 0 < row['source_bytes'] <= 2_000_000):
            raise ValueError('Invalid entry in the pinned CC0 source list')
        if index:
            time.sleep(3.5)  # Space anonymous requests to the Wikimedia media host.
        request = urllib.request.Request(row['source'], headers={
            'User-Agent': 'TN-Assistant-WakeEval/1.0 (https://github.com/huuthitv95/TN-Assistant)'})
        with urllib.request.urlopen(request, timeout=30) as response:
            audio = response.read(row['source_bytes'] + 1)
        if len(audio) != row['source_bytes'] or hashlib.sha256(audio).hexdigest() != row['source_sha256']:
            raise RuntimeError('CC0 source checksum differs: ' + name)
        pcm = directory / name
        subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', 'pipe:0',
                        '-ar', '16000', '-ac', '1', '-f', 's16le', str(pcm)], input=audio, check=True)
        if pcm.stat().st_size != row['pcm_bytes']:
            raise RuntimeError('Decoded CC0 sample length differs: ' + name)
        rows.append(dict(row, expected_wake=False, sample_rate=16000, channels=1, sample_width=2,
                         bytes=pcm.stat().st_size, sha256=digest(pcm)))
        files.append((name, pcm))
    manifest = directory / 'manifest.json'
    manifest.write_text(json.dumps(rows, ensure_ascii=False), encoding='utf-8')
    return manifest.read_bytes(), [('manifest.json', manifest), *files]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit Android SDK emulator serial, for example emulator-5570')
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    parser.add_argument('--cc0-negative', action='store_true',
                        help='Download public Vietnamese non-wake recordings; run with -e tnWakeCorpus cc0-negative')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('--serial must identify an Android SDK emulator')

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

    folder = 'files/tn-wake-cc0-negative' if args.cc0_negative else 'files/tn-wake-test'
    # Android 10 toybox tar restores numeric owners, even when run as the app.
    uid = int(adb('shell', 'run-as', PACKAGE, 'id', '-u'))
    gid = int(adb('shell', 'run-as', PACKAGE, 'id', '-g'))
    with tempfile.TemporaryDirectory() as temporary:
        manifest_bytes, files = cc0_recordings(Path(temporary)) if args.cc0_negative else verified_files(CORPUS)
        archive = BytesIO()
        with tarfile.open(fileobj=archive, mode='w', format=tarfile.USTAR_FORMAT) as output:
            for name, path in files:
                entry = output.gettarinfo(str(path), arcname=name)
                entry.uid, entry.gid = uid, gid
                entry.uname = entry.gname = ''
                with path.open('rb') as source:
                    output.addfile(entry, source)
        # Finish extracting the upload before touching the installed samples.
        command = (
            f"run-as {PACKAGE} sh -c 'set -e; umask 077; mkdir -p files; "
            f"stage=$(mktemp -d {folder}.XXXXXX); "
            'trap "rm -rf $stage" EXIT; '
            'base64 -d > "$stage/input.tar"; tar -xf "$stage/input.tar" -C "$stage"; '
            f'rm "$stage/input.tar"; mkdir -p {folder}; '
            f'mv "$stage/"*.pcm {folder}/; mv "$stage/manifest.json" {folder}/manifest.json\''
        )
        adb('shell', '-T', command, data=base64.b64encode(archive.getvalue()))
        total_bytes = sum(path.stat().st_size for _, path in files)
        for name, path in files:
            installed = adb('exec-out', 'run-as', PACKAGE, 'cat', folder + '/' + name)
            if len(installed) != path.stat().st_size or hashlib.sha256(installed).hexdigest() != digest(path):
                raise RuntimeError('Installed corpus checksum differs: ' + name)

    print(json.dumps({
        'serial': args.serial,
        'package': PACKAGE,
        'samples': len(files) - 1,
        'bytes': total_bytes,
        'corpus': 'cc0-negative' if args.cc0_negative else 'synthetic',
        'manifest_sha256': hashlib.sha256(manifest_bytes).hexdigest(),
        'provider_calls': 0,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
