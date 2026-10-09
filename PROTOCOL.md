# WebSocket protocol

Protocol version: 1. Endpoint: `ws://ANDROID_IP:8765/dashboard`.

The endpoint accepts telemetry and music commands from a simulator or vehicle gateway. It uses UTF-8 JSON text frames, with a 64 KiB message limit and one active client. Authentication and TLS are not implemented; use a local development network.

USB BRIDGE telemetry takes priority. Telemetry requests are rejected while a BRIDGE port is attached; music commands remain available.

## Connection and discovery

The server sends `hello` and the current `media` status after connection. The following `hello` example is abbreviated:

```json
{
  "type": "hello",
  "protocol": 1,
  "playlist": [
    {
      "id": "seventh-heaven.mp3",
      "numericId": 0,
      "title": "Seventh Heaven",
      "artist": "INOHA",
      "durationMs": 234000
    }
  ],
  "telemetryFields": ["speedKmh", "mode"]
}
```

The actual response contains the full playlist and telemetry field list. `id` identifies a track for WebSocket commands; `numericId` maps it to the USB protocol.

| Request | Response |
| --- | --- |
| `{"type":"playlist"}` | Full playlist |
| `{"type":"state","id":5}` | `state` with current `values` and telemetry connection status |
| `{"type":"bridge_status","id":6}` | BRIDGE connection and parser diagnostics |
| `{"type":"ping","id":7}` | `pong` |

An optional request `id` is echoed in the corresponding response. In a `state` response, `connected` indicates recent telemetry, rather than socket connectivity. Unknown telemetry values are returned as JSON `null`.

## Telemetry

```json
{
  "type": "telemetry",
  "id": 1,
  "seq": 1,
  "values": {
    "mode": "COMFORT",
    "gear": "D",
    "parking": false,
    "speedKmh": 31.5,
    "speedLimit": 50,
    "longitudinalG": 0.5,
    "lateralG": 0.1,
    "outsideC": 24,
    "clock": "14:32",
    "wheels": [
      {"battery": 84, "temperature": 42},
      {"battery": 82, "temperature": 43},
      {"battery": 83, "temperature": 44},
      {"battery": 81, "temperature": 42}
    ]
  }
}
```

Accepted messages return `{"type":"ack","id":1,"seq":1}`. Rejected messages return `{"type":"error","id":1,"message":"..."}` without changing state.

`seq` must increase on each telemetry message within a connection. Restart the sequence after reconnecting. A publication rate of 10 Hz matches the renderer's 100 ms interpolation interval.

Omitted fields retain their previous values. Unknown fields, incorrect types, nonfinite numbers and out-of-range values cause the entire update to be rejected. The server assigns a monotonic arrival timestamp.

### Fields

| Field | Type and range | Units or meaning |
| --- | --- | --- |
| `speedKmh` | Number, 0–400 | km/h; absolute speed, including reverse |
| `longitudinalG`, `lateralG` | Number, −20 to +20 | G; positive lateral acceleration is rightward |
| `mode` | `COMFORT`, `SPORT` | Drive mode |
| `gear` | `R`, `N`, `D` | Selected gear |
| `parking` | Boolean | Parking brake engaged |
| `speedLimit` | Integer, 1–300 | km/h |
| `leftDoor`, `rightDoor` | Boolean | Door open |
| `leftTurn`, `rightTurn`, `hazards` | Boolean | Signal enabled; Android generates the blink phase |
| `lowBeam`, `highBeam` | Boolean | Light enabled |
| `throttle`, `brake` | Number, 0–1 | Normalized pedal input |
| `steering` | Number, −1 to +1 | Negative left, positive right |
| `launch` | `OFF`, `ARMED`, `ACTIVE` | Launch state |
| `outsideC` | Number, −100 to +100 | °C |
| `clock` | `HH:mm` or `null` | 24-hour time; `null` selects Android local time |
| `powerKw` | Number, −100000 to +100000 | kW; negative values indicate recovery |
| `wheels` | Array of four objects | Order: FL, FR, RL, RR |
| `wheels[].battery` | Number, 0–100 | Percent |
| `wheels[].temperature` | Number, −100 to +300 | °C |
| `recoveredKwh`, `tripKwh`, `remainingKwh` | Number, 0–100000 | kWh |
| `recoveredKm`, `tripKm`, `rangeKm` | Number, 0–100000 | km |
| `averageKwh` | Number, 0–100000 | kWh/100 km |

A supplied `wheels` array must contain all four objects. Each object may omit `battery` or `temperature` to retain that field's previous value.

### State ownership

The sender determines vehicle state and interlocks. Android displays the received state, interpolates speed and acceleration, and derives visual and audio warnings. It accepts gear changes at speed and does not apply simulator driving restrictions to incoming data.

After disconnection or three seconds without telemetry, the dashboard retains the last state and disables repeating warning cues. Music playback is independent of telemetry connectivity. Send a full snapshot after reconnecting.

## Music

```json
{"type":"music","id":10,"action":"play"}
{"type":"music","id":11,"action":"pause"}
{"type":"music","id":12,"action":"next"}
{"type":"music","id":13,"action":"select","trackId":"misery.mp3"}
{"type":"music","id":14,"action":"seek","positionMs":60000}
{"type":"music","id":15,"action":"volume","value":0.15}
{"type":"music","id":16,"action":"master","value":1.0}
```

Accepted commands return `ack` and media status. Track preparation and seeking are asynchronous; use subsequent `media` messages to confirm the result. `select` and `next` preserve the current play/pause intent. Seek positions are nonnegative and clamped to track duration. Volume and master gain accept values from 0 to 1.

Playback starts paused, with volume 0.15 and master gain 1.0.

### Media status

Status is sent after connection, on player changes and every 250 ms:

```json
{
  "type": "media",
  "trackId": "seventh-heaven.mp3",
  "numericTrackId": 0,
  "playing": true,
  "positionMs": 12000,
  "durationMs": 234000,
  "volume": 0.15,
  "master": 1.0,
  "speed": 1.0,
  "serviceConnected": true,
  "error": null
}
```

`playing` reports the native player's state. `speed` is the reported playback rate and may be zero while paused; changing playback rate is not supported. `serviceConnected` indicates attachment to the Android media service. `error` contains a player or service error, or `null`.
