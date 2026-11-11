"""Generate explicitly synthetic wake-word evaluation PCM from the existing offline TTS trial."""
from pathlib import Path
import json
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.runtime/tn-wake/corpus'
PHRASES = ['Ti En ơi', 'Xin chào Ti En', 'Tê Nờ ơi', 'Xin chào Tê Nờ',
           'Ti En ơi, mở bản đồ', 'Xin chào Tê Nờ, tăng âm lượng',
           'Hôm nay trời đẹp quá', 'Kiki ơi', 'Tên của bạn là gì', 'Xin chào mọi người']
VOICES = ['Mai Anh', 'Minh Triết', 'Quang Sơn']

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ffmpeg = next((p for p in Path('C:/Tools/ffmpeg').rglob('ffmpeg.exe')), None)
    if ffmpeg is None:
        raise RuntimeError('ffmpeg.exe not found in C:/Tools/ffmpeg')
    old_manifest = json.loads((OUT / 'manifest.json').read_text(encoding='utf-8')) if (OUT / 'manifest.json').exists() else []
    known = {item['file']: item for item in old_manifest}
    manifest = []
    for v, voice in enumerate(VOICES):
        for i, text in enumerate(PHRASES):
            target = OUT / f'{v:02}-{i:02}.pcm'
            if target.exists():
                manifest.append(known.get(target.name, {'file': target.name, 'text': text, 'voice': voice, 'expected_wake': i < 6}))
                continue
            payload = json.dumps({'text': text, 'voice': voice}).encode()
            request = urllib.request.Request('http://127.0.0.1:8092/api/synthesize', payload,
                                             {'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=180) as response:
                info = json.load(response)
            source = ROOT / '.runtime/local-tts' / info['url'].lstrip('/')
            subprocess.run([str(ffmpeg), '-hide_banner', '-loglevel', 'error', '-y', '-i', str(source),
                            '-ar', '16000', '-ac', '1', '-f', 's16le', str(target)], check=True)
            manifest.append({'file': target.name, 'text': text, 'voice': voice,
                             'expected_wake': i < 6, 'source_id': info['id']})
            print(target.name, flush=True)
    (OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

if __name__ == '__main__':
    main()
