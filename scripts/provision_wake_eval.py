"""Provision the synthetic corpus or an opt-in, public CC0 negative into a Preview AVD.

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
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / '.runtime/wake192-acoustic/corpus-v1'
PACKAGE = 'vn.tn.assistant.preview'
CC0_SOURCE = ('https://upload.wikimedia.org/wikipedia/commons/4/4a/'
              '%22Ch%E1%BB%AF_Qu%E1%BB%91c_Ng%E1%BB%AF%22_northern_pronunciation.ogg')
CC0_LICENSE = 'https://commons.wikimedia.org/wiki/Special:PermanentLink/1269894826'
CC0_SHA256 = 'f9761f114906b7af710b0f7b999f55fcffb7a98f4c470f042099048dba64031b'


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


def cc0_recording(directory):
    request = urllib.request.Request(CC0_SOURCE, headers={
        'User-Agent': 'TN-Assistant-WakeEval/1.0 (https://github.com/huuthitv95/TN-Assistant)'})
    with urllib.request.urlopen(request, timeout=30) as response:
        ogg = response.read(44290)
    if len(ogg) != 44289 or hashlib.sha256(ogg).hexdigest() != CC0_SHA256:
        raise RuntimeError('Public CC0 source has changed')
    pcm = directory / 'chu-quoc-ngu.pcm'
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', 'pipe:0',
                    '-ar', '16000', '-ac', '1', '-f', 's16le', str(pcm)], input=ogg, check=True)
    if pcm.stat().st_size != 67584:
        raise RuntimeError('Expected 2.112 seconds of mono 16 kHz PCM')
    manifest = directory / 'manifest.json'
    manifest.write_text(json.dumps([{
        'file': pcm.name, 'expected_wake': False, 'source': CC0_SOURCE,
        'source_sha256': CC0_SHA256, 'license': 'CC0-1.0', 'license_evidence': CC0_LICENSE,
        'sample_rate': 16000, 'channels': 1, 'sample_width': 2,
        'bytes': pcm.stat().st_size, 'sha256': digest(pcm),
    }]), encoding='utf-8')
    return manifest.read_bytes(), [('manifest.json', manifest), (pcm.name, pcm)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit Android SDK emulator serial, for example emulator-5570')
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    parser.add_argument('--cc0-negative', action='store_true',
                        help='Download one public Vietnamese non-wake recording; run with -e tnWakeCorpus cc0-negative')
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
        manifest_bytes, files = cc0_recording(Path(temporary)) if args.cc0_negative else verified_files(CORPUS)
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
