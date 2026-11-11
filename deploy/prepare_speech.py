"""One-time preparation of pinned, independently sourced speech models."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path


PINS = {
    'Systran/faster-whisper-small': '536b0662742c02347bc0e980a01041f333bce120',
    'pnnbao-ump/VieNeu-TTS-v3-Turbo': '61b85e3d937fbbacb387714180e8182823512523',
    'OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX': 'ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--kind', choices=['asr', 'tts'], required=True)
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    os.environ['HF_HOME'] = str(root / 'huggingface')
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    from huggingface_hub import snapshot_download
    if args.kind == 'asr':
        repo = 'Systran/faster-whisper-small'
        snapshot = snapshot_download(repo, revision=PINS[repo], local_dir=str(root / 'whisper-small'))
        from faster_whisper import WhisperModel
        model = WhisperModel(snapshot, device='cpu', compute_type='int8', cpu_threads=6, local_files_only=True)
        model_path = str(Path(snapshot).resolve())
        packages = ['faster-whisper', 'ctranslate2', 'onnxruntime']
    else:
        for repo, patterns in [
            ('pnnbao-ump/VieNeu-TTS-v3-Turbo', ['README.md', 'onnx_update/*', '*.json', 'speaker_encoder.onnx', 'denoiser.onnx']),
            ('OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX', ['*']),
        ]:
            snapshot = Path(snapshot_download(repo, revision=PINS[repo], allow_patterns=patterns))
            # The SDK uses main; pin that offline reference to the audited commit.
            ref = snapshot.parent.parent / 'refs' / 'main'
            ref.parent.mkdir(exist_ok=True)
            ref.write_text(PINS[repo])
        # ONNX external tensor files need real files on ORT versions that reject
        # resolving HF blob symlinks. Materialize within this dedicated cache only.
        import shutil
        for item in (root / 'huggingface' / 'hub').glob('models--*/snapshots/**/*'):
            if item.is_symlink() and item.is_file():
                source = item.resolve()
                if not source.is_relative_to(root):
                    raise RuntimeError('Unexpected cache path')
                temp = item.with_name(item.name + '.materialize')
                shutil.copyfile(source, temp)
                os.replace(temp, item)
        from vieneu import Vieneu
        model = Vieneu(mode='v3turbo', backend='onnx', device='cpu', precision='fp32', threads=4)
        voice = next(identifier for label, identifier in model.list_preset_voices() if 'Thùy Dung' in label or 'Thùy Dung' in identifier)
        audio = model.infer('Xin chào, tôi là trợ lý Ti En.', voice=voice, apply_watermark=False)
        model.save(audio, str(root / 'tn-speech-warmup.wav'))
        model_path = str(root / 'huggingface')
        packages = ['vieneu', 'onnxruntime', 'sea-g2p']
    manifest = {'kind': args.kind, 'model_path': model_path, 'model_pins': PINS, 'packages': {p: importlib.metadata.version(p) for p in packages}, 'files': []}
    for path in sorted(root.rglob('*')):
        if path.is_file() and not any(part.startswith('.') for part in path.relative_to(root).parts) and path.name != 'MODEL-MANIFEST.json':
            with path.open('rb') as stream:
                checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
            manifest['files'].append({'path': path.relative_to(root).as_posix(), 'size': path.stat().st_size, 'sha256': checksum})
    (root / 'MODEL-MANIFEST.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'kind': args.kind, 'prepared': True, 'files': len(manifest['files']), 'packages': manifest['packages']}), flush=True)


if __name__ == '__main__':
    main()
