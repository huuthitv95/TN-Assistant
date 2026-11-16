"""Exercise the Preview boot reminder across a real Android SDK emulator reboot.

The original app preference XML is held in memory and restored byte-for-byte. The test clears
its synthetic session before opening MainActivity, so it performs no authenticated request or
provider speech call.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


PACKAGE = 'vn.tn.assistant.preview'
TEST_COMPONENT = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
TEST_CLASS = 'vn.tn.assistant.ui.TnBootWelcomeRealBootTest'
PREFERENCES = 'shared_prefs/tn-session-v1.xml'
NOTIFICATION_PERMISSION = 'android.permission.POST_NOTIFICATIONS'
RESTORABLE_PERMISSION_FLAGS = (
    'review-required', 'revoked-compat', 'revoke-when-requested', 'user-fixed', 'user-set',
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit Android SDK emulator serial, for example emulator-5570')
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('--serial must identify an Android SDK emulator')
    base = [args.adb, '-s', args.serial]

    def command(parts, *, data=None, timeout=60, check=True):
        result = subprocess.run(base + list(parts), input=data, capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError('ADB operation failed with exit code ' + str(result.returncode))
        return result

    def adb(*parts, data=None, timeout=60):
        return command(parts, data=data, timeout=timeout).stdout

    def boot_count():
        value = adb('shell', 'settings', 'get', 'global', 'boot_count').decode('utf-8').strip()
        if not value.isdigit():
            raise RuntimeError('Android did not report a numeric boot count')
        return int(value)

    def notification_permission_state():
        output = adb('shell', 'dumpsys', 'package', PACKAGE).decode('utf-8', errors='replace')
        match = re.search(
            r'android\.permission\.POST_NOTIFICATIONS:\s*granted=(true|false),\s*flags=\[([^\]]*)\]',
            output,
        )
        if not match:
            raise RuntimeError('Could not snapshot Preview notification permission state')
        flags = frozenset(value.strip().lower().replace('_', '-')
                           for value in match.group(2).split('|') if value.strip())
        derived = {'user-sensitive-when-granted', 'user-sensitive-when-denied'}
        unknown = flags - set(RESTORABLE_PERMISSION_FLAGS) - derived
        if unknown:
            raise RuntimeError('Preview notification permission has flags this harness cannot restore')
        return {'granted': match.group(1) == 'true', 'flags': flags}

    def restore_notification_permission(original):
        current = notification_permission_state()
        if current['granted'] != original['granted']:
            operation = 'grant' if original['granted'] else 'revoke'
            command(('shell', 'pm', operation, PACKAGE, NOTIFICATION_PERMISSION))
        command(('shell', 'pm', 'clear-permission-flags', PACKAGE, NOTIFICATION_PERMISSION,
                 *RESTORABLE_PERMISSION_FLAGS))
        mutable_flags = sorted(original['flags'] & set(RESTORABLE_PERMISSION_FLAGS))
        if mutable_flags:
            command(('shell', 'pm', 'set-permission-flags', PACKAGE, NOTIFICATION_PERMISSION, *mutable_flags))
        restored = notification_permission_state()
        if restored != original:
            raise RuntimeError('Preview notification permission did not restore exactly')

    def wait_for_boot(timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = command(('get-state',), timeout=10, check=False)
            if state.returncode == 0 and state.stdout.decode('utf-8').strip() == 'device':
                completed = command(('shell', 'getprop', 'sys.boot_completed'), timeout=10, check=False)
                if completed.returncode == 0 and completed.stdout.decode('utf-8').strip() == '1':
                    return
            time.sleep(1)
        raise RuntimeError('The selected emulator did not finish booting in time')

    def instrumentation(method):
        result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     TEST_CLASS + '#' + method, TEST_COMPONENT, timeout=240).decode('utf-8', errors='replace')
        if 'OK (1 test)' not in result or 'FAILURES!!!' in result:
            raise RuntimeError('Boot acceptance instrumentation failed: ' + method)

    def restore_preferences(original, existed):
        command(('shell', 'am', 'force-stop', PACKAGE), timeout=20, check=False)
        if existed:
            write_preferences(original)
            restored = adb('exec-out', 'run-as', PACKAGE, 'cat', PREFERENCES)
            if restored != original:
                raise RuntimeError('Preview preferences did not restore exactly')
        else:
            command(('shell', 'run-as', PACKAGE, 'rm', '-f', PREFERENCES), timeout=20)
            check = command(('shell', 'run-as', PACKAGE, 'test', '-f', PREFERENCES), timeout=20, check=False)
            if check.returncode == 0:
                raise RuntimeError('Temporary Preview preferences were not removed')

    def write_preferences(data):
        remote = "run-as " + PACKAGE + " sh -c 'umask 077; cat > " + PREFERENCES + "'"
        adb('shell', '-T', remote, data=data)
        adb('shell', 'run-as', PACKAGE, 'chmod', '600', PREFERENCES)

    def package_stopped_state():
        output = adb('shell', 'dumpsys', 'package', PACKAGE).decode('utf-8', errors='replace')
        match = re.search(r'User 0:.*?\bstopped=(true|false)', output, re.DOTALL)
        if not match:
            raise RuntimeError('Could not inspect the Preview stopped state')
        return match.group(1) == 'true'

    def make_sessionless_preferences(data):
        try:
            root = ET.fromstring(data)
        except ET.ParseError as error:
            raise RuntimeError('Could not prepare isolated Preview preferences') from error
        for child in list(root):
            if child.tag == 'string' and child.attrib.get('name') == 'refresh_ciphertext':
                root.remove(child)
        return ET.tostring(root, encoding='utf-8', xml_declaration=True)

    if adb('get-state').decode('utf-8').strip() != 'device':
        raise RuntimeError('The selected emulator is not online')
    if not adb('shell', 'pm', 'path', PACKAGE).startswith(b'package:'):
        raise RuntimeError('TN Assistant Preview is not installed on the selected emulator')
    if not adb('shell', 'pm', 'list', 'instrumentation').__contains__(TEST_COMPONENT.encode('utf-8')):
        raise RuntimeError('Preview instrumentation APK is not installed')

    preference_check = command(('shell', 'run-as', PACKAGE, 'test', '-f', PREFERENCES), check=False)
    if preference_check.returncode not in (0, 1):
        raise RuntimeError('Could not inspect the Preview preference file')
    original_exists = preference_check.returncode == 0
    original = adb('exec-out', 'run-as', PACKAGE, 'cat', PREFERENCES) if original_exists else b''
    original_sha256 = hashlib.sha256(original).hexdigest() if original_exists else None
    original_notification_permission = notification_permission_state()
    preflight_passed = False
    stage_attempted = False
    permission_change_attempted = False
    verified = False
    failure = None
    before_boot = None

    try:
        if not original_notification_permission['granted']:
            permission_change_attempted = True
            command(('shell', 'pm', 'grant', PACKAGE, NOTIFICATION_PERMISSION))
        instrumentation('preflightRequiresNotificationPermissionAndNoExistingBootChannel')
        preflight_passed = True
        before_boot = boot_count()
        stage_attempted = True
        instrumentation('stageOneTimeSessionAndBootWelcomeOptInForHostReboot')
        staged_preferences = adb('exec-out', 'run-as', PACKAGE, 'cat', PREFERENCES)
        # Instrumentation marks its target package stopped when it exits. Start the real app
        # without the synthetic session to clear that Android state, then restore the staged
        # encrypted session on disk before reboot so BOOT_COMPLETED can reach the receiver.
        write_preferences(make_sessionless_preferences(staged_preferences))
        launched = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/vn.tn.assistant.MainActivity').decode(
            'utf-8', errors='replace')
        if 'Status: ok' not in launched or package_stopped_state():
            raise RuntimeError('Could not prepare Preview for real BOOT_COMPLETED delivery')
        write_preferences(staged_preferences)
        command(('reboot',), timeout=30)
        wait_for_boot()
        after_boot = boot_count()
        if after_boot != before_boot + 1:
            raise RuntimeError('The Android boot count did not advance exactly once')
        instrumentation('actualBootPromptOpensMainActivityWithoutStartingSpeech')
        verified = True
    except Exception as error:
        failure = error
    finally:
        if preflight_passed and stage_attempted:
            try:
                wait_for_boot(timeout=180)
                instrumentation('cleanupSyntheticBootState')
            except Exception:
                # Still attempt exact preference restoration if test cleanup itself failed.
                pass
        permission_error = None
        if permission_change_attempted:
            try:
                restore_notification_permission(original_notification_permission)
            except Exception as error:
                permission_error = error
        if stage_attempted:
            try:
                wait_for_boot(timeout=180)
                restore_preferences(original, original_exists)
            except Exception as error:
                raise RuntimeError('Could not restore Preview state after reboot acceptance') from error
        if permission_error is not None:
            raise RuntimeError('Could not restore Preview notification permission') from permission_error

    if failure is not None:
        raise RuntimeError(str(failure)) from failure
    if not verified:
        raise RuntimeError('Real boot acceptance did not complete')
    restored_sha256 = hashlib.sha256(
        adb('exec-out', 'run-as', PACKAGE, 'cat', PREFERENCES)
    ).hexdigest() if original_exists else None
    if restored_sha256 != original_sha256:
        raise RuntimeError('Preview preference checksum changed after cleanup')
    print(json.dumps({
        'serial': args.serial,
        'boot_count_before': before_boot,
        'boot_count_after': after_boot,
        'boot_notification_and_tap': 'passed',
        'provider_calls': 0,
        'preferences_restored': True,
        'production_changed': False,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
