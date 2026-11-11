"""Promote a staged TN backend binary without changing dashboard or APK files."""

import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import shutil

import deploy_remote as remote
import deploy_ui_release as ui


BASE = Path('/srv/tn-assistant')
BACKUPS = Path('/var/backups/tn-assistant')
MONITOR = Path('/var/lib/tn-assistant/ops/availability-24h.json')
MONITOR_SAMPLES = Path('/var/lib/tn-assistant/ops/availability-samples.jsonl')
CORE_TABLES = ('users', 'devices', 'payments', 'entitlements')


def monitor_completed():
    ui.regular_file(MONITOR)
    ui.regular_file(MONITOR_SAMPLES)
    report = json.loads(MONITOR.read_text(encoding='utf-8'))
    if report.get('status') != 'completed' or report.get('failed_samples') != 0 or report.get('samples', 0) < 1400:
        raise RuntimeError('The 24-hour monitor is incomplete or recorded failures')
    started = dt.datetime.fromisoformat(report['started_at'])
    ended = dt.datetime.fromisoformat(report['ends_at'])
    completed = dt.datetime.fromisoformat(report['completed_at'])
    if (started.tzinfo is None or ended.tzinfo is None or completed.tzinfo is None
            or (ended - started) < dt.timedelta(hours=24)
            or completed < ended):
        raise RuntimeError('The monitor has an invalid completion interval')
    first = last = None
    count = 0
    max_gap = 0.0
    with MONITOR_SAMPLES.open(encoding='utf-8') as stream:
        for line in stream:
            sample = json.loads(line)
            when = dt.datetime.fromisoformat(sample['at'])
            if not sample.get('ok') or sample.get('service', {}).get('NRestarts') != '0':
                raise RuntimeError('The monitor samples include a failed or restarted service')
            if last is not None:
                gap = (when - last).total_seconds()
                if gap < 0:
                    raise RuntimeError('The monitor sample order is invalid')
                max_gap = max(max_gap, gap)
            first = first or when
            last = when
            count += 1
    if (count != report['samples'] or first is None or last is None
            or first > started + dt.timedelta(minutes=2)
            or last < ended - dt.timedelta(minutes=2) or max_gap > 120):
        raise RuntimeError('The monitor sample coverage is incomplete')
    return {'scope': 'service_availability_across_releases',
            'samples': count, 'failed_samples': 0, 'max_gap_seconds': max_gap,
            'started_at': report['started_at'], 'ended_at': report['ends_at']}


def staging_accepted(stage, binary_sha256, source_sha256):
    evidence_path = stage / 'STAGING_ACCEPTANCE.json'
    ui.regular_file(evidence_path)
    evidence = json.loads(evidence_path.read_text(encoding='utf-8'))
    required = {
        'status': 'passed',
        'binary_sha256': binary_sha256,
        'source_sha256': source_sha256,
        'isolated_database': True,
        'health_status': 'ok',
        'readiness_status': 'ready',
        'external_provider_calls': 0,
    }
    if any(evidence.get(key) != value for key, value in required.items()):
        raise RuntimeError('Candidate has no matching isolated staging acceptance')
    observed = dt.datetime.fromisoformat(evidence['observed_at_utc'].replace('Z', '+00:00'))
    if (observed.tzinfo is None or observed > dt.datetime.now(dt.timezone.utc)
            or dt.datetime.now(dt.timezone.utc) - observed > dt.timedelta(hours=6)):
        raise RuntimeError('Candidate staging acceptance is stale')
    return {'observed_at_utc': evidence['observed_at_utc'], 'status': 'passed'}


def ensure_same_critical_data(before, after):
    if before['schema_sha256'] != after['schema_sha256'] or before['configuration_sha256'] != after['configuration_sha256']:
        raise RuntimeError('Database schema or runtime configuration changed during backend promotion')
    for table in CORE_TABLES:
        if (before['counts'][table] != after['counts'][table]
                or before['table_sha256'][table] != after['table_sha256'][table]):
            raise RuntimeError('Protected application data changed during backend promotion')


def scan_live_secret_values(source, environment):
    candidates = {value.encode() for key, value in environment.items()
                  if re.search(r'(SECRET|PASSWORD|TOKEN|API_KEY)', key)
                  and len(value) >= 8 and not value.startswith('/')}
    if not candidates:
        raise RuntimeError('No live secret values were available for the source scan')
    for path in source.rglob('*'):
        if path.is_file():
            content = path.read_bytes()
            if any(secret in content for secret in candidates):
                raise RuntimeError('Private value found in backend source archive')


def public_postflight():
    code, body = remote.request('https://assistant.tnfastvn.com/v1/health')
    if code != 200 or not isinstance(body, dict) or body.get('status') != 'ok':
        raise RuntimeError('Public backend health failed')
    code, body = remote.request('https://assistant.tnfastvn.com/v1/ready')
    if not ui.readiness_passes(code, body):
        raise RuntimeError('Public backend readiness failed')
    code, _ = remote.request('https://assistant.tnfastvn.com/', decode_json=False)
    if code != 200:
        raise RuntimeError('Public dashboard failed after backend promotion')


