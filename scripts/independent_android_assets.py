"""Fetch and verify the independently licensed TN wake-detection assets."""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/app/src/independent/assets"
MANIFEST = OUT / "licenses/independent-inputs.json"
VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"
VOSK_SHA256 = "efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5"
SILERO_URL = "https://raw.githubusercontent.com/snakers4/silero-vad/v5.1.2/src/silero_vad/data/silero_vad.onnx"
SILERO_SHA256 = "2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f"
LICENSE_URLS = {
    "licenses/vosk.txt": "https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING",
    "licenses/silero.txt": "https://raw.githubusercontent.com/snakers4/silero-vad/v5.1.2/LICENSE",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "TNAssistantAssetBuilder/1.0 (support@tnfastvn.com)"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def verify() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    failures = []
    recorded = {item["path"] for item in manifest.get("files", [])}
    actual = {path.relative_to(OUT).as_posix() for path in OUT.rglob("*") if path.is_file() and path != MANIFEST}
    failures.extend(sorted(actual - recorded))
    failures.extend(sorted(recorded - actual))
    for item in manifest.get("files", []):
        target = OUT / item["path"]
        if not target.is_file() or sha256(target.read_bytes()) != item["sha256"]:
            failures.append(item["path"])
    if not manifest.get("files") or failures:
        raise SystemExit("Independent Android asset verification failed: " + ", ".join(failures or ["empty manifest"]))
    if manifest.get("sources", {}).get("vosk_archive_sha256") != VOSK_SHA256:
        raise SystemExit("Vosk source archive hash does not match the pinned model")
    if manifest.get("sources", {}).get("silero_sha256") != SILERO_SHA256:
        raise SystemExit("Silero source checksum does not match the pinned model")
    print(f"Verified {len(manifest['files'])} independently licensed Android asset files")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--fetch", action="store_true")
    group.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        verify()
        return

    archive_bytes = fetch(VOSK_URL)
    if sha256(archive_bytes) != VOSK_SHA256:
        raise SystemExit("Vosk model archive checksum mismatch")
    silero_bytes = fetch(SILERO_URL)
    if sha256(silero_bytes) != SILERO_SHA256:
        raise SystemExit("Silero model checksum mismatch")

    model_root = OUT / "models/vosk-model-small-vn-0.4"
    model_root.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        for member in archive.infolist():
            relative = PurePosixPath(member.filename)
            if member.is_dir():
                continue
            if relative.is_absolute() or ".." in relative.parts or relative.parts[:1] != ("vosk-model-small-vn-0.4",):
                raise SystemExit(f"Unsafe Vosk model archive path: {member.filename}")
            target = OUT / "models" / Path(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(member))
    (OUT / "models/silero_vad.onnx").write_bytes(silero_bytes)

    for relative, url in LICENSE_URLS.items():
        target = OUT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(fetch(url))

    unexpected = [path.relative_to(OUT).as_posix() for path in OUT.rglob("*") if path.is_file() and path != MANIFEST and not path.relative_to(OUT).as_posix().startswith(("models/", "licenses/"))]
    if unexpected:
        raise SystemExit("Unexpected Android asset files remain: " + ", ".join(unexpected))

    files = []
    for path in sorted(OUT.rglob("*")):
        if not path.is_file() or path == MANIFEST:
            continue
        files.append({"path": path.relative_to(OUT).as_posix(), "sha256": sha256(path.read_bytes()), "bytes": path.stat().st_size})
    manifest = {
        "version": 2,
        "sources": {
            "vosk_model": VOSK_URL,
            "vosk_license": "Apache-2.0",
            "vosk_archive_sha256": VOSK_SHA256,
            "silero_model": SILERO_URL,
            "silero_license": "MIT",
            "silero_sha256": SILERO_SHA256,
            "wake_phrase_accuracy": "not_yet_measured",
        },
        "files": files,
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Fetched and pinned {len(files)} independent Android assets; wake accuracy remains unverified")


if __name__ == "__main__":
    main()
