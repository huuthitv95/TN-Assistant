"""Verify the staging network namespace, then probe its local backend."""

import errno
import json
import os
import secrets
from pathlib import Path
import socket
import sqlite3
import sys
import urllib.error
import urllib.request


STAGING_ADDR = '127.0.0.1:18094'


def verify_network():
    interfaces = [name for _, name in socket.if_nameindex()]
    if interfaces != ['lo']:
        raise RuntimeError('Staging namespace has interfaces other than loopback')
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
        connection.settimeout(1.5)
        external = connection.connect_ex(('203.0.113.1', 443))
    if external != errno.ENETUNREACH:
        raise RuntimeError('Staging namespace can route external IPv4 traffic')
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
        connection.settimeout(1.5)
        loopback = connection.connect_ex(('127.0.0.1', 1))
    if loopback != errno.ECONNREFUSED:
        raise RuntimeError('Staging loopback is unavailable')


def get_json(path):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = opener.open('http://' + STAGING_ADDR + path, timeout=2)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read(65537))


def alert_request(method, path, body=None, token=''):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://' + STAGING_ADDR + path, data=data, headers=headers, method=method)
    try:
        response = opener.open(request, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read(65537))


def verify_basic_alerts():
    status, account = alert_request('POST', '/v1/auth/guest', {'device_id': 'staging-alert-' + secrets.token_hex(8), 'device_name': 'Isolated alert acceptance'})
    if status != 200 or not isinstance(account.get('access_token'), str):
        raise RuntimeError('Isolated alert account creation failed')
    token = account['access_token']
    verified = []
    try:
        for index, (text, kind) in enumerate((('đặt hẹn giờ năm phút', 'Timer'), ('đặt báo thức lúc bảy giờ sáng', 'Alarm'),
                ('nhắc tôi kiểm tra lịch lúc bảy giờ sáng', 'Reminder'), ('tắt báo thức tiếp theo', 'DismissAlarm'))):
            context = 'staging-alert-' + str(index)
            status, offer = alert_request('POST', '/v1/assistant/text', {'text': text, 'mode': 'commands', 'timezone': 'Asia/Ho_Chi_Minh',
                'client_capabilities': {'basic_alerts': True},
                'request_id': context + '-offer', 'conversation_id': context}, token)
            preview = offer.get('confirmation', {})
            if status != 200 or offer.get('confirmation_required') is not True or preview.get('mode') != 'commands':
                raise RuntimeError('Isolated Basic alert did not require confirmation: ' + kind)
            if any(d.get('type') != 'SpeechSynthesizer' for d in offer.get('directives', [])):
                raise RuntimeError('Isolated Basic alert escaped before consent')
            approval = {'text': 'Xác nhận', 'mode': 'commands', 'request_id': context + '-approve', 'conversation_id': context,
                        'confirmation_token': offer.get('confirmation_token')}
            status, result = alert_request('POST', '/v1/assistant/text', approval, token)
            directives = result.get('directives', [])
            if status != 200 or len(directives) != 1 or directives[0].get('type') != 'Alerts' or not directives[0].get('action_id'):
                raise RuntimeError('Confirmed isolated Basic alert was not admitted')
            payload = directives[0].get('payload', {})
            if payload.get('type') != kind or (kind == 'Timer' and payload.get('duration') != 300):
                raise RuntimeError('Isolated Basic alert arguments changed')
            if kind in ('Alarm', 'Reminder') and (payload.get('timezone') != 'Asia/Ho_Chi_Minh' or payload.get('utc_offset_seconds') != 25200):
                raise RuntimeError('Isolated Basic alert lost its timezone')
            approval['request_id'] = context + '-reuse'
            status, _ = alert_request('POST', '/v1/assistant/text', approval, token)
            if status != 409:
                raise RuntimeError('Isolated Basic alert confirmation was reusable')
            verified.append(kind)
        status, result = alert_request('POST', '/v1/assistant/text', {'text': 'mở danh sách báo thức', 'mode': 'commands', 'request_id': 'staging-alert-list'}, token)
        directives = result.get('directives', [])
        if status != 200 or result.get('confirmation_required') or len(directives) != 1 or directives[0].get('payload', {}).get('type') != 'ShowAlarms':
            raise RuntimeError('Isolated alarm listing was not read-only')
        verified.append('ShowAlarms')
    finally:
        status, result = alert_request('DELETE', '/v1/me', token=token)
        if status != 200 or result.get('ok') is not True:
            raise RuntimeError('Isolated alert account cleanup failed')
    return {'status': 'passed', 'verified_alert_types': verified, 'single_use_confirmations': 4, 'qa_account_deleted': True,
            'measurement': 'real HTTP to isolated candidate; no Android device action or provider inference'}


def main():
    if len(sys.argv) != 2:
        raise SystemExit(2)
    verify_network()
    if sys.argv[1] == '--basic-alerts':
        print(json.dumps(verify_basic_alerts()))
        return
    if sys.argv[1] == '--http':
        health_code, health = get_json('/v1/health')
        ready_code, readiness = get_json('/v1/ready')
        print(json.dumps({'health_code': health_code, 'health_status': health.get('status'),
                          'ready_code': ready_code, 'readiness': readiness}))
        return
    if sys.argv[1] == '--preflight':
        database = Path(os.environ['TN_DB'])
        if not database.is_file() or not os.access(database, os.R_OK | os.W_OK):
            raise RuntimeError('Staging database is not readable and writable by the service user')
        connection = sqlite3.connect(database.as_uri() + '?mode=rw', uri=True)
        try:
            if connection.execute('PRAGMA integrity_check').fetchone() != ('ok',):
                raise RuntimeError('Staging database integrity check failed')
        finally:
            connection.close()
        return
    marker = Path(sys.argv[1])
    descriptor = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w', encoding='ascii') as output:
        output.write('private network namespace verified\n')
        output.flush()
        os.fsync(output.fileno())


if __name__ == '__main__':
    main()
