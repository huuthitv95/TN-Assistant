"""Install a verified TN release on its existing VPS; secrets are read only on stdin."""
import argparse
import base64
from contextlib import closing
import datetime as dt
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import ssl
import struct
import subprocess
import tarfile
import time
import urllib.error
import urllib.request

BASE = Path('/srv/tn-assistant')
ENV = Path('/etc/tn-assistant/tn-assistant.env')
PROVIDERS = Path('/etc/tn-assistant/providers.json')
DB = Path('/var/lib/tn-assistant/tn-assistant.sqlite')
WEB = Path('/www/wwwroot/tn.assistant')
AA_PANEL_WEBROOT = Path('/www/wwwroot/tn.assistant')
KEEP = {'.well-known', '.htaccess', '.user.ini'}
OLD_STATIC = KEEP | {'assets', 'fonts', 'icons', 'index.html', 'manifest.webmanifest'}
PRESERVED_DATA_TABLES = ('users', 'devices', 'payments', 'entitlements')


def run(args, **kwargs):
    kwargs.setdefault('check', True)
    return subprocess.run(args, capture_output=True, text=True, **kwargs)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def preserved_data_counts(connection):
    return {table: connection.execute('SELECT count(*) FROM ' + table).fetchone()[0] for table in PRESERVED_DATA_TABLES}


def unpack(archive, destination, expected):
    if sha(archive) != expected:
        raise RuntimeError('Archive checksum mismatch: ' + archive.name)
    destination.mkdir(mode=0o755)
    with tarfile.open(archive, 'r:gz') as source:
        members = source.getmembers()
        for member in members:
            path = Path(member.name)
            if not member.isfile() or path.is_absolute() or '..' in path.parts:
                raise RuntimeError('Unexpected archive entry')
        source.extractall(destination, members=members, filter='data')
    manifest = json.loads((destination / 'MANIFEST.json').read_text())
    expected_files = {entry['path'] for entry in manifest} | {'MANIFEST.json'}
    actual = {p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()}
    if actual != expected_files:
        raise RuntimeError('Manifest file list mismatch')
    for entry in manifest:
        path = destination / entry['path']
        if path.stat().st_size != entry['size'] or sha(path) != entry['sha256']:
            raise RuntimeError('Manifest checksum mismatch')


def read_env():
    return {line.split('=', 1)[0]: line.split('=', 1)[1].strip().strip(chr(34)).strip(chr(39)) for line in ENV.read_text().splitlines() if line.strip() and not line.lstrip().startswith('#') and '=' in line}


