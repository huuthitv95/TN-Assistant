"""Publish a versioned Android preview and its verified source without restarting the API."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tarfile
import tempfile

BASE = Path('/srv/tn-assistant')
STAGING = BASE / 'staging'
SOURCES = BASE / 'source'
DOWNLOADS = Path('/var/lib/tn-assistant/downloads')


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate_args(release, apk_name, source_hash, apk_hash):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', release):
        raise RuntimeError('A safe release id is required')
    if not re.fullmatch(r'tn-assistant-preview[0-9]+\.apk', apk_name):
        raise RuntimeError('APK name must use the versioned preview filename')
    if any(not re.fullmatch(r'[0-9a-f]{64}', value) for value in (source_hash, apk_hash)):
        raise RuntimeError('SHA-256 values must contain 64 lowercase hexadecimal characters')


def validate_source_version(source_meta, apk_version):
    version_name = re.search(r'\bversionName\s*=\s*"(\d{2})\.(\d{2})\.(\d{2})\.(\d{3})"', source_meta)
    version_code = re.search(r'\bversionCode\s*=\s*(\d{8})\b', source_meta)
    if not version_name or not version_code:
        raise RuntimeError('APK filename and source version do not match')
    year, month, day, build = version_name.groups()
    expected_code = f'{year}{month}{day}{int(build) % 100:02d}'
    if build != apk_version or version_code.group(1) != expected_code:
        raise RuntimeError('APK filename and source version do not match')


def inspect_source_archive(path, release):
    with tarfile.open(path, 'r:gz') as archive:
        members = archive.getmembers()
        by_name = {}
        for member in members:
            name = member.name
            relative = PurePosixPath(name)
            if (not member.isfile() or relative.is_absolute() or '..' in relative.parts
                    or not relative.parts or '\\' in name or name in by_name):
                raise RuntimeError('Source bundle contains an unsafe or duplicate path')
            by_name[name] = member
        manifest_member = by_name.get('MANIFEST.json')
        if not manifest_member:
            raise RuntimeError('Source bundle manifest is missing')
        manifest_stream = archive.extractfile(manifest_member)
        manifest = json.load(manifest_stream)
        entries = manifest if isinstance(manifest, list) else None
        if not isinstance(entries, list) or not entries:
            raise RuntimeError('Source bundle manifest has no file entries')
        names = [entry.get('path') for entry in entries]
        if (any(not isinstance(name, str) or not name for name in names)
                or 'MANIFEST.json' in names or len(names) != len(set(names))
                or set(by_name) != set(names) | {'MANIFEST.json'}):
            raise RuntimeError('Source bundle contents differ from its manifest')
        content = {}
        for entry in entries:
            name = entry.get('path')
            relative = PurePosixPath(name or '')
            if relative.is_absolute() or '..' in relative.parts or '\\' in (name or ''):
                raise RuntimeError('Source manifest contains an unsafe path')
            stream = archive.extractfile(by_name[name])
            data = stream.read()
            if len(data) != entry.get('size') or sha256(data) != entry.get('sha256'):
                raise RuntimeError('Source file does not match its manifest: ' + name)
            content[name] = data
        metadata = json.loads(content['RELEASE.json'])
        if metadata.get('release') != release or metadata.get('runtime') is not False:
            raise RuntimeError('Source release metadata does not match the requested preview')
        mark_path = 'android/app/src/independent/res/drawable-nodpi/tn_user_welcome_circle.png'
        license_path = 'android/app/src/independent/assets/licenses/tn-welcome-icon.json'
        mark = content.get(mark_path)
        license_metadata = json.loads(content[license_path]) if license_path in content else {}
        if not mark or sha256(mark) != license_metadata.get('sha256'):
            raise RuntimeError('Source bundle does not contain the licensed owner-supplied welcome icon')
        return content


def write_source(content, target):
    if not target.is_dir() or any(target.iterdir()):
        raise RuntimeError('Source staging directory is not empty and private')
    for name, data in content.items():
        destination = target.joinpath(*PurePosixPath(name).parts)
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with destination.open('xb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(destination, 0o600)


def prepare_download(source, downloads, apk_name, metadata):
    payloads = (
        (apk_name, source.read_bytes()),
        (apk_name + '.json', (json.dumps(metadata, indent=2) + '\n').encode()),
        (apk_name + '.sha256', (metadata['apk_sha256'] + '  ' + apk_name + '\n').encode()),
    )
    temporary = []
    try:
        for _, payload in payloads:
            descriptor, name = tempfile.mkstemp(prefix='.android-publish-', dir=downloads)
            temporary_path = Path(name)
            temporary.append(temporary_path)
            with os.fdopen(descriptor, 'wb') as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary_path, 0o644)
        return temporary, payloads
    except BaseException:
        for path in temporary:
            path.unlink(missing_ok=True)
        raise


def cleanup_staging(stage):
    """Remove only the two verified inputs after a successful atomic publish."""
    try:
        if (stage.parent != STAGING or stage.is_symlink()
                or stage.resolve(strict=True) != stage.absolute()):
            return False
        expected = {'source.tar.gz', 'tn-assistant-preview.apk'}
        entries = list(stage.iterdir())
        if {path.name for path in entries} != expected:
            return False
        for path in entries:
            if (path.is_symlink() or not path.is_file()
                    or path.resolve(strict=True) != path.absolute()):
                return False
        for path in entries:
            path.unlink()
        stage.rmdir()
        return True
    except OSError:
        return False


def publish(args):
    stage = STAGING / args.release
    source_archive = stage / 'source.tar.gz'
    apk_path = stage / 'tn-assistant-preview.apk'
    target_source = SOURCES / args.release
    apk_targets = [DOWNLOADS / (args.apk_name + suffix) for suffix in ('', '.json', '.sha256')]
    for directory in (BASE, STAGING, stage, SOURCES, DOWNLOADS):
        if not directory.is_dir() or directory.resolve(strict=True) != directory.absolute():
            raise RuntimeError('Expected an existing directory without symlink components')
    for path in (source_archive, apk_path):
        if (not path.exists() or not path.is_file() or path.is_symlink()
                or path.resolve(strict=True) != path.absolute()):
            raise RuntimeError('Expected regular staged release files')
    if target_source.exists() or target_source.is_symlink() or any(path.exists() or path.is_symlink() for path in apk_targets):
        raise RuntimeError('A release or download target already exists; refusing overwrite')
    if sha256(source_archive.read_bytes()) != args.source_sha256:
        raise RuntimeError('Source archive checksum mismatch')
    apk_sha = sha256(apk_path.read_bytes())
    if apk_sha != args.apk_sha256:
        raise RuntimeError('APK checksum mismatch')
    content = inspect_source_archive(source_archive, args.release)
    source_meta = content.get('android/app/build.gradle.kts', b'').decode(errors='replace')
    apk_version = re.search(r'preview([0-9]+)\.apk$', args.apk_name)
    if not apk_version:
        raise RuntimeError('APK filename and source version do not match')
    validate_source_version(source_meta, apk_version.group(1))
    metadata = {
        'release': args.release,
        'apk_name': args.apk_name,
        'apk_sha256': apk_sha,
        'source_sha256': args.source_sha256,
        'published_at': dt.datetime.now(dt.timezone.utc).isoformat(),
        'api_service_restarted': False,
    }
    lock_path = BASE / '.android-publish.lock'
    descriptor = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    source_temp = Path(tempfile.mkdtemp(prefix='.android-source-', dir=SOURCES))
    temporary_downloads = []
    linked = []
    try:
        import fcntl
        with os.fdopen(descriptor, 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            write_source(content, source_temp)
            temporary_downloads, payloads = prepare_download(apk_path, DOWNLOADS, args.apk_name, metadata)
            for temporary, (name, _) in zip(temporary_downloads, payloads):
                destination = DOWNLOADS / name
                if destination.exists() or destination.is_symlink():
                    raise RuntimeError('Download target appeared during publish; refusing overwrite')
                os.link(temporary, destination)
                linked.append((destination, destination.stat().st_ino))
            if target_source.exists() or target_source.is_symlink():
                raise RuntimeError('Source destination appeared during publish; refusing overwrite')
            os.rename(source_temp, target_source)
            source_temp = None
        staging_cleaned = cleanup_staging(stage)
        return {
            **metadata,
            'source_directory': str(target_source),
            'published_files': [str(path) for path in apk_targets],
            'staging_cleaned': staging_cleaned,
        }
    except BaseException:
        for destination, inode in linked:
            if destination.is_file() and not destination.is_symlink() and destination.stat().st_ino == inode:
                destination.unlink()
        if source_temp and source_temp.exists():
            shutil.rmtree(source_temp)
        raise
    finally:
        for path in temporary_downloads:
            path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--apk-name', required=True)
    parser.add_argument('--source-sha256', required=True)
    parser.add_argument('--apk-sha256', required=True)
    args = parser.parse_args()
    validate_args(args.release, args.apk_name, args.source_sha256, args.apk_sha256)
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise RuntimeError('Run locally on the existing Linux VPS as root')
    os.umask(0o077)
    print(json.dumps(publish(args)), flush=True)


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(json.dumps({'publish': 'failed', 'error': message}), flush=True)
        raise SystemExit(1)
