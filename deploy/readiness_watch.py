"""Read-only production readiness observation; never calls a speech provider."""

import datetime as dt
import json
import os
from pathlib import Path
import urllib.error
import urllib.request


STATE = Path('/var/lib/tn-assistant/ops')
ROOTFS_IDENTITY = Path('/.fly-vps-rootfs-v1')
URLS = {
    'local_health': 'http://127.0.0.1:8094/v1/health',
    'local_ready': 'http://127.0.0.1:8094/v1/ready',
    'public_health': 'https://assistant.tnfastvn.com/v1/health',
    'public_ready': 'https://assistant.tnfastvn.com/v1/ready',
}


def probe(url, readiness=False):
    try:
        try:
            response = urllib.request.urlopen(url, timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            body = json.loads(response.read(65537))
        if not isinstance(body, dict):
            raise ValueError('unexpected response shape')
        result = {'http': status, 'ok': status == 200}
        if readiness:
            result['ok'] = result['ok'] and body.get('ready') is True
            result['status'] = body.get('status') if body.get('status') in ('ready', 'not_ready') else 'unknown'
            checks = body.get('checks')
            if isinstance(checks, dict):
                result['checks'] = {
                    key: {
                        'healthy': value.get('healthy') is True,
                        'status': str(value.get('status', 'unknown'))[:64],
                    }
                    for key in ('runtime', 'database', 'asr', 'tts')
                    if isinstance(value := checks.get(key), dict)
                }
        else:
            result['ok'] = result['ok'] and body.get('status') == 'ok'
        return result
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return {'ok': False, 'error': type(error).__name__}


def rootfs_identity_ok(path=ROOTFS_IDENTITY):
    try:
        return path.is_file() and not path.is_symlink() and path.read_text(encoding='ascii').strip() == 'fly-vps-rootfs-v1'
    except (OSError, UnicodeError):
        return False


def observe(state=STATE, urls=URLS):
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    latest_file = state / 'readiness-latest.json'
    previous = None
    if latest_file.exists():
        try:
            previous = json.loads(latest_file.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            pass
    sample = {'at': dt.datetime.now(dt.timezone.utc).isoformat()}
    for key, url in urls.items():
        sample[key] = probe(url, key.endswith('_ready'))
    sample['service_available'] = sample['local_health']['ok'] and sample['public_health']['ok']
    sample['features_ready'] = sample['local_ready']['ok'] and sample['public_ready']['ok']
    sample['persistent_rootfs_identity_ok'] = rootfs_identity_ok()
    sample['ok'] = sample['service_available'] and sample['features_ready']
    signature = {key: sample[key] for key in urls}
    signature['persistent_rootfs_identity_ok'] = sample['persistent_rootfs_identity_ok']
    old_signature = {key: previous.get(key) for key in urls} if isinstance(previous, dict) else None
    if old_signature is not None:
        old_signature['persistent_rootfs_identity_ok'] = previous.get('persistent_rootfs_identity_ok')
    if signature != old_signature:
        with (state / 'readiness-transitions.jsonl').open('a', encoding='utf-8') as output:
            output.write(json.dumps(sample, ensure_ascii=False) + '\n')
    temporary = latest_file.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(sample, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(temporary, latest_file)
    return sample


if __name__ == '__main__':
    print(json.dumps(observe(), ensure_ascii=False))
