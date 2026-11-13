import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

import publish_support as support


class SupportPublishTest(unittest.TestCase):
    def test_explicit_initial_snapshot_is_used_only_for_missing_config(self):
        with tempfile.TemporaryDirectory() as temp, sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE records(kind TEXT,id TEXT,owner TEXT,data TEXT,created_at TEXT)')
            initial = {'sandbox': True, 'device_limit': 10, 'map_packages': ['maps']}
            backup = Path(temp) / 'created.json'
            support.configure(connection, backup, initial)
            stored = json.loads(connection.execute("SELECT data FROM records WHERE id='public'").fetchone()[0])
            self.assertEqual(initial, {k: v for k, v in stored.items() if k != 'support'})
            self.assertIsNone(json.loads(backup.read_text())['before'])
            support.configure(connection, Path(temp) / 'updated.json', {'device_limit': 999})
            self.assertEqual(stored, json.loads(connection.execute("SELECT data FROM records WHERE id='public'").fetchone()[0]))

    def test_config_merge_preserves_unrelated_fields_and_existing_facebook(self):
        original = {'device_limit': 10, 'support': {'facebook_url': 'https://www.facebook.com/tn-owner', 'contact_description': 'TN'}}
        merged = support.merged_config(original)
        self.assertEqual(10, merged['device_limit'])
        self.assertEqual(original['support']['facebook_url'], merged['support']['facebook_url'])
        self.assertEqual('TN', merged['support']['contact_description'])
        self.assertEqual(7, len(merged['support']))
        self.assertNotIn('faq_url', original['support'])

    def test_config_update_is_targeted_backed_up_and_refuses_missing_row(self):
        with tempfile.TemporaryDirectory() as temp, sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE records(kind TEXT,id TEXT,data TEXT)')
            connection.execute("INSERT INTO records VALUES('config','runtime','{\"mode\":\"independent\"}')")
            connection.commit()
            with self.assertRaises(RuntimeError):
                support.configure(connection, Path(temp) / 'missing.json')
            connection.execute("INSERT INTO records VALUES('config','public',?)", (json.dumps({'device_limit': 10}),))
            connection.commit()
            backup = Path(temp) / 'backup.json'
            support.configure(connection, backup)
            self.assertEqual({'device_limit': 10}, json.loads(backup.read_text())['before'])
            self.assertEqual('{"mode":"independent"}', connection.execute("SELECT data FROM records WHERE id='runtime'").fetchone()[0])
            current = connection.execute("SELECT data FROM records WHERE id='public'").fetchone()[0]
            with self.assertRaises(FileExistsError):
                support.configure(connection, backup)
            self.assertEqual(current, connection.execute("SELECT data FROM records WHERE id='public'").fetchone()[0])

    def test_manifest_rejects_extra_files_and_changed_content(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in support.FILES:
                (root / name).write_text('reviewed')
            manifest = {name: support.digest(root / name) for name in support.FILES}
            support.verified_files(root, manifest)
            (root / 'secret.env').write_text('not public')
            with self.assertRaises(RuntimeError):
                support.verified_files(root, manifest)
            (root / 'secret.env').unlink()
            (root / 'faq.html').write_text('changed')
            with self.assertRaises(RuntimeError):
                support.verified_files(root, manifest)


if __name__ == '__main__':
    unittest.main()
