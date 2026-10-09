"""Read-only observation of the existing binary COM relay on Pixel Tablet (< 20 seconds)."""

import argparse
import json
import os
import threading
import time

from test_android_bridge import ROOT, Observer, adb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="COM7")
    args = parser.parse_args()
    watchdog = threading.Timer(20, lambda: os._exit(20))
    watchdog.start()
    observer = None
    start = time.monotonic()
    try:
        assert "Pixel_Tablet" in adb("emu", "avd", "name")
        adb("forward", "tcp:8765", "tcp:8765")
        # Observe the active app; do not disrupt an already aligned hardware stream.
        observer = Observer()
        status = observer.wait(lambda x: x["attached"], seconds=3)
        assert status["port"] == args.port + " · PC relay", status
        time.sleep(2)
        status = observer.request("bridge_status")
        state = observer.request("state")
        assert status["protocolError"] is None, status
        assert status["terminal"] is None, status
        result = {
            "device": "Pixel_Tablet",
            "port": args.port,
            "seconds": round(time.monotonic() - start, 2),
            "checks": "PASS",
            "bridge": status,
            "state": state,
            "note": "Silence is valid; packet count need not increase.",
        }
        (ROOT / "qa/live-binary-verification.json").write_text(
            json.dumps(result, indent=2) + "\n", encoding="utf-8"
        )
        print(json.dumps(result, indent=2))
    finally:
        if observer:
            observer.ws.close()
        watchdog.cancel()


if __name__ == "__main__":
    main()
