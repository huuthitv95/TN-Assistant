"""Accept a staged TN backend with a private DB and loopback-only service."""

import argparse
import datetime as dt
import grp
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import shutil
import sqlite3
import subprocess
import time

import deploy_remote as remote
import deploy_ui_release as ui


STAGING_ROOT = Path('/var/lib/tn-assistant-staging')
STAGING_USER = 'tnstage'
STAGING_ADDR = '127.0.0.1:18094'
PROXY_NAMES = frozenset({'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY',
                         'http_proxy', 'https_proxy', 'all_proxy'})


def run(args, check=True):
    return subprocess.run(args, text=True, capture_output=True, check=check)


def staging_account():
    try:
        account = pwd.getpwnam(STAGING_USER)
    except KeyError:
        run(['useradd', '--system', '--user-group', '--no-create-home',
             '--shell', '/usr/sbin/nologin', STAGING_USER])
        account = pwd.getpwnam(STAGING_USER)
    group = grp.getgrnam(STAGING_USER)
    production = pwd.getpwnam('tnassistant')
    if (account.pw_uid == 0 or account.pw_uid == production.pw_uid
            or account.pw_gid != group.gr_gid
            or production.pw_gid in os.getgrouplist(STAGING_USER, account.pw_gid)):
        raise RuntimeError('Staging account is not isolated from production')
    return account, group


def stage_parent(release, group):
    if not STAGING_ROOT.exists():
        STAGING_ROOT.mkdir(mode=0o750)
        os.chown(STAGING_ROOT, 0, group.gr_gid)
        os.chmod(STAGING_ROOT, 0o750)
    ui.real_directory(STAGING_ROOT)
    metadata = STAGING_ROOT.stat()
    if metadata.st_uid != 0 or metadata.st_gid != group.gr_gid or metadata.st_mode & 0o027:
        raise RuntimeError('Unexpected private staging root ownership or mode')
    parent = STAGING_ROOT / release
    ui.absent(parent)
    parent.mkdir(mode=0o750)
    os.chown(parent, 0, group.gr_gid)
    os.chmod(parent, 0o750)
    return parent


def copy_private(source, destination, account):
    ui.regular_file(source)
    shutil.copy2(source, destination)
    os.chown(destination, account.pw_uid, account.pw_gid)
    os.chmod(destination, 0o600)


def neutralize_jobs(database):
    changed = 0
    connection = sqlite3.connect(database)
    try:
        rows = connection.execute("SELECT id,data FROM records WHERE kind='managed_news_job'").fetchall()
        for identifier, raw in rows:
            job = json.loads(raw)
            if job.get('status') in ('queued', 'running'):
                job['status'] = 'failed'
                job['error_code'] = 'STAGING_JOB_DISABLED'
                connection.execute("UPDATE records SET data=? WHERE kind='managed_news_job' AND id=?",
                                   (json.dumps(job, ensure_ascii=False, separators=(',', ':')), identifier))
                changed += 1
        remaining = connection.execute("SELECT data FROM records WHERE kind='managed_news_job'").fetchall()
        if any(json.loads(row[0]).get('status') in ('queued', 'running') for row in remaining):
            raise RuntimeError('Queued news work remains in the isolated database')
        connection.commit()
        if connection.execute('PRAGMA journal_mode=DELETE').fetchone() != ('delete',):
            raise RuntimeError('Isolated database could not leave WAL mode')
    finally:
        connection.close()
    if any(database.with_name(database.name + suffix).exists() for suffix in ('-wal', '-shm')):
        raise RuntimeError('Isolated database has unexpected WAL sidecars')
    return changed


