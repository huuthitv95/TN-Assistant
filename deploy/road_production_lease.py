"""Bounded operator lease for the TN road acceptance test (run inside the VPS).

No auth bypass: the host registers/logs in through the public TN API. This helper
grants a temporary local TN entitlement, enables managed driving briefly, then
restores configuration and removes only the two uniquely named test accounts.
An independent systemd timer runs cleanup after 5 minutes, including host loss.
State and this script must live under /run, owned by root and not in the webroot.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess

DATABASE = '/var/lib/tn-assistant/tn-assistant.sqlite'
LOCK = Path('/run/tn-road-production-lease.json')
ROAD_ID = 'osm-way-605133464-4-0-backward'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['begin', 'grant', 'enable', 'disable', 'finish'])
    parser.add_argument('identity')
    args = parser.parse_args()
    if os.geteuid() != 0 or not re.fullmatch('[a-f0-9]{16}', args.identity):
        raise RuntimeError('Root and a unique bounded lease identity are required')
    script = Path(__file__).resolve()
    if script != Path('/run/tn-road-production-lease.py') or script.is_symlink():
        raise RuntimeError('Lease helper must be installed at its fixed /run path')
    names = [f'tn-road-qa-{args.identity}-{tier}' for tier in ('basic', 'premium')]
    unit = 'tn-road-qa-' + args.identity
    connection = sqlite3.connect(DATABASE, timeout=15)
    connection.execute('PRAGMA foreign_keys=ON')
    connection.execute('BEGIN IMMEDIATE')
    try:
        raw = connection.execute("SELECT data FROM records WHERE kind='config' AND id='runtime'").fetchone()[0]
        if args.operation == 'begin':
            cfg = json.loads(raw)
            if cfg['driving_enabled'] is not False:
                raise RuntimeError('Requires initially disabled managed driving')
            if connection.execute('SELECT count(*) FROM managed_roads WHERE id=?', (ROAD_ID,)).fetchone()[0] != 1:
                raise RuntimeError('The road acceptance target is missing or ambiguous')
            if connection.execute("SELECT count(*) FROM entitlements WHERE starts_at<=strftime('%Y-%m-%dT%H:%M:%fZ','now') AND expires_at>strftime('%Y-%m-%dT%H:%M:%fZ','now')").fetchone()[0]:
                raise RuntimeError('An active Premium entitlement exists; refusing a global production flag window')
            if connection.execute('SELECT count(*) FROM users WHERE username IN (?,?)', names).fetchone()[0]:
                raise RuntimeError('Test identity already exists')
            enabled = dict(cfg, driving_enabled=True)
            state = dict(identity=args.identity, original=raw, enabled=json.dumps(enabled), names=names)
            fd = os.open(LOCK, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as stream:
                json.dump(state, stream)
                stream.flush()
                os.fsync(stream.fileno())
            # Arm recovery before any account/entitlement/config mutation.
            result = subprocess.run(['systemd-run', '--quiet', '--unit=' + unit,
                '--on-active=300s', '--timer-property=AccuracySec=1s',
                '/usr/bin/python3', str(script), 'finish', args.identity], capture_output=True)
            if result.returncode:
                LOCK.unlink()
                raise RuntimeError('Could not arm independent recovery timer')
            connection.commit()
            print(json.dumps(dict(operation='begin', recovery_seconds=300)))
            return
        if not LOCK.exists():
            if args.operation == 'finish':
                print(json.dumps(dict(operation='finish', already_finished=True)))
                return
            raise RuntimeError('Lease missing or expired')
        state = json.loads(LOCK.read_text())
        if state['identity'] != args.identity or state['names'] != names:
            raise RuntimeError('Lease ownership mismatch')
        if args.operation == 'grant':
            row = connection.execute('SELECT id,role FROM users WHERE username=?', (names[1],)).fetchone()
            if not row or row[1] != 'user':
                raise RuntimeError('Expected a registered temporary TN user')
            start = dt.datetime.now(dt.timezone.utc)
            stamp = lambda value: value.isoformat(timespec='seconds').replace('+00:00', 'Z')
            connection.execute('INSERT INTO entitlements VALUES(?,?,?,?,?,?)',
                ('road-qa-' + args.identity, row[0], 'road-qa', 'local-admin:road-qa-' + args.identity,
                 stamp(start - dt.timedelta(seconds=10)), stamp(start + dt.timedelta(minutes=5))))
        elif args.operation in ('enable', 'disable', 'finish'):
            # Compare-and-swap: never replace unrelated concurrent configuration.
            if raw not in (state['original'], state['enabled']):
                raise RuntimeError('Runtime config changed outside lease; operator review required')
            wanted = state['enabled'] if args.operation == 'enable' else state['original']
            connection.execute("UPDATE records SET data=? WHERE kind='config' AND id='runtime' AND data=?", (wanted, raw))
            if args.operation == 'finish':
                for name in names:
                    row = connection.execute('SELECT id,role FROM users WHERE username=?', (name,)).fetchone()
                    if row is None:
                        continue
                    uid, role = row
                    if role != 'user':
                        raise RuntimeError('Temporary user role changed; refusing account removal')
                    # The test never pays, redeems or links. Unexpected activity prevents deletion.
                    for table in ('redemptions', 'payments', 'link_codes'):
                        if connection.execute(f'SELECT count(*) FROM {table} WHERE user_id=?', (uid,)).fetchone()[0]:
                            raise RuntimeError('Unexpected account activity; retain for review')
                    for table in ('sessions', 'entitlements', 'settings', 'devices'):
                        connection.execute(f'DELETE FROM {table} WHERE user_id=?', (uid,))
                    connection.execute('DELETE FROM users WHERE id=? AND username=? AND role=?', (uid, name, 'user'))
        connection.commit()
        if args.operation == 'finish':
            LOCK.unlink()
            # Stopping the timer is safe even from its own cleanup service.
            subprocess.run(['systemctl', 'stop', unit + '.timer'], capture_output=True)
        print(json.dumps(dict(operation=args.operation, ok=True)))
    finally:
        connection.close()


if __name__ == '__main__':
    main()
