"""Read-only USB CDC -> Pixel Tablet emulator relay, binary protocol v1.

Metadata probes never open the serial port. One subscribed client owns the raw
stream. Subscription byte 01 belongs to TCP only; NOTHING is written to USB.
Start BRIDGE, attach Android, then start ECU for a clean packet boundary.
"""

import argparse
import json
import logging
import select
import socket
import socketserver
import threading
from pathlib import Path

import serial
from serial.tools import list_ports

PROTOCOL = "minini-binary-v1"
LOGGER = logging.getLogger(__name__)


class Relay:
    def __init__(self, port, capture=None):
        self.port = port
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.byte_count = 0
        self.capture = Path(capture).open("wb") if capture else None  # noqa: SIM115 -- closed after the stream worker exits
        self.captured = 0

    def available(self):
        return any(p.device.upper() == self.port.upper() for p in list_ports.comports())

    def stream(self, client):
        if not self.lock.acquire(blocking=False):
            return
        device = serial.Serial()
        try:
            device.port = self.port
            device.baudrate = 921600
            device.bytesize = serial.EIGHTBITS
            device.parity = serial.PARITY_NONE
            device.stopbits = serial.STOPBITS_ONE
            device.timeout = 0.1
            device.xonxoff = device.rtscts = device.dsrdtr = False
            device.dtr = device.rts = False
            device.open()
            LOGGER.info("%s open: raw binary, 921600 8N1, read-only", self.port)
            while not self.stop.is_set():
                if select.select([client], [], [], 0)[0]:
                    # EOF or any further input ends the subscription.
                    client.recv(1)
                    break
                data = device.read(min(max(1, device.in_waiting), 8192))
                if not data:
                    continue
                client.sendall(data)
                self.byte_count += len(data)
                if self.capture and self.captured < 65536:
                    chunk = data[: 65536 - self.captured]
                    self.capture.write(chunk)
                    self.capture.flush()
                    self.captured += len(chunk)
        except (serial.SerialException, OSError) as error:
            LOGGER.warning("%s stream ended: %s", self.port, error)
        finally:
            device.close()
            self.lock.release()
            LOGGER.info("Stream disconnected; bytes forwarded=%s", self.byte_count)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        relay = self.server.relay
        client = self.request
        client.settimeout(1)
        client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            available = relay.available()
            client.sendall(
                (
                    json.dumps(
                        {
                            "relayPort": relay.port,
                            "connected": available,
                            "protocol": PROTOCOL,
                        }
                    )
                    + "\n"
                ).encode("ascii")
            )
            if available and client.recv(1) == b"\x01":
                relay.stream(client)
        except OSError:
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True)
    parser.add_argument("--listen-port", type=int, default=8766)
    parser.add_argument(
        "--capture", help="Capture first 64 KiB of forwarded binary bytes"
    )
    parser.add_argument("--seconds", type=float, default=0)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    relay = Relay(args.port, args.capture)
    with Server(("127.0.0.1", args.listen_port), Handler) as server:
        server.relay = relay
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        LOGGER.info(
            "Binary relay 127.0.0.1:%s -> %s (opens on subscription)",
            args.listen_port,
            args.port,
        )
        try:
            if args.seconds:
                relay.stop.wait(args.seconds)
            else:
                while not relay.stop.wait(1):
                    pass
        except KeyboardInterrupt:
            pass
        finally:
            relay.stop.set()
            server.shutdown()
            # Wait for the bounded serial read to release its capture file.
            with relay.lock:
                if relay.capture:
                    relay.capture.close()
            LOGGER.info("Stopped; bytes forwarded=%s", relay.byte_count)


if __name__ == "__main__":
    main()
