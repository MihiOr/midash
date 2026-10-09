"""Transport tests: probes consume nothing; subscriptions forward all bytes unchanged."""

import json
import socket
import threading
import time
import unittest
from unittest.mock import patch

from com_relay import Handler, Relay, Server


class RelayTest(unittest.TestCase):
    def test_probe_and_binary_subscription(self):
        payload = bytes.fromhex(
            "04 AC 00 00 13 48 02 DE 2C 06 70 76 18 01 FC 0C"
        ) + bytes(range(256))
        opened = []

        class Device:
            in_waiting = len(payload)
            sent = False

            def open(self):
                opened.append(self)

            def close(self):
                pass

            def read(self, size):
                if not self.sent:
                    self.sent = True
                    return payload
                time.sleep(0.01)
                return b""

            def write(self, data):
                raise AssertionError("USB must be read-only")

        relay = Relay("COM_TEST")
        with (
            patch.object(relay, "available", return_value=True),
            patch("com_relay.serial.Serial", Device),
            Server(("127.0.0.1", 0), Handler) as server,
        ):
            server.relay = relay
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:

                def connect():
                    c = socket.create_connection(server.server_address, timeout=1)
                    header = bytearray()
                    while not header.endswith(b"\n"):
                        header.extend(c.recv(1))
                    self.assertEqual("minini-binary-v1", json.loads(header)["protocol"])
                    return c

                with connect():
                    pass
                self.assertEqual([], opened)
                with connect() as client:
                    client.sendall(b"\x01")
                    received = bytearray()
                    while len(received) < len(payload):
                        received.extend(client.recv(8192))
                    self.assertEqual(payload, received)
                    with connect():
                        pass
                    self.assertEqual(1, len(opened))
                self.assertFalse(opened[0].rtscts)
                self.assertEqual(921600, opened[0].baudrate)
            finally:
                relay.stop.set()
                server.shutdown()
                with relay.lock:
                    pass


if __name__ == "__main__":
    unittest.main()
