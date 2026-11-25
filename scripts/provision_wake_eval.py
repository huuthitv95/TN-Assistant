"""Provision the synthetic corpus or public CC0 non-wake recordings into a Preview AVD.

The PCM files stay local and are copied only into the selected emulator's app-private test directory.
"""
import argparse
import base64
import contextlib
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import shutil
import shlex
import tarfile
import tempfile
import time
import urllib.request
import stat


_WINDOWS_REPARSE_POINT = 0x400
# GetFileAttributesW returns a DWORD, not a pointer-sized INVALID_HANDLE_VALUE.
_WINDOWS_INVALID_FILE_ATTRIBUTES = 0xFFFFFFFF


def _windows_kernel32():
    import ctypes
    from ctypes import wintypes

    # SECURITY_ATTRIBUTES was removed from ctypes.wintypes in Python 3.14.
    # Keep the ABI local and explicit rather than depending on that alias.
    class SecurityAttributes(ctypes.Structure):
        _fields_ = [
            ('nLength', wintypes.DWORD),
            ('lpSecurityDescriptor', ctypes.c_void_p),
            ('bInheritHandle', wintypes.BOOL),
        ]

    kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel32.GetFileAttributesW.argtypes = [wintypes.LPCWSTR]
    kernel32.GetFileAttributesW.restype = wintypes.DWORD
    kernel32.CreateFileW.argtypes = [
        wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
        ctypes.POINTER(SecurityAttributes), wintypes.DWORD,
        wintypes.DWORD, wintypes.HANDLE,
    ]
    kernel32.CreateFileW.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    return kernel32


def _reject_reparse(path, kind):
    """Reject links/reparse points in every existing parent and at the leaf."""
    path = os.path.abspath(os.fspath(path))
    components = list(Path(path).parents)[::-1] + [Path(path)]
    if os.name == 'nt':
        kernel32 = _windows_kernel32()
        for component in components:
            if not os.path.lexists(component):
                continue
            attributes = kernel32.GetFileAttributesW(str(component))
            if attributes == _WINDOWS_INVALID_FILE_ATTRIBUTES:
                error = __import__('ctypes').get_last_error()
                raise RuntimeError(f'{kind} cannot be inspected: {__import__("ctypes").WinError(error)}')
            if attributes & _WINDOWS_REPARSE_POINT:
                raise RuntimeError(f'{kind} must not contain a symlink or reparse point')
    else:
        for component in components:
            if os.path.lexists(component) and os.path.islink(component):
                raise RuntimeError(f'{kind} must not contain a symlink')


def _read_verified(path, kind, directory=False):
    """Open once, reject links, and return bytes consumed from that same handle."""
    path = os.fspath(path)
    _reject_reparse(path, kind)
    if os.name == 'nt':
        import ctypes
        import msvcrt
        kernel32 = _windows_kernel32()
        GENERIC_READ = 0x80000000
        FILE_SHARE_READ = 1
        FILE_SHARE_WRITE = 2
        FILE_SHARE_DELETE = 4
        OPEN_EXISTING = 3
        FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
        FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
        flags = FILE_FLAG_OPEN_REPARSE_POINT | (FILE_FLAG_BACKUP_SEMANTICS if directory else 0)
        handle = kernel32.CreateFileW(str(path), GENERIC_READ,
                                      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                      None, OPEN_EXISTING, flags, None)
        if handle == ctypes.c_void_p(-1).value:
            raise RuntimeError(f'{kind} cannot be opened safely: {ctypes.WinError(ctypes.get_last_error())}')
        fd = None
        try:
            fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | getattr(os, 'O_BINARY', 0))
            # open_osfhandle transfers ownership of the native HANDLE to fd.
            handle = None
            result = os.fstat(fd)
            if directory:
                if not stat.S_ISDIR(result.st_mode):
                    raise RuntimeError(f'{kind} must be a real directory')
                return None
            if not stat.S_ISREG(result.st_mode):
                raise RuntimeError(f'{kind} must be a regular file')
            with os.fdopen(fd, 'rb') as source:
                fd = None
                return source.read()
        except (OSError, ValueError) as error:
            raise RuntimeError(f'{kind} cannot be opened safely') from error
        finally:
            if fd is not None:
                os.close(fd)
            elif handle is not None:
                kernel32.CloseHandle(handle)
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    try:
        fd = os.open(path, flags)
    except OSError as error:
        raise RuntimeError(f'{kind} cannot be opened safely') from error
    try:
        result = os.fstat(fd)
        if directory:
            if not stat.S_ISDIR(result.st_mode):
                raise RuntimeError(f'{kind} must be a real directory')
            return None
        if not stat.S_ISREG(result.st_mode):
            raise RuntimeError(f'{kind} must be a regular file')
        with os.fdopen(fd, 'rb') as source:
            fd = None
            return source.read()
    finally:
        if fd is not None:
            os.close(fd)


ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / '.runtime/wake192-acoustic/corpus-v1'
PACKAGE = 'vn.tn.assistant.preview'
CC0_SOURCES = Path(__file__).with_name('wake_cc0_negative.json')
SYNTHETIC_MANIFEST_SHA256 = 'e39e1b5b1db889a663fc5b00bbb296a8f5d1e20ba1766afdfa6b440d35d01b5e'


