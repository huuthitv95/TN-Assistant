"""Generate TN's packaged speed-warning prompts from pinned local speech models.

First run deploy/prepare_speech.py --kind tts --root <external-model-directory>.
Inference runs offline and does not use provider credentials or original-app audio.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess

PINS = {
    'pnnbao-ump/VieNeu-TTS-v3-Turbo': '61b85e3d937fbbacb387714180e8182823512523',
    'OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX': 'ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae',
}
TEXT = 'Chú ý tốc độ. Bạn đã đạt ngưỡng cảnh báo.'
VOICES = {'south': 'Thùy Dung', 'north': 'Ngọc Huyền'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-root', type=Path, required=True)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.model_root.resolve()
    for repo, revision in PINS.items():
        ref = root / 'huggingface/hub' / ('models--' + repo.replace('/', '--')) / 'refs/main'
        if not ref.is_file() or ref.read_text().strip() != revision:
            raise RuntimeError('Prepare the pinned offline model before generation: ' + repo)
    if importlib.metadata.version('vieneu') != '3.8.3':
        raise RuntimeError('Use vieneu==3.8.3')
    os.environ.update(HF_HOME=str(root / 'huggingface'), HF_HUB_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1')
    import numpy as np
    import soundfile as sf
    from vieneu import Vieneu
    engine = Vieneu(mode='v3turbo', backend='onnx', device='cpu', precision='fp32', threads=4)
    available = {identifier for _, identifier in engine.list_preset_voices()}
    if not set(VOICES.values()).issubset(available):
        raise RuntimeError('Required Northern/Southern female presets unavailable')
    output = args.repo / 'android/app/src/independent/res/raw'
    output.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for accent, voice in VOICES.items():
        audio = np.asarray(engine.infer(TEXT, voice=voice, apply_watermark=False), dtype=np.float32).reshape(-1)
        duration = audio.size / engine.sample_rate
        peak = float(np.max(np.abs(audio))) if audio.size else 0
        if not np.isfinite(audio).all() or not .5 <= duration <= 8 or peak < .001:
            raise RuntimeError('Invalid generated prompt: ' + accent)
        wav = root / ('tn-road-' + accent + '.wav')
        sf.write(wav, audio * (.85 / peak), engine.sample_rate, subtype='PCM_16')
        target = output / ('tn_road_overspeed_' + accent + '.mp3')
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(wav),
                        '-map_metadata', '-1', '-ac', '1', '-codec:a', 'libmp3lame', '-b:a', '96k', str(target)], check=True)
        artifacts.append({'path': target.relative_to(args.repo).as_posix(), 'voice': voice, 'accent': accent,
                          'text': TEXT, 'duration_seconds': round(duration, 3),
                          'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    provenance = {'engine': 'VieNeu-TTS-v3-Turbo', 'model_pins': PINS,
                  'packages': {p: importlib.metadata.version(p) for p in ('vieneu', 'onnxruntime', 'sea-g2p')},
                  'source': 'https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Turbo',
                  'model_license': 'Apache-2.0', 'artifacts': artifacts}
    (args.repo / 'android/road-prompts.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'generated': artifacts}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
