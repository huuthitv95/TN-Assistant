import json
import sqlite3
import unittest

from migrate_config import migrate_database, migrate_environment, migrate_providers, runtime_initialization_record_delta


class ConfigurationMigrationTest(unittest.TestCase):
    def test_environment_idempotence_and_values_preserved(self):
        original = '# comment\nKIKI_DB=/data/existing.db\nTN_ZALO_APP_SECRET="fixture-secret"\nKIKI_WEBHOOK_SECRET=fixture-webhook\n'
        migrated, changes = migrate_environment(original)
        self.assertEqual(2, changes)
        self.assertIn('TN_DB=/data/existing.db', migrated)
        self.assertIn('TN_ZALO_APP_SECRET="fixture-secret"', migrated)
        self.assertIn('TN_WEBHOOK_SECRET=fixture-webhook', migrated)
        self.assertEqual((migrated, 0), migrate_environment(migrated))

    def test_environment_conflicts_fail_and_identical_duplicates_merge(self):
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            migrate_environment('KIKI_DB=/data/a.db\nTN_DB=/data/b.db\n')
        self.assertEqual(('TN_DB=/data/a.db\n', 2), migrate_environment('KIKI_DB=/data/a.db\nTN_DB=/data/a.db\n'))

    def test_provider_migration_preserves_upstream_identity_and_unrelated_values(self):
        original = {'nlu': {'mode': 'legacy_kiki', 'client_app_type': 'APP_KIKI', 'url': 'https://legacy_kiki.invalid', 'secret_env': 'KIKI_NLU_SECRET', 'header_env': {'X-Provider': 'KIKI_HEADER'}, 'options': {'device_id_env': 'KIKI_DEVICE', 'form_fields': {'asr_type': 'KIKI'}}}}
        migrated, changes = migrate_providers(original)
        self.assertEqual(4, changes)
        self.assertEqual('legacy_tn', migrated['nlu']['mode'])
        self.assertEqual('TN_HEADER', migrated['nlu']['header_env']['X-Provider'])
        self.assertEqual('TN_DEVICE', migrated['nlu']['options']['device_id_env'])
        self.assertEqual('APP_KIKI', migrated['nlu']['client_app_type'])
        self.assertEqual('https://legacy_kiki.invalid', migrated['nlu']['url'])
        self.assertEqual('legacy_kiki', original['nlu']['mode'])
        self.assertEqual((migrated, 0), migrate_providers(migrated))

    def test_database_only_provider_record_changes(self):
        connection = sqlite3.connect(':memory:')
        connection.execute('CREATE TABLE records(kind TEXT,id TEXT,data TEXT)')
        connection.execute('INSERT INTO records VALUES(?,?,?)', ('config', 'providers', json.dumps({'tts': {'mode': 'legacy_kiki', 'secret_env': 'KIKI_TTS_SECRET'}})))
        connection.execute('INSERT INTO records VALUES(?,?,?)', ('history', 'owned', 'legacy_kiki APP_KIKI KIKI_TTS_SECRET'))
        connection.commit()
        self.assertEqual(2, migrate_database(connection))
        self.assertEqual(0, migrate_database(connection))
        self.assertEqual('legacy_kiki APP_KIKI KIKI_TTS_SECRET', connection.execute("SELECT data FROM records WHERE kind='history'").fetchone()[0])
        connection.close()

    def test_runtime_initialization_expected_record_delta(self):
        connection = sqlite3.connect(':memory:')
        connection.execute('CREATE TABLE records(kind TEXT,id TEXT,data TEXT)')
        self.assertEqual(2, runtime_initialization_record_delta(connection))
        connection.execute('INSERT INTO records VALUES(?,?,?)', ('config', 'runtime', '{}'))
        self.assertEqual(0, runtime_initialization_record_delta(connection))
        connection.close()


if __name__ == '__main__':
    unittest.main()
