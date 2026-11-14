"""SDK-emulator acceptance for traffic FGS permission revocation/process death.

Requires installed Preview + instrumentation APKs. No production calls, credentials,
mock GPS or app-data wipe. Saved settings remain within the app's private sandbox.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

PACKAGE = 'vn.tn.assistant.preview'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'vn.tn.assistant.ui.TnRoadServiceTest'
PERMISSIONS = ['android.permission.ACCESS_COARSE_LOCATION',
               'android.permission.ACCESS_FINE_LOCATION', 'android.permission.POST_NOTIFICATIONS']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise RuntimeError('Only an explicitly selected SDK emulator is permitted')
    base = [args.adb, '-s', args.serial]

    def adb(*command, check=True):
        return subprocess.run(base + list(command), capture_output=True, text=True, encoding='utf-8', errors='replace', check=check).stdout

    def event(**values):
        print(json.dumps(values), flush=True)

    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Device is not an SDK emulator')
    info = adb('shell', 'dumpsys', 'package', PACKAGE)
    grants = {}
    for permission in PERMISSIONS:
        match = re.search(re.escape(permission) + r': granted=(true|false)', info)
        if not match:
            raise RuntimeError('Missing required installed Preview permission declaration')
        grants[permission] = match[1] == 'true'

    def prefs_digest():
        xml = adb('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/tn-session-v1.xml')
        entries = []
        for entry in ET.fromstring(xml):
            entries.append((entry.tag, sorted(entry.attrib.items()), entry.text or '',
                            sorted((child.tag, child.text or '') for child in entry)))
        return hashlib.sha256(json.dumps(sorted(entries), ensure_ascii=False).encode()).digest()

    before = prefs_digest()

    def service_running():
        output = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE + '/vn.tn.assistant.driving.TnRoadService')
        return bool(re.search(r'\* ServiceRecord\{.*TnRoadService', output))

    if service_running():
        raise RuntimeError('Stop the existing user trip before running isolated acceptance')
    try:
        for operation in ('permission', 'process'):
            for permission in PERMISSIONS:
                adb('shell', 'pm', 'grant', PACKAGE, permission)
            command = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'roadTermination', operation,
                              '-e', 'class', CLASS + '#externalTerminationProbe', RUNNER]
            probe = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, encoding='utf-8', errors='replace', bufsize=1)
            lines = queue.Queue()
            def collect():
                for line in probe.stdout:
                    lines.put(line)
            reader = threading.Thread(target=collect, daemon=True)
            reader.start()
            try:
                deadline = time.monotonic() + 45
                ready, pid = False, None
                while time.monotonic() < deadline and not (ready and pid):
                    try:
                        line = lines.get(timeout=.5)
                    except queue.Empty:
                        if probe.poll() is not None:
                            break
                        continue
                    if 'road_probe=READY' in line:
                        ready = True
                    match = re.search(r'road_pid=(\d+)', line)
                    if match:
                        pid = match[1]
                if not ready or not pid or not service_running():
                    raise RuntimeError('Probe did not establish a live foreground service')
                active_pids = adb('shell', 'pidof', PACKAGE).split()
                if pid not in active_pids:
                    raise RuntimeError('Probe PID no longer belongs to Preview')
                event(operation=operation, phase='foreground_confirmed')
                if operation == 'permission':
                    adb('shell', 'pm', 'revoke', PACKAGE, 'android.permission.ACCESS_FINE_LOCATION')
                else:
                    adb('shell', 'run-as', PACKAGE, 'kill', '-9', pid)
                probe.wait(timeout=20)
                deadline = time.monotonic() + 10
                while service_running() and time.monotonic() < deadline:
                    time.sleep(.25)
                if service_running():
                    raise RuntimeError('Traffic service survived external termination')
                time.sleep(3)
                if service_running():
                    raise RuntimeError('Traffic service restarted without user action')
                event(operation=operation, phase='stopped_without_restart')
            finally:
                if probe.poll() is None:
                    # A failed acceptance is terminated deliberately before recovery;
                    # it is never silently restarted or reported as a pass.
                    adb('shell', 'am', 'force-stop', PACKAGE)
                    probe.wait(timeout=10)
                reader.join(timeout=2)
                restored = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'roadTermination', 'restore',
                               '-e', 'class', CLASS + '#restoreAfterExternalTermination', RUNNER)
                if 'OK (1 test)' not in restored:
                    raise RuntimeError('Recovery failed; app-private settings backup is retained')
                if before != prefs_digest():
                    raise RuntimeError('Settings were not restored exactly')
                event(operation=operation, phase='settings_restored')
    finally:
        # Coarse is restored before fine when granting, fine before coarse when revoking.
        for permission in PERMISSIONS:
            if grants[permission]:
                adb('shell', 'pm', 'grant', PACKAGE, permission)
        for permission in reversed(PERMISSIONS):
            if not grants[permission]:
                adb('shell', 'pm', 'revoke', PACKAGE, permission)
    event(result='passed', operations=2, settings_unchanged=True)


if __name__ == '__main__':
    main()