def write_staging_environment(production, destination, runtime):
    ui.regular_file(production)
    rows = []
    for row in production.read_bytes().splitlines():
        stripped = row.lstrip()
        key = stripped.split(b'=', 1)[0].decode('ascii', 'ignore').strip()
        if key not in PROXY_NAMES:
            rows.append(row)
    overrides = {
        'TN_RUNTIME_MODE': 'independent',
        'TN_ADDR': STAGING_ADDR,
        'TN_DB': str(runtime / 'tn-assistant.sqlite'),
        'TN_PROVIDER_FILE': str(runtime / 'providers.json'),
        'TN_AI_MASTER_KEY_FILE': str(runtime / 'tn-ai-master.key'),
        'TN_STATIC_DIR': str(runtime / 'static'),
        'TN_DOWNLOAD_DIR': str(runtime / 'downloads'),
        'TN_PUBLIC_URL': 'http://' + STAGING_ADDR,
        'TN_ZALO_EGRESS_MODE': 'direct',
        'TN_ADMIN_PASSWORD': '',
    }
    content = b'\n'.join(rows) + b'\n' + ''.join(key + '=' + value + '\n' for key, value in overrides.items()).encode()
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def service_properties(unit):
    keys = ('ActiveState', 'MainPID', 'User', 'Group', 'WorkingDirectory',
            'PrivateNetwork', 'ProtectSystem', 'ReadWritePaths')
    result = run(['systemctl', 'show', unit, '--no-pager', '--property=' + ','.join(keys)])
    return dict(row.split('=', 1) for row in result.stdout.splitlines() if '=' in row)


def process_environment(pid):
    result = {}
    for item in Path('/proc') .joinpath(str(pid), 'environ').read_bytes().split(b'\0'):
        if b'=' in item:
            key, value = item.split(b'=', 1)
            result[key.decode()] = value.decode()
    return result


def wait_for_candidate(unit, runtime):
    deadline = time.monotonic() + 30
    last_status = None
    while time.monotonic() < deadline:
        properties = service_properties(unit)
        if properties.get('ActiveState') in ('failed', 'inactive'):
            raise RuntimeError('Isolated staging service exited before readiness')
        try:
            pid = int(properties.get('MainPID', '0'))
            if pid <= 1:
                time.sleep(0.5)
                continue
            result = run(['nsenter', '-t', str(pid), '-n', '--', '/usr/bin/python3',
                          str(runtime / 'staging_network_probe.py'), '--http'], check=False)
            if result.returncode != 0:
                last_status = 'namespace probe failed'
                time.sleep(0.5)
                continue
            observed = json.loads(result.stdout)
            readiness = observed.get('readiness')
            if (observed.get('health_code') == 200 and observed.get('health_status') == 'ok'
                    and ui.readiness_passes(observed.get('ready_code'), readiness)):
                return properties
            last_status = readiness.get('status') if isinstance(readiness, dict) else 'invalid'
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError('Isolated staging health/readiness did not pass: ' + str(last_status or 'unavailable'))


def verify_candidate_process(unit, runtime, properties):
    pid = int(properties.get('MainPID', '0'))
    if (pid <= 1 or properties.get('User') != STAGING_USER or properties.get('Group') != STAGING_USER
            or properties.get('WorkingDirectory') != str(runtime)
            or properties.get('ProtectSystem') != 'strict'
            or str(runtime) not in properties.get('ReadWritePaths', '')
            or properties.get('PrivateNetwork') != 'yes'
            or os.readlink(f'/proc/{pid}/ns/net') == os.readlink('/proc/self/ns/net')):
        raise RuntimeError('Isolated staging unit properties are invalid')
    if not ui.process_executable_matches(pid, runtime):
        raise RuntimeError('Staging process does not match the staged binary')
    environment = process_environment(pid)
    expected = {'TN_ADDR': STAGING_ADDR, 'TN_DB': str(runtime / 'tn-assistant.sqlite'),
                'TN_PROVIDER_FILE': str(runtime / 'providers.json'),
                'TN_AI_MASTER_KEY_FILE': str(runtime / 'tn-ai-master.key'),
                'TN_DOWNLOAD_DIR': str(runtime / 'downloads')}
    if any(environment.get(key) != value for key, value in expected.items()):
        raise RuntimeError('Staging process environment overrides were not applied')
    if any(environment.get(key) for key in PROXY_NAMES):
        raise RuntimeError('Proxy environment variables leaked into staging')
    marker = runtime / 'network-policy-ok'
    ui.regular_file(marker)
    if marker.read_text(encoding='ascii') != 'private network namespace verified\n':
        raise RuntimeError('Staging egress guard did not run')


