"""Incrementally deploy a verified binary/source/APK without changing live configuration or data."""
import argparse
from contextlib import closing
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import sqlite3
import stat
import subprocess
import tarfile
import time

import deploy_remote as remote

BASE = remote.BASE
ENV = remote.ENV
PROVIDERS = remote.PROVIDERS
DB = remote.DB
WEB = remote.WEB
AA_PANEL_WEBROOT = Path('/www/wwwroot/tn.assistant')
DOWNLOADS = Path('/var/lib/tn-assistant/downloads')
BACKUPS = Path('/var/backups/tn-assistant')
SERVICE = 'tn-assistant.service'
HEALTH = 'http://127.0.0.1:8094/v1/health'


def validate_arguments(release, apk_name, hashes):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', release):
        raise RuntimeError('A safe release id is required')
    if not re.fullmatch(r'tn-assistant-preview[0-9]+(?:-[A-Za-z0-9_-]+)?\.apk', apk_name):
        raise RuntimeError('APK name must be a versioned tn-assistant-previewNNN.apk name')
    if any(not re.fullmatch(r'[0-9a-f]{64}', value) for value in hashes):
        raise RuntimeError('Every supplied SHA-256 must contain 64 lowercase hexadecimal characters')


def absent(path):
    if path.exists() or path.is_symlink():
        raise RuntimeError('Destination already exists; refusing overwrite: ' + str(path))


def real_directory(path):
    if not path.is_dir() or path.resolve(strict=True) != path.absolute():
        raise RuntimeError('Expected an existing directory without symlink components: ' + str(path))


def regular_file(path):
    if not path.is_file() or path.is_symlink() or path.resolve(strict=True) != path.absolute():
        raise RuntimeError('Expected an existing regular file without symlink components: ' + str(path))


def private_directory(path):
    if not path.exists():
        path.mkdir(mode=0o700)
    real_directory(path)
    if path.stat().st_uid != 0 or stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise RuntimeError('Private directory must be root owned with mode 0700: ' + str(path))


def systemd_properties():
    names = ('LoadState', 'ActiveState', 'SubState', 'User', 'Group', 'WorkingDirectory',
             'ExecStart', 'EnvironmentFiles', 'FragmentPath', 'MainPID')
    result = remote.run(['systemctl', 'show', SERVICE, '--no-pager', '--property=' + ','.join(names)])
    return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)


def validate_service(properties):
    expected = {'LoadState': 'loaded', 'ActiveState': 'active', 'SubState': 'running',
                'User': 'tnassistant', 'Group': 'tnassistant', 'WorkingDirectory': str(BASE / 'current'),
                'EnvironmentFiles': str(ENV) + ' (ignore_errors=no)'}
    if any(properties.get(key) != value for key, value in expected.items()):
        raise RuntimeError('Effective systemd service differs from the existing TN current/env contract')
    command = properties.get('ExecStart', '')
    binary = re.escape(str(BASE / 'current/tn-assistant'))
    if not re.search(r'(?:^|[ {;])path=' + binary + r'\s*;', command) or not re.search(r'argv\[\]=' + binary + r' serve\s*;', command):
        raise RuntimeError('Effective ExecStart must run current/tn-assistant serve without extra arguments')
    if not properties.get('MainPID', '').isdigit() or int(properties['MainPID']) <= 1:
        raise RuntimeError('Expected a running backend process')
    fragment = Path(properties.get('FragmentPath', ''))
    if not fragment.is_absolute():
        raise RuntimeError('Expected an absolute systemd unit fragment path')
    regular_file(fragment)


def readiness_passes(status, body):
    return status == 200 and isinstance(body, dict) and body.get('ready') is True and body.get('status') == 'ready'


def process_executable_matches(pid, release):
    """Compare through the process root, since Fly's systemd process runs under /data/rootfs."""
    expected = PurePosixPath(str(release)) / 'tn-assistant'
    process_exe = f'/proc/{pid}/exe'
    process_rooted_binary = f'/proc/{pid}/root/{expected.as_posix().lstrip("/")}'
    try:
        return os.path.samefile(process_exe, process_rooted_binary)
    except OSError:
        return False


