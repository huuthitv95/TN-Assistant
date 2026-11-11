"""Pinned local synthetic wake corpus. Never calls an ASR/TTS service.

The generated voice is an evaluation fixture, not TN's default assistant voice.
Model-card attribution: rhasspy/piper-voices, VAIS-1000, CC BY 4.0.
Piper engine is used locally under GPL-3.0; neither engine nor model enters APKs.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import subprocess
import urllib.request
import wave


ROOT = Path(__file__).resolve().parents[1]
DEFAULT = ROOT / '.runtime/wake192-acoustic'
REVISION = '8aaa3c9839d2b669cb57a94e1ec92ae0928897e8'
BASE = ('https://huggingface.co/rhasspy/piper-voices/resolve/' + REVISION
        + '/vi/vi_VN/vais1000/medium/')
FILES = {
    'vi_VN-vais1000-medium.onnx': 'ec7c89e2c85f4d1edc24b6120c18aaf1bda614f06b511567eb9c7c0de15e2dab',
    'vi_VN-vais1000-medium.onnx.json': '446fe82963706ec77de910560a71bffa119d7a8b5e58b2f0f18c3946be445f0e',
    'MODEL_CARD': '302db8a930ffc2b1c2181db26deaf8272116eca2b08b8f80da6375b0a994af7b',
}
PHRASES = [
    ('p00', 'Ti En ơi', True, 'development'),
    ('p01', 'Tê En ơi', True, 'holdout'),
    ('p02', 'Tê Nờ ơi', True, 'development'),
    ('p03', 'Ti En ơi, mở bản đồ', True, 'development'),
    ('p04', 'Tê Nờ ơi, mở nhạc', True, 'holdout'),
    ('n00', 'Hôm nay trời đẹp quá', False, 'development'),
    ('n01', 'Kiki ơi', False, 'development'),
    ('n02', 'Tên của bạn là gì', False, 'development'),
    ('n03', 'Xin chào mọi người', False, 'development'),
    ('n04', 'Trời ơi', False, 'development'),
    ('n05', 'Em ơi', False, 'development'),
    ('n06', 'Anh ơi', False, 'holdout'),
    ('n07', 'Tiền ơi', False, 'development'),
    ('n08', 'Tôi tên Ti En', False, 'development'),
    ('n09', 'Nhắc tôi gọi Ti En ơi', False, 'holdout'),
    ('n10', 'Bạn Ti En ơi', False, 'holdout'),
    ('n11', 'Chào em ơi', False, 'holdout'),
    ('n12', 'Mở bản đồ', False, 'development'),
    ('n13', 'Tìm đường đến Hà Nội', False, 'holdout'),
    ('n14', 'Tiền mua đồ ở đâu', False, 'holdout'),
    ('n15', 'Tiếng chim ở ngoài', False, 'holdout'),
    ('n16', 'Tình yêu ơi', False, 'development'),
    ('n17', 'Em ơi, mở nhạc', False, 'holdout'),
    ('n18', 'Tê nờ in tên của tôi', False, 'holdout'),
    ('n19', 'Tôi gọi tên ơi', False, 'holdout'),
]
LENGTH_SCALES = (0.85, 1.0, 1.15)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch_models(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for filename, expected in FILES.items():
        target = directory / filename
        if target.exists():
            if target.is_symlink() or digest(target) != expected:
                raise RuntimeError('Existing generator model differs: ' + filename)
            continue
        temporary = target.with_suffix(target.suffix + '.part')
        request = urllib.request.Request(BASE + filename, headers={'User-Agent': 'TN-Wake-Evaluation/1'})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open('xb') as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
        if digest(temporary) != expected:
            raise RuntimeError('Downloaded generator model hash differs: ' + filename)
        temporary.rename(target)
        print(json.dumps({'downloaded': filename, 'sha256': expected}), flush=True)


def verify_corpus(corpus):
    manifest = json.loads((corpus / 'manifest.json').read_text(encoding='utf-8'))
    if len(manifest) != len(PHRASES) * len(LENGTH_SCALES):
        raise RuntimeError('Corpus size differs from its fixed plan')
    expected = {f'{name}-r{rate}.pcm': (text, label, split, scale)
                for name, text, label, split in PHRASES
                for rate, scale in enumerate(LENGTH_SCALES)}
    names = [entry['file'] for entry in manifest]
    if len(names) != len(set(names)) or set(names) != set(expected):
        raise RuntimeError('Corpus sample identities differ from its fixed plan')
    for entry in manifest:
        text, label, split, scale = expected[entry['file']]
        if ((entry.get('text'), entry.get('expected_wake'), entry.get('split'), entry.get('length_scale'))
                != (text, label, split, scale)):
            raise RuntimeError('Corpus labels differ from its fixed plan: ' + entry['file'])
        if (entry.get('sample_rate'), entry.get('channels'), entry.get('sample_width')) != (16000, 1, 2):
            raise RuntimeError('Unexpected PCM format in the manifest')
        path = corpus / entry['file']
        if path.is_symlink() or path.parent != corpus or digest(path) != entry['sha256']:
            raise RuntimeError('PCM fixture differs: ' + entry['file'])
        if path.stat().st_size != entry['bytes']:
            raise RuntimeError('PCM fixture length differs: ' + entry['file'])
    return manifest


def generate(directory):
    # Import the QA synthesizer only after explicit model acquisition/verification.
    import numpy as np
    from piper import PiperVoice, SynthesisConfig

    models = directory / 'models'
    for name, expected in FILES.items():
        if digest(models / name) != expected:
            raise RuntimeError('Generator input hash differs: ' + name)
    if importlib.metadata.version('piper-tts') != '1.3.0':
        raise RuntimeError('Use the pinned QA generator environment')
    corpus = directory / 'corpus-v1'
    if corpus.exists():
        manifest = verify_corpus(corpus)
        print(json.dumps({'corpus': str(corpus), 'verified_existing_samples': len(manifest)}))
        return
    corpus.mkdir()
    wavs = directory / 'source-wav-v1'
    wavs.mkdir()
    ffmpeg = Path('C:/Tools/ffmpeg/bin/ffmpeg.exe')
    if not ffmpeg.is_file():
        raise RuntimeError('Expected the existing QA ffmpeg executable')
    # A concrete plan is persisted before synthesis or ASR evaluation.
    plan = [{'id': name, 'text': text, 'expected_wake': label, 'split': split}
            for name, text, label, split in PHRASES]
    (directory / 'plan-v1.json').write_text(json.dumps({
        'phrases': plan, 'length_scales': LENGTH_SCALES, 'created_before_asr_evaluation': True,
        'synthetic_only': True, 'human_acceptance': False,
    }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    voice = PiperVoice.load(str(models / 'vi_VN-vais1000-medium.onnx'))
    manifest = []
    for index, (name, text, label, split) in enumerate(PHRASES):
        for rate_index, scale in enumerate(LENGTH_SCALES):
            filename = f'{name}-r{rate_index}.pcm'
            wav_path = wavs / (Path(filename).stem + '.wav')
            np.random.seed(192000 + index * 10 + rate_index)
            settings = SynthesisConfig(length_scale=scale, noise_scale=0.0, noise_w_scale=0.0)
            with wave.open(str(wav_path), 'wb') as output:
                voice.synthesize_wav(text, output, syn_config=settings)
            target = corpus / filename
            subprocess.run([str(ffmpeg), '-hide_banner', '-loglevel', 'error', '-nostdin',
                            '-i', str(wav_path), '-ar', '16000', '-ac', '1', '-f', 's16le',
                            str(target)], check=True)
            manifest.append({
                'file': filename, 'text': text, 'expected_wake': label, 'split': split,
                'voice': 'piper/vi_VN-vais1000-medium', 'length_scale': scale,
                'synthetic': True, 'sample_rate': 16000, 'channels': 1, 'sample_width': 2,
                'sha256': digest(target), 'bytes': target.stat().st_size,
                'duration_ms': round(target.stat().st_size / 32),
            })
            print(json.dumps({'generated': filename, 'expected_wake': label}), flush=True)
    (corpus / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    metadata = {
        'generator': 'piper-tts 1.3.0, local CPU, noise scales zero',
        'generator_packages': {p: importlib.metadata.version(p) for p in (
            'piper-tts', 'onnxruntime', 'numpy', 'flatbuffers', 'packaging', 'protobuf')},
        'model_repository': 'rhasspy/piper-voices', 'model_revision': REVISION,
        'model_files_sha256': FILES, 'model_card_url': BASE + 'MODEL_CARD',
        'dataset_attribution': 'VAIS-1000 Vietnamese Speech Synthesis Corpus; model published by Rhasspy',
        'model_card_dataset_license': 'https://creativecommons.org/licenses/by/4.0/',
        'engine_license': 'GPL-3.0, local QA tool only',
        'manifest_sha256': digest(corpus / 'manifest.json'),
        'plan_sha256': digest(directory / 'plan-v1.json'),
        'samples': len(manifest), 'positives': sum(row['expected_wake'] for row in manifest),
        'negatives': sum(not row['expected_wake'] for row in manifest),
        'independent_speaker_count': 1, 'synthetic_only': True,
        'external_provider_calls': 0, 'included_in_apk': False, 'installed_on_vps': False,
    }
    (directory / 'CORPUS-v1.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    verify_corpus(corpus)
    print(json.dumps(metadata), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('fetch', 'generate', 'verify'))
    args = parser.parse_args()
    if args.action == 'fetch':
        fetch_models(DEFAULT / 'models')
    elif args.action == 'generate':
        generate(DEFAULT)
    else:
        print(json.dumps({'verified_samples': len(verify_corpus(DEFAULT / 'corpus-v1'))}))


if __name__ == '__main__':
    main()