def request(path, method='GET', data=None, token=None, extra=None, decode_json=True):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    headers.update(extra or {})
    payload = json.dumps(data).encode() if data is not None else None
    try:
        response = urllib.request.urlopen(urllib.request.Request(path, payload, headers, method=method), timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        value = json.loads(raw) if decode_json and 'application/json' in response.headers.get('Content-Type', '') else raw
        return response.status, value


def static_install(source):
    for path in WEB.iterdir():
        if path.name in KEEP:
            continue
        if path.parent != WEB:
            raise RuntimeError('Unexpected static path')
        if path.is_dir() and not path.is_symlink():
            shutil.rmtree(path)
        else:
            path.unlink()
    for path in source.iterdir():
        if path.name in KEEP:
            continue
        if path.is_dir():
            shutil.copytree(path, WEB / path.name)
        else:
            shutil.copy2(path, WEB / path.name)
    for path in [WEB] + [p for p in WEB.rglob('*') if '.well-known' not in p.relative_to(WEB).parts and p.name not in KEEP]:
        if path.is_symlink():
            raise RuntimeError('Unexpected symlink in public static content')
        os.chmod(path, 0o755 if path.is_dir() else 0o644)


def websocket_probe(token, host='assistant.tnfastvn.com', port=443, secure=True):
    key = base64.b64encode(secrets.token_bytes(16)).decode()
    with socket.create_connection((host, port), timeout=12) as raw_socket:
        connection_context = ssl.create_default_context().wrap_socket(raw_socket, server_hostname=host) if secure else closing(raw_socket)
        with connection_context as connection:
            host_header = host if port in (80, 443) else f'{host}:{port}'
            connection.sendall(('GET /v1/speech/stream HTTP/1.1\r\nHost: ' + host_header + '\r\nAuthorization: Bearer ' + token + '\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ' + key + '\r\n\r\n').encode())
            buffer = b''
            while b'\r\n\r\n' not in buffer:
                chunk = connection.recv(4096)
                if not chunk or len(buffer) > 32768:
                    raise RuntimeError('WebSocket handshake ended before headers')
                buffer += chunk
            header, buffer = buffer.split(b'\r\n\r\n', 1)
            lines = header.decode().split('\r\n')
            headers = {line.split(':', 1)[0].lower(): line.split(':', 1)[1].strip() for line in lines[1:] if ':' in line}
            expected = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            if lines[0].split()[1] != '101' or headers.get('sec-websocket-accept') != expected:
                raise RuntimeError('WebSocket upgrade handshake failed')
            def read_exact(count):
                nonlocal buffer
                while len(buffer) < count:
                    chunk = connection.recv(4096)
                    if not chunk:
                        raise RuntimeError('WebSocket closed before expected speech event')
                    buffer += chunk
                value, buffer = buffer[:count], buffer[count:]
                return value

            def send_text(value):
                payload = json.dumps(value, separators=(',', ':')).encode()
                mask = secrets.token_bytes(4)
                size = len(payload)
                if size < 126:
                    frame = bytes((0x81, 0x80 | size))
                elif size <= 65535:
                    frame = bytes((0x81, 0xfe)) + struct.pack('!H', size)
                else:
                    frame = bytes((0x81, 0xff)) + struct.pack('!Q', size)
                masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
                connection.sendall(frame + mask + masked)

            send_text({'type': 'start', 'session_id': 'tn-release-probe', 'sample_rate': 16000, 'channels': 1, 'encoding': 'pcm_s16le'})
            first, second = read_exact(2)
            length = second & 0x7f
            if length == 126:
                length = struct.unpack('!H', read_exact(2))[0]
            elif length == 127:
                length = struct.unpack('!Q', read_exact(8))[0]
            if length > 65536 or second & 0x80 or first & 0x0f != 1:
                raise RuntimeError('Unexpected WebSocket frame')
            event = json.loads(read_exact(length))
            code = event.get('error', {}).get('code')
            if event.get('type') != 'ready':
                raise RuntimeError('TN ASR did not signal ready: ' + str(code or event.get('type')))
            send_text({'type': 'cancel'})
            return {'upgrade_status': 101, 'first_event': 'ready', 'cancel_sent': True}


def verify(password, runtime):
    for base in ('http://127.0.0.1:8094', 'https://assistant.tnfastvn.com'):
        code, health = request(base + '/v1/health')
        if code != 200 or health.get('status') != 'ok':
            raise RuntimeError('Health check failed')
    code, login = request('https://assistant.tnfastvn.com/v1/auth/login', 'POST', {'username': 'admin', 'password': password})
    if code != 200 or login.get('user', {}).get('role') != 'admin':
        raise RuntimeError('Administrator login failed')
    token = login['access_token']
    try:
        code, result = request('https://assistant.tnfastvn.com/v1/admin/users', token=token)
        if code != 200 or not any(user.get('username') == 'admin' for user in result.get('items', [])):
            raise RuntimeError('Administrator permissions check failed')
        code, provider_config = request('https://assistant.tnfastvn.com/v1/admin/providers', token=token)
        if code != 200 or any(not isinstance(provider, dict) or provider.get('mode') not in ('legacy_tn', 'generic') for provider in provider_config.values()):
            raise RuntimeError('Canonical provider modes check failed')
        ws_result = websocket_probe(token)
        for path in (runtime / 'static').rglob('*'):
            if path.is_file():
                code, body = request('https://assistant.tnfastvn.com/' + path.relative_to(runtime / 'static').as_posix(), decode_json=False)
                if code != 200 or hashlib.sha256(body).hexdigest() != sha(path):
                    raise RuntimeError('Public static content differs from release')
        challenge = WEB / '.well-known/acme-challenge' / ('tn-check-' + secrets.token_hex(8))
        challenge.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
        marker = secrets.token_hex(16).encode()
        try:
            challenge.write_bytes(marker)
            os.chmod(challenge, 0o644)
            code, body = request('http://assistant.tnfastvn.com/.well-known/acme-challenge/' + challenge.name)
            if code != 200 or body != marker:
                raise RuntimeError('ACME HTTP-01 content check failed')
        finally:
            challenge.unlink(missing_ok=True)
        return {'admin_login': True, 'admin_permissions': True, 'websocket': ws_result, 'static_verified': True, 'acme_verified': True, 'health': health}
    finally:
        request('https://assistant.tnfastvn.com/v1/auth/logout', 'POST', {'refresh_token': login['refresh_token']}, token=token)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--runtime-sha256', required=True)
    parser.add_argument('--source-sha256', required=True)
    parser.add_argument('--apk-sha256', required=True)
    args = parser.parse_args()
    if os.geteuid() != 0 or not re.fullmatch(r'[a-zA-Z0-9_-]+', args.release):
        raise RuntimeError('Root and a safe release id are required')
    if WEB != AA_PANEL_WEBROOT or WEB.is_relative_to(Path('/root')):
        raise RuntimeError('Website files must stay in the confirmed aaPanel webroot /www/wwwroot/tn.assistant')
    import pwd

    stage = BASE / 'staging' / args.release
    release = BASE / 'releases' / args.release
    sources = BASE / 'source' / args.release
    backup = Path('/var/backups/tn-assistant') / args.release
    if any(path.exists() for path in (release, sources, backup)):
        raise RuntimeError('Release or backup already exists; refusing overwrite')
    unknown = {path.name for path in WEB.iterdir()} - OLD_STATIC
    if unknown:
        raise RuntimeError('Unclassified webroot files; review before cleanup: ' + ','.join(sorted(unknown)))
    if sha(stage / 'tn-assistant-preview.apk') != args.apk_sha256:
        raise RuntimeError('APK checksum mismatch')
    runtime = stage / 'runtime-unpacked'
    source = stage / 'source-unpacked'
    unpack(stage / 'runtime.tar.gz', runtime, args.runtime_sha256)
    unpack(stage / 'source.tar.gz', source, args.source_sha256)
    spec = importlib.util.spec_from_file_location('migration', source / 'deploy/migrate_config.py')
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    original_env = ENV.read_text()
    new_env, env_changes = migration.migrate_environment(original_env)
    original_providers = PROVIDERS.read_text()
    new_providers, provider_changes = migration.migrate_providers(json.loads(original_providers))
    with closing(sqlite3.connect(DB.as_uri() + '?mode=ro', uri=True)) as connection:
        if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('Database integrity failed before deployment')
        old_db_provider, new_db_provider, db_changes = migration.database_provider_plan(connection)
        before_counts = preserved_data_counts(connection)
    account = pwd.getpwnam('tnassistant')
    binary = runtime / 'tn-assistant'
    os.chmod(binary, 0o755)
    run(['runuser', '-u', 'tnassistant', '--', 'test', '-x', str(binary)])
    release.parent.mkdir(exist_ok=True)
    shutil.copytree(runtime, release)
    for path in release.rglob('*'):
        os.chmod(path, 0o755 if path.is_dir() or path.name == 'tn-assistant' else 0o644)
    sources.parent.mkdir(exist_ok=True, mode=0o700)
    shutil.copytree(source, sources)
    os.chmod(sources, 0o700)
    # Prompt occurs only after package/config validation, and nothing has stopped yet.
    password = getpass.getpass('New administrator password (not echoed): ')
    if len(password) < 10:
        raise RuntimeError('Password must contain at least ten characters')
    zalo_secret = getpass.getpass('Zalo application secret (not echoed; blank preserves current): ')
    if any(character in zalo_secret for character in '\r\n\x00'):
        raise RuntimeError('Zalo application secret must occupy one line')
    def set_zalo_secret(text):
        if not zalo_secret:
            return text
        lines = [line for line in text.splitlines() if not line.startswith('TN_ZALO_APP_SECRET=')]
        return '\n'.join(lines) + '\nTN_ZALO_APP_SECRET=' + json.dumps(zalo_secret) + '\n'
    rollback_env = set_zalo_secret(original_env)
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    previous_release = (BASE / 'current').resolve()
    stopped = False
    migration_started = False
    static_backup_complete = False
    static_changed = False
    reset_completed = False
    try:
        run(['systemctl', 'stop', 'tn-assistant.service'])
        stopped = True
        if run(['systemctl', 'is-active', 'tn-assistant.service'], check=False).returncode == 0:
            raise RuntimeError('Service failed to stop')
        # These are the authoritative snapshots, after writers have stopped.
        original_env = ENV.read_text()
        new_env, env_changes = migration.migrate_environment(original_env)
        new_env = set_zalo_secret(new_env)
        rollback_env = set_zalo_secret(original_env)
        original_providers = PROVIDERS.read_text()
        new_providers, provider_changes = migration.migrate_providers(json.loads(original_providers))
        with closing(sqlite3.connect(DB.as_uri() + '?mode=ro', uri=True)) as connection:
            old_db_provider, new_db_provider, db_changes = migration.database_provider_plan(connection)
            before_counts = preserved_data_counts(connection)
            before_record_count = connection.execute('SELECT count(*) FROM records').fetchone()[0]
            expected_record_delta = migration.runtime_initialization_record_delta(connection)
        shutil.copy2(ENV, backup / 'tn-assistant.env')
        shutil.copy2(PROVIDERS, backup / 'providers.json')
        shutil.copytree(WEB, backup / 'static')
        static_backup_complete = True
        shutil.copytree(DB.parent, backup / 'persistent', ignore=shutil.ignore_patterns(DB.name, DB.name + '-wal', DB.name + '-shm'))
        external_paths = []
        current_env = read_env()
        for key in ('TN_DOWNLOAD_DIR', 'KIKI_DOWNLOAD_DIR', 'TN_AI_MASTER_KEY_FILE'):
            value = current_env.get(key)
            if value:
                path = Path(value).resolve()
                if path.exists() and path != DB.parent and DB.parent not in path.parents:
                    if path == Path('/') or len(path.parts) < 3:
                        raise RuntimeError('Unexpected external data path; review required')
                    target = backup / 'external' / key
                    target.parent.mkdir(exist_ok=True)
                    if path.is_dir():
                        shutil.copytree(path, target)
                    else:
                        shutil.copy2(path, target)
                    external_paths.append({'setting': key, 'original_path': str(path), 'backup_path': str(target)})
        (backup / 'EXTERNAL_PATHS.json').write_text(json.dumps(external_paths, indent=2))
        for config in ('/etc/systemd/system/tn-assistant.service', '/www/server/panel/vhost/nginx/assistant.tnfastvn.com.conf', '/www/server/panel/vhost/nginx/tn-assistant-proxy.inc'):
            if Path(config).is_file():
                shutil.copy2(config, backup / Path(config).name)
        with closing(sqlite3.connect(DB.as_uri() + '?mode=ro', uri=True)) as connection:
            with closing(sqlite3.connect(backup / 'database.sqlite')) as destination:
                connection.backup(destination)
                if destination.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                    raise RuntimeError('Backup integrity failed')
        backup_manifest = [{'path': path.relative_to(backup).as_posix(), 'sha256': sha(path), 'size': path.stat().st_size} for path in backup.rglob('*') if path.is_file()]
        (backup / 'BACKUP_MANIFEST.json').write_text(json.dumps(backup_manifest, indent=2))
        migration_started = True
        migration.atomic_write(ENV, new_env)
        if provider_changes:
            migration.atomic_write(PROVIDERS, json.dumps(new_providers, indent=2) + '\n')
        with closing(sqlite3.connect(DB.as_uri() + '?mode=rw', uri=True)) as connection:
            migration.migrate_database(connection)
        for path in (DB, Path(str(DB) + '-wal'), Path(str(DB) + '-shm')):
            if path.exists():
                os.chown(path, account.pw_uid, account.pw_gid)
        reset = run(['runuser', '-u', 'tnassistant', '--', str(binary), 'reset-admin', '--db', str(DB), '--username', 'admin', '--password-stdin'], input=password + '\n')
        if 'all account sessions revoked' not in reset.stdout:
            raise RuntimeError('Admin reset did not confirm session revocation')
        reset_completed = True
        static_changed = True
        static_install(runtime / 'static')
        temporary_link = BASE / 'current-next'
        if temporary_link.exists() or temporary_link.is_symlink():
            raise RuntimeError('Unexpected pending release symlink')
        temporary_link.symlink_to(release, target_is_directory=True)
        os.replace(temporary_link, BASE / 'current')
        run(['/www/server/nginx/sbin/nginx', '-t'])
        run(['systemctl', 'start', 'tn-assistant.service'])
        for attempt in range(30):
            try:
                code, health = request('http://127.0.0.1:8094/v1/health')
                if code == 200 and health.get('status') == 'ok':
                    break
            except OSError:
                pass
            time.sleep(1)
        result = verify(password, runtime)
        run(['systemctl', 'restart', 'tn-assistant.service'])
        for attempt in range(30):
            try:
                if request('http://127.0.0.1:8094/v1/health')[0] == 200:
                    break
            except OSError:
                pass
            time.sleep(1)
        with closing(sqlite3.connect(DB.as_uri() + '?mode=ro', uri=True)) as connection:
            if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise RuntimeError('Database failed final integrity')
            after_counts = preserved_data_counts(connection)
            after_record_count = connection.execute('SELECT count(*) FROM records').fetchone()[0]
        if before_counts != after_counts or after_record_count != before_record_count + expected_record_delta:
            raise RuntimeError('Application data counts changed unexpectedly')
        code, persistence_login = request('https://assistant.tnfastvn.com/v1/auth/login', 'POST', {'username': 'admin', 'password': password})
        if code != 200 or persistence_login.get('user', {}).get('role') != 'admin':
            raise RuntimeError('Administrator login failed after service restart')
        request('https://assistant.tnfastvn.com/v1/auth/logout', 'POST', {'refresh_token': persistence_login['refresh_token']}, token=persistence_login['access_token'])
        result.update({'release': args.release, 'previous_release': previous_release.name, 'backup': str(backup), 'database_integrity': 'ok', 'data_counts_unchanged': True, 'runtime_initialization_records': expected_record_delta, 'restart_persistence': True, 'environment_changes': env_changes, 'provider_changes': provider_changes, 'database_provider_changes': db_changes, 'zalo_secret_present': bool(read_env().get('TN_ZALO_APP_SECRET')), 'verified_at': dt.datetime.now(dt.timezone.utc).isoformat()})
        (stage / 'DEPLOYMENT.json').write_text(json.dumps(result, indent=2))
        print(json.dumps(result), flush=True)
    except BaseException as error:
        rollback_errors = []
        def recover(label, action):
            try:
                action()
            except BaseException as recovery_error:
                rollback_errors.append(label + ':' + type(recovery_error).__name__)
        if stopped:
            recover('stop', lambda: run(['systemctl', 'stop', 'tn-assistant.service'], check=False))
            if migration_started:
                recover('environment', lambda: migration.atomic_write(ENV, rollback_env))
                recover('provider_file', lambda: migration.atomic_write(PROVIDERS, original_providers))
            if migration_started and db_changes:
                def restore_provider_record():
                    with closing(sqlite3.connect(DB.as_uri() + '?mode=rw', uri=True)) as connection:
                        with connection:
                            cursor = connection.execute("UPDATE records SET data=? WHERE kind='config' AND id='providers' AND data=?", (old_db_provider, new_db_provider))
                            if cursor.rowcount != 1:
                                current = connection.execute("SELECT data FROM records WHERE kind='config' AND id='providers'").fetchone()
                                if current is None or current[0] != old_db_provider:
                                    raise RuntimeError('Provider configuration changed; refusing unsafe rollback')
                recover('provider_database', restore_provider_record)
            for path in (DB, Path(str(DB) + '-wal'), Path(str(DB) + '-shm')):
                if path.exists():
                    recover('database_owner', lambda path=path: os.chown(path, account.pw_uid, account.pw_gid))
            if static_changed and static_backup_complete:
                recover('static', lambda: static_install(backup / 'static'))
            def restore_release_link():
                link = BASE / ('rollback-' + secrets.token_hex(6))
                link.symlink_to(previous_release, target_is_directory=True)
                os.replace(link, BASE / 'current')
            recover('release', restore_release_link)
            recover('start', lambda: run(['systemctl', 'start', 'tn-assistant.service']))
        print(json.dumps({'deployment': 'failed', 'error_type': type(error).__name__, 'error': str(error) if not isinstance(error, subprocess.CalledProcessError) else 'Command failed; inspect service locally', 'rollback_attempted': stopped, 'rollback_errors': rollback_errors, 'password_and_session_reset_preserved': reset_completed}), flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
