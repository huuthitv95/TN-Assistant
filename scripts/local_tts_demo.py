"""Local-only Vietnamese TTS trial. No original-app data or paid speech API."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.runtime/local-tts'
SAMPLES = [
    ('welcome', 'Xin chào, tôi là trợ lý Kiki. Chúc bạn một chuyến đi an toàn.'),
    ('driving', 'Phía trước có camera tốc độ. Tốc độ tối đa là năm mươi ki lô mét trên giờ.'),
    ('news', 'Đây là bản tin mẫu để kiểm tra giọng đọc, không phải tin tức thực tế. Hôm nay, chúng ta thử chức năng đọc tiếng Việt trên máy ảo Android. Bạn có thể tạm dừng hoặc tiếp tục phát bản tin.'),
]

def load_engine(offline):
    os.environ['HF_HOME'] = str(RUNTIME / 'huggingface')
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    os.environ['HF_HUB_DISABLE_SYMLINKS_WARNING'] = '1'
    if offline:
        os.environ['HF_HUB_OFFLINE'] = '1'
        import sys
        def loopback_only(event, arguments):
            if event == 'socket.connect':
                address = arguments[1]
                if not isinstance(address, tuple) or address[0] not in ('127.0.0.1', '::1'):
                    raise PermissionError('External Python socket connections are disabled in this local trial')
        sys.addaudithook(loopback_only)
    else:
        from huggingface_hub import hf_hub_download
        from vieneu._v3_turbo_engine.onnx_runtime_lite import _GRAPH_FILES, _CODEC_FILES
        for repository, folder, filenames in [
            ('pnnbao-ump/VieNeu-TTS-v3-Turbo', 'onnx_update', _GRAPH_FILES),
            ('OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX', None, _CODEC_FILES),
        ]:
            for filename in filenames:
                hf_hub_download(repository, filename, subfolder=folder)
    # ORT 1.30 rejects external tensor data resolved through HF's blob symlinks.
    # Materialize only our trial's snapshot links; leave shared/global caches alone.
    import shutil
    cache = (RUNTIME/'huggingface').resolve()
    for link in cache.glob('hub/models--*/snapshots/**/*'):
        if link.is_symlink() and link.is_file():
            source = link.resolve()
            if not source.is_relative_to(cache) or not link.parent.resolve().is_relative_to(cache):
                raise RuntimeError('Model cache path leaves the trial directory')
            temp = link.with_name(link.name+'.materializing')
            shutil.copyfile(source, temp)
            os.replace(temp, link)
    from vieneu import Vieneu
    return Vieneu(mode='v3turbo', backend='onnx', device='cpu', precision='fp32', threads=4)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare', action='store_true', help='One-time public model download')
    parser.add_argument('--port', type=int, default=8092)
    args = parser.parse_args()
    RUNTIME.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    engine = load_engine(offline=not args.prepare)
    voices = [{'id': identifier, 'label': label} for label, identifier in engine.list_preset_voices()]
    info = {'engine': 'VieNeu-TTS-v3-Turbo', 'package_version': importlib.metadata.version('vieneu'),
            'backend': engine.backend, 'precision': 'fp32', 'threads': 4, 'sample_rate': engine.sample_rate,
            'offline_model_loading': not args.prepare, 'load_seconds': round(time.monotonic()-started, 3),
            'external_python_sockets_blocked': not args.prepare,
            'voices': voices}
    (RUNTIME / ('prepare.json' if args.prepare else 'server-info.json')).write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(info, ensure_ascii=True), flush=True)
    if args.prepare:
        # Warm up phonemizer too: later server initialization and generation use cached assets.
        audio = engine.infer('Xin chào.', voice=voices[0]['id'], apply_watermark=False)
        engine.save(audio, str(RUNTIME / 'warmup.wav'))
        print('PREPARED', flush=True)
        return
    audio_dir = RUNTIME / 'audio'
    audio_dir.mkdir(exist_ok=True)
    lock = threading.Lock()
    voice_ids = {v['id'] for v in voices}
    index = {}
    manifest = audio_dir / 'index.json'
    if manifest.exists():
        index.update(json.loads(manifest.read_text(encoding='utf-8')))

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *values):
            print(fmt % values, flush=True)

        def send(self, status, data, mime='application/json; charset=utf-8', headers=None):
            if isinstance(data, (dict, list)):
                data = json.dumps(data, ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Cache-Control', 'no-store')
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def allowed(self):
            expected = f'http://127.0.0.1:{args.port}'
            return (self.headers.get('Host') == f'127.0.0.1:{args.port}' and
                    self.headers.get('Origin', expected) == expected)

        def do_GET(self):
            if not self.allowed():
                return self.send(403, {'error': 'Use the loopback address'})
            if self.path == '/':
                return self.send(200, (ROOT/'scripts/local-tts.html').read_bytes(), 'text/html; charset=utf-8')
            if self.path == '/health':
                return self.send(200, info)
            if self.path == '/api/samples':
                return self.send(200, [{'id': k, 'text': t} for k, t in SAMPLES])
            match = re.fullmatch(r'/audio/([0-9a-f]{24})\.wav', self.path)
            if match:
                file = audio_dir / (match[1]+'.wav')
                if not file.exists():
                    return self.send(404, {'error': 'Audio not found'})
                data = file.read_bytes()
                range_header = self.headers.get('Range')
                if range_header:
                    match_range = re.fullmatch(r'bytes=(\d+)-(\d*)', range_header)
                    if not match_range:
                        return self.send(416, b'', headers={'Content-Range': f'bytes */{len(data)}'})
                    start = int(match_range[1])
                    end = min(int(match_range[2]) if match_range[2] else len(data)-1, len(data)-1)
                    if start > end:
                        return self.send(416, b'', headers={'Content-Range': f'bytes */{len(data)}'})
                    return self.send(206, data[start:end+1], 'audio/wav', {'Content-Range': f'bytes {start}-{end}/{len(data)}', 'Accept-Ranges': 'bytes'})
                return self.send(200, data, 'audio/wav', {'Accept-Ranges': 'bytes'})
            return self.send(404, {'error': 'Not found'})

        def do_POST(self):
            if not self.allowed():
                return self.send(403, {'error': 'Use the loopback address'})
            if self.path != '/api/synthesize':
                return self.send(404, {'error': 'Not found'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 8192:
                    raise ValueError('Invalid request size')
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict) or set(payload) != {'text', 'voice'}:
                    raise ValueError('Expected text and voice')
                text, voice = payload['text'], payload['voice']
                if not isinstance(text, str) or not 1 <= len(text.strip()) <= 500:
                    raise ValueError('Text must contain 1–500 characters')
                if not isinstance(voice, str) or voice not in voice_ids:
                    raise ValueError('Unknown voice')
            except (ValueError, TypeError) as error:
                return self.send(400, {'error': str(error)})
            if not lock.acquire(blocking=False):
                return self.send(429, {'error': 'Đang tạo audio. Vui lòng thử lại.'})
            try:
                key = hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:24]
                if key in index and (audio_dir/(key+'.wav')).exists():
                    return self.send(200, {**index[key], 'cached': True})
                start = time.monotonic()
                import numpy as np
                audio = engine.infer(text, voice=voice, apply_watermark=False)
                audio = np.asarray(audio)
                if audio.size < engine.sample_rate // 4 or not np.isfinite(audio).all() or np.max(np.abs(audio)) < 0.001:
                    raise RuntimeError('Model returned invalid or silent audio')
                file = audio_dir / (key+'.wav')
                engine.save(audio, str(file))
                duration = len(audio)/engine.sample_rate
                elapsed = time.monotonic()-start
                result = {'id': key, 'url': f'/audio/{key}.wav', 'voice': voice, 'text': text,
                          'duration_seconds': round(duration, 3), 'generation_seconds': round(elapsed, 3),
                          'rtf': round(elapsed/duration, 3), 'sample_rate': engine.sample_rate,
                          'sha256': hashlib.sha256(file.read_bytes()).hexdigest(), 'bytes': file.stat().st_size,
                          'peak': round(float(np.max(np.abs(audio))), 5), 'rms': round(float(np.sqrt(np.mean(audio**2))), 5),
                          'offline_inference': True, 'cached': False}
                index[key] = result
                manifest.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
                self.send(200, result)
            except Exception:
                import traceback
                traceback.print_exc()
                self.send(500, {'error': 'Local synthesis failed; inspect server.log'})
            finally:
                lock.release()

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print(f'READY http://127.0.0.1:{args.port}', flush=True)
    server.serve_forever()

if __name__ == '__main__':
    main()
