"""Exercise the real executable rollback with installed releases and a disposable guest database."""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import subprocess
import sys
import time

import accept_backend_staging as staging
import deploy_ui_release as ui
import staging_network_probe as network


class RejectedCandidate(Exception):
    """Only this deliberate postflight failure counts as an exercised rollback."""


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def probe():
    # Invoked via nsenter. The disposable access token stays in the parent's pipes.
    network.verify_network()
    instruction = json.load(sys.stdin)
    method, path = instruction['method'], instruction['path']
    status, data = network.alert_request(method, path, instruction.get('body'), instruction.get('token', ''))
    require(status == 200, f'{method} {path} returned {status}')
    print(json.dumps(data))


def request(pid, method, path, body=None, token=''):
    result = subprocess.run(['nsenter', '-t', str(pid), '-n', '--', sys.executable,
                             '-B', str(Path(__file__).resolve()), '--probe'],
                            input=json.dumps(dict(method=method, path=path, body=body, token=token)),
                            capture_output=True, text=True, timeout=10)
    require(result.returncode == 0, f'Isolated request failed: {method} {path}')
    return json.loads(result.stdout)


def wait_for_process(unit, release, runtime):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        properties = staging.service_properties(unit)
        if properties.get('ActiveState') in ('inactive', 'failed'):
            raise RuntimeError('Isolated rollback service stopped')
        pid = int(properties.get('MainPID') or 0)
        if pid > 1 and ui.process_executable_matches(pid, release):
            require(properties.get('PrivateNetwork') == 'yes' and
                    properties.get('User') == staging.STAGING_USER and
                    properties.get('WorkingDirectory') == str(runtime) and
                    properties.get('ProtectSystem') == 'strict' and
                    properties.get('ReadWritePaths') == str(runtime) and
                    os.readlink(f'/proc/{pid}/ns/net') != os.readlink('/proc/self/ns/net'),
                    'Service isolation differs from rehearsal setup')
            env = staging.process_environment(pid)
            require(env.get('TN_DB') == str(runtime / 'test.sqlite') and
                    env.get('TN_ADDR') == staging.STAGING_ADDR, 'Wrong isolated database or address')
            try:
                health = request(pid, 'GET', '/v1/health')
            except RuntimeError:
                time.sleep(.25)
                continue
            require(health.get('status') == 'ok', 'Isolated backend is unhealthy')
            return pid
        time.sleep(.25)
    raise RuntimeError('Timed out waiting for the expected isolated release')


def copy_release(name, stage):
    origin = ui.BASE / 'releases' / name / 'tn-assistant'
    ui.regular_file(origin)
    target = stage / name
    target.mkdir(mode=0o755)
    target.chmod(0o755)
    executable = target / 'tn-assistant'
    shutil.copyfile(origin, executable)
    executable.chmod(0o555)
    checksum = ui.remote.sha(origin)
    require(ui.remote.sha(executable) == checksum, 'Staged executable hash mismatch')
    return target, checksum


def live_identity():
    current = (ui.BASE / 'current').resolve(strict=True)
    props = ui.systemd_properties()
    ui.validate_service(props)
    require(ui.process_executable_matches(int(props['MainPID']), current), 'Live executable differs from symlink')
    return current, props['MainPID']