def verify_process(properties, release, environment):
    pid = int(properties['MainPID'])
    if not process_executable_matches(pid, release):
        raise RuntimeError('Running backend executable does not match the expected release')
    effective = {}
    for item in Path(f'/proc/{pid}/environ').read_bytes().split(b'\0'):
        if b'=' in item:
            key, value = item.split(b'=', 1)
            effective[key.decode()] = value.decode()
    for key in ('TN_DB', 'TN_PROVIDER_FILE', 'TN_STATIC_DIR', 'TN_DOWNLOAD_DIR', 'TN_RUNTIME_MODE', 'TN_ADDR', 'TN_AI_MASTER_KEY_FILE'):
        if effective.get(key, '') != environment.get(key, ''):
            raise RuntimeError('Running process and environment file disagree for ' + key)


def database_state(connection):
    if connection.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
        raise RuntimeError('SQLite integrity check failed')
    schema = connection.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name").fetchall()
    tables = [row[1] for row in schema if row[0] == 'table']
    required = {'schema_migrations', 'users', 'devices', 'sessions', 'records', 'payments', 'entitlements'}
    if not required.issubset(tables):
        raise RuntimeError('Existing application database is incomplete')
    if connection.execute('SELECT version FROM schema_migrations ORDER BY version').fetchall() != [(1,)]:
        raise RuntimeError('This incremental deployment requires the existing version 1 database')
    runtime = connection.execute("SELECT data FROM records WHERE kind='config' AND id='runtime'").fetchone()
    if not runtime or json.loads(runtime[0]).get('runtime_mode') != 'independent':
        raise RuntimeError('Independent runtime must already be initialized; deployment will not initialize it')
    counts = {table: connection.execute('SELECT count(*) FROM "' + table.replace('"', '""') + '"').fetchone()[0] for table in tables}
    def fingerprint(query):
        rows = connection.execute(query).fetchall()
        return hashlib.sha256(json.dumps(rows, ensure_ascii=True, default=lambda value: {'bytes': value.hex()}, separators=(',', ':')).encode()).hexdigest()
    table_hashes = {}
    for table in tables:
        quoted = '"' + table.replace('"', '""') + '"'
        columns = [row[1] for row in connection.execute('PRAGMA table_info(' + quoted + ')')]
        if not columns:
            raise RuntimeError('Application table has no columns')
        order = ','.join('"' + column.replace('"', '""') + '"' for column in columns)
        table_hashes[table] = fingerprint('SELECT * FROM ' + quoted + ' ORDER BY ' + order)
    return {'integrity': 'ok', 'counts': counts, 'table_sha256': table_hashes,
            'schema_sha256': hashlib.sha256(json.dumps(schema, separators=(',', ':')).encode()).hexdigest(),
            'users_sha256': fingerprint('SELECT * FROM users ORDER BY id'),
            'sessions_sha256': fingerprint('SELECT * FROM sessions ORDER BY id'),
            'configuration_sha256': fingerprint("SELECT * FROM records WHERE kind='config' ORDER BY id")}


def inspect_database(path, backup=None):
    with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as connection:
        connection.execute('PRAGMA query_only=ON')
        connection.execute('BEGIN')
        state = database_state(connection)
        if backup is not None:
            absent(backup)
            with closing(sqlite3.connect(backup)) as destination:
                connection.backup(destination)
                if database_state(destination) != state:
                    raise RuntimeError('Consistent SQLite backup verification failed')
            os.chmod(backup, 0o600)
        return state


def validate_archive(archive):
    with tarfile.open(archive, 'r:gz') as source:
        seen = set()
        for member in source.getmembers():
            relative = PurePosixPath(member.name)
            if (not member.isfile() or relative.is_absolute() or '..' in relative.parts
                    or '\\' in member.name or relative.as_posix() != member.name or member.name in seen):
                raise RuntimeError('Archive contains an unsafe or duplicate entry')
            seen.add(member.name)


