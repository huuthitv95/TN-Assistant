"""Create a source-only archive for a backend-only TN Assistant release."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile


ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def selected_files():
    backend = ROOT / 'backend'
    files = [path for path in backend.glob('*.go') if not path.name.endswith('_test.go')]
    files += [backend / 'go.mod', backend / 'go.sum']
    files += list((backend / 'migrations').glob('*.sql'))
    files += [ROOT / 'deploy' / name for name in (
        'deploy_remote.py', 'deploy_ui_release.py', 'promote_backend_only.py',
        'accept_backend_staging.py', 'staging_network_probe.py',
        'refresh_provider_readiness.py',
        'package_backend_source.py', 'tn-assistant.env.example',
        'ZALO_VN_GATEWAY_PLAN.md', 'zalo-gateway.safe.example.yaml',
    )]
    files += [path for path in (ROOT / 'deploy' / 'zalo-gateway').glob('*')
              if path.is_file() and ((path.suffix == '.go' and not path.name.endswith('_test.go')) or path.name in
                                     {'go.mod', 'README.md', 'tn-zalo-gateway.service', 'zalo-gateway.env.example'})]
    if not files or any(not path.is_file() or path.is_symlink() for path in files):
        raise RuntimeError('Selected backend source is missing or contains a symlink')
    return sorted(set(files), key=lambda path: path.relative_to(ROOT).as_posix())


def add_bytes(archive, name, content):
    info = tarfile.TarInfo(name)
    info.size = len(content)
    info.mode = 0o644
    info.uid = info.gid = 0
    info.uname = info.gname = 'root'
    archive.addfile(info, io.BytesIO(content))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', args.release):
        raise RuntimeError('Unsafe release identifier')
    if not args.binary.is_file() or args.binary.is_symlink() or args.output.exists():
        raise RuntimeError('Binary missing or output already exists')
    files = selected_files()
    binary_hash = sha256(args.binary)
    metadata = {
        'release': args.release,
        'runtime': False,
        'private_content_scan': 'not_performed',
        'binary_sha256': binary_hash,
        'scope': 'backend-only source and Zalo gateway deployment files',
    }
    entries = [{'path': path.relative_to(ROOT).as_posix(), 'size': path.stat().st_size,
                'sha256': sha256(path)} for path in files]
    release_bytes = (json.dumps(metadata, indent=2) + '\n').encode()
    entries.append({'path': 'RELEASE.json', 'size': len(release_bytes),
                    'sha256': hashlib.sha256(release_bytes).hexdigest()})
    manifest_bytes = (json.dumps(entries, indent=2) + '\n').encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.output, 'x:gz') as archive:
        for path in files:
            add_bytes(archive, path.relative_to(ROOT).as_posix(), path.read_bytes())
        add_bytes(archive, 'RELEASE.json', release_bytes)
        add_bytes(archive, 'MANIFEST.json', manifest_bytes)
    print(json.dumps({'release': args.release, 'source_files': len(files),
                      'binary_sha256': binary_hash, 'archive_sha256': sha256(args.output),
                      'archive_bytes': args.output.stat().st_size}))


if __name__ == '__main__':
    main()
