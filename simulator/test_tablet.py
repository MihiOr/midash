"""<= 30-second integration check. Refuses to run on any AVD except Pixel_Tablet."""

from pathlib import Path
import json
import os
import subprocess
import time
import websocket
from physics import initial_state

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
SERIAL = os.environ.get("MIDASH_SERIAL", "emulator-5554")


def adb(*args):
    return subprocess.check_output(
        [str(ADB), "-s", SERIAL, *args],
        text=True,
        timeout=3,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


class Client:
    def __init__(self):
        self.start = time.perf_counter()
        for attempt in range(10):
            try:
                self.ws = websocket.create_connection(
                    "ws://127.0.0.1:8765/dashboard",
                    timeout=0.4,
                    enable_multithread=True,
                )
                break
            except (OSError, websocket.WebSocketException):
                if attempt == 9:
                    raise
                time.sleep(0.15)
        self.ws.settimeout(0.02)
        self.seq = 0
        self.request = 0
        self.next_sample = 0
        self.messages = []
        self.media = None
        self.media_time = 0
        self.state = initial_state()
        self.publish = True

    def send(self, message):
        self.request += 1
        message["id"] = self.request
        self.ws.send(json.dumps(message, allow_nan=False))
        return self.request

    def pump(self, seconds):
        end = time.perf_counter() + seconds
        while time.perf_counter() < end:
            now = time.perf_counter()
            assert now - self.start < 28, (
                "Integration test exceeded its 28-second budget"
            )
            if self.publish and now >= self.next_sample:
                self.seq += 1
                self.send(dict(type="telemetry", seq=self.seq, values=self.state))
                self.next_sample = now + 0.1
            try:
                message = json.loads(self.ws.recv())
                self.messages.append(message)
                if message["type"] == "media":
                    self.media = message
                    self.media_time = time.perf_counter()
            except websocket.WebSocketTimeoutException:
                pass

    def music(self, action, **values):
        self.send(dict(type="music", action=action, **values))

    def speed_ratio(self, duration=2):
        self.pump(0.3)
        assert self.media["playing"]
        p0 = self.media["positionMs"]
        t0 = self.media_time
        self.pump(duration)
        assert self.media["playing"]
        assert self.media["speed"] == 1.0
        return (self.media["positionMs"] - p0) / 1000 / (self.media_time - t0)

    def snapshot(self):
        ident = self.send(dict(type="state"))
        self.pump(0.08)
        return next(
            m["values"]
            for m in reversed(self.messages)
            if m.get("id") == ident and m["type"] == "state"
        )


def main():
    assert "Pixel_Tablet" in adb("emu", "avd", "name"), "Use Pixel_Tablet only"
    c = Client()
    result = {}
    try:
        c.pump(0.3)
        hello = next(m for m in c.messages if m["type"] == "hello")
        assert hello["protocol"] == 1 and len(hello["playlist"]) == 2
        assert {t["id"] for t in hello["playlist"]} == {
            "seventh-heaven.mp3",
            "misery.mp3",
        }
        assert not c.media["playing"]
        c.music("pause")
        c.pump(0.12)
        assert c.media["error"] is None, "Pause before the first Play must remain valid"
        c.state.update(
            mode="SPORT",
            gear="N",
            parking=False,
            speedKmh=41.5,
            outsideC=-8.5,
            lateralG=0.7,
            longitudinalG=0.4,
            clock="14:32",
        )
        c.state["wheels"] = [
            dict(battery=b, temperature=t)
            for b, t in [(84, 42), (24, 55), (5, 70), (90, 38)]
        ]
        c.pump(0.3)
        c.state["gear"] = "D"
        c.pump(0.3)
        state = c.snapshot()
        assert (
            state["gear"] == "D"
            and state["speedKmh"] == 41.5
            and state["outsideC"] == -8.5
        )
        assert state["wheels"][2] == dict(battery=5, temperature=70)
        c.publish = False
        ident = c.send(
            dict(
                type="telemetry",
                seq=c.seq + 1,
                values=dict(speedKmh=80, wheels=[dict(battery=110, temperature=0)] * 4),
            )
        )
        c.pump(0.1)
        assert any(m["type"] == "error" and m.get("id") == ident for m in c.messages)
        assert c.snapshot()["speedKmh"] == 41.5, (
            "Malformed packet must be rejected atomically"
        )
        ident = c.send(dict(type="telemetry", seq=0, values=dict(speedKmh=0)))
        c.pump(0.1)
        assert any(m["type"] == "error" and m.get("id") == ident for m in c.messages)
        assert c.snapshot()["speedKmh"] == 41.5
        c.publish = True
        c.music("play")
        c.pump(0.6)
        result["before_pause_ratio"] = c.speed_ratio()
        for _ in range(10):
            c.music("pause")
            c.pump(0.11)
            assert not c.media["playing"]
            c.music("play")
            c.pump(0.16)
        result["after_10_pause_ratio"] = c.speed_ratio(3)
        result["reported_playing_speed"] = c.media["speed"]
        for ratio in [result["before_pause_ratio"], result["after_10_pause_ratio"]]:
            assert 0.90 < ratio < 1.10, ratio
        c.publish = False
        adb("shell", "input", "tap", "1280", "1161")
        c.pump(0.1)
        assert c.snapshot()["mode"] == "SPORT", "Android mode touch must be inert"
        adb("shell", "input", "tap", "1846", "1170")
        c.pump(0.1)
        assert c.media["playing"], "Android cover touch must be inert"
        adb("shell", "input", "keyevent", "KEYCODE_DPAD_UP")
        c.pump(0.1)
        assert c.snapshot()["speedKmh"] == 41.5, "Android must not simulate input"
        c.publish = True
        c.music("select", trackId="misery.mp3")
        c.music("play")
        c.pump(0.7)
        assert c.media["trackId"] == "misery.mp3"
        result["second_track_ratio"] = c.speed_ratio()
        assert 0.90 < result["second_track_ratio"] < 1.10
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        c.pump(0.4)
        assert c.media["playing"], "Media service must keep playing in the background"
        adb("shell", "am", "start", "-W", "-n", "com.mihior.midash/.MainActivity")
        c.pump(0.2)
        assert c.media["playing"], "Returning must attach to the existing player"
        c.music("play")
        c.pump(0.2)
        assert c.media["playing"] and c.media["error"] is None
        c.music("seek", positionMs=60000)
        c.pump(0.4)
        assert 59500 < c.media["positionMs"] < 62000
        c.music("volume", value=0.23)
        c.pump(0.1)
        assert abs(c.media["volume"] - 0.23) < 0.0001
        c.music("pause")
        c.pump(0.1)
        assert not c.media["playing"]
        assert c.media["error"] is None
        result.update(
            device="Pixel_Tablet",
            seconds=round(time.perf_counter() - c.start, 2),
            telemetry_packets=c.seq,
            playlist=len(hello["playlist"]),
            checks="PASS",
        )
        (ROOT / "qa/pixel-tablet-integration.json").write_text(
            json.dumps(result, indent=2) + "\n"
        )
        print(json.dumps(result, indent=2))
    finally:
        c.music("pause")
        c.ws.close()


if __name__ == "__main__":
    main()
