#!/usr/bin/env python3
"""Rebuild ONNX Runtime 1.20.0 Android JNI and patch its official AAR for 16 KB pages."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android" / "app"
VENDOR = APP / "third_party" / "onnxruntime-jni-1.20.0"
OUTPUT = APP / "libs" / "onnxruntime-android-1.20.0-tn16k.aar"
ABIS = {
    "arm64-v8a": "aarch64-linux-android21",
    "armeabi-v7a": "armv7a-linux-androideabi21",
}
JNI_RE = re.compile(r"\b(Java_[^\s]+|JNI_OnLoad)\s*$")


def run(command: list[str]) -> str:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout


def exported_jni_symbols(readelf: Path, library: Path) -> set[str]:
    output = run([str(readelf), "--dyn-syms", "--wide", str(library)])
    return {match.group(1) for line in output.splitlines() if (match := JNI_RE.search(line))}


def validate_16k_load_segments(readelf: Path, library: Path) -> None:
    output = run([str(readelf), "--program-headers", str(library)])
    loads = [line for line in output.splitlines() if re.search(r"\bLOAD\b", line)]
    if not loads or any(not re.search(r"\s0x0*4000\s*$", line) for line in loads):
        raise RuntimeError(f"{library.name}: one or more PT_LOAD segments are not 16 KB aligned")


def build(official_aar: Path, ndk_root: Path, output: Path) -> None:
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system())
    if not host:
        raise RuntimeError(f"Unsupported NDK host: {platform.system()}")
    suffix = ".exe" if platform.system() == "Windows" else ""
    bin_dir = ndk_root / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    clang = bin_dir / f"clang{suffix}"
    readelf = bin_dir / f"llvm-readelf{suffix}"
    if not official_aar.is_file() or not clang.is_file() or not readelf.is_file():
        raise FileNotFoundError("Provide the official ONNX Runtime 1.20.0 AAR and a complete Android NDK")

    sources = sorted((VENDOR / "native").glob("*.c"))
    includes = [
        VENDOR / "native",
        VENDOR / "generated",
        VENDOR / "include",
        VENDOR / "include" / "onnxruntime" / "core" / "session",
        VENDOR / "orttraining" / "orttraining" / "training_api" / "include",
    ]
    if not sources or any(not directory.is_dir() for directory in includes):
        raise FileNotFoundError("The vendored ONNX Runtime JNI sources or headers are incomplete")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tn-onnx-16k-") as temporary:
        work = Path(temporary)
        replacements: dict[str, bytes] = {}
        with zipfile.ZipFile(official_aar) as source_archive:
            for abi, target in ABIS.items():
                runtime_member = f"jni/{abi}/libonnxruntime.so"
                original_member = f"jni/{abi}/libonnxruntime4j_jni.so"
                original_runtime = source_archive.read(runtime_member)
                original_jni = work / abi / "original-jni.so"
                original_jni.parent.mkdir(parents=True)
                original_jni.write_bytes(source_archive.read(original_member))
                (work / abi / "libonnxruntime.so").write_bytes(original_runtime)
                compiled = work / abi / "libonnxruntime4j_jni.so"
                command = [
                    str(clang), f"--target={target}", "-std=c11", "-shared", "-fPIC", "-O2",
                    "-fvisibility=hidden", "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384",
                    "-Wl,-z,common-page-size=16384", "-Wl,-soname,libonnxruntime4j_jni.so",
                ]
                command += [f"-I{path}" for path in includes]
                command += [str(source) for source in sources]
                command += [f"-L{work / abi}", "-lonnxruntime", "-ldl", "-lm", "-o", str(compiled)]
                run(command)
                missing = exported_jni_symbols(readelf, original_jni) - exported_jni_symbols(readelf, compiled)
                if missing:
                    raise RuntimeError(f"{abi}: rebuilt JNI library is missing exports: {sorted(missing)}")
                validate_16k_load_segments(readelf, compiled)
                replacements[original_member] = compiled.read_bytes()

            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as result_archive:
                for member in source_archive.infolist():
                    payload = replacements.get(member.filename)
                    if payload is None:
                        payload = source_archive.read(member.filename)
                    result_archive.writestr(member, payload)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--official-aar", type=Path, required=True, help="Maven ONNX Runtime Android AAR version 1.20.0")
    parser.add_argument("--ndk-root", type=Path, required=True, help="Android NDK root")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    build(args.official_aar.resolve(), args.ndk_root.resolve(), args.output.resolve())
    print(json.dumps({"output": str(args.output.resolve()), "bytes": args.output.stat().st_size}, indent=2))


if __name__ == "__main__":
    main()
