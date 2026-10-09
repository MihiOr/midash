"""Pixel Tablet end-to-end transport/protocol regression. Hard 28-second deadline."""

import json
import os
import socket
import socketserver
import subprocess
import threading
import time
from pathlib import Path

import websocket

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"


def adb(*args):
    return subprocess.check_output(
        [str(ADB), "-s", SERIAL, *args], text=True, timeout=4
    )


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            self.request.sendall(
                b'{"relayPort":"COM_TEST","connected":true,"protocol":"minini-binary-v1"}\n'
            )
            if self.request.recv(1) != b"\x01":
                return
            with self.server.lock:
                self.server.clients.add(self.request)
            self.request.recv(1)
        except OSError:
            pass
        finally:
            with self.server.lock:
                self.server.clients.discard(self.request)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 8767), Handler)
        self.clients = set()
        self.lock = threading.Lock()
        threading.Thread(target=self.serve_forever, daemon=True).start()

    def send(self, payload, split=False):
        data = payload
        with self.lock:
            for client in list(self.clients):
                try:
                    if split:
                        client.sendall(data[:9])
                        time.sleep(0.02)
                        client.sendall(data[9:])
                    else:
                        client.sendall(data)
                except OSError:
                    pass

    def disconnect(self):
        with self.lock:
            for c in list(self.clients):
                try:
                    c.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                c.close()
        self.shutdown()
        self.server_close()


class Observer:
    def __init__(self):
        self.ws = websocket.create_connection(
            "ws://127.0.0.1:8765/dashboard", timeout=1
        )
        self.id = 0

    def request(self, kind, **params):
        self.id += 1
        self.ws.send(json.dumps(dict(type=kind, id=self.id, **params)))
        while True:
            value = json.loads(self.ws.recv())
            if value.get("id") == self.id:
                return value

    def wait(self, predicate, seconds=3):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = self.request("bridge_status")
            if predicate(value):
                return value
            time.sleep(0.05)
        raise AssertionError(value)

    def media(self):
        self.request("music", action="pause")
        while True:
            value = json.loads(self.ws.recv())
            if value.get("type") == "media":
                return value