def digest(path):
    payload = _read_verified(path, 'Digest input')
    return hashlib.sha256(payload).hexdigest()


def verified_files(corpus):
    corpus = Path(corpus)
    _read_verified(corpus, 'Corpus directory', directory=True)
    manifest_path = corpus / 'manifest.json'
    manifest_bytes = _read_verified(manifest_path, 'Corpus manifest')
    if hashlib.sha256(manifest_bytes).hexdigest() != SYNTHETIC_MANIFEST_SHA256:
        raise RuntimeError('Synthetic manifest differs from the pinned canonical manifest')
    rows = json.loads(manifest_bytes.decode('utf-8'))
    if len(rows) != 75:
        raise RuntimeError('Expected the pinned 75-sample wake corpus')
    expected_names = [f'p{index:02d}-r{repeat}.pcm' for index in range(5) for repeat in range(3)]
    expected_names += [f'n{index:02d}-r{repeat}.pcm' for index in range(20) for repeat in range(3)]
    if [row.get('file') for row in rows] != expected_names:
        raise RuntimeError('Synthetic manifest filename/order differs from canonical sample set')
    if [row.get('expected_wake') for row in rows] != [True] * 15 + [False] * 60:
        raise RuntimeError('Synthetic manifest labels differ from canonical sample set')
    files = [('manifest.json', manifest_bytes)]
    seen = set()
    for row in rows:
        name = row.get('file')
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9.-]+', name) or name in seen:
            raise RuntimeError('Invalid or duplicate corpus filename')
        seen.add(name)
        path = corpus / name
        if row.get('sample_rate') != 16000 or row.get('channels') != 1 or row.get('sample_width') != 2:
            raise RuntimeError('Unexpected PCM format')
        sample_bytes = _read_verified(path, 'Corpus sample: ' + name)
        if len(sample_bytes) != row.get('bytes') or hashlib.sha256(sample_bytes).hexdigest() != row.get('sha256'):
            raise RuntimeError('Corpus sample checksum differs: ' + name)
        files.append((name, sample_bytes))
    return manifest_bytes, files


def _snapshot_hash(path):
    return hashlib.sha256(_read_verified(path, 'FFmpeg snapshot')).hexdigest()