def verify_package_metadata(directory, release, runtime):
    metadata = json.loads((directory / 'RELEASE.json').read_text())
    if metadata.get('release') != release or metadata.get('runtime') is not runtime or metadata.get('source_contains_secrets') is not False:
        raise RuntimeError('Release metadata does not match the requested bundle')


def elf_identity(path):
    with path.open('rb') as stream:
        header = stream.read(20)
    if len(header) != 20 or header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[16:18], 'little') not in (2, 3):
        raise RuntimeError('Expected a 64-bit little-endian Linux ELF binary')
    return int.from_bytes(header[18:20], 'little')


def atomic_switch(current, expected, target):
    if not current.is_symlink() or current.resolve(strict=True) != expected:
        raise RuntimeError('Current release changed concurrently; refusing symlink replacement')
    link = current.parent / ('.ui-current-' + secrets.token_hex(12))
    try:
        link.symlink_to(target, target_is_directory=True)
        os.replace(link, current)
    finally:
        if link.is_symlink():
            link.unlink()


def wait_healthy(release, environment):
    for attempt in range(30):
        try:
            code, health = remote.request(HEALTH)
            if code == 200 and isinstance(health, dict) and health.get('status') == 'ok':
                ready_code, readiness = remote.request('http://127.0.0.1:8094/v1/ready')
                if readiness_passes(ready_code, readiness):
                    properties = systemd_properties()
                    validate_service(properties)
                    verify_process(properties, release, environment)
                    return {'status': 'ok', 'running_release': release.name, 'readiness': readiness}
        except (OSError, ValueError, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError('Backend failed health, readiness and running-executable checks')


def activate(current, previous, candidate, verify, recover):
    """Rollback only the executable symlink/service; never restore or write the live database."""
    atomic_switch(current, previous, candidate)
    try:
        remote.run(['systemctl', 'restart', SERVICE])
        return verify()
    except BaseException as error:
        try:
            atomic_switch(current, candidate, previous)
            remote.run(['systemctl', 'restart', SERVICE])
            recover()
        except BaseException as rollback_error:
            raise RuntimeError('Candidate failed; executable rollback failed (' + type(rollback_error).__name__ + '); live DB was not restored') from error
        raise RuntimeError('Candidate failed; previous executable restored; live DB was not restored (' + type(error).__name__ + ')') from error


def static_inventory(directory, public=False):
    """Hash a static tree and reject symlinks before replacement or backup."""
    real_directory(directory)
    if public:
        unexpected = {path.name for path in directory.iterdir()} - remote.OLD_STATIC
        if unexpected:
            raise RuntimeError('Unclassified public static paths; refusing replacement: ' + ','.join(sorted(unexpected)))
    result = {}
    for path in sorted(directory.rglob('*')):
        relative = path.relative_to(directory).as_posix()
        if path.is_symlink():
            raise RuntimeError('Static tree contains a symlink: ' + relative)
        if path.is_dir():
            result[relative] = {'type': 'directory'}
        elif path.is_file():
            result[relative] = {'type': 'file', 'size': path.stat().st_size, 'sha256': remote.sha(path)}
        else:
            raise RuntimeError('Static tree contains a non-regular entry: ' + relative)
    return result


def backup_static(source, destination, public=False):
    """Make and verify a private copy before touching the live webroot."""
    expected = static_inventory(source, public=public)
    absent(destination)
    shutil.copytree(source, destination)
    for path in [destination] + list(destination.rglob('*')):
        os.chmod(path, 0o700 if path.is_dir() else 0o600)
    if static_inventory(destination) != expected:
        raise RuntimeError('Static webroot backup checksum mismatch')
    return expected


def verify_static_install(source):
    """Confirm local files and the public reverse proxy serve the packaged dashboard."""
    expected = static_inventory(source)
    deployed = static_inventory(WEB, public=True)
    if any(deployed.get(name) != entry for name, entry in expected.items()):
        raise RuntimeError('Installed dashboard differs from the release')
    from urllib.parse import quote
    for name, entry in expected.items():
        if entry['type'] != 'file' or name.split('/', 1)[0] in remote.KEEP:
            continue
        url = 'https://assistant.tnfastvn.com/' + quote(name, safe='/')
        code, body = remote.request(url, decode_json=False)
        if code != 200 or not isinstance(body, bytes) or hashlib.sha256(body).hexdigest() != entry['sha256']:
            raise RuntimeError('Public dashboard content differs from the release')
    return {'static_verified': True, 'static_file_count': sum(item['type'] == 'file' for item in expected.values())}


def publish_downloads(stage_apk, downloads, apk_name, metadata):
    """Hard-link completed temporary files into new names, atomically refusing existing destinations."""
    targets = [downloads / apk_name, downloads / (apk_name + '.json'), downloads / (apk_name + '.sha256')]
    for target in targets:
        absent(target)
    temporary, published = [], []
    try:
        for index, target in enumerate(targets):
            path = downloads / ('.ui-publish-' + secrets.token_hex(12))
            temporary.append(path)
            with path.open('xb') as output:
                if index == 0:
                    with stage_apk.open('rb') as source:
                        shutil.copyfileobj(source, output)
                elif index == 1:
                    output.write((json.dumps(metadata, indent=2) + '\n').encode())
                else:
                    output.write((metadata['apk_sha256'] + '  ' + apk_name + '\n').encode())
                output.flush()
                os.fsync(output.fileno())
            os.chmod(path, 0o644)
        if remote.sha(temporary[0]) != metadata['apk_sha256']:
            raise RuntimeError('Published APK copy checksum mismatch')
        for source, target in zip(temporary, targets):
            os.link(source, target)
            published.append((target, source.stat().st_ino))
        return [str(target) for target in targets]
    except BaseException:
        for target, inode in published:
            if target.is_file() and not target.is_symlink() and target.stat().st_ino == inode:
                target.unlink()
        raise
    finally:
        for path in temporary:
            path.unlink(missing_ok=True)


def deploy(args):
    if WEB != AA_PANEL_WEBROOT or WEB.is_relative_to(Path('/root')):
        raise RuntimeError('Website files must stay in the confirmed aaPanel webroot /www/wwwroot/tn.assistant')
    stage = BASE / 'staging' / args.release
    release = BASE / 'releases' / args.release
    sources = BASE / 'source' / args.release
    backup = BACKUPS / args.release
    current = BASE / 'current'
    for path in (BASE, BASE / 'staging', stage, BASE / 'releases', BASE / 'source', DOWNLOADS, DB.parent):
        real_directory(path)
    static_before = static_inventory(WEB, public=True)
    private_directory(BACKUPS)
    for path in (release, sources, backup, stage / 'runtime-unpacked', stage / 'source-unpacked', stage / 'UI_DEPLOYMENT.json'):
        absent(path)
    for suffix in ('', '.json', '.sha256'):
        absent(DOWNLOADS / (args.apk_name + suffix))
    if not current.is_symlink():
        raise RuntimeError('Existing current must be a release symlink')
    previous = current.resolve(strict=True)
    if previous.parent != BASE / 'releases':
        raise RuntimeError('Existing current must point directly into the release directory')
    real_directory(previous)
    for path in (ENV, PROVIDERS, DB, previous / 'tn-assistant', stage / 'runtime.tar.gz', stage / 'source.tar.gz', stage / 'tn-assistant-preview.apk'):
        regular_file(path)
    environment = remote.read_env()
    expected_env = {'TN_DB': str(DB), 'TN_PROVIDER_FILE': str(PROVIDERS), 'TN_STATIC_DIR': str(WEB),
                    'TN_DOWNLOAD_DIR': str(DOWNLOADS), 'TN_RUNTIME_MODE': 'independent', 'TN_ADDR': '127.0.0.1:8094'}
    # Preserve current configurations that rely on main.go's independent-mode default.
    effective_env = dict(environment)
    effective_env.setdefault('TN_RUNTIME_MODE', 'independent')
    if any(effective_env.get(key) != value for key, value in expected_env.items()):
        raise RuntimeError('Live environment paths/mode differ from this incremental deployment contract')
    key = Path(environment.get('TN_AI_MASTER_KEY_FILE') or str(DB.parent / '.runtime/tn-ai-master.key'))
    if not key.is_absolute() or WEB == key or WEB in key.parents or DOWNLOADS in key.parents:
        raise RuntimeError('AI master key must be an absolute private path outside public directories')
    regular_file(key)
    if key.stat().st_size != 32:
        raise RuntimeError('Existing AI master key must contain exactly 32 bytes; it will not be created or replaced')
    private_inputs = {'environment': ENV, 'providers': PROVIDERS, 'master_key': key}
    fingerprints = {name: remote.sha(path) for name, path in private_inputs.items()}
    properties = systemd_properties()
    validate_service(properties)
    verify_process(properties, previous, environment)
    wait_healthy(previous, environment)
    inspect_database(DB)
    if remote.sha(stage / 'tn-assistant-preview.apk') != args.apk_sha256:
        raise RuntimeError('APK checksum mismatch')
    runtime, source = stage / 'runtime-unpacked', stage / 'source-unpacked'
    for archive, target, checksum, is_runtime in ((stage / 'runtime.tar.gz', runtime, args.runtime_sha256, True),
                                                 (stage / 'source.tar.gz', source, args.source_sha256, False)):
        validate_archive(archive)
        remote.unpack(archive, target, checksum)
        verify_package_metadata(target, args.release, is_runtime)
    regular_file(runtime / 'tn-assistant')
    candidate_static = static_inventory(runtime / 'static')
    if not candidate_static or not any(entry['type'] == 'file' for entry in candidate_static.values()):
        raise RuntimeError('Candidate dashboard is empty')
    if elf_identity(runtime / 'tn-assistant') != elf_identity(previous / 'tn-assistant'):
        raise RuntimeError('Candidate and existing ELF architectures differ')
    if not (source / 'deploy/package_release.py').is_file() or not (source / 'android/app/src/independent/AndroidManifest.xml').is_file():
        raise RuntimeError('Expected the independently packaged source bundle')
    shutil.copytree(runtime, release)
    for path in [release] + list(release.rglob('*')):
        os.chmod(path, 0o755 if path.is_dir() or path.name == 'tn-assistant' else 0o644)
    shutil.copytree(source, sources)
    for path in [sources] + list(sources.rglob('*')):
        os.chmod(path, 0o700 if path.is_dir() else 0o600)
    remote.run(['runuser', '-u', 'tnassistant', '--', 'test', '-x', str(release / 'tn-assistant')])
    backup.mkdir(mode=0o700)
    stopped = False
    try:
        stopped = True
        remote.run(['systemctl', 'stop', SERVICE])
        if systemd_properties().get('ActiveState') not in ('inactive', 'failed'):
            raise RuntimeError('Service did not stop; backup/switch refused')
        if any(remote.sha(path) != fingerprints[name] for name, path in private_inputs.items()):
            raise RuntimeError('Live private configuration changed during staging')
        if static_inventory(WEB, public=True) != static_before:
            raise RuntimeError('Public dashboard changed during staging; backup/switch refused')
        before = inspect_database(DB, backup / 'database.sqlite')
        backup_static(WEB, backup / 'static', public=True)
        for name, path in private_inputs.items():
            target = backup / (name + '.private')
            shutil.copy2(path, target)
            os.chmod(target, 0o600)
            if remote.sha(target) != fingerprints[name]:
                raise RuntimeError('Private configuration backup checksum mismatch')
        shutil.copy2(Path(properties['FragmentPath']), backup / 'tn-assistant.service')
        (backup / 'DATABASE_STATE.json').write_text(json.dumps(before, indent=2) + '\n')
        (backup / 'SERVICE_STATE.json').write_text(json.dumps(properties, indent=2) + '\n')
        (backup / 'PRIVATE_PATHS.json').write_text(json.dumps({name: str(path) for name, path in private_inputs.items()}, indent=2) + '\n')
        for path in [backup] + list(backup.rglob('*')):
            os.chmod(path, 0o700 if path.is_dir() else 0o600)
        entries = []
        for path in backup.rglob('*'):
            if path.is_file():
                entries.append({'path': path.relative_to(backup).as_posix(), 'sha256': remote.sha(path), 'size': path.stat().st_size})
        (backup / 'BACKUP_MANIFEST.json').write_text(json.dumps(entries, indent=2) + '\n')
        os.chmod(backup / 'BACKUP_MANIFEST.json', 0o600)
        metadata = {'release': args.release, 'apk_name': args.apk_name, 'apk_sha256': args.apk_sha256,
                    'source_sha256': args.source_sha256, 'runtime_sha256': args.runtime_sha256,
                    'published_at': dt.datetime.now(dt.timezone.utc).isoformat()}
        static_changed = False

        def restore_static():
            nonlocal static_changed
            if not static_changed:
                return
            remote.static_install(backup / 'static')
            if static_inventory(WEB, public=True) != static_before:
                raise RuntimeError('Previous dashboard was not restored exactly')
            static_changed = False

        def verify_candidate():
            nonlocal static_changed
            health = wait_healthy(release, environment)
            static_changed = True
            remote.static_install(runtime / 'static')
            static_result = verify_static_install(runtime / 'static')
            if inspect_database(DB) != before:
                raise RuntimeError('Database schema/counts/users/sessions/configuration changed; binary rollback required')
            if any(remote.sha(path) != fingerprints[name] for name, path in private_inputs.items()):
                raise RuntimeError('Private configuration changed; binary rollback required')
            published = publish_downloads(stage / 'tn-assistant-preview.apk', DOWNLOADS, args.apk_name, metadata)
            return {'health': health, 'published_files': published, **static_result}

        def recover_previous():
            restore_static()
            return wait_healthy(previous, environment)

        result = activate(current, previous, release, verify_candidate, recover_previous)
        stopped = False
    except BaseException as deployment_error:
        # If failure preceded activation, start the unchanged old executable. Never replace DB/configuration.
        rollback_error = None
        if stopped and current.is_symlink() and current.resolve(strict=True) == previous:
            if 'restore_static' in locals():
                try:
                    restore_static()
                except BaseException as error:
                    rollback_error = error
            remote.run(['systemctl', 'start', SERVICE], check=False)
        if rollback_error is not None:
            raise RuntimeError('Deployment failed and static rollback failed (' + type(rollback_error).__name__ + ')') from deployment_error
        raise
    result.update(metadata)
    result.update({'previous_release': previous.name, 'backup': str(backup), 'database_integrity': 'ok',
                   'data_counts_unchanged': True, 'schema_unchanged': True, 'users_and_sessions_unchanged': True,
                   'private_configuration_unchanged': True, 'configuration_migration': False,
                   'password_reset': False, 'static_replaced': True})
    (stage / 'UI_DEPLOYMENT.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--runtime-sha256', required=True, type=str.lower)
    parser.add_argument('--source-sha256', required=True, type=str.lower)
    parser.add_argument('--apk-sha256', required=True, type=str.lower)
    parser.add_argument('--apk-name', default='tn-assistant-preview122.apk')
    args = parser.parse_args()
    validate_arguments(args.release, args.apk_name, (args.runtime_sha256, args.source_sha256, args.apk_sha256))
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise RuntimeError('Run locally on the existing Linux VPS as root')
    os.umask(0o077)
    real_directory(BASE)
    import fcntl
    descriptor = os.open(BASE / '.ui-deploy.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print(json.dumps(deploy(args)), flush=True)


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        # Never echo subprocess output, environment contents or database rows.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(json.dumps({'deployment': 'failed', 'error': message, 'live_database_restored': False}), flush=True)
        raise SystemExit(1)
