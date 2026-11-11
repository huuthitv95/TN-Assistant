"""Create separate clean Linux runtime and source bundles using explicit allowlists."""
import argparse
import getpass
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.runtime', '.gradle', '.kotlin', '.git', '.cxx', '__pycache__', 'node_modules', 'build', 'dist', 'reports', 'reference', 'notes', 'work', 'tests', 'test', 'androidTest'}
PRIVATE_ENDINGS = {'.env', '.db', '.db-wal', '.db-shm', '.sqlite', '.sqlite3', '.dpapi', '.key', '.pem', '.pfx', '.jks', '.keystore', '.log', '.dmp', '.pyc', '.exe'}


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def allowed(path):
    rel = path.relative_to(ROOT)
    if path.is_symlink() or any(part in EXCLUDED for part in rel.parts):
        return False
    name = path.name.lower()
    if path.suffix.lower() in PRIVATE_ENDINGS or name == 'local.properties' or name.endswith('_test.go') or name.startswith('test_') or '.test.' in name:
        return False
    if path.suffix.lower() not in {'.go', '.kt', '.java', '.ts', '.tsx', '.py'} and any(fragment in name for fragment in ('password', 'credential', 'backup', 'audit')):
        return False
    return True


def source_files():
    top_files = {
        'backend': {'go.mod', 'go.sum', '.env.example', 'providers.example.json', 'README.md', 'generate_openapi.py'},
        'admin': {'index.html', 'package.json', 'package-lock.json', 'tsconfig.json', 'vite.config.ts'},
        'android': {'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'},
    }
    deploy_files = {
        'assistant-proxy-locations.conf', 'deploy_remote.py', 'deploy_ui_release.py', 'install_speech.py', 'migrate_config.py', 'monitor.py', 'readiness_watch.py',
        'package_release.py', 'prepare_speech.py', 'providers.assistant.example.json', 'README.md',
        'publish_android_preview.py', 'requirements-asr.lock', 'requirements-tts.lock', 'speech_worker.py',
        'tn-assistant.env.example', 'tn-assistant.service', 'tn-assistant-monitor.service',
    }
    selected = set()
    for folder, names in top_files.items():
        selected.update(ROOT / folder / name for name in names if (ROOT / folder / name).is_file())
    selected.update(path for path in (ROOT / 'backend').glob('*.go'))
    selected.update(ROOT / 'android/app' / name for name in ('build.gradle.kts', 'gradle.lockfile'))
    # build.gradle.kts references this independently built local dependency.
    # Omitting it makes an otherwise complete source archive impossible to build.
    onnx_runtime = ROOT / 'android/app/libs/onnxruntime-android-1.20.0-tn16k.aar'
    if not onnx_runtime.is_file():
        raise RuntimeError('Independent Android ONNX runtime library is missing')
    selected.add(onnx_runtime)
    for tree in (
        'backend/migrations', 'admin/src', 'admin/public', 'android/gradle',
        'android/app/src/independent/java', 'android/app/src/independent/assets',
        'android/app/src/independent/res',
    ):
        selected.update(path for path in (ROOT / tree).rglob('*') if path.is_file())
    selected.add(ROOT / 'android/app/src/independent/AndroidManifest.xml')
    selected.update({ROOT / 'api/openapi.json', ROOT / 'api/CONTRACT.md'})
    selected.add(ROOT / 'scripts/independent_android_assets.py')
    for name in deploy_files:
        selected.add(ROOT / 'deploy' / name)
    osm_importer = ROOT / 'scripts/import_osm.py'
    osm_lock = ROOT / 'scripts/requirements-osm.lock'
    if osm_importer.is_file():
        selected.add(osm_importer)
    if osm_lock.is_file():
        selected.add(osm_lock)
    android_source = ROOT / 'android/app/src/independent/java/vn/tn/assistant'
    required_android = {'MainActivity.kt', 'audio/NativeAudio.kt', 'audio/tn/SileroVad.kt', 'audio/tn/WakePhrase.kt'}
    selected_android = {path.relative_to(android_source).as_posix() for path in selected if path.is_file() and android_source in path.parents}
    missing_android = sorted(required_android - selected_android)
    if missing_android:
        raise RuntimeError('Independent Android source is incomplete: ' + ', '.join(missing_android))
    if any('/src/main/' in path.as_posix() for path in selected):
        raise RuntimeError('Legacy Android main source must never enter the TN release bundle')
    return sorted((path for path in selected if allowed(path)), key=lambda p: p.relative_to(ROOT).as_posix())


def manifest(directory, name='MANIFEST.json'):
    entries = [{'path': p.relative_to(directory).as_posix(), 'size': p.stat().st_size, 'sha256': digest(p)} for p in sorted(directory.rglob('*')) if p.is_file() and p.name != name]
    (directory / name).write_text(json.dumps(entries, indent=2) + '\n', encoding='utf-8')
    return entries


