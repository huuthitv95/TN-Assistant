"""Opt-in GPS -> production HTTPS -> Android dialog acceptance on an SDK emulator.

Creates two temporary accounts via the normal TN API. Uses a root-operated,
five-minute VPS recovery lease to grant local Premium and enable managed roads.
Does not call speech providers, restart production or publish an APK.
Requires Preview/helper APKs installed and an authenticated Fly CLI.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import urllib.request
import xml.etree.ElementTree as ET

PACKAGE = 'vn.tn.assistant.preview'
ORIGIN = 'https://assistant.tnfastvn.com'
APP = 'tn-ubuntu2404-sin-261026'
HELPER = '/run/tn-road-production-lease.py'
PRIVATE_INPUT = 'files/tn-road-production.private.json'
PERMISSIONS = ['android.permission.ACCESS_COARSE_LOCATION', 'android.permission.ACCESS_FINE_LOCATION']
QUERY = dict(lat=10.769737, lon=106.71151015, bearing=80.042485845,
             road_id='osm-way-605133464-4-0-backward', speed=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apply', action='store_true', help='Create temporary production accounts and enable the bounded lease')
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    parser.add_argument('--fly', default=str(Path(os.environ.get('TEMP', '')) / 'tn-assistant-flyctl/flyctl.exe'))
    args = parser.parse_args()
    if not args.apply or not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('--apply and an explicit SDK emulator serial are required')
    base = [args.adb, '-s', args.serial]

    def event(**values):
        print(json.dumps(values), flush=True)

    def command(argv, *, data=None, timeout=90):
        result = subprocess.run(argv, input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            # Never echo command arguments, input, token-bearing responses or stderr.
            raise RuntimeError('Operator command failed, exit=' + str(result.returncode))
        return result.stdout

    def adb(*parts, data=None, timeout=90):
        return command(base + list(parts), data=data, timeout=timeout).decode('utf-8')

    def remote(source):
        encoded = base64.b64encode(source.encode()).decode()
        shell = "chroot /data/rootfs python3 -c 'import base64;exec(base64.b64decode(\"" + encoded + "\"))'"
        return command([args.fly, 'ssh', 'console', '-a', APP, '-C', shell]).decode('utf-8').strip()

    identity = secrets.token_hex(8)

    def lease(operation):
        value = remote('import subprocess\nr=subprocess.run(["python3",' + repr(HELPER) + ',' +
                       repr(operation) + ',' + repr(identity) + ']);raise SystemExit(r.returncode)')
        event(lease=operation, result=value)

    def remove_remote_helper():
        remote('import pathlib\np=pathlib.Path(' + repr(HELPER) + ')\n' +
               'assert not pathlib.Path("/run/tn-road-production-lease.json").exists()\np.unlink(missing_ok=True)')

    def post(path, payload):
        request = urllib.request.Request(ORIGIN + path, data=json.dumps(payload).encode(),
                                         headers={'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200 or response.url != ORIGIN + path:
                raise RuntimeError('Unexpected auth response')
            return json.load(response)

    with urllib.request.urlopen(ORIGIN + '/v1/health', timeout=15) as response:
        health = json.load(response)
        if response.status != 200 or health.get('status') != 'ok':
            raise RuntimeError('Production health preflight failed')
    event(preflight='production_health_ok')

    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('SDK emulator required')
    if '* ServiceRecord{' in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE + '/vn.tn.assistant.driving.TnRoadService'):
        raise RuntimeError('An existing traffic trip must finish before this test')
    adb('shell', 'am', 'force-stop', PACKAGE)
    info = adb('shell', 'dumpsys', 'package', PACKAGE)
    grants = {}
    for permission in PERMISSIONS:
        match = re.search(re.escape(permission) + r': granted=(true|false)', info)
        if not match:
            raise RuntimeError('Installed Preview lacks location permission declaration')
        grants[permission] = match[1] == 'true'
    mock = adb('shell', 'appops', 'get', PACKAGE, 'android:mock_location')
    mode = re.search(r'MOCK_LOCATION: (\w+)', mock)
    old_mock = mode[1] if mode else 'default'
    if old_mock not in ('default', 'allow', 'ignore', 'deny', 'foreground'):
        raise RuntimeError('Unknown mock-location appop')
    pref_path = 'shared_prefs/tn-session-v1.xml'
    before = adb('shell', 'run-as ' + PACKAGE + " sh -c 'if test -f " + pref_path +
                 "; then cat " + pref_path + "; else printf __TN_PREFS_ABSENT__; fi'").strip()
    pref_mode = None if before == '__TN_PREFS_ABSENT__' else adb(
        'shell', 'run-as ' + PACKAGE + " sh -c 'stat -c %a " + pref_path + "'").strip()

    def digest(xml):
        if xml == '__TN_PREFS_ABSENT__':
            return hashlib.sha256(xml.encode()).digest()
        rows = [(node.tag, sorted(node.attrib.items()), node.text or '',
                 sorted((child.tag, child.text or '') for child in node)) for node in ET.fromstring(xml)]
        return hashlib.sha256(json.dumps(sorted(rows), ensure_ascii=False).encode()).digest()

    before_digest = digest(before)
    source = (Path(__file__).resolve().parents[1] / 'deploy/road_production_lease.py').read_bytes()
    encoded = base64.b64encode(source).decode()
    remote('import pathlib,base64,os\np=pathlib.Path(' + repr(HELPER) + ')\n' +
           'assert not pathlib.Path("/run/tn-road-production-lease.json").exists(), "A lease already exists"\n' +
           'assert not p.is_symlink()\np.write_bytes(base64.b64decode(' + repr(encoded) + '))\nos.chmod(p,0o600)')
    lease('begin')
    try:
        accounts = {}
        for tier in ('basic', 'premium'):
            login = dict(username=f'tn-road-qa-{identity}-{tier}', password=secrets.token_urlsafe(24),
                         device_id=f'road-qa-{identity}-{tier}', device_name='TN SDK road acceptance')
            response = post('/v1/auth/register', dict(login, name='Temporary road acceptance'))
            # Registration creates a session; revoke it before Android logs in independently.
            post('/v1/auth/logout', dict(refresh_token=response['refresh_token']))
            accounts[tier] = login
        lease('grant')
        for permission in PERMISSIONS:
            adb('shell', 'pm', 'grant', PACKAGE, permission)
        adb('shell', 'appops', 'set', PACKAGE, 'android:mock_location', 'allow')
        for tier, expected in [('basic', 'premium_required'), ('premium', 'driving_disabled'), ('premium', 'matched')]:
            if expected == 'matched':
                lease('enable')
            payload = json.dumps(dict(login=accounts[tier], expected=expected, query=QUERY)).encode()
            # stdin goes directly into the app-private sandbox, never adb arguments/logs or shared storage.
            adb('shell', 'run-as ' + PACKAGE + " sh -c 'umask 077; cat > " + PRIVATE_INPUT + "'", data=payload)
            output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'roadProduction', 'true',
                '-e', 'class', 'vn.tn.assistant.ui.TnRoadProductionTest',
                PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=90)
            if 'OK (1 test)' not in output or 'FAILURES' in output:
                # Test source contains no credential logging; keep only bounded assertion diagnostics.
                safe = [line for line in output.splitlines() if 'Assertion' in line or 'Live GPS/API state' in line]
                event(tier=tier, expected=expected, passed=False, assertions=safe[:4])
                raise RuntimeError('Production Android acceptance failed')
            event(tier=tier, expected=expected, passed=True)
            if expected == 'matched':
                lease('disable')
            if digest(adb('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/tn-session-v1.xml')) != before_digest:
                raise RuntimeError('Private settings did not restore exactly')
    finally:
        try:
            lease('finish')
            remove_remote_helper()
        finally:
            adb('shell', 'am', 'force-stop', PACKAGE)
            # Exact named private input; never remove app data or shared directories.
            adb('shell', 'run-as', PACKAGE, 'rm', '-f', PRIVATE_INPUT)
            # Recovery after instrumentation crash/timeout also restores the original encrypted preferences.
            current = adb('shell', 'run-as ' + PACKAGE + " sh -c 'if test -f " + pref_path +
                          "; then cat " + pref_path + "; else printf __TN_PREFS_ABSENT__; fi'").strip()
            if digest(current) != before_digest:
                if before == '__TN_PREFS_ABSENT__':
                    adb('shell', 'run-as', PACKAGE, 'rm', '-f', pref_path)
                else:
                    adb('shell', 'run-as ' + PACKAGE + " sh -c 'cat > " + pref_path + "'", data=before.encode())
                    adb('shell', 'run-as', PACKAGE, 'chmod', pref_mode, pref_path)
            adb('shell', 'appops', 'set', PACKAGE, 'android:mock_location', old_mock)
            for permission in PERMISSIONS:
                if grants[permission]:
                    adb('shell', 'pm', 'grant', PACKAGE, permission)
            for permission in reversed(PERMISSIONS):
                if not grants[permission]:
                    adb('shell', 'pm', 'revoke', PACKAGE, permission)
            restored = adb('shell', 'run-as ' + PACKAGE + " sh -c 'if test -f " + pref_path +
                           "; then cat " + pref_path + "; else printf __TN_PREFS_ABSENT__; fi'").strip()
            if digest(restored) != before_digest:
                raise RuntimeError('Final private settings recovery failed')
    event(result='passed', cases=3, production_driving_restored=True, private_settings_restored=True)


if __name__ == '__main__':
    main()
