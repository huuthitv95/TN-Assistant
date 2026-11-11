"""Run one short TTS and ASR inference through the live TN backend."""

import io
import json
import re
import secrets
import subprocess
import urllib.error
import urllib.request
import wave


BASE = 'http://127.0.0.1:8094'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class SmokeError(RuntimeError):
    pass


def call(method, path, *, token='', data=None, content_type='application/json'):
    if not path.startswith('/v1/'):
        raise SmokeError('Unexpected backend path')
    headers = {'Content-Type': content_type} if data is not None else {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=50) as response:
            return response.status, response.headers.get('Content-Type', ''), response.read(2 << 20)
    except urllib.error.HTTPError as error:
        try:
            body = json.loads(error.read(4096))
            code = body.get('error', {}).get('code', 'HTTP_ERROR')
        except (ValueError, AttributeError):
            code = 'HTTP_ERROR'
        raise SmokeError(f'{method} {path.split("?")[0]} returned HTTP {error.code}: {code}') from None
    except urllib.error.URLError as error:
        raise SmokeError(f'{method} {path.split("?")[0]} could not reach the local backend') from None


def json_call(method, path, *, token='', body=None):
    data = json.dumps(body, separators=(',', ':')).encode() if body is not None else None
    status, _, raw = call(method, path, token=token, data=data)
    try:
        return status, json.loads(raw)
    except ValueError:
        raise SmokeError(f'{method} {path} returned invalid JSON') from None


def wav_from_mp3(mp3):
    if not 0 < len(mp3) <= (1 << 20):
        raise SmokeError('TTS audio size is outside the smoke limit')
    result = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error',
                             '-f', 'mp3', '-i', 'pipe:0', '-ac', '1', '-ar', '16000',
                             '-f', 's16le', 'pipe:1'], input=mp3, capture_output=True,
                            timeout=15, check=False)
    pcm = result.stdout
    if result.returncode != 0 or len(pcm) < 3200 or len(pcm) > 192000 or len(pcm) % 2:
        raise SmokeError('TTS audio could not be converted to a short PCM sample')
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16000)
        output.writeframes(pcm)
    return buffer.getvalue()


def main():
    result = {'provider_calls': {}, 'readiness': {}, 'temporary_guest': {'deleted': False},
              'secrets_audio_transcript_retained': False}
    token = ''
    try:
        _, guest = json_call('POST', '/v1/auth/guest', body={
            'device_id': 'provider-smoke-' + secrets.token_hex(12),
            'device_name': 'TN provider readiness smoke',
        })
        token = guest['access_token']
        _, tts = json_call('POST', '/v1/tts', token=token, body={'text': 'TN ready'})
        path = tts.get('url', '')
        if not re.fullmatch(r'/v1/tts/audio/[A-Za-z0-9_-]+', path):
            raise SmokeError('TTS returned an unexpected audio path')
        _, mime, mp3 = call('GET', path, token=token)
        if tts.get('mime_type') != 'audio/mpeg' or not mime.startswith('audio/mpeg'):
            raise SmokeError('TTS returned unexpected audio type')
        result['provider_calls']['tts'] = {'provider': 'original_tts', 'http': 200,
                                           'audio_http': 200, 'audio_bytes': len(mp3)}
        wav = wav_from_mp3(mp3)
        status, _, raw = call('POST', '/v1/speech/decode?sample_rate=16000&channels=1',
                              token=token, data=wav, content_type='audio/wav')
        asr = json.loads(raw)
        if status != 200 or asr.get('source') != 'deepgram' or not str(asr.get('text', '')).strip():
            raise SmokeError('Deepgram did not return a nonempty transcript')
        result['provider_calls']['asr'] = {'provider': 'deepgram', 'http': 200,
                                           'input': 'synthetic 16 kHz mono WAV',
                                           'input_bytes': len(wav), 'transcript_nonempty': True}
        _, ready = json_call('GET', '/v1/ready')
        checks = ready.get('checks', {})
        result['readiness'] = {'http': 200 if ready.get('ready') is True else 503,
                               'ready': ready.get('ready') is True,
                               'asr_status': checks.get('asr', {}).get('status'),
                               'tts_status': checks.get('tts', {}).get('status'),
                               'asr_last_success_at': checks.get('asr', {}).get('last_success_at'),
                               'tts_last_success_at': checks.get('tts', {}).get('last_success_at')}
        if ready.get('ready') is not True:
            raise SmokeError('Provider calls succeeded but backend readiness is false')
    finally:
        if token:
            try:
                status, deleted = json_call('DELETE', '/v1/me', token=token)
                result['temporary_guest']['deleted'] = status == 200 and deleted.get('ok') is True
            except SmokeError:
                pass
    print(json.dumps(result, separators=(',', ':')))
    if not result['temporary_guest']['deleted']:
        raise SmokeError('Disposable guest cleanup failed')


if __name__ == '__main__':
    try:
        main()
    except SmokeError as error:
        print(json.dumps({'status': 'failed', 'error': str(error)}))
        raise SystemExit(1)
