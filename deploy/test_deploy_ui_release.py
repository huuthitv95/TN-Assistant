import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import deploy_ui_release as deployment


class IncrementalDeploymentTest(unittest.TestCase):
    def test_arguments_reject_traversal_and_accept_versioned_artifacts(self):
        checksum = 'a' * 64
        deployment.validate_arguments('release-122', 'tn-assistant-preview122.apk', (checksum,) * 3)
        for args in (
            ('../release', 'tn-assistant-preview122.apk', (checksum,) * 3),
            ('release', '../tn-assistant-preview122.apk', (checksum,) * 3),
            ('release', 'tn-assistant-preview122.apk', ('A' * 64,) * 3),
        ):
            with self.subTest(args=args), self.assertRaises(RuntimeError):
                deployment.validate_arguments(*args)

    def test_readiness_gate_rejects_liveness_and_stale_provider_checks(self):
        self.assertTrue(deployment.readiness_passes(200, {'ready': True, 'status': 'ready'}))
        self.assertFalse(deployment.readiness_passes(200, {'status': 'ok'}))
        self.assertFalse(deployment.readiness_passes(200, {'ready': False, 'status': 'not_ready'}))
        self.assertFalse(deployment.readiness_passes(503, {'ready': True, 'status': 'ready'}))

    def test_database_state_hashes_every_application_table(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'database.sqlite'
            connection = sqlite3.connect(path)
            connection.executescript('''
                CREATE TABLE schema_migrations(version INTEGER);
                INSERT INTO schema_migrations VALUES (1);
                CREATE TABLE users(id TEXT PRIMARY KEY, role TEXT);
                INSERT INTO users VALUES ('u1', 'admin');
                CREATE TABLE devices(id TEXT PRIMARY KEY, user_id TEXT, name TEXT);
                INSERT INTO devices VALUES ('d1', 'u1', 'TN device');
                CREATE TABLE sessions(id TEXT PRIMARY KEY, user_id TEXT);
                INSERT INTO sessions VALUES ('s1', 'u1');
                CREATE TABLE records(kind TEXT, id TEXT, data TEXT);
                INSERT INTO records VALUES ('config', 'runtime', '{"runtime_mode":"independent"}');
                CREATE TABLE payments(id TEXT PRIMARY KEY, status TEXT);
                INSERT INTO payments VALUES ('p1', 'pending');
                CREATE TABLE entitlements(id TEXT PRIMARY KEY, user_id TEXT);
                INSERT INTO entitlements VALUES ('e1', 'u1');
            ''')
            first = deployment.database_state(connection)
            connection.execute("UPDATE devices SET name='Changed but same row count' WHERE id='d1'")
            changed = deployment.database_state(connection)
            connection.close()
            self.assertNotEqual(first['table_sha256']['devices'], changed['table_sha256']['devices'])
            self.assertNotEqual(first, changed)

    def test_process_executable_is_checked_through_its_root_namespace(self):
        release = '/srv/tn-assistant/releases/candidate'
        with patch.object(deployment.os.path, 'samefile', return_value=True) as samefile:
            self.assertTrue(deployment.process_executable_matches(1234, release))
        samefile.assert_called_once_with(
            '/proc/1234/exe',
            '/proc/1234/root/srv/tn-assistant/releases/candidate/tn-assistant',
        )

    def test_candidate_failure_restores_previous_symlink_without_database_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            releases = base / 'releases'
            releases.mkdir()
            previous = releases / 'previous'
            candidate = releases / 'candidate'
            previous.mkdir()
            candidate.mkdir()
            current = base / 'current'
            current.write_text('symlink placeholder')
            active_target = {'path': previous}

            def fake_atomic_switch(_current, expected, target):
                if active_target['path'] != expected:
                    raise RuntimeError('Current release changed concurrently; refusing symlink replacement')
                active_target['path'] = target

            recoveries = []
            with patch.object(deployment, 'atomic_switch', side_effect=fake_atomic_switch) as switch:
                with patch.object(deployment.remote, 'run') as run:
                    with self.assertRaisesRegex(RuntimeError, 'previous executable restored'):
                        deployment.activate(
                            current,
                            previous,
                            candidate,
                            lambda: (_ for _ in ()).throw(RuntimeError('health failed')),
                            lambda: recoveries.append('healthy'),
                        )
            self.assertEqual(previous, active_target['path'])
            self.assertEqual(['healthy'], recoveries)
            self.assertEqual(2, run.call_count)
            self.assertEqual(2, switch.call_count)

    def test_static_backup_is_complete_and_private_copy_matches_live_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            web = root / 'web'
            web.mkdir()
            (web / 'index.html').write_text('<main>dashboard</main>')
            (web / 'assets').mkdir()
            (web / 'assets' / 'app.js').write_text('export const ready = true;')
            destination = root / 'backup' / 'static'
            destination.parent.mkdir()

            before = deployment.backup_static(web, destination, public=True)

            self.assertEqual(before, deployment.static_inventory(destination))
            self.assertEqual('<main>dashboard</main>', (destination / 'index.html').read_text())
            self.assertEqual('export const ready = true;', (destination / 'assets' / 'app.js').read_text())

    def test_public_static_verification_checks_local_and_reverse_proxy_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            web = root / 'web'
            source = root / 'release-static'
            web.mkdir()
            source.mkdir()
            content = b'<main>dashboard</main>'
            (web / 'index.html').write_bytes(content)
            (source / 'index.html').write_bytes(content)
            with patch.object(deployment, 'WEB', web), patch.object(deployment.remote, 'request', return_value=(200, content)) as request:
                result = deployment.verify_static_install(source)
            request.assert_called_once_with('https://assistant.tnfastvn.com/index.html', decode_json=False)
            self.assertEqual({'static_verified': True, 'static_file_count': 1}, result)

            with patch.object(deployment, 'WEB', web), patch.object(deployment.remote, 'request', return_value=(200, b'old dashboard')):
                with self.assertRaisesRegex(RuntimeError, 'Public dashboard content differs'):
                    deployment.verify_static_install(source)

    def test_static_install_keeps_host_files_and_restores_the_previous_dashboard(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            web = root / 'web'
            web.mkdir()
            (web / 'index.html').write_text('old dashboard')
            (web / '.htaccess').write_text('host rule')
            (web / 'assets').mkdir()
            (web / 'assets' / 'old.js').write_text('old bundle')
            candidate = root / 'candidate'
            candidate.mkdir()
            (candidate / 'index.html').write_text('new dashboard')
            (candidate / 'assets').mkdir()
            (candidate / 'assets' / 'new.js').write_text('new bundle')
            backup = root / 'backup'
            before = deployment.backup_static(web, backup, public=True)

            with patch.object(deployment.remote, 'WEB', web):
                deployment.remote.static_install(candidate)
                self.assertEqual('new dashboard', (web / 'index.html').read_text())
                self.assertTrue((web / '.htaccess').is_file())
                self.assertFalse((web / 'assets' / 'old.js').exists())
                deployment.remote.static_install(backup)

            self.assertEqual(before, deployment.static_inventory(web, public=True))


if __name__ == '__main__':
    unittest.main()