def rehearse(previous_name, current_name):
    require(os.geteuid() == 0, 'Run inside the Fly VPS as root')
    require(all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', name) for name in (previous_name, current_name))
            and previous_name != current_name, 'Specify two different installed releases')
    before = live_identity()
    require(before[0] == ui.BASE / 'releases' / current_name, 'Expected live release has changed')
    account, group = staging.staging_account()
    label = 'rollback-' + secrets.token_hex(6)
    stage = staging.stage_parent(label, group)
    unit = 'tn-stage-' + label + '.service'
    try:
        previous, previous_hash = copy_release(previous_name, stage)
        candidate, candidate_hash = copy_release(current_name, stage)
        require(previous_hash != candidate_hash, 'Rehearsal requires distinct executable versions')
        current = stage / 'current'
        current.symlink_to(previous, target_is_directory=True)
        runtime = stage / 'runtime'
        runtime.mkdir(mode=0o700)
        os.chown(runtime, account.pw_uid, account.pw_gid)
        database = runtime / 'test.sqlite'
        # All application state is created in runtime by the staging user. Never copy live credentials.
        env = {'TN_RUNTIME_MODE': 'independent', 'TN_ADDR': staging.STAGING_ADDR, 'TN_DB': str(database),
               'TN_AI_MASTER_KEY_FILE': str(runtime / 'ai.key'), 'TN_ADMIN_PASSWORD': '',
               'TN_DOWNLOAD_DIR': str(runtime / 'downloads'), 'TN_STATIC_DIR': str(runtime / 'static'),
               'TN_PUBLIC_URL': 'http://' + staging.STAGING_ADDR}
        staging.run(['systemd-run', '--unit=' + unit, '--uid=' + staging.STAGING_USER,
                     '--gid=' + staging.STAGING_USER, '--working-directory=' + str(runtime),
                     '--property=PrivateNetwork=yes', '--property=ProtectSystem=strict',
                     '--property=ReadWritePaths=' + str(runtime), '--property=PrivateTmp=yes',
                     '--property=NoNewPrivileges=yes', '--property=ProtectHome=yes',
                     '--property=InaccessiblePaths=/etc/tn-assistant /var/lib/tn-assistant /srv/tn-assistant /www/wwwroot',
                     '--property=RuntimeMaxSec=150', '--property=TimeoutStopSec=10',
                     *('--setenv=' + k + '=' + v for k, v in env.items()), str(current / 'tn-assistant'), 'serve'])
        initial_pid = wait_for_process(unit, previous, runtime)
        guest = request(initial_pid, 'POST', '/v1/auth/guest',
                        {'device_id': label, 'device_name': 'Isolated rollback rehearsal'})
        token, uid = guest['access_token'], guest['user']['id']
        require(bool(token and uid), 'Guest session missing')
        version = database.stat()
        initial_volume = request(initial_pid, 'GET', '/v1/settings', token=token)['volume']
        changed_volume = 43 if initial_volume != 43 else 44
        phases = []

        def verify_candidate():
            pid = wait_for_process(unit, candidate, runtime)
            require(pid != initial_pid and request(pid, 'GET', '/v1/me', token=token)['user']['id'] == uid,
                    'Original session did not survive candidate activation')
            settings = request(pid, 'PUT', '/v1/settings', {'volume': changed_volume}, token)
            require(settings.get('volume') == changed_volume, 'Candidate write was not acknowledged')
            phases.append(pid)
            raise RejectedCandidate()

        def verify_recovery():
            pid = wait_for_process(unit, previous, runtime)
            require(pid != initial_pid and pid not in phases, 'Fallback executable did not restart')
            require(request(pid, 'GET', '/v1/me', token=token)['user']['id'] == uid,
                    'Rollback lost the original guest session')
            require(request(pid, 'GET', '/v1/settings', token=token)['volume'] == changed_volume,
                    'Rollback discarded the candidate write')
            require(request(pid, 'DELETE', '/v1/me', token=token).get('ok') is True,
                    'Guest cleanup failed on restored release')
            phases.append(pid)

        try:
            ui.activate(current, previous, candidate, verify_candidate, verify_recovery, service=unit)
        except RuntimeError as error:
            if not isinstance(error.__cause__, RejectedCandidate) or len(phases) != 2:
                raise
        else:
            raise RuntimeError('The simulated postflight failure did not trigger rollback')
        require(current.resolve(strict=True) == previous and os.path.samestat(version, database.stat()),
                'Rollback did not retain the initial database and switch the executable back')
        with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as db:
            require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'Scratch DB integrity failed')
    finally:
        staging.stop_and_clean(unit, stage)
    require(live_identity() == before and not stage.exists(), 'Production identity changed or rehearsal files remain')
    return {'previous': previous_name, 'previous_sha256': previous_hash, 'candidate': current_name,
            'candidate_sha256': candidate_hash, 'postflight_failure_exercised': True,
            'guest_session_preserved': True, 'candidate_write_preserved': True,
            'database_integrity': 'ok', 'private_stage_removed': True, 'production_pid_unchanged': True}


if __name__ == '__main__':
    if sys.argv[1:] == ['--probe']:
        probe()
    else:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('--previous', required=True)
        parser.add_argument('--current', required=True)
        args = parser.parse_args()
        print(json.dumps(rehearse(args.previous, args.current)))
