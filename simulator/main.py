"""Minini desktop simulator and remote controls. Qt supplies the GUI and WebSocket client."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from PySide6.QtCore import QEvent, QTimer, Qt, QUrl
from PySide6.QtGui import QCloseEvent, QFont, QFontDatabase
from PySide6.QtNetwork import QAbstractSocket, QNetworkProxy
from PySide6.QtWebSockets import QWebSocket
from PySide6.QtWidgets import (
    QApplication,
    QAbstractSpinBox,
    QCheckBox,
    QComboBox,
    QDoubleSpinBox,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QPushButton,
    QScrollArea,
    QSlider,
    QSplitter,
    QVBoxLayout,
    QWidget,
)

from physics import HeldInputs, Simulator

KEYS = {
    Qt.Key.Key_Up: "up",
    Qt.Key.Key_Down: "down",
    Qt.Key.Key_Left: "left",
    Qt.Key.Key_Right: "right",
    Qt.Key.Key_W: "w",
    Qt.Key.Key_S: "s",
    Qt.Key.Key_A: "a",
    Qt.Key.Key_D: "d",
    Qt.Key.Key_Space: "space",
}


class SimulatorWindow(QMainWindow):
    def __init__(self, url):
        super().__init__()
        app = QApplication.instance()
        if not app.property("midashFontLoaded"):
            font_id = QFontDatabase.addApplicationFont(
                str(
                    Path(__file__).resolve().parents[1]
                    / "app/src/main/assets/Roboto-Variable.ttf"
                )
            )
            families = QFontDatabase.applicationFontFamilies(font_id)
            if families:
                app.setFont(QFont(families[0], 10))
            app.setProperty("midashFontLoaded", True)
        self.setWindowTitle("MiNini — Python simulator")
        self.resize(1120, 820)
        self.sim = Simulator()
        self.inputs = HeldInputs()
        self.seq = 0
        self.message_id = 0
        self.ready = False
        self.want_connection = False
        self.last_ack = -1
        self.last_ack_time = 0
        self.last_time = time.perf_counter()
        self.accumulator = 0.0
        self.last_publish = 0.0
        self.socket = QWebSocket()
        self.socket.setProxy(QNetworkProxy(QNetworkProxy.ProxyType.NoProxy))
        self.socket.connected.connect(self.connected)
        self.socket.disconnected.connect(self.disconnected)
        self.socket.textMessageReceived.connect(self.receive)
        self.socket.errorOccurred.connect(
            lambda _: self.connection_label.setText(self.socket.errorString())
        )
        self.fields = {}
        self.flags = {}
        self.wheel_fields = []
        root = QWidget()
        layout = QVBoxLayout(root)
        self.setCentralWidget(root)
        connection = QHBoxLayout()
        self.url = QLineEdit(url)
        self.connect_button = QPushButton("Connect")
        self.connect_button.clicked.connect(self.toggle_connection)
        adb = QPushButton("Connect Pixel Tablet via ADB")
        adb.clicked.connect(self.adb_connect)
        connection.addWidget(self.url, 1)
        connection.addWidget(self.connect_button)
        connection.addWidget(adb)
        layout.addLayout(connection)
        self.connection_label = QLabel(
            "Disconnected — Android is a passive display on port 8765"
        )
        layout.addWidget(self.connection_label)
        columns = QSplitter()
        layout.addWidget(columns, 1)
        left = QWidget()
        left_layout = QVBoxLayout(left)
        columns.addWidget(left)
        right = QScrollArea()
        right.setWidgetResizable(True)
        telemetry = QWidget()
        self.telemetry_layout = QVBoxLayout(telemetry)
        right.setWidget(telemetry)
        columns.addWidget(right)
        columns.setSizes([460, 600])

        drive = QGroupBox("Driving controls — held keys are independent")
        grid = QGridLayout(drive)
        left_layout.addWidget(drive)
        self.keyboard = QCheckBox("Keyboard driving: arrows / WASD / Space")
        self.keyboard.setChecked(True)
        self.keyboard.toggled.connect(lambda _: self.inputs.clear())
        grid.addWidget(self.keyboard, 0, 0, 1, 3)
        grid.addWidget(
            QLabel("Holding gas + steering keeps both inputs active."), 1, 0, 1, 3
        )
        for name, label, row, col in [
            ("gas", "Gas (Up)", 2, 1),
            ("left", "Left", 3, 0),
            ("brake", "Brake (Down)", 3, 1),
            ("right", "Right", 3, 2),
        ]:
            button = QPushButton(label)
            button.setMinimumHeight(42)
            button.setAutoRepeat(False)
            button.pressed.connect(lambda key=name + "_button": self.inputs.press(key))
            button.released.connect(
                lambda key=name + "_button": self.inputs.release(key)
            )
            grid.addWidget(button, row, col)
        self.input_label = QLabel("Gas 0% · Brake 0% · Steering 0")
        grid.addWidget(self.input_label, 4, 0, 1, 3)
        self.mode = QComboBox()
        self.mode.addItems(["COMFORT", "SPORT"])
        self.mode.currentTextChanged.connect(
            lambda value: self.set_value("mode", value)
        )
        self.gear = QComboBox()
        self.gear.addItems(["R", "N", "D"])
        self.gear.setCurrentText("N")
        self.gear.currentTextChanged.connect(self.select_gear)
        self.gear.activated.connect(lambda _: self.keyboard.setFocus())
        self.mode.activated.connect(lambda _: self.keyboard.setFocus())
        grid.addWidget(self.mode, 5, 0, 1, 2)
        grid.addWidget(self.gear, 5, 2)
        self.parking = QCheckBox("Parking brake")
        self.parking.setChecked(True)
        self.parking.toggled.connect(self.parking_change)
        grid.addWidget(self.parking, 6, 0, 1, 3)
        for i, (field, label) in enumerate(
            [
                ("leftDoor", "Left door"),
                ("rightDoor", "Right door"),
                ("leftTurn", "Left signal"),
                ("rightTurn", "Right signal"),
                ("hazards", "Hazards"),
                ("lowBeam", "Low beams"),
                ("highBeam", "High beams"),
            ]
        ):
            box = QCheckBox(label)
            box.toggled.connect(lambda value, key=field: self.set_value(key, value))
            self.flags[field] = box
            grid.addWidget(box, 7 + i // 2, i % 2)
        self.manual = QCheckBox("Manual telemetry (pause vehicle physics)")
        grid.addWidget(self.manual, 11, 0, 1, 3)
        reset = QPushButton("Reset vehicle")
        reset.clicked.connect(self.reset)
        grid.addWidget(reset, 12, 0, 1, 3)
        self.speed_label = QLabel("0 km/h · 0.00 G · 0 kW")
        grid.addWidget(self.speed_label, 13, 0, 1, 3)

        music = QGroupBox("Music stored on Android")
        music_layout = QVBoxLayout(music)
        left_layout.addWidget(music)
        self.playlist = QComboBox()
        self.playlist.activated.connect(self.select_track)
        music_layout.addWidget(self.playlist)
        buttons = QHBoxLayout()
        for label, action in [("Play", "play"), ("Pause", "pause"), ("Next", "next")]:
            button = QPushButton(label)
            button.clicked.connect(lambda _, a=action: self.music(a))
            buttons.addWidget(button)
        music_layout.addLayout(buttons)
        self.music_label = QLabel(
            "Playlist will be requested from Android on connection."
        )
        music_layout.addWidget(self.music_label)
        self.seek = QSlider(Qt.Orientation.Horizontal)
        self.seek.setRange(0, 1000)
        self.seek.sliderReleased.connect(self.seek_music)
        music_layout.addWidget(self.seek)
        self.duration_ms = 0
        for name, label, value in [
            ("volume", "Music volume", 15),
            ("master", "Master volume", 100),
        ]:
            row = QHBoxLayout()
            row.addWidget(QLabel(label))
            slider = QSlider(Qt.Orientation.Horizontal)
            slider.setRange(0, 100)
            slider.setValue(value)
            slider.valueChanged.connect(lambda v, a=name: self.music(a, value=v / 100))
            setattr(self, name, slider)
            row.addWidget(slider)
            music_layout.addLayout(row)
        self.launch = QComboBox()
        self.launch.addItems(["OFF", "ARMED", "ACTIVE"])
        self.launch.currentTextChanged.connect(lambda v: self.manual_value("launch", v))
        self.telemetry_layout.addWidget(
            QLabel("Telemetry values are sent to Android at 10 Hz.")
        )
        values = QGroupBox("Vehicle telemetry")
        form = QFormLayout(values)
        self.telemetry_layout.addWidget(values)
        specs = [
            ("speedKmh", "Speed (km/h)", 0, 400, 1),
            ("longitudinalG", "Longitudinal G", -20, 20, 3),
            ("lateralG", "Lateral G", -20, 20, 3),
            ("powerKw", "Power (kW)", -10000, 10000, 1),
            ("outsideC", "Outside (°C)", -100, 100, 1),
            ("speedLimit", "Speed limit", 1, 300, 0),
            ("throttle", "Throttle 0–1", 0, 1, 2),
            ("brake", "Brake 0–1", 0, 1, 2),
            ("steering", "Steering −1–1", -1, 1, 2),
            ("recoveredKwh", "Recovered (kWh)", 0, 100000, 2),
            ("recoveredKm", "Recovered (km)", 0, 100000, 2),
            ("tripKm", "Trip (km)", 0, 100000, 2),
            ("tripKwh", "Trip (kWh)", 0, 100000, 2),
            ("averageKwh", "Average (kWh/100 km)", 0, 100000, 2),
            ("rangeKm", "Range (km)", 0, 100000, 1),
            ("remainingKwh", "Remaining (kWh)", 0, 100000, 2),
        ]
        for field, label, minimum, maximum, decimals in specs:
            spin = QDoubleSpinBox()
            spin.setRange(minimum, maximum)
            spin.setDecimals(decimals)
            spin.setValue(self.sim.state[field])
            spin.setKeyboardTracking(False)
            spin.valueChanged.connect(
                lambda value, key=field: self.edit_value(key, value)
            )
            form.addRow(label, spin)
            self.fields[field] = spin
        form.addRow("Launch (manual)", self.launch)
        self.clock = QLineEdit()
        self.clock.setPlaceholderText("HH:mm — blank uses Android clock")
        self.clock.editingFinished.connect(self.set_clock)
        form.addRow("Clock", self.clock)
        wheels = QGroupBox("Four independent wheels")
        wheel_grid = QGridLayout(wheels)
        self.telemetry_layout.addWidget(wheels)
        for i, name in enumerate(["FL", "FR", "RL", "RR"]):
            wheel_grid.addWidget(QLabel(name), i + 1, 0)
            for j, field in enumerate(["battery", "temperature"]):
                spin = QDoubleSpinBox()
                spin.setRange(
                    0 if field == "battery" else -100,
                    100 if field == "battery" else 300,
                )
                spin.setDecimals(1)
                spin.setValue(self.sim.state["wheels"][i][field])
                spin.setKeyboardTracking(False)
                spin.valueChanged.connect(
                    lambda v, index=i, key=field: self.sim.state["wheels"][
                        index
                    ].__setitem__(key, v)
                )
                wheel_grid.addWidget(spin, i + 1, j + 1)
                self.wheel_fields.append((i, field, spin))
        wheel_grid.addWidget(QLabel("Battery %"), 0, 1)
        wheel_grid.addWidget(QLabel("Motor °C"), 0, 2)
        self.telemetry_layout.addStretch()
        left_layout.addStretch()
        self.timer = QTimer(self)
        self.timer.setTimerType(Qt.TimerType.PreciseTimer)
        self.timer.timeout.connect(self.tick)
        self.timer.start(8)
        self.reconnect = QTimer(self)
        self.reconnect.setInterval(2000)
        self.reconnect.timeout.connect(self.try_reconnect)
        self.reconnect.start()
        QApplication.instance().installEventFilter(self)

    def eventFilter(self, obj, event):
        if event.type() == QEvent.Type.ApplicationDeactivate:
            self.inputs.clear()
        if (
            event.type() in (QEvent.Type.KeyPress, QEvent.Type.KeyRelease)
            and event.key() in KEYS
        ):
            name = KEYS[event.key()]
            if event.type() == QEvent.Type.KeyRelease:
                if name in self.inputs.down:
                    self.inputs.release(name, event.isAutoRepeat())
                    return True
            elif self.keyboard.isChecked() and self.isActiveWindow():
                focus = QApplication.focusWidget()
                if not isinstance(focus, (QLineEdit, QAbstractSpinBox, QComboBox)):
                    self.inputs.press(name, event.isAutoRepeat())
                    return True
        return super().eventFilter(obj, event)

    def set_value(self, key, value):
        self.sim.state[key] = value

    def manual_value(self, key, value):
        if value != self.sim.state[key]:
            self.manual.setChecked(True)
            self.set_value(key, value)

    def edit_value(self, key, value):
        if key in (
            "speedKmh",
            "longitudinalG",
            "lateralG",
            "powerKw",
            "throttle",
            "brake",
            "steering",
        ):
            self.manual.setChecked(True)
        self.set_value(key, int(value) if key == "speedLimit" else value)
        if key == "speedKmh":
            self.sim.velocity = (
                value / 3.6 * (-1 if self.sim.state["gear"] == "R" else 1)
            )

    def set_clock(self):
        value = self.clock.text().strip()
        import re

        if not value or re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value):
            self.sim.state["clock"] = value or None
        else:
            self.connection_label.setText("Clock must be HH:mm")

    def select_gear(self, value):
        if self.manual.isChecked():
            self.sim.state["gear"] = value
        elif not self.sim.gear(value):
            self.connection_label.setText(
                "Stop before reversing travel direction. N → D while moving forwards is allowed."
            )
        self.sync_widget(self.gear, self.sim.state["gear"])

    def parking_change(self, value):
        if self.manual.isChecked() or self.sim.state["speedKmh"] < 1:
            self.sim.state["parking"] = value
        self.sync_widget(self.parking, self.sim.state["parking"])

    def reset(self):
        self.inputs.clear()
        self.sim = Simulator()
        self.manual.setChecked(False)
        self.refresh()

    def toggle_connection(self):
        if self.want_connection:
            self.want_connection = False
            self.socket.close()
            self.connect_button.setText("Connect")
        else:
            self.want_connection = True
            self.connect_button.setText("Disconnect")
            self.socket.open(QUrl(self.url.text()))

    def try_reconnect(self):
        if (
            self.want_connection
            and self.socket.state() == QAbstractSocket.SocketState.UnconnectedState
        ):
            self.socket.open(QUrl(self.url.text()))

    def connected(self):
        self.seq = 0
        self.last_ack = -1
        self.connection_label.setText("Connected — waiting for Android playlist")
        self.keyboard.setFocus()

    def disconnected(self):
        self.ready = False
        self.inputs.clear()
        self.connection_label.setText(
            "Disconnected — reconnecting…" if self.want_connection else "Disconnected"
        )

    def send(self, message):
        if not self.ready:
            return
        self.message_id += 1
        message["id"] = self.message_id
        self.socket.sendTextMessage(
            json.dumps(message, allow_nan=False, separators=(",", ":"))
        )

    def receive(self, text):
        try:
            message = json.loads(text)
        except ValueError:
            return
        kind = message.get("type")
        if kind in ("hello", "playlist"):
            if kind == "hello" and message.get("protocol") != 1:
                self.connection_label.setText("Unsupported Android protocol")
                self.socket.close()
                return
            self.ready = True
            self.playlist.clear()
            for item in message["playlist"]:
                self.playlist.addItem(f"{item['title']} — {item['artist']}", item["id"])
        elif kind == "ack" and "seq" in message:
            self.last_ack = message["seq"]
            self.last_ack_time = time.perf_counter()
            self.connection_label.setText(
                f"Connected · telemetry acknowledged #{self.last_ack} · 10 Hz"
            )
        elif kind == "error":
            self.connection_label.setText("Android: " + message["message"])
        elif kind == "media":
            self.duration_ms = message["durationMs"]
            position = message["positionMs"]
            rate = f" · {message['speed']:.2f}×" if message["playing"] else ""
            self.music_label.setText(
                f"{'Playing' if message['playing'] else 'Paused'} · {self.timestamp(position)} / {self.timestamp(self.duration_ms)}{rate}"
            )
            index = self.playlist.findData(message["trackId"])
            if index >= 0:
                self.playlist.setCurrentIndex(index)
            if not self.seek.isSliderDown():
                self.seek.setValue(round(position / max(1, self.duration_ms) * 1000))
            for key in ["volume", "master"]:
                widget = getattr(self, key)
                if not widget.isSliderDown():
                    self.sync_widget(widget, round(message[key] * 100))
            if message.get("error"):
                self.connection_label.setText(message["error"])

    @staticmethod
    def timestamp(ms):
        seconds = int(ms) // 1000
        return f"{seconds // 60}:{seconds % 60:02}"

    def music(self, action, **values):
        self.send(dict(type="music", action=action, **values))

    def select_track(self, index):
        self.music("select", trackId=self.playlist.itemData(index))

    def seek_music(self):
        self.music(
            "seek", positionMs=round(self.duration_ms * self.seek.value() / 1000)
        )

    def tick(self):
        now = time.perf_counter()
        dt = min(0.1, now - self.last_time)
        self.last_time = now
        pedals = self.inputs.pedals()
        if not self.manual.isChecked():
            self.accumulator += dt
            while self.accumulator >= 1 / 120:
                self.sim.step(1 / 120, *pedals)
                self.accumulator -= 1 / 120
        else:
            self.accumulator = 0
        if now - self.last_publish >= 0.1:
            self.last_publish += 0.1
            if now - self.last_publish > 0.1:
                self.last_publish = now
            self.refresh()
            if self.ready:
                # Never accumulate old sensor packets while the connection is congested.
                if self.socket.bytesToWrite() < 16384:
                    self.seq += 1
                    self.send(
                        dict(type="telemetry", seq=self.seq, values=self.sim.snapshot())
                    )
                if self.last_ack >= 0 and now - self.last_ack_time > 3:
                    self.connection_label.setText(
                        "Connected but telemetry acknowledgements are stale"
                    )

    @staticmethod
    def sync_widget(widget, value):
        previous = widget.blockSignals(True)
        if isinstance(widget, QComboBox):
            widget.setCurrentText(value)
        elif isinstance(widget, QCheckBox):
            widget.setChecked(value)
        else:
            widget.setValue(value)
        widget.blockSignals(previous)

    def refresh(self):
        s = self.sim.state
        self.input_label.setText(
            f"Gas {s['throttle']:.0%} · Brake {s['brake']:.0%} · Steering {s['steering']:+.0f}"
        )
        self.speed_label.setText(
            f"{s['speedKmh']:.1f} km/h · {s['longitudinalG']:.2f} / {s['lateralG']:.2f} G · {s['powerKw']:.1f} kW"
        )
        for key, widget in self.fields.items():
            if not widget.hasFocus() and not widget.lineEdit().hasFocus():
                self.sync_widget(widget, s[key])
        for index, key, widget in self.wheel_fields:
            if not widget.hasFocus() and not widget.lineEdit().hasFocus():
                self.sync_widget(widget, s["wheels"][index][key])
        for key, widget in self.flags.items():
            self.sync_widget(widget, s[key])
        for widget, value in [
            (self.mode, s["mode"]),
            (self.gear, s["gear"]),
            (self.parking, s["parking"]),
            (self.launch, s["launch"]),
        ]:
            self.sync_widget(widget, value)

    def adb_connect(self):
        adb = (
            Path(os.environ.get("LOCALAPPDATA", ""))
            / "Android/Sdk/platform-tools/adb.exe"
        )
        try:
            devices = subprocess.check_output(
                [str(adb), "devices"], text=True, timeout=3
            )
            serial = None
            for line in devices.splitlines()[1:]:
                if "\tdevice" not in line:
                    continue
                candidate = line.split()[0]
                if candidate.startswith("emulator-"):
                    name = subprocess.check_output(
                        [str(adb), "-s", candidate, "emu", "avd", "name"],
                        text=True,
                        timeout=3,
                    )
                    if "Pixel_Tablet" in name:
                        serial = candidate
                        break
            if not serial:
                raise RuntimeError(
                    "Start the Pixel_Tablet AVD in Android Studio first."
                )
            subprocess.run(
                [str(adb), "-s", serial, "forward", "tcp:8765", "tcp:8765"],
                check=True,
                timeout=3,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            self.url.setText("ws://127.0.0.1:8765/dashboard")
            if not self.want_connection:
                self.toggle_connection()
            else:
                self.socket.close()
        except (OSError, subprocess.SubprocessError, RuntimeError) as error:
            self.connection_label.setText(str(error))

    def closeEvent(self, event: QCloseEvent):
        self.inputs.clear()
        self.want_connection = False
        self.sim.state.update(throttle=0.0, brake=0.0, steering=0.0)
        if self.ready:
            self.seq += 1
            self.send(dict(type="telemetry", seq=self.seq, values=self.sim.snapshot()))
        self.socket.close()
        self.timer.stop()
        QApplication.instance().removeEventFilter(self)
        event.accept()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="ws://127.0.0.1:8765/dashboard")
    parser.add_argument("--connect", action="store_true")
    args = parser.parse_args()
    app = QApplication(sys.argv[:1])
    app.setStyle("Fusion")
    window = SimulatorWindow(args.url)
    window.show()
    if args.connect:
        window.toggle_connection()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
