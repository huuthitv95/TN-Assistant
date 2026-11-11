import unittest

import package_release


class AndroidSourceSelectionTest(unittest.TestCase):
    def test_source_bundle_includes_the_local_library_required_by_gradle(self):
        library = package_release.ROOT / 'android/app/libs/onnxruntime-android-1.20.0-tn16k.aar'
        self.assertTrue(library.is_file())
        self.assertIn(library, package_release.source_files())

    def test_independent_assets_are_complete_in_source_bundle(self):
        selected = set(package_release.source_files())
        assets = package_release.ROOT / 'android/app/src/independent/assets'
        required = {path for path in assets.rglob('*') if path.is_file()}
        self.assertTrue(required)
        self.assertEqual([], sorted(str(path.relative_to(assets)) for path in required - selected))
        for relative in ('fonts/barlowregular.ttf', 'fonts/barlowmedium.ttf',
                         'fonts/barlowsemibold.ttf', 'driving_info_config.json'):
            self.assertIn(assets / relative, selected)

    def test_android_source_bundle_uses_only_independent_application_source(self):
        selected = package_release.source_files()
        app_source = package_release.ROOT / 'android/app/src'
        android = [path for path in selected if app_source in path.parents]
        self.assertTrue(android)
        self.assertTrue(all(path.relative_to(app_source).parts[0] == 'independent' for path in android))
        self.assertTrue(all(package_release.allowed(path) for path in selected))


if __name__ == '__main__':
    unittest.main()