def stop_and_clean(unit, private_stage):
    if unit:
        run(['systemctl', 'stop', unit], check=False)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            active = service_properties(unit).get('ActiveState')
            if active not in ('active', 'activating', 'deactivating'):
                break
            time.sleep(0.25)
        else:
            raise RuntimeError('Staging service did not stop; private database retained for safe recovery')
        run(['systemctl', 'reset-failed', unit], check=False)
    if private_stage is not None:
        if (private_stage.parent != STAGING_ROOT or private_stage.is_symlink()
                or private_stage.resolve(strict=True) != private_stage.absolute()):
            raise RuntimeError('Refusing cleanup outside the intended staging directory')
        shutil.rmtree(private_stage)


def accept(args):
    stage = ui.BASE / 'staging' / args.release
    candidate = stage / 'tn-assistant'
    source = stage / 'backend-source.tar.gz'
    evidence_path = stage / 'STAGING_ACCEPTANCE.json'
    for path in (ui.BASE, ui.BASE / 'staging', stage):
        ui.real_directory(path)
    for path in (candidate, source, ui.DB, ui.ENV, ui.PROVIDERS):
        ui.regular_file(path)
    ui.absent(evidence_path)
    if remote.sha(candidate) != args.binary_sha256 or remote.sha(source) != args.source_sha256:
        raise RuntimeError('Staged binary/source checksum mismatch')
    current = (ui.BASE / 'current').resolve(strict=True)
    if current != ui.BASE / 'releases' / args.expected_current:
        raise RuntimeError('Production release changed since staging')
    properties = ui.systemd_properties()
    ui.validate_service(properties)
    ui.verify_process(properties, current, remote.read_env())
    account, group = staging_account()
    private_stage = stage_parent(args.release, group)
    runtime = private_stage / 'runtime'
    unit = ''
    accepted = False
    try:
        runtime.mkdir(mode=0o700)
        os.chown(runtime, account.pw_uid, account.pw_gid)
        os.chmod(runtime, 0o700)
        snapshot = runtime / 'tn-assistant.sqlite'
        ui.inspect_database(ui.DB, snapshot)
        neutralized = neutralize_jobs(snapshot)
        os.chown(snapshot, account.pw_uid, account.pw_gid)
        os.chmod(snapshot, 0o600)
        copy_private(ui.PROVIDERS, runtime / 'providers.json', account)
        environment = remote.read_env()
        key = Path(environment.get('TN_AI_MASTER_KEY_FILE') or str(ui.DB.parent / '.runtime/tn-ai-master.key'))
        if not key.is_absolute() or ui.WEB in key.parents or ui.DOWNLOADS in key.parents or key.stat().st_size != 32:
            raise RuntimeError('Production master-key path is invalid')
        copy_private(key, runtime / 'tn-ai-master.key', account)
        for name in ('static', 'downloads'):
            directory = runtime / name
            directory.mkdir(mode=0o700)
            os.chown(directory, account.pw_uid, account.pw_gid)
        shutil.copy2(candidate, runtime / 'tn-assistant')
        os.chmod(runtime / 'tn-assistant', 0o755)
        probe_source = Path(__file__).with_name('staging_network_probe.py')
        ui.regular_file(probe_source)
        shutil.copy2(probe_source, runtime / 'staging_network_probe.py')
        os.chmod(runtime / 'staging_network_probe.py', 0o644)
        launcher = runtime / 'launch.sh'
        launcher.write_text('#!/bin/sh\nset -eu\n/usr/bin/python3 ' + str(runtime / 'staging_network_probe.py')
                            + ' ' + str(runtime / 'network-policy-ok') + '\n/usr/bin/python3 '
                            + str(runtime / 'staging_network_probe.py') + ' --preflight\nexec '
                            + str(runtime / 'tn-assistant') + ' serve\n', encoding='ascii')
        os.chmod(launcher, 0o755)
        staging_env = private_stage / 'staging.env'
        write_staging_environment(ui.ENV, staging_env, runtime)
        unit = 'tn-stage-' + secrets.token_hex(5) + '.service'
        run(['systemd-run', '--unit=' + unit, '--no-block',
             '--property=User=' + STAGING_USER, '--property=Group=' + STAGING_USER,
             '--property=WorkingDirectory=' + str(runtime),
             '--property=EnvironmentFile=' + str(staging_env),
             '--property=PrivateNetwork=yes',
             '--property=ProtectSystem=strict', '--property=ReadWritePaths=' + str(runtime),
             '--property=NoNewPrivileges=yes', '--property=PrivateTmp=yes',
             '--property=ProtectHome=yes', '--property=RuntimeMaxSec=90s',
             '--property=TimeoutStopSec=10s',
             '--property=UnsetEnvironment=' + ' '.join(sorted(PROXY_NAMES)),
             str(launcher)])
        candidate_properties = wait_for_candidate(unit, runtime)
        verify_candidate_process(unit, runtime, candidate_properties)
        if remote.sha(runtime / 'tn-assistant') != args.binary_sha256:
            raise RuntimeError('Staging executable checksum changed')
        alerts = None
        if args.check_basic_alerts:
            result = run(['nsenter', '-t', candidate_properties['MainPID'], '-n', '--', '/usr/bin/python3',
                          str(runtime / 'staging_network_probe.py'), '--basic-alerts'])
            alerts = json.loads(result.stdout)
            if alerts.get('status') != 'passed':
                raise RuntimeError('Isolated Basic alert acceptance failed')
        accepted = True
    finally:
        stop_and_clean(unit, private_stage)
    if not accepted:
        raise RuntimeError('Staging did not complete')
    report = {
        'status': 'passed', 'release': args.release,
        'binary_sha256': args.binary_sha256, 'source_sha256': args.source_sha256,
        'isolated_database': True, 'health_status': 'ok', 'readiness_status': 'ready',
        'external_provider_calls': 0,
        'network_policy': 'systemd PrivateNetwork=yes; only loopback interface; external route ENETUNREACH',
        'staging_user': STAGING_USER, 'staging_addr': STAGING_ADDR,
        'neutralized_news_jobs_in_snapshot': neutralized, 'private_snapshot_removed': True,
        'observed_at_utc': dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z'),
    }
    if alerts is not None:
        report['basic_alerts'] = alerts
    temporary = stage / '.staging-acceptance.tmp'
    with temporary.open('x', encoding='utf-8') as output:
        output.write(json.dumps(report, indent=2) + '\n')
        output.flush()
        os.fsync(output.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, evidence_path)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--expected-current', required=True)
    parser.add_argument('--binary-sha256', required=True)
    parser.add_argument('--source-sha256', required=True)
    parser.add_argument('--check-basic-alerts', action='store_true')
    args = parser.parse_args()
    if any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', name)
           for name in (args.release, args.expected_current)):
        raise RuntimeError('Unsafe release identifier')
    if any(not re.fullmatch(r'[0-9a-f]{64}', value)
           for value in (args.binary_sha256, args.source_sha256)):
        raise RuntimeError('Invalid SHA-256 argument')
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise RuntimeError('Run on the existing VPS as root')
    os.umask(0o077)
    print(json.dumps(accept(args)))


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(json.dumps({'staging': 'failed', 'error': message}))
        raise SystemExit(1)
