import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import struct
import threading
import unittest


SCRIPT = Path(__file__).with_name("deploy_remote.py")
SPEC = importlib.util.spec_from_file_location("deploy_remote", SCRIPT)
DEPLOY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOY)


def read_exact(connection, size):
    value = bytearray()
    while len(value) < size:
        chunk = connection.recv(size - len(value))
        if not chunk:
            raise RuntimeError("socket closed early")
        value.extend(chunk)
    return bytes(value)


class SpeechWebSocketProbeTests(unittest.TestCase):
    def test_probe_sends_start_accepts_ready_and_cancels(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]
        failures = []

        def server():
            try:
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(3)
                    request = bytearray()
                    while b"\r\n\r\n" not in request:
                        request.extend(connection.recv(4096))
                    headers = request.decode().split("\r\n")
                    key = next(line.split(":", 1)[1].strip() for line in headers if line.lower().startswith("sec-websocket-key:"))
                    accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
                    connection.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").encode())
                    first, second = read_exact(connection, 1)[0], read_exact(connection, 1)[0]
                    self.assertEqual(first & 0x0F, 1)
                    self.assertTrue(second & 0x80)
                    length = second & 0x7F
                    if length == 126:
                        length = struct.unpack("!H", read_exact(connection, 2))[0]
                    mask = read_exact(connection, 4)
                    payload = read_exact(connection, length)
                    start = json.loads(bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))
                    self.assertEqual(start["type"], "start")
                    self.assertEqual(start["sample_rate"], 16000)
                    ready = json.dumps({"type": "ready"}, separators=(",", ":")).encode()
                    connection.sendall(bytes((0x81, len(ready))) + ready)
                    first, second = read_exact(connection, 1)[0], read_exact(connection, 1)[0]
                    self.assertEqual(first & 0x0F, 1)
                    self.assertTrue(second & 0x80)
                    length = second & 0x7F
                    mask = read_exact(connection, 4)
                    payload = read_exact(connection, length)
                    cancel = json.loads(bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))
                    self.assertEqual(cancel["type"], "cancel")
            except BaseException as error:
                failures.append(error)
            finally:
                listener.close()

        worker = threading.Thread(target=server, daemon=True)
        worker.start()
        result = DEPLOY.websocket_probe("staging-token", host="127.0.0.1", port=port, secure=False)
        worker.join(timeout=5)
        self.assertFalse(worker.is_alive())
        self.assertFalse(failures, failures)
        self.assertEqual(result, {"upgrade_status": 101, "first_event": "ready", "cancel_sent": True})


if __name__ == "__main__":
    unittest.main()
