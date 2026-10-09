# USB telemetry

MiDash receives vehicle telemetry and music commands from the BRIDGE over native USB CDC. The packet format is defined in [ANDROID_BINARY_PROTOCOL.md](bridge/ANDROID_BINARY_PROTOCOL.md).

## Android connection

Connect the BRIDGE's USB CDC interface to an Android USB host/OTG port. The app uses usb-serial-for-android to enumerate devices, request permission and read data.

Serial settings are 921600 baud, 8 data bits, no parity, 1 stop bit and no flow control. For native USB CDC, baud rate is a line-coding setting.

| Condition | Behavior |
| --- | --- |
| No port available | Displays `BRIDGE not connected`; detects newly attached devices automatically |
| One port available | Connects after USB permission is granted |
| Multiple ports available | Opens the port selector |
| Healthy connection | Hides connection status text |
| Disconnect or error | Shows a yellow message and retains the last received values |

Tap a connection error to retry or select another port. Android identifies USB devices by product name, VID:PID and port number; Windows serial-port names apply only to the host relay.

## Emulator relay

A debug app running in the Android emulator can receive BRIDGE data through the Windows serial relay. Python 3.10 or later is required.

Find the BRIDGE serial port in Device Manager and run from the project root:

```powershell
$bridgePort = Read-Host 'BRIDGE serial port'
.\bridge\start.ps1 -Port $bridgePort
```

The launcher creates a Python environment, installs dependencies and starts the relay in the background. Repeated launches reuse a compatible running instance. Add `-Foreground` to display the relay log. Background logs are written to `bridge/.runtime/`.

Stop the relay before changing ports or opening the port in a serial monitor:

```powershell
.\bridge\stop.ps1
```

The host listens at `127.0.0.1:8766`. The debug emulator app connects to `10.0.2.2:8766`, the emulator's host address. Physical devices and release builds use USB.

The relay sends a JSON header containing `relayPort`, `connected` and `protocol: minini-binary-v1`. A subscription byte of `01` starts the serial stream. Discovery connections do not read the serial port. One subscriber receives the original byte stream; the relay does not write to BRIDGE.

## State updates

Packets are validated before any telemetry or music command is applied. Omitted fields retain their previous values, and fields not yet received remain unknown. Unsigned 32-bit values use `Long`; energy and distance use `Double` with 0.001 resolution.

Sequence gaps are valid because BRIDGE may replace unsent packets. They do not reset the parser or generate a warning. Sequence wrap is supported, and a backwards sequence starts a new diagnostic epoch.

A malformed stream stops further updates and displays a protocol error. The protocol has no synchronization marker or application CRC, so automatic recovery of packet boundaries is not guaranteed.

### Startup and recovery

1. Start BRIDGE.
2. Open its USB connection in Android or subscribe through the relay.
3. Start ECU.

Repeat this sequence after a protocol error. Firmware terminal errors require both microcontrollers to be reset. Reopening Android alone does not guarantee a packet boundary or a full telemetry snapshot.

### Delivery limits

BRIDGE can replace an unsent packet with a newer packet. A packet already submitted to USB completes before the next packet begins. Updates and music events in replaced packets are lost; Android retains the last values it received.

The stream has no heartbeat, Android acknowledgement or snapshot request. Silence is valid when values have not changed. A new full snapshot requires an ECU restart with an established BRIDGE connection.

## Music commands

Music commands are dispatched through `MediaController` to `MusicService`, an Android `MediaBrowserService` using `MediaPlayer` and `MediaSession`. Playback continues while the activity is in the background or being recreated.

| Numeric track ID | Default catalog entry |
| --- | --- |
| 0 | Seventh Heaven — INOHA |
| 1 | misery. — pupsies |

Commands execute in packet order, including repeated commands. Unknown track IDs report an error and retain the current selection. Seek positions are in milliseconds; volume and master gain use unsigned 12-bit values divided by 4095.

The USB protocol does not return a playlist. Catalog discovery is available through the [WebSocket endpoint](PROTOCOL.md).

## Diagnostics

Send `{"type":"bridge_status","id":1}` to `ws://ANDROID_IP:8765/dashboard` for connection status, sequence counters, parser errors and field-receipt information. A `state` request returns current telemetry, with JSON `null` for unknown fields.

Partial telemetry and sequence gaps appear in diagnostics without raising a connection warning. Simulator telemetry is rejected while BRIDGE is attached.

## Tests

`BridgeUiTest` covers connection messages and port selection. `MediaServiceTest` covers background playback, activity recreation and repeated pause/play. Both are Android instrumentation tests.

`bridge/test_relay.py` tests the host relay. `bridge/test_android_bridge.py` exercises Android with a synthetic relay on TCP 8767. For a hardware session, `bridge/check_bridge.py --port YOUR_SERIAL_PORT` observes the running relay. Device integration scripts contain target-device settings that must match the test environment.