@contextlib.contextmanager
def _hold_windows_executable(path):
    """Hold a Windows file handle that denies replacement for this invocation.

    Windows honors the no-write/no-delete share mask while this handle remains
    open.  The yielded descriptor is also the hash input, avoiding a second
    pathname open whose share flags would conflict with the guard.  Other
    platforms use the documented pathname/hash integrity boundary.
    """
    if os.name != 'nt':
        yield None
        return
    import ctypes
    import msvcrt
    kernel32 = _windows_kernel32()
    GENERIC_READ = 0x80000000
    FILE_SHARE_READ = 1
    OPEN_EXISTING = 3
    handle = kernel32.CreateFileW(str(path), GENERIC_READ, FILE_SHARE_READ,
                                  None, OPEN_EXISTING, 0, None)
    if handle == ctypes.c_void_p(-1).value:
        raise RuntimeError('FFmpeg snapshot cannot be held safely: ' +
                           str(ctypes.WinError(ctypes.get_last_error())))
    fd = None
    try:
        fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | getattr(os, 'O_BINARY', 0))
        handle = None
        yield fd
    finally:
        if fd is not None:
            os.close(fd)
        elif handle is not None:
            kernel32.CloseHandle(handle)


def _hash_fd(fd):
    os.lseek(fd, 0, os.SEEK_SET)
    value = hashlib.sha256()
    while chunk := os.read(fd, 1024 * 1024):
        value.update(chunk)
    os.lseek(fd, 0, os.SEEK_SET)
    return value.hexdigest()


def ffmpeg_identity(executable, snapshot_directory):
    """Create and execute an invocation-owned snapshot.

    The source is consumed from one opened handle and the snapshot is created with
    O_EXCL, then made non-writable where the host permits it.  On Windows the
    snapshot is held open with write/delete sharing denied while version inspection
    runs, preventing replacement by ordinary same-host file operations.  On other
    platforms and for launchers that do not honor this boundary, hashes detect
    mutation but cannot prevent a pathname replacement before process creation.
    """
    resolved = Path(executable).absolute()
    _reject_reparse(resolved, 'FFmpeg executable')
    snapshot = Path(snapshot_directory) / (
        'ffmpeg-snapshot-' + secrets.token_hex(12) + resolved.suffix)
    snapshot_bytes = _read_verified(resolved, 'FFmpeg executable')
    try:
        fd = os.open(snapshot, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                     getattr(os, 'O_BINARY', 0), 0o500)
    except OSError as error:
        raise RuntimeError('FFmpeg snapshot cannot be created exclusively') from error
    try:
        with os.fdopen(fd, 'wb') as target:
            fd = None
            target.write(snapshot_bytes)
            target.flush()
            os.fsync(target.fileno())
    finally:
        if fd is not None:
            os.close(fd)
    try:
        os.chmod(snapshot, 0o500)
    except OSError:
        pass
    _reject_reparse(snapshot, 'FFmpeg snapshot')
    snapshot_sha256 = hashlib.sha256(snapshot_bytes).hexdigest()
    if _snapshot_hash(snapshot) != snapshot_sha256:
        raise RuntimeError('FFmpeg snapshot changed before execution')
    with _hold_windows_executable(snapshot) as held_fd:
        if held_fd is not None and _hash_fd(held_fd) != snapshot_sha256:
            raise RuntimeError('FFmpeg snapshot changed before version inspection')
        version = subprocess.run([str(snapshot), '-version'], check=True, capture_output=True,
                                 text=True).stdout.splitlines()[0]
        if held_fd is not None:
            if _hash_fd(held_fd) != snapshot_sha256:
                raise RuntimeError('FFmpeg snapshot changed during version inspection')
        elif _snapshot_hash(snapshot) != snapshot_sha256:
            raise RuntimeError('FFmpeg snapshot changed during version inspection')
    return str(snapshot), version, snapshot_sha256


