import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import publish_android_preview as publisher


class AndroidPreviewPublishTest(unittest.TestCase):
    def valid_source_files(self):
        mark_path = 'android/app/src/independent/res/drawable-nodpi/tn_user_welcome_circle.png'
        license_path = 'android/app/src/independent/assets/licenses/tn-welcome-icon.json'
        mark = b'owner-supplied-icon'
        return {
            'RELEASE.json': json.dumps({'release': 'release123', 'runtime': False}).encode(),
            'android/app/build.gradle.kts': b'versionCode=26103123; versionName="26.10.31.123"',
            mark_path: mark,
            license_path: json.dumps({'sha256': hashlib.sha256(mark).hexdigest()}).encode(),
        }

    def make_archive(self, path, files):
        entries = [
            {'path': name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
            for name, data in sorted(files.items())
        ]
        payload = {**files, 'MANIFEST.json': json.dumps(entries).encode()}
        with tarfile.open(path, 'w:gz') as archive:
            for name, data in payload.items():
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))

    def test_valid_source_archive_checks_release_manifest_and_waveform_artwork(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / 'source.tar.gz'
            self.make_archive(archive, self.valid_source_files())
            result = publisher.inspect_source_archive(archive, 'release123')
            self.assertIn('android/app/build.gradle.kts', result)

    def test_source_version_matches_versioned_preview_across_release_dates(self):
        publisher.validate_source_version('versionCode=26103135; versionName="26.10.31.135"', '135')
        publisher.validate_source_version('versionCode=26103134; versionName="26.10.31.134"', '134')
        publisher.validate_source_version('versionCode=26110681; versionName="26.11.06.181"', '181')

    def test_source_version_rejects_filename_or_version_code_mismatch(self):
        for source, apk_version in (
            ('versionCode=26103135; versionName="26.10.31.135"', '134'),
            ('versionCode=26103134; versionName="26.10.31.135"', '135'),
            ('versionCode=26110680; versionName="26.11.06.181"', '181'),
            ('versionCode=26110681; versionName="26.11.05.181"', '181'),
            ('versionName="preview.135"', '135'),
        ):
            with self.subTest(source=source, apk_version=apk_version), self.assertRaisesRegex(
                    RuntimeError, 'version do not match'):
                publisher.validate_source_version(source, apk_version)

    def test_source_archive_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / 'source.tar.gz'
            files = self.valid_source_files()
            entries = [
                {'path': name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                for name, data in sorted(files.items())
            ] + [{'path': '../escape', 'size': 0, 'sha256': hashlib.sha256(b'').hexdigest()}]
            with tarfile.open(archive, 'w:gz') as output:
                for name, data in files.items():
                    info = tarfile.TarInfo(name); info.size = len(data)
                    output.addfile(info, io.BytesIO(data))
                manifest = json.dumps(entries).encode()
                info = tarfile.TarInfo('MANIFEST.json'); info.size = len(manifest)
                output.addfile(info, io.BytesIO(manifest))
                escape = tarfile.TarInfo('../escape'); escape.size = 0
                output.addfile(escape, io.BytesIO(b''))
            with self.assertRaisesRegex(RuntimeError, 'unsafe or duplicate path'):
                publisher.inspect_source_archive(archive, 'release123')

    def test_publish_arguments_reject_traversal_or_mismatched_name(self):
        checksum = 'a' * 64
        publisher.validate_args('release123', 'tn-assistant-preview123.apk', checksum, checksum)
        for values in (
            ('../release', 'tn-assistant-preview123.apk', checksum, checksum),
            ('release123', '../preview123.apk', checksum, checksum),
            ('release123', 'tn-assistant-preview.apk', checksum, checksum),
            ('release123', 'tn-assistant-preview123.apk', 'A' * 64, checksum),
        ):
            with self.subTest(values=values), self.assertRaises(RuntimeError):
                publisher.validate_args(*values)

    def test_staging_cleanup_removes_only_the_verified_publish_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            original_staging = publisher.STAGING
            try:
                publisher.STAGING = Path(temporary) / 'staging'
                stage = publisher.STAGING / 'release123'
                stage.mkdir(parents=True)
                (stage / 'source.tar.gz').write_bytes(b'source')
                (stage / 'tn-assistant-preview.apk').write_bytes(b'apk')
                self.assertTrue(publisher.cleanup_staging(stage))
                self.assertFalse(stage.exists())

                stage.mkdir()
                (stage / 'source.tar.gz').write_bytes(b'source')
                (stage / 'tn-assistant-preview.apk').write_bytes(b'apk')
                (stage / 'keep.txt').write_text('preserve')
                self.assertFalse(publisher.cleanup_staging(stage))
                self.assertEqual('preserve', (stage / 'keep.txt').read_text())
            finally:
                publisher.STAGING = original_staging


if __name__ == '__main__':
    unittest.main()