def licenses(runtime):
    decoded = subprocess.check_output(['go', 'list', '-m', '-json', 'all'], cwd=ROOT / 'backend', text=True)
    parser, position, modules = json.JSONDecoder(), 0, []
    while position < len(decoded):
        while position < len(decoded) and decoded[position].isspace():
            position += 1
        if position == len(decoded):
            break
        module, position = parser.raw_decode(decoded, position)
        modules.append({'module': module['Path'], 'version': module.get('Version', 'local')})
        directory = Path(module['Dir']) if module.get('Dir') else None
        if directory and directory.is_dir() and not module.get('Main'):
            for pattern in ('LICENSE*', 'COPYING*', 'NOTICE*'):
                for path in directory.glob(pattern):
                    if path.is_file():
                        target = runtime / 'licenses/go' / module['Path'].replace('/', '_') / path.name
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(path, target)
    lock = json.loads((ROOT / 'admin/package-lock.json').read_text())
    for package, metadata in lock['packages'].items():
        if not package or metadata.get('dev'):
            continue
        directory = ROOT / 'admin' / package
        for pattern in ('LICENSE*', 'COPYING*', 'NOTICE*'):
            for path in directory.glob(pattern):
                if path.is_file():
                    target = runtime / 'licenses/npm' / package.replace('/', '_') / path.name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, target)
    (runtime / 'licenses/MODULES.json').write_text(json.dumps(modules, indent=2) + '\n')


def archive(directory, target):
    with tarfile.open(target, 'w:gz') as output:
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                info = output.gettarinfo(path, arcname=path.relative_to(directory).as_posix())
                info.uid, info.gid, info.uname, info.gname = 0, 0, 'root', 'root'
                info.mode = 0o755 if path.name == 'tn-assistant' or path.name == 'gradlew' else 0o644
                with path.open('rb') as content:
                    output.addfile(info, content)
    target.with_suffix(target.suffix + '.sha256').write_text(digest(target) + '  ' + target.name + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--release', required=True)
    parser.add_argument('--scan-private', action='store_true', required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise RuntimeError('Release output already exists; refusing overwrite')
    args.output.mkdir(parents=True)
    runtime, source = args.output / 'runtime', args.output / 'source'
    runtime.mkdir()
    source.mkdir()
    shutil.copy2(args.binary, runtime / 'tn-assistant')
    shutil.copytree(ROOT / 'admin/dist', runtime / 'static')
    (runtime / 'ops').mkdir()
    shutil.copy2(ROOT / 'deploy/monitor.py', runtime / 'ops/monitor.py')
    shutil.copy2(ROOT / 'deploy/readiness_watch.py', runtime / 'ops/readiness_watch.py')
    licenses(runtime)
    for path in source_files():
        target = source / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    for directory in (runtime, source):
        (directory / 'RELEASE.json').write_text(json.dumps({'release': args.release, 'runtime': directory == runtime, 'source_contains_secrets': False}, indent=2) + '\n')
        manifest(directory)
    sensitive = [
        getpass.getpass('Administrator password for private-content scan (not echoed): ').encode(),
        getpass.getpass('Zalo secret for private-content scan (not echoed): ').encode(),
        getpass.getpass('Gemini API key for private-content scan (not echoed): ').encode(),
    ]
    if any(len(value) < 8 for value in sensitive):
        raise RuntimeError('Private-content scan requires the intended secret values')
    for path in [p for directory in (runtime, source) for p in directory.rglob('*') if p.is_file()] + [args.apk]:
        overlap = max(map(len, sensitive)) - 1
        tail = b''
        with path.open('rb') as stream:
            while chunk := stream.read(1024 * 1024):
                content = tail + chunk
                if any(value in content for value in sensitive):
                    raise RuntimeError('Private content found in selected payload; packaging stopped')
                tail = content[-overlap:]
    archive(runtime, args.output / 'runtime.tar.gz')
    archive(source, args.output / 'source.tar.gz')
    shutil.copy2(args.apk, args.output / 'tn-assistant-preview.apk')
    (args.output / 'tn-assistant-preview.apk.sha256').write_text(digest(args.output / 'tn-assistant-preview.apk') + '  tn-assistant-preview.apk\n')
    result = {'release': args.release, 'runtime_files': len(manifest(runtime)), 'source_files': len(manifest(source)), 'private_content_scan_passed': True, 'runtime_sha256': digest(args.output / 'runtime.tar.gz'), 'source_sha256': digest(args.output / 'source.tar.gz'), 'apk_sha256': digest(args.output / 'tn-assistant-preview.apk')}
    (args.output / 'BUILD.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