def _run_ffmpeg(ffmpeg, expected_sha256, audio):
    """Run only while the invocation snapshot still has its recorded identity."""
    if _snapshot_hash(ffmpeg) != expected_sha256:
        raise RuntimeError('FFmpeg snapshot changed before conversion')
    with _hold_windows_executable(ffmpeg) as held_fd:
        if held_fd is not None and _hash_fd(held_fd) != expected_sha256:
            raise RuntimeError('FFmpeg snapshot changed before conversion')
        result = subprocess.run([ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', 'pipe:0',
                                 '-ar', '16000', '-ac', '1', '-f', 's16le', 'pipe:1'],
                                input=audio, check=True, capture_output=True)
        if held_fd is not None:
            if _hash_fd(held_fd) != expected_sha256:
                raise RuntimeError('FFmpeg snapshot changed during conversion; PCM provenance rejected')
        elif _snapshot_hash(ffmpeg) != expected_sha256:
            raise RuntimeError('FFmpeg snapshot changed during conversion; PCM provenance rejected')
    return result.stdout


def cc0_recordings(ffmpeg, ffmpeg_sha256):
    sources = json.loads(CC0_SOURCES.read_text(encoding='utf-8'))
    if not sources or len({row['file'] for row in sources}) != len(sources):
        raise ValueError('Expected a nonempty list of unique CC0 sample names')
    rows, files = [], []
    for index, row in enumerate(sources):
        name = row['file']
        if (not re.fullmatch(r'[a-z0-9-]+\.pcm', name) or row['license'] != 'CC0-1.0'
                or not row['source'].startswith(('https://upload.wikimedia.org/wikipedia/commons/',
                                                 'https://cdn.freesound.org/previews/'))
                or not 0 < row['source_bytes'] <= 10_000_000):
            raise ValueError('Invalid entry in the pinned CC0 source list')
        if index:
            time.sleep(3.5)  # Space anonymous requests to the media hosts.
        request = urllib.request.Request(row['source'], headers={
            'User-Agent': 'TN-Assistant-WakeEval/1.0 (https://github.com/huuthitv95/TN-Assistant)'})
        with urllib.request.urlopen(request, timeout=30) as response:
            audio = response.read(row['source_bytes'] + 1)
        if len(audio) != row['source_bytes'] or hashlib.sha256(audio).hexdigest() != row['source_sha256']:
            raise RuntimeError('CC0 source checksum differs: ' + name)
        decoded = _run_ffmpeg(ffmpeg, ffmpeg_sha256, audio)
        if len(decoded) != row['pcm_bytes']:
            raise RuntimeError('Decoded CC0 sample length differs: ' + name)
        rows.append(dict(row, expected_wake=False, sample_rate=16000, channels=1, sample_width=2,
                         bytes=len(decoded), sha256=hashlib.sha256(decoded).hexdigest()))
        files.append((name, decoded))
    manifest_bytes = json.dumps(rows, ensure_ascii=False).encode('utf-8')
    return manifest_bytes, [('manifest.json', manifest_bytes), *files]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit Android SDK emulator serial, for example emulator-5570')
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    parser.add_argument('--ffmpeg', default=shutil.which('ffmpeg'),
                        help='Absolute FFmpeg executable used for CC0 conversion')
    parser.add_argument('--cc0-negative', action='store_true',
                        help='Download CC0 Vietnamese speech and car ambience; run with -e tnWakeCorpus cc0-negative')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('--serial must identify an Android SDK emulator')
    if args.cc0_negative and not args.ffmpeg:
        parser.error('--ffmpeg is required for --cc0-negative')
    ffmpeg = ffmpeg_version = ffmpeg_sha256 = None

    def adb(*parts, data=None):
        result = subprocess.run([args.adb, '-s', args.serial, *parts], input=data,
                                capture_output=True, timeout=120)
        if result.returncode:
            detail = result.stderr.decode('utf-8', errors='replace').strip()
            raise RuntimeError(f'ADB operation failed ({result.returncode}): {detail}')
        return result.stdout

    if adb('get-state').decode('utf-8').strip() != 'device':
        raise RuntimeError('The selected emulator is not online')
    if adb('shell', 'getprop', 'ro.hardware').strip() not in (b'ranchu', b'goldfish'):
        raise RuntimeError('The selected device is not an Android SDK emulator')
    if not adb('shell', 'pm', 'path', PACKAGE).startswith(b'package:'):
        raise RuntimeError('TN Assistant Preview is not installed on the selected emulator')

    folder = 'files/tn-wake-cc0-negative' if args.cc0_negative else 'files/tn-wake-test'
    # Android 10 toybox tar restores numeric owners, even when run as the app.
    uid = int(adb('shell', 'run-as', PACKAGE, 'id', '-u'))
    gid = int(adb('shell', 'run-as', PACKAGE, 'id', '-g'))
    with tempfile.TemporaryDirectory() as temporary:
        if args.cc0_negative:
            ffmpeg, ffmpeg_version, ffmpeg_sha256 = ffmpeg_identity(args.ffmpeg, temporary)
            manifest_bytes, files = cc0_recordings(ffmpeg, ffmpeg_sha256)
        else:
            manifest_bytes, files = verified_files(CORPUS)
        archive = BytesIO()
        checksums = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n'
                            for name, data in files).encode('ascii')
        with tarfile.open(fileobj=archive, mode='w', format=tarfile.USTAR_FORMAT) as output:
            for name, payload in [*files, ('.sha256', checksums)]:
                entry = tarfile.TarInfo(name)
                entry.size = len(payload)
                entry.mode = 0o600
                entry.uid, entry.gid = uid, gid
                entry.uname = entry.gname = ''
                output.addfile(entry, BytesIO(payload))
        # Validate in an app-private sibling before publication. A no-clobber
        # move to the parent keeps concurrent invocations from nesting payloads.
        folder_name = folder.rsplit('/', 1)[-1]
        shell_script = f'''set -e
umask 077
base=files
test ! -L "$base"
mkdir -p "$base"
stage=$(mktemp -d "$base/.tn-wake-stage.XXXXXX")
token=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s.%N)
marker="$stage/.owner"
printf '%s\\n' "$token" > "$marker"
payload="$stage/{folder_name}"
mkdir "$payload"
destination="$base/{folder_name}"
cleanup() {{
    if [ -d "$stage" ] && [ ! -L "$stage" ] &&
       [ -f "$stage/.owner" ] && [ "$(cat "$stage/.owner")" = "$token" ]; then
        rm -rf -- "$stage"
    fi
}}
trap cleanup EXIT
# Never create or recursively clean a destination before publication.
if [ -e "$destination" ] || [ -L "$destination" ]; then
    echo "Corpus already exists: $destination" >&2
    exit 1
fi
base64 -d > "$stage/input.tar"
tar -xf "$stage/input.tar" -C "$payload"
rm "$stage/input.tar"
(
    cd "$payload"
    test "$(find . -mindepth 1 -maxdepth 1 | wc -l)" = {len(files) + 1}
    test "$(sha256sum .sha256 | cut -d' ' -f1)" = {hashlib.sha256(checksums).hexdigest()}
    sha256sum -c .sha256 >/dev/null
    rm .sha256
)
mv -n -- "$payload" "$base/"
test ! -e "$payload"
'''
        command = 'run-as ' + shlex.quote(PACKAGE) + ' sh -c ' + shlex.quote(shell_script)
        # Windows adb shell may truncate binary stdin at Ctrl-Z.
        adb('shell', '-T', command, data=base64.b64encode(archive.getvalue()))
        total_bytes = sum(len(payload) for _, payload in files)
        for name, payload in files:
            installed = adb('exec-out', 'run-as', PACKAGE, 'cat', folder + '/' + name)
            if installed != payload:
                raise RuntimeError('Installed corpus differs: ' + name)

    print(json.dumps({
        'serial': args.serial,
        'package': PACKAGE,
        'samples': len(files) - 1,
        'bytes': total_bytes,
        'corpus': 'cc0-negative' if args.cc0_negative else 'synthetic',
        'manifest_sha256': hashlib.sha256(manifest_bytes).hexdigest(),
        'provider_calls': 0,
        'ffmpeg': {'executable': str(Path(args.ffmpeg).absolute()) if args.cc0_negative else None,
                   'version': ffmpeg_version, 'sha256': ffmpeg_sha256},
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