def deploy(args):
    stage = BASE / 'staging' / args.release
    release = BASE / 'releases' / args.release
    source = BASE / 'source' / args.release
    backup = BACKUPS / args.release
    current = BASE / 'current'
    ui.real_directory(BASE)
    ui.real_directory(BASE / 'staging')
    ui.real_directory(BASE / 'releases')
    ui.real_directory(BASE / 'source')
    ui.real_directory(stage)
    ui.real_directory(ui.WEB)
    if ui.WEB != Path('/www/wwwroot/tn.assistant') or ui.WEB.is_relative_to(Path('/root')):
        raise RuntimeError('Unexpected aaPanel webroot')
    monitor = monitor_completed()
    for path in (release, source, backup, stage / 'source-unpacked', stage / 'BACKEND_DEPLOYMENT.json'):
        ui.absent(path)
    if not current.is_symlink():
        raise RuntimeError('Current backend is not a release symlink')
    previous = current.resolve(strict=True)
    if previous != BASE / 'releases' / args.expected_current:
        raise RuntimeError('Current backend release changed since staging')
    ui.real_directory(previous)
    candidate = stage / 'tn-assistant'
    archive = stage / 'backend-source.tar.gz'
    for path in (candidate, archive, ui.ENV, ui.PROVIDERS, ui.DB, previous / 'tn-assistant'):
        ui.regular_file(path)
    if remote.sha(candidate) != args.binary_sha256 or remote.sha(archive) != args.source_sha256:
        raise RuntimeError('Staged candidate or source checksum mismatch')
    staging = staging_accepted(stage, args.binary_sha256, args.source_sha256)
    if ui.elf_identity(candidate) != ui.elf_identity(previous / 'tn-assistant'):
        raise RuntimeError('Staged candidate architecture differs from production')
    environment = remote.read_env()
    expected_env = {'TN_DB': str(ui.DB), 'TN_PROVIDER_FILE': str(ui.PROVIDERS),
                    'TN_STATIC_DIR': str(ui.WEB), 'TN_DOWNLOAD_DIR': str(ui.DOWNLOADS),
                    'TN_RUNTIME_MODE': 'independent', 'TN_ADDR': '127.0.0.1:8094'}
    effective_env = dict(environment)
    effective_env.setdefault('TN_RUNTIME_MODE', 'independent')
    if any(effective_env.get(key) != value for key, value in expected_env.items()):
        raise RuntimeError('Live environment differs from backend-only deployment contract')
    if environment.get('TN_ZALO_EGRESS_MODE', '') not in ('', 'direct'):
        raise RuntimeError('Gateway mode must be activated in a separate verified step')
    key = Path(environment.get('TN_AI_MASTER_KEY_FILE') or str(ui.DB.parent / '.runtime/tn-ai-master.key'))
    if not key.is_absolute() or key == ui.WEB or ui.WEB in key.parents or ui.DOWNLOADS in key.parents:
        raise RuntimeError('AI master key must be an absolute private path outside public directories')
    ui.regular_file(key)
    if key.stat().st_size != 32:
        raise RuntimeError('Existing AI master key has an unexpected length')
    private_inputs = {'environment': ui.ENV, 'providers': ui.PROVIDERS, 'master_key': key}
    private_hashes = {name: remote.sha(path) for name, path in private_inputs.items()}
    properties = ui.systemd_properties()
    ui.validate_service(properties)
    ui.verify_process(properties, previous, environment)
    ui.wait_healthy(previous, environment)
    ui.inspect_database(ui.DB)
    static_before = ui.static_inventory(ui.WEB, public=True)
    ui.validate_archive(archive)
    remote.unpack(archive, stage / 'source-unpacked', args.source_sha256)
    metadata = json.loads((stage / 'source-unpacked' / 'RELEASE.json').read_text(encoding='utf-8'))
    if (metadata.get('release') != args.release or metadata.get('runtime') is not False
            or metadata.get('private_content_scan') != 'not_performed'
            or metadata.get('binary_sha256') != args.binary_sha256):
        raise RuntimeError('Staged source metadata does not match the binary')
    if not (stage / 'source-unpacked/backend/zalo_transport.go').is_file():
        raise RuntimeError('Staged backend source is incomplete')
    scan_live_secret_values(stage / 'source-unpacked', environment)
    shutil.copytree(stage / 'source-unpacked', source)
    for path in [source] + list(source.rglob('*')):
        os.chmod(path, 0o700 if path.is_dir() else 0o600)
    release.mkdir(mode=0o755)
    os.chmod(release, 0o755)
    shutil.copy2(candidate, release / 'tn-assistant')
    os.chmod(release / 'tn-assistant', 0o755)
    release_metadata = {'release': args.release, 'backend_only': True,
                        'binary_sha256': args.binary_sha256, 'source_sha256': args.source_sha256,
                        'previous_release': previous.name}
    (release / 'RELEASE.json').write_text(json.dumps(release_metadata, indent=2) + '\n', encoding='utf-8')
    os.chmod(release / 'RELEASE.json', 0o644)
    remote.run(['runuser', '-u', 'tnassistant', '--', 'test', '-x', str(release / 'tn-assistant')])
    ui.private_directory(BACKUPS)
    backup.mkdir(mode=0o700)
    stopped = False
    try:
        stopped = True
        remote.run(['systemctl', 'stop', ui.SERVICE])
        if ui.systemd_properties().get('ActiveState') not in ('inactive', 'failed'):
            raise RuntimeError('Backend did not stop before consistent backup')
        if current.resolve(strict=True) != previous or any(remote.sha(path) != private_hashes[name] for name, path in private_inputs.items()):
            raise RuntimeError('Release or private configuration changed during preflight')
        if ui.static_inventory(ui.WEB, public=True) != static_before:
            raise RuntimeError('Dashboard changed during preflight')
        before = ui.inspect_database(ui.DB, backup / 'database.sqlite')
        for name, path in private_inputs.items():
            target = backup / (name + '.private')
            shutil.copy2(path, target)
            os.chmod(target, 0o600)
            if remote.sha(target) != private_hashes[name]:
                raise RuntimeError('Private backup checksum mismatch')
        unit = Path(properties['FragmentPath'])
        shutil.copy2(unit, backup / 'tn-assistant.service')
        os.chmod(backup / 'tn-assistant.service', 0o600)
        backup_manifest = [{'path': path.relative_to(backup).as_posix(), 'sha256': remote.sha(path),
                            'size': path.stat().st_size} for path in backup.iterdir() if path.is_file()]
        (backup / 'BACKUP_MANIFEST.json').write_text(json.dumps(backup_manifest, indent=2) + '\n', encoding='utf-8')
        os.chmod(backup / 'BACKUP_MANIFEST.json', 0o600)

        def verify_candidate():
            health = ui.wait_healthy(release, environment)
            if remote.sha(release / 'tn-assistant') != args.binary_sha256:
                raise RuntimeError('Running release binary checksum changed')
            ensure_same_critical_data(before, ui.inspect_database(ui.DB))
            if any(remote.sha(path) != private_hashes[name] for name, path in private_inputs.items()):
                raise RuntimeError('Private configuration changed during promotion')
            if ui.static_inventory(ui.WEB, public=True) != static_before:
                raise RuntimeError('Dashboard changed during backend-only promotion')
            public_postflight()
            return health

        result = ui.activate(current, previous, release, verify_candidate,
                             lambda: ui.wait_healthy(previous, environment))
        stopped = False
    except BaseException as deployment_error:
        if stopped and current.is_symlink() and current.resolve(strict=True) == previous:
            try:
                remote.run(['systemctl', 'start', ui.SERVICE])
                ui.wait_healthy(previous, environment)
            except BaseException as recovery_error:
                raise RuntimeError('Backend promotion failed and previous service recovery failed ('
                                   + type(recovery_error).__name__ + ')') from deployment_error
        raise
    report = {'status': 'deployed', 'release': args.release, 'previous_release': previous.name,
              'binary_sha256': args.binary_sha256, 'source_sha256': args.source_sha256,
              'backup': str(backup), 'monitor': monitor, 'staging': staging,
              'database_integrity': 'ok',
              'protected_data_unchanged': True, 'private_configuration_unchanged': True,
              'webroot_unchanged': True, 'apk_unchanged': True,
              'server_environment_secret_scan_passed': True, 'health': result}
    final_report = stage / 'BACKEND_DEPLOYMENT.json'
    temporary_report = stage / '.backend-deployment.tmp'
    try:
        with temporary_report.open('x', encoding='utf-8') as output:
            output.write(json.dumps(report, indent=2) + '\n')
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary_report, 0o600)
        os.replace(temporary_report, final_report)
    except Exception as error:
        temporary_report.unlink(missing_ok=True)
        report['status'] = 'deployed_report_write_failed'
        report['report_write_error'] = type(error).__name__
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--expected-current', required=True)
    parser.add_argument('--binary-sha256', required=True)
    parser.add_argument('--source-sha256', required=True)
    args = parser.parse_args()
    for name in (args.release, args.expected_current):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', name):
            raise RuntimeError('Unsafe release identifier')
    if any(not re.fullmatch(r'[0-9a-f]{64}', value) for value in (args.binary_sha256, args.source_sha256)):
        raise RuntimeError('Invalid SHA-256 argument')
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise RuntimeError('Run as root on the existing Linux VPS')
    os.umask(0o077)
    ui.real_directory(BASE)
    descriptor = os.open(BASE / '.ui-deploy.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print(json.dumps(deploy(args)), flush=True)


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(json.dumps({'deployment': 'failed', 'error': message, 'live_database_restored': False}), flush=True)
        raise SystemExit(1)
