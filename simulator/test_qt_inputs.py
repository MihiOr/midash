"""Real Qt event routing and socket discovery; all tests finish in a few seconds."""

import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
import unittest
from PySide6.QtCore import QEvent, Qt
from PySide6.QtGui import QKeyEvent
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
from main import SimulatorWindow

app = QApplication.instance() or QApplication([])


class QtInputTests(unittest.TestCase):
    def setUp(self):
        self.window = SimulatorWindow("ws://127.0.0.1:8765/dashboard")
        self.window.show()
        self.window.activateWindow()
        self.window.keyboard.setFocus()
        QTest.qWait(40)

    def tearDown(self):
        self.window.close()
        QTest.qWait(10)

    def event(self, kind, key, repeat=False):
        QApplication.sendEvent(
            self.window,
            QKeyEvent(kind, key, Qt.KeyboardModifier.NoModifier, "", repeat, 1),
        )

    def test_key_repeat_and_simultaneous_steering(self):
        self.window.gear.setFocus()
        self.window.gear.setCurrentText("D")
        self.window.gear.activated.emit(2)
        self.assertTrue(self.window.keyboard.hasFocus())
        self.event(QEvent.Type.KeyPress, Qt.Key.Key_Up)
        for _ in range(12):
            self.event(QEvent.Type.KeyRelease, Qt.Key.Key_Up, True)
            self.event(QEvent.Type.KeyPress, Qt.Key.Key_Up, True)
            self.assertEqual(self.window.inputs.pedals()[0], 1)
        self.event(QEvent.Type.KeyPress, Qt.Key.Key_Right)
        self.assertEqual(self.window.inputs.pedals(), (1, 0, 1))
        self.event(QEvent.Type.KeyRelease, Qt.Key.Key_Right)
        self.assertEqual(self.window.inputs.pedals(), (1, 0, 0))
        self.event(QEvent.Type.KeyRelease, Qt.Key.Key_Up)
        self.assertEqual(self.window.inputs.pedals(), (0, 0, 0))

    def test_playlist_is_discovered_from_android(self):
        from test_tablet import adb

        self.assertIn("Pixel_Tablet", adb("emu", "avd", "name"))
        self.window.toggle_connection()
        for _ in range(40):
            QTest.qWait(25)
            if self.window.ready and self.window.last_ack >= 0:
                break
        self.assertTrue(self.window.ready)
        self.assertEqual(self.window.playlist.count(), 2)
        self.assertEqual(self.window.playlist.itemData(0), "seventh-heaven.mp3")
        self.assertGreaterEqual(self.window.last_ack, 0)
        self.window.grab().save(
            str(
                __import__("pathlib").Path(__file__).resolve().parents[1]
                / "qa/screenshots/python-simulator.png"
            )
        )
        self.window.close()
        QTest.qWait(80)
        self.assertTrue(
            adb("shell", "pidof", "com.mihior.midash").strip(),
            "Disconnect must not crash Android",
        )


if __name__ == "__main__":
    unittest.main()
