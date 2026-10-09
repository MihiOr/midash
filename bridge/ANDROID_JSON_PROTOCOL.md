# Legacy BRIDGE JSON protocol

Archived specification for the former USB–UART output. Current MiDash builds use the [binary USB CDC protocol](ANDROID_BINARY_PROTOCOL.md) and do not accept this serial JSON format. The development [WebSocket protocol](../PROTOCOL.md) is a separate interface.

## Transport and framing

The legacy BRIDGE used USART1 TX on PA9 at 921600 baud, 8N1, without flow control. An Android USB host connected through a 3.3 V USB–UART adapter with TX wired to adapter RX and a shared ground.

The stream was one-way. MFI acknowledgements operated between ECU and BRIDGE; Android did not acknowledge messages or send commands.

Each message is an ASCII JSON object followed by CRLF. The JSON body is limited to 4095 characters, or 4097 bytes including CRLF. An 8192-byte line buffer accommodates the maximum message. USB reads may split a line or contain several lines. A receiver accumulates bytes through LF, removes the final CR and parses the complete object.

The stream has no startup banner, debug output or heartbeat. It may be silent while telemetry is unchanged.

## Message structure

```json
{"seq":1234,"values":{"speedKmh":73.4,"powerKw":41.2,"throttle":0.380952}}
```

`seq` is unsigned 32-bit, starts at 1 and increments after an MFI acknowledgement. It wraps from 4294967295 to 0.

`values` is always an object. It contains changed fields and can be empty when a message carries only music commands. `commands` is present when a message contains music events.

ECU sends all 35 telemetry fields at startup, followed by changes. Receivers update each supplied field and retain omitted fields. Repeated values in separate messages remain valid. A host that connects after the startup snapshot receives no automatic replacement snapshot; unknown values remain unknown until received. The channel has no snapshot request.

## Vehicle fields

| Field | JSON type | Range and units |
| --- | --- | --- |
| `speedKmh` | Number | 0–255 km/h; resolution 0.1 |
| `longitudinalG` | Number | −2.048 to +2.047 G; resolution 0.001 |
| `lateralG` | Number | −2.048 to +2.047 G; resolution 0.001 |
| `mode` | String | `COMFORT`, `SPORT` |
| `gear` | String | `R`, `N`, `D` |
| `parking` | Boolean | Parking brake engaged |
| `speedLimit` | Integer | 1–300 km/h |
| `throttle` | Number | 0–1; up to six decimal places |
| `brake` | Number | 0–1; up to six decimal places |
| `steering` | Number | −1 to +1; up to six decimal places |
| `launch` | String | `OFF`, `ARMED`, `ACTIVE` |
| `powerKw` | Number | −3276.8 to +3276.7 kW; resolution 0.1; negative is recovery |

Pedal values have already been divided by 4095 and steering by 2047. The serialized values use physical units or normalized input values and require no additional scaling. BRIDGE rounds normalized inputs to six decimal places and removes trailing zeroes.

## Doors and lights

| Field | JSON type | Meaning of `true` |
| --- | --- | --- |
| `leftDoor` | Boolean | Left door open |
| `rightDoor` | Boolean | Right door open |
| `leftTurn` | Boolean | Left turn signal enabled |
| `rightTurn` | Boolean | Right turn signal enabled |
| `lowBeam` | Boolean | Low beam enabled |
| `highBeam` | Boolean | High beam enabled |

Signal fields carry the enabled state. The display generates blink timing.

## Wheels

```json
{"seq":1235,"values":{"wheels":{"FL":{"battery":92},"RR":{"temperature":87.4}}}}
```

`wheels` contains objects keyed by `FL`, `FR`, `RL` and `RR`: front left, front right, rear left and rear right. Wheel objects and their fields can be omitted independently.

| Wheel field | JSON type | Range and units |
| --- | --- | --- |
| `battery` | Integer | 0–100 percent |
| `temperature` | Number | −3276.8 to +3276.7 °C; resolution 0.1 |

In the example, only FL battery and RR temperature change. All other wheel values retain their previous state. Object ordering has no semantic significance.

## Energy, distance and environment

| Field | JSON type | Range and units |
| --- | --- | --- |
| `recoveredKwh` | Number | 0–4294967.295 kWh; resolution 0.001 |
| `recoveredKm` | Number | 0–4294967.295 km; resolution 0.001 |
| `tripKm` | Number | 0–4294967.295 km; resolution 0.001 |
| `tripKwh` | Number | 0–4294967.295 kWh; resolution 0.001 |
| `averageKwh` | Number | 0–4294967.295 kWh/100 km; resolution 0.001 |
| `rangeKm` | Number | 0–4294967.295 km; resolution 0.001 |
| `remainingKwh` | Number | 0–4294967.295 kWh; resolution 0.001 |
| `outsideC` | Number | −3276.8 to +3276.7 °C; resolution 0.1 |
| `clock` | String or null | `00:00`–`23:59`; null selects Android local time |

## Music events

```json
{"seq":1236,"values":{},"commands":[{"command":"music.next"},{"command":"music.next"}]}
{"seq":1237,"values":{},"commands":[{"command":"music.play"}]}
```

Commands execute in array order. Two identical entries represent two events. Commands can accompany telemetry changes.

| Command | `value` | Meaning |
| --- | --- | --- |
| `music.play` | Absent | Start or resume |
| `music.pause` | Absent | Pause |
| `music.next` | Absent | Next track |
| `music.previus` | Absent | Previous track; spelling retained for compatibility |
| `music.select` | Unsigned 32-bit, 0–4294967295 | Numeric track ID |
| `music.seek` | Unsigned 32-bit, 0–4294967295 | Position in milliseconds |
| `music.volume` | Number, 0–1, up to six decimal places | Music volume |
| `music.master` | Number, 0–1, up to six decimal places | Master gain |

The first four commands have no `value` key. The remaining commands require one. Track IDs must match the Android catalog. The serial protocol provides no playlist or query responses.

## Status messages

Status messages have no telemetry `values` and are handled separately:

```json
{"status":"WARNING","error":"BAD_PACKET"}
{"status":"TERMINAL","error":"BAD_PACKET","connected":false}
{"status":"TERMINAL","error":"INVALID_PACKET","connected":false}
{"status":"TERMINAL","error":"MFI_TIMEOUT","connected":false}
```

`BAD_PACKET` indicates an invalid MFI CRC, type, field range or structure. Three consecutive invalid packets produce a terminal error and disable the MFI connection. `INVALID_PACKET` indicates invalid framing or a local processing error. `MFI_TIMEOUT` indicates that the request/acknowledgement exceeded two seconds.

Terminal errors mark the connection inactive while preserving the last received state. A warning has no `connected` key. Error severity remains set until firmware reset.

## Receiver behavior

1. Accumulate bytes until a complete line is available.
2. Parse the JSON object; report invalid lines without clearing existing state.
3. Handle status messages separately from telemetry.
4. Apply supplied scalar and wheel fields individually.
5. Execute music commands in array order.
6. Preserve unknown and omitted values until received.

Keys and string values are case-sensitive. Decimal numbers use a period, and JSON object key order is unrestricted. Sequence values support diagnostics and unsigned wrap; silence alone does not indicate disconnection.
