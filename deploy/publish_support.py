"""Publish TN-owned static help and merge only its five URLs into public config."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
import urllib.request

WEB = Path('/www/wwwroot/tn.assistant')
DB = Path('/var/lib/tn-assistant/tn-assistant.sqlite')
URLS = {
    'faq_url': 'faq.html', 'contact_url': 'contact.html',
    'complaints_url': 'complaints.html',
    'vulnerable_customers_url': 'accessibility.html',
    'recording_help_url': 'recording.html',
}
FILES = set(URLS.values()) | {'style.css'}
ORIGIN = 'https://assistant.tnfastvn.com'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def merged_config(current):
    if not isinstance(current, dict) or not isinstance(current.get('support', {}), dict):
        raise RuntimeError('Public/support configuration must be an object')
    result = dict(current)
    result['support'] = {**current.get('support', {}),
                         **{key: ORIGIN + '/support/' + name for key, name in URLS.items()}}
    return result


def verified_files(directory, manifest):
    if not directory.is_dir() or directory.is_symlink() or directory.resolve() != directory.absolute():
        raise RuntimeError('Expected real static directory')
    if set(manifest) != FILES or {p.name for p in directory.iterdir()} != FILES:
        raise RuntimeError('Only the six reviewed support files may be published')
    for name in FILES:
        path = directory / name
        expected = manifest[name]
        if (not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{64}', expected)
                or not path.is_file() or path.is_symlink() or path.stat().st_size > 128 * 1024
                or digest(path) != expected):
            raise RuntimeError('Support content failed verification')


def configure(connection, backup, initial_config=None):
    connection.execute('BEGIN IMMEDIATE')
    try:
        row = connection.execute("SELECT data FROM records WHERE kind='config' AND id='public'").fetchone()
        if row is None and initial_config is None:
            raise RuntimeError('Existing public config required; refusing to invent defaults')
        before = json.loads(row[0]) if row else None
        result = merged_config(before if row else initial_config)
        with backup.open('x', encoding='utf-8') as output:
            json.dump({'before': before, 'after': result}, output, ensure_ascii=False)
        os.chmod(backup, 0o600)
        if row:
            connection.execute("UPDATE records SET data=? WHERE kind='config' AND id='public'",
                               (json.dumps(result, ensure_ascii=False),))
        else:
            connection.execute("INSERT INTO records(kind,id,owner,data,created_at) VALUES('config','public','',?,?)",
                               (json.dumps(result, ensure_ascii=False), dt.datetime.now(dt.timezone.utc).isoformat()))
        connection.commit()
    except BaseException:
        connection.rollback()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--initial-public-config', type=Path,
                        help='Explicit snapshot of effective /v1/config defaults, used only when no record exists')
    args = parser.parse_args()
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_-]{0,79}', args.release) or os.geteuid() != 0:
        raise RuntimeError('A safe release and root are required')
    stage = Path('/srv/tn-assistant/staging') / args.release
    if stage.resolve(strict=True) != stage:
        raise RuntimeError('Unexpected staging path')
    manifest = json.loads((stage / 'support-manifest.json').read_text())
    verified_files(stage / 'support', manifest)
    if WEB.resolve(strict=True) != WEB or DB.resolve(strict=True) != DB:
        raise RuntimeError('Unexpected webroot or database path')
    target = WEB / 'support'
    if target.exists() or target.is_symlink():
        raise RuntimeError('Support directory already exists; refusing overwrite')
    backup_dir = Path('/var/backups/tn-assistant') / args.release
    backup_dir.mkdir(mode=0o700)
    temporary = Path(tempfile.mkdtemp(prefix='.tn-support-', dir=stage))
    published = False
    configured = False
    try:
        for name in FILES:
            shutil.copyfile(stage / 'support' / name, temporary / name)
            os.chmod(temporary / name, 0o644)
        verified_files(temporary, manifest)
        os.chmod(temporary, 0o755)
        os.rename(temporary, target)
        published = True
        for name in sorted(FILES):
            request = urllib.request.Request(ORIGIN + '/support/' + name, headers={'Cache-Control': 'no-cache'})
            with urllib.request.urlopen(request, timeout=15) as response:
                if response.status != 200 or hashlib.sha256(response.read(128 * 1024 + 1)).hexdigest() != manifest[name]:
                    raise RuntimeError('Public support content differs from the reviewed files')
        with sqlite3.connect(DB, timeout=15) as connection:
            initial = json.loads(args.initial_public_config.read_text()) if args.initial_public_config else None
            configure(connection, backup_dir / 'public-config.json', initial)
        configured = True
        print(json.dumps({'status': 'published', 'webroot': str(WEB), 'configured_urls': URLS,
                          'facebook_changed': False, 'api_restarted': False,
                          'published_at': dt.datetime.now(dt.timezone.utc).isoformat()}))
    except BaseException:
        if published and not configured:
            # Delete only the newly created, still-identical six-file directory.
            verified_files(target, manifest)
            shutil.rmtree(target)
        raise
    finally:
        if temporary.exists():
            if temporary.parent != stage or temporary.is_symlink() or temporary.resolve() != temporary:
                raise RuntimeError('Unexpected temporary path')
            for path in temporary.iterdir():
                if path.name not in FILES or not path.is_file() or path.is_symlink():
                    raise RuntimeError('Unexpected temporary content')
                path.unlink()
            temporary.rmdir()


if __name__ == '__main__':
    main()
