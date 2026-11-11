"""Bounded 24-hour availability observation; stores no credentials or response bodies."""
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request

STATE = Path('/var/lib/tn-assistant/ops')


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    os.replace(temporary, path)


def probe(url, health=False):
    try:
        with urllib.request.urlopen(url, timeout=12) as response:
            result = {'status': response.status, 'ok': response.status == 200}
            if health:
                result['ok'] = result['ok'] and json.load(response).get('status') == 'ok'
            return result
    except Exception as error:
        return {'ok': False, 'error': type(error).__name__}


def main():
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    release = os.environ.get('TN_MONITOR_RELEASE', '')
    run_id = os.environ.get('TN_MONITOR_RUN_ID', '')
    if release and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', release):
        raise RuntimeError('Invalid release-specific monitor identifier')
    if run_id and (not release or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', run_id)):
        raise RuntimeError('Invalid release monitor run identifier')
    label = f'{release}-{run_id}' if run_id else release
    report_file = STATE / (f'availability-{label}-24h.json' if release else 'availability-24h.json')
    samples_file = STATE / (f'availability-{label}-samples.jsonl' if release else 'availability-samples.jsonl')
    expected_release = Path('/srv/tn-assistant/releases') / release if release else None
    if report_file.exists():
        report = json.loads(report_file.read_text())
        if release and (report.get('release') != release or report.get('run_id', '') != run_id):
            raise RuntimeError('Existing release monitor report has a different identity')
        if report.get('status') == 'completed':
            return
        if report.get('status') != 'observing':
            raise RuntimeError('Existing monitor report cannot be resumed')
        if release and report.get('samples', 0):
            last = report.get('last_sample', {}).get('at')
            if not last:
                raise RuntimeError('Release-specific monitor has no last sample')
            gap = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(last)).total_seconds()
            if gap > 120:
                report['failed_samples'] += 1
                report['resume_gap_seconds'] = gap
                save(report_file, report)
    else:
        if samples_file.exists():
            raise RuntimeError('Monitor samples exist without a matching report')
        started = dt.datetime.now(dt.timezone.utc)
        report = {'started_at': started.isoformat(), 'ends_at': (started + dt.timedelta(hours=24)).isoformat(), 'status': 'observing', 'samples': 0, 'failed_samples': 0}
        if release:
            properties = subprocess.check_output(['systemctl', 'show', 'tn-assistant.service',
                                                  '--property=ActiveState,MainPID,ExecMainStartTimestampMonotonic'], text=True)
            baseline = dict(line.split('=', 1) for line in properties.splitlines() if '=' in line)
            if (baseline.get('ActiveState') != 'active' or not baseline.get('MainPID', '').isdigit()
                    or int(baseline['MainPID']) <= 1
                    or not baseline.get('ExecMainStartTimestampMonotonic', '').isdigit()):
                raise RuntimeError('The release-specific monitor requires an active backend process')
            report.update({'release': release, 'run_id': run_id, 'scope': 'release_specific_service_availability',
                           'baseline_main_pid': baseline['MainPID'],
                           'baseline_exec_start_monotonic': baseline.get('ExecMainStartTimestampMonotonic', '')})
        save(report_file, report)
    if expected_release is not None and Path('/srv/tn-assistant/current').resolve(strict=True) != expected_release:
        raise RuntimeError('The release-specific monitor must start on the expected release')
    deadline = dt.datetime.fromisoformat(report['ends_at']).timestamp()
    while time.time() < deadline:
        properties = subprocess.check_output(['systemctl', 'show', 'tn-assistant.service',
                                              '--property=ActiveState,NRestarts,MainPID,ExecMainStartTimestampMonotonic'], text=True)
        service = dict(line.split('=', 1) for line in properties.splitlines() if '=' in line)
        sample = {'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'local': probe('http://127.0.0.1:8094/v1/health', True), 'public_health': probe('https://assistant.tnfastvn.com/v1/health', True), 'dashboard': probe('https://assistant.tnfastvn.com/'), 'service': service}
        sample['ok'] = service.get('ActiveState') == 'active' and all(sample[key]['ok'] for key in ('local', 'public_health', 'dashboard'))
        if release:
            try:
                sample['release_unchanged'] = Path('/srv/tn-assistant/current').resolve(strict=True) == expected_release
            except OSError:
                sample['release_unchanged'] = False
            sample['process_unchanged'] = (service.get('MainPID') == report['baseline_main_pid']
                                           and service.get('ExecMainStartTimestampMonotonic') == report['baseline_exec_start_monotonic'])
            sample['ok'] = sample['ok'] and sample['release_unchanged'] and sample['process_unchanged']
        with samples_file.open('a') as output:
            output.write(json.dumps(sample) + '\n')
        report['samples'] += 1
        report['failed_samples'] += not sample['ok']
        report['last_sample'] = sample
        save(report_file, report)
        time.sleep(min(60, max(0, deadline - time.time())))
    report['status'] = 'completed'
    report['completed_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
    save(report_file, report)


if __name__ == '__main__':
    main()
