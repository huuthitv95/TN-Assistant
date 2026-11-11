"""Install TN speech sidecars without touching the current application release."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def run(args, **kwargs):
    print(json.dumps({'step': args[0], 'operation': args[1:4]}), flush=True)
    subprocess.run(args, check=True, **kwargs)


def main():
    root = Path('/srv/tn-assistant/speech')
    root.mkdir(mode=0o755, exist_ok=True)
    # Fly's container /tmp is a small tmpfs. ONNX/Python wheels can exceed it
    # while being unpacked, so keep install scratch on the persistent volume.
    temp_root = Path('/var/lib/tn-assistant/tmp/speech-install')
    temp_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    install_env = {
        **os.environ,
        'TMPDIR': str(temp_root),
        'TMP': str(temp_root),
        'TEMP': str(temp_root),
        'PIP_NO_CACHE_DIR': '1',
    }
    source = Path(__file__).resolve().parent
    for name in ('speech_worker.py', 'prepare_speech.py'):
        shutil.copyfile(source / name, root / name)
    for kind in ('asr', 'tts'):
        pinned_lock = source / ('requirements-' + kind + '.lock')
        if pinned_lock.is_file():
            shutil.copyfile(pinned_lock, root / pinned_lock.name)
    run(['apt-get', 'update', '-qq'])
    run(['apt-get', 'install', '-y', '-qq', 'python3.12-venv', 'libsndfile1', 'espeak-ng', 'ca-certificates'], env={**os.environ, 'DEBIAN_FRONTEND': 'noninteractive'})
    for kind in ('asr', 'tts'):
        venv = root / ('venv-' + kind)
        if not (venv / 'bin/python').exists():
            run(['/usr/bin/python3.12', '-m', 'venv', str(venv)])
        python = str(venv / 'bin/python')
        lockfile = root / ('requirements-' + kind + '.lock')
        if lockfile.is_file():
            pip_args = ['install', '--disable-pip-version-check', '-r', str(lockfile)]
        else:
            packages = ['faster-whisper==1.2.1', 'huggingface-hub==1.33.0'] if kind == 'asr' else ['vieneu==3.8.3', 'huggingface-hub==1.33.0', 'onnxruntime==1.30.0']
            pip_args = ['install', '--disable-pip-version-check', *packages]
        run([python, '-m', 'pip', *pip_args], env=install_env)
        run([python, '-m', 'pip', 'check'], env=install_env)
        models = Path('/var/lib/tn-assistant/models') / kind
        run([python, str(root / 'prepare_speech.py'), '--kind', kind, '--root', str(models)], env={**os.environ, 'HF_HUB_DISABLE_TELEMETRY': '1'})
        lock = subprocess.check_output([python, '-m', 'pip', 'freeze'], text=True)
        (root / ('requirements-' + kind + '.lock')).write_text(lock)
        port = 8091 if kind == 'asr' else 8092
        model_arg = '--model-path ' + str(models / 'whisper-small') if kind == 'asr' else ''
        unit = f'''[Unit]
Description=TN independent {kind.upper()} worker
After=network.target
[Service]
User=tnassistant
Group=tnassistant
WorkingDirectory={root}
Environment=HF_HOME={models}/huggingface
Environment=HF_HUB_OFFLINE=1
Environment=HF_HUB_DISABLE_TELEMETRY=1
Environment=OMP_NUM_THREADS={'6' if kind == 'asr' else '4'}
ExecStart={python} {root}/speech_worker.py --kind {kind} --port {port} {model_arg}
Restart=on-failure
RestartSec=5
TimeoutStartSec=240
TimeoutStopSec=10
KillMode=control-group
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/tn-assistant/models/{kind}
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
IPAddressDeny=any
IPAddressAllow=localhost
MemoryMax=8G
TasksMax=128
[Install]
WantedBy=multi-user.target
'''
        Path('/etc/systemd/system/tn-' + kind + '.service').write_text(unit)
        run(['chown', '-R', 'tnassistant:tnassistant', str(models)])
        run(['systemctl', 'daemon-reload'])
        run(['systemctl', 'enable', '--now', 'tn-' + kind])
        print(json.dumps({'installed': kind, 'port': port}), flush=True)


if __name__ == '__main__':
    main()
