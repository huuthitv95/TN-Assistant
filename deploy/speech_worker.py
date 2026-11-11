"""TN-owned loopback speech worker. No original-app code, data or credentials.

One model subprocess permits actual cancellation of native inference. A bounded
admission semaphore limits active+queued requests to five; audio is never saved.
"""
import argparse
import io
import json
import multiprocessing as mp
import os
from pathlib import Path
import select
import socket
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def engine_process(connection, kind, model_path):
    try:
        import numpy as np
        if kind == 'asr':
            from faster_whisper import WhisperModel
            engine = WhisperModel(model_path, device='cpu', compute_type='int8', cpu_threads=6, num_workers=1, local_files_only=True)
            info = {'engine': 'faster-whisper-small', 'precision': 'int8', 'threads': 6, 'sample_rate': 16000}
        else:
            from vieneu import Vieneu
            engine = Vieneu(mode='v3turbo', backend='onnx', device='cpu', precision='fp32', threads=4)
            voices = engine.list_preset_voices()
            voice = next((identifier for label, identifier in voices if 'Thùy Dung' in label or 'Thùy Dung' in identifier), None)
            if not voice:
                raise RuntimeError('Configured Southern Vietnamese female preset missing')
            info = {'engine': 'VieNeu-TTS-v3-Turbo', 'precision': 'fp32', 'threads': 4, 'sample_rate': engine.sample_rate, 'voice': 'Thùy Dung'}
        connection.send(('ready', info))
        while True:
            payload = connection.recv()
            start = time.monotonic()
            try:
                if kind == 'asr':
                    raw, content_type = payload
                    if content_type == 'audio/wav':
                        with wave.open(io.BytesIO(raw)) as wav:
                            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != 16000:
                                raise ValueError('PCM16 mono 16000 Hz WAV required')
                            raw = wav.readframes(wav.getnframes())
                    if len(raw) % 2 or not 320 <= len(raw) <= 480000:
                        raise ValueError('Audio must be 0.01 to 15 seconds PCM16 mono 16 kHz')
                    samples = np.frombuffer(raw, dtype='<i2').astype(np.float32) / 32768.0
                    segments, _ = engine.transcribe(samples, language='vi', task='transcribe', beam_size=1, best_of=1, temperature=0, condition_on_previous_text=False, vad_filter=True)
                    text = ' '.join(s.text.strip() for s in segments if s.no_speech_prob < 0.8).strip()
                    result = {'text': text, 'language': 'vi', 'audio_seconds': len(samples) / 16000, 'provider_latency_ms': round((time.monotonic() - start) * 1000)}
                    connection.send(('ok', json.dumps(result, ensure_ascii=False).encode(), 'application/json'))
                else:
                    text = payload['text'].strip()
                    if not 1 <= len(text) <= 500:
                        raise ValueError('Text must contain 1 to 500 characters')
                    if payload.get('voice', 'Thùy Dung') not in ('Thùy Dung', voice, 'south', '5', '11'):
                        raise ValueError('Only the configured TN voice is available')
                    audio = np.asarray(engine.infer(text, voice=voice, apply_watermark=False), dtype=np.float32).reshape(-1)
                    if not audio.size or not np.isfinite(audio).all() or float(np.max(np.abs(audio))) < 0.0001:
                        raise RuntimeError('Speech model produced no valid audio')
                    pcm = (np.clip(audio, -1, 1) * 32767).astype('<i2').tobytes()
                    output = io.BytesIO()
                    with wave.open(output, 'wb') as wav:
                        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(engine.sample_rate); wav.writeframes(pcm)
                    connection.send(('ok', output.getvalue(), 'audio/wav'))
            except ValueError:
                connection.send(('error', 'INVALID_INPUT'))
            except Exception:
                connection.send(('error', 'INFERENCE_FAILED'))
    except BaseException as error:
        try:
            connection.send(('fatal', type(error).__name__))
        except (OSError, EOFError):
            pass