def wire(*cells):
    """Test-side encoder: explicit ID, bit width, RAW; no production parser dependency."""
    bits = f"{len(cells):08b}"
    for ident, width, raw in cells:
        bits += f"{ident:06b}"
        if width:
            bits += f"{raw & ((1 << width) - 1):0{width}b}"
    return int(bits.ljust((len(bits) + 7) // 8 * 8, "0"), 2).to_bytes(
        (len(bits) + 7) // 8, "big"
    )


def packet(seq, *cells):
    return wire((0x2B, 32, seq), *cells)


def main():
    start = time.monotonic()
    watchdog = threading.Timer(28, lambda: os._exit(28))
    watchdog.start()
    server = None
    observer = None
    try:
        assert "Pixel_Tablet" in adb("emu", "avd", "name")
        adb("forward", "tcp:8765", "tcp:8765")
        adb("shell", "am", "force-stop", "com.mihior.midash")
        adb(
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "com.mihior.midash/.MainActivity",
            "--ei",
            "bridge_relay_port",
            "8767",
        )
        time.sleep(0.25)
        observer = Observer()
        assert observer.request("bridge_status")["label"].startswith(
            "BRIDGE not connected"
        )
        assert observer.request("state")["values"]["speedKmh"] is None
        server = Server()
        observer.wait(lambda x: x["attached"])
        assert observer.request("bridge_status")["label"] == ""
        server.send(
            packet(
                4294967294,
                (0, 12, 734),
                (7, 12, 1560),
                (3, 1, 1),
                (4, 8, 2),
                (19, 8, 92),
            ),
            split=True,
        )
        observer.wait(lambda x: x["received"] >= 1)
        server.send(packet(4294967295, (26, 16, 874), (38, 0, 0), (38, 0, 0)))
        observer.wait(lambda x: x["musicCommands"] == 2)
        state = observer.request("state")["values"]
        assert state["mode"] == "SPORT" and state["wheels"][0]["battery"] == 92
        assert (
            state["wheels"][3]["temperature"] == 87.4
            and state["wheels"][1]["battery"] is None
        )
        assert abs(state["throttle"] - 1560 / 4095) < 0.000001
        server.send(
            packet(
                0,
                (29, 32, 4294967295),
                (34, 16, -32768),
                (16, 0, 0),
                (39, 32, 1),
                (41, 12, 819),
                (42, 12, 1638),
                (40, 32, 1000),
                (36, 0, 0),
            )
        )
        status = observer.wait(lambda x: x["seq"] == 0 and x["musicCommands"] == 8)
        assert status["missingPackets"] == 0 and status["label"] == ""
        # Whole skipped packets are normal; packets coalesced in one TCP read also work.
        server.send(packet(3, (35, 12, 765)) + packet(4, (35, 12, 4095)))
        status = observer.wait(lambda x: x["seq"] == 4)
        assert status["missingPackets"] == 2 and status["label"] == ""
        assert observer.request("state")["values"]["tripKm"] == 4294967.295
        time.sleep(3.2)  # Silence is healthy, including partial snapshots.
        assert observer.request("state")["connected"]
        assert observer.request("bridge_status")["label"] == ""
        assert (
            observer.request("telemetry", seq=1, values={"speedKmh": 0})["type"]
            == "error"
        )
        server.send(wire((63, 8, 1)))
        observer.wait(lambda x: x["warning"] == "BAD_PACKET" and bool(x["label"]))
        assert observer.request("state")["connected"]
        server.send(bytes.fromhex("01 FC 0C"))
        observer.wait(lambda x: x["terminal"] == "MFI_TIMEOUT" and bool(x["label"]))
        assert not observer.request("state")["connected"]
        media = observer.media()
        assert media["trackId"] == "misery.mp3" and media["error"] is None, media
        assert (
            abs(media["volume"] - 0.2) < 0.001 and abs(media["master"] - 0.4) < 0.001
        ), media
        server.disconnect()
        server = None
        observer.wait(lambda x: not x["attached"])
        server = Server()
        observer.wait(lambda x: x["attached"])
        server.send(packet(1, (0, 12, 200)))
        status = observer.wait(lambda x: x["seq"] == 1)
        assert status["label"] == "", status
        assert observer.request("state")["values"]["wheels"][0]["battery"] == 92
        before = status["musicCommands"]
        # Nonzero padding rejects the entire packet, including its earlier music command.
        invalid = bytearray(packet(2, (38, 0, 0), (0, 12, 2000), (3, 1, 0)))
        invalid[-1] |= 1
        server.send(bytes(invalid) + packet(3, (0, 12, 10), (38, 0, 0)))
        status = observer.wait(lambda x: x["invalidPackets"] == 1)
        assert (
            status["protocolError"]
            and status["label"]
            and status["musicCommands"] == before
        )
        assert observer.request("state")["values"]["speedKmh"] == 20
        assert not observer.request("state")["connected"]
        result = {
            "device": "Pixel_Tablet",
            "seconds": round(time.monotonic() - start, 2),
            "checks": "PASS",
            "cases": [
                "no port",
                "auto attach",
                "binary fragments",
                "coalesced packets",
                "wheel deltas",
                "unknown fields",
                "uint32 wrap",
                "seq gaps without warning",
                "ordered repeated commands",
                "numeric track IDs",
                "MediaService commands",
                "silence",
                "no healthy/partial status text",
                "warning",
                "terminal",
                "disconnect/reconnect",
                "atomic reject",
                "no false resync",
            ],
        }
        (ROOT / "qa/binary-bridge-integration.json").write_text(
            json.dumps(result, indent=2) + "\n", encoding="utf-8"
        )
        print(json.dumps(result, indent=2))
    finally:
        if observer:
            observer.ws.close()
        if server:
            server.disconnect()
        watchdog.cancel()


if __name__ == "__main__":
    main()