class SpeechRuntime:
    def __init__(self, kind, model_path):
        self.kind, self.model_path = kind, model_path
        self.lock = threading.Lock()
        self.admission = threading.BoundedSemaphore(5)
        self.active = 0
        self.queued = 0
        self.completed = 0
        self.cancelled = 0
        self.rejected = 0
        self.info = {}
        self.process = None
        self.connection = None
        self.start()

    def start(self):
        parent, child = mp.Pipe()
        self.connection = parent
        self.process = mp.Process(target=engine_process, args=(child, self.kind, self.model_path), daemon=True)
        self.process.start()
        child.close()
        if not parent.poll(180):
            self.stop()
            raise RuntimeError('Speech model startup timed out')
        status, value = parent.recv()
        if status != 'ready':
            self.stop()
            raise RuntimeError('Speech model startup failed: ' + value)
        self.info = value

    def stop(self):
        if self.process:
            self.process.terminate()
            self.process.join(5)
            if self.process.is_alive():
                self.process.kill(); self.process.join(3)
        if self.connection:
            self.connection.close()
        self.info = {}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--kind', choices=['asr', 'tts'], required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--model-path', default='')
    args = parser.parse_args()
    runtime = SpeechRuntime(args.kind, args.model_path)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass  # No prompts, audio, keys or request bodies in journals.

        def send(self, status, body, mime='application/json'):
            if not isinstance(body, bytes):
                body = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def disconnected(self):
            ready, _, _ = select.select([self.connection], [], [], 0)
            if not ready:
                return False
            try:
                return not self.connection.recv(1, socket.MSG_PEEK | socket.MSG_DONTWAIT)
            except (ConnectionResetError, OSError):
                return True

        def allowed(self):
            return self.headers.get('Host') in (f'127.0.0.1:{args.port}', f'localhost:{args.port}') and not self.headers.get('Origin')

        def do_GET(self):
            if not self.allowed():
                return self.send(403, {'error': {'code': 'LOOPBACK_ONLY'}})
            if self.path != '/health':
                return self.send(404, {})
            ready = bool(runtime.info) and runtime.process.is_alive()
            self.send(200 if ready else 503, {'ready': ready, 'status': 'ready' if ready else 'unavailable', **runtime.info, 'active': runtime.active, 'queued': runtime.queued, 'queue_limit': 4, 'completed': runtime.completed, 'cancelled': runtime.cancelled, 'rejected': runtime.rejected, 'offline_models': True})

        def do_POST(self):
            if not self.allowed():
                return self.send(403, {'error': {'code': 'LOOPBACK_ONLY'}})
            if self.path != ('/recognize' if args.kind == 'asr' else '/synthesize'):
                return self.send(404, {})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= (481024 if args.kind == 'asr' else 8192):
                    return self.send(413, {'error': {'code': 'INPUT_TOO_LARGE'}})
                self.connection.settimeout(10)
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError()
                if args.kind == 'asr':
                    ct = self.headers.get('Content-Type', '').split(';')[0]
                    if ct not in ('audio/wav', 'audio/pcm', 'application/octet-stream'):
                        return self.send(415, {'error': {'code': 'INVALID_AUDIO'}})
                    payload = (body, ct)
                else:
                    payload = json.loads(body)
                    if not isinstance(payload, dict) or not isinstance(payload.get('text'), str):
                        raise ValueError()
            except (ValueError, TypeError, OSError):
                return self.send(400, {'error': {'code': 'INVALID_INPUT'}})
            if not runtime.admission.acquire(False):
                runtime.rejected += 1
                return self.send(429, {'error': {'code': 'SPEECH_BUSY'}})
            runtime.queued += 1
            acquired = False
            deadline = time.monotonic() + 28
            try:
                while not acquired:
                    if self.disconnected():
                        runtime.cancelled += 1
                        return
                    if time.monotonic() > deadline:
                        return self.send(504, {'error': {'code': 'SPEECH_QUEUE_TIMEOUT'}})
                    acquired = runtime.lock.acquire(timeout=0.1)
                runtime.queued -= 1
                runtime.active = 1
                if not runtime.info or not runtime.process.is_alive():
                    return self.send(503, {'error': {'code': 'MODEL_RESTARTING'}})
                runtime.connection.send(payload)
                while not runtime.connection.poll(0.1):
                    if self.disconnected() or time.monotonic() > deadline:
                        runtime.cancelled += 1
                        runtime.stop()
                        threading.Thread(target=reload_model, daemon=True).start()
                        return self.send(504, {'error': {'code': 'SPEECH_CANCELLED'}})
                result = runtime.connection.recv()
                if result[0] == 'ok':
                    runtime.completed += 1
                    return self.send(200, result[1], result[2])
                return self.send(400 if result[1] == 'INVALID_INPUT' else 503, {'error': {'code': result[1]}})
            except (EOFError, OSError):
                return self.send(503, {'error': {'code': 'MODEL_UNAVAILABLE'}})
            finally:
                if acquired:
                    runtime.active = 0
                    runtime.lock.release()
                else:
                    runtime.queued -= 1
                runtime.admission.release()

    def reload_model():
        with runtime.lock:
            try:
                runtime.start()
            except Exception:
                runtime.stop()

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.daemon_threads = True
    print(json.dumps({'status': 'ready', 'kind': args.kind, 'port': args.port, **runtime.info}), flush=True)
    server.serve_forever()


if __name__ == '__main__':
    mp.set_start_method('spawn')
    main()
