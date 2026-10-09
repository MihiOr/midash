# BRIDGE binary protocol

This specification defines the BRIDGE-to-Android USB stream. It supersedes the [legacy JSON protocol](ANDROID_JSON_PROTOCOL.md). The ECU-to-BRIDGE MFI protocol retains its own CRC and acknowledgement.

## Transport

Native USB CDC, configured for 921600 baud, 8N1 and no flow control. Baud rate is a CDC line-coding setting. The host reads raw bytes; USB transfer boundaries do not identify packet boundaries.

## Packet format

```text
COUNT[8] | (ID[6] | RAW[width specified by ID]) × COUNT | 0–7 zero padding bits
```

`COUNT` is the number of cells, from 1 to 63. Each packet starts on a byte boundary. Bits are ordered MSB-first, including multibyte values. Cells are packed consecutively without byte alignment; unused low bits of the final byte are zero.

The format has no preamble, packet-type byte, length in bytes, CRC, footer or newline. Each ID determines the width of its value.

An ordinary packet begins with `0x2B` (`seq`), followed by at least one telemetry or music cell. A status packet contains a single `0x3F` cell. Maximum packet size is 301 bytes.

### Updates and sequence

ECU sends all 35 telemetry fields at startup, then sends changes and events. Omitted fields retain their previous values. With no changes, there are no packets or heartbeat messages. BRIDGE may replace an unsent packet, so an initial full snapshot is not guaranteed to reach a host that connects late.

`seq` is unsigned 32-bit, starts at 1 and increments after an MFI acknowledgement. It wraps from 4294967295 to 0; an ECU restart begins again at 1. The MFI acknowledgement confirms receipt by BRIDGE, not by Android.

Skipped sequence values are expected when packets are replaced. The next complete packet remains valid and can be applied without waiting for missing sequences or resetting the parser. The Android channel provides no acknowledgement or outbound command.

## Telemetry fields

Widths are in bits. `uN` denotes unsigned values; `iN` denotes signed two's-complement values. Signed values are decoded before scaling. Bounds are inclusive. Unsigned 32-bit values require a 64-bit host representation.

| ID hex | Field | RAW | Decoded value |
| --- | --- | --- | --- |
| 00 | speedKmh | u12, 0–2550 | /10; 0–255 km/h |
| 01 | longitudinalG | i12 | /1000; −2.048 to +2.047 G |
| 02 | lateralG | i12 | /1000; −2.048 to +2.047 G |
| 03 | mode | u1 | 0=COMFORT, 1=SPORT |
| 04 | gear | u8, 0–2 | 0=R, 1=N, 2=D |
| 05 | parking | u1 | 0=OFF, 1=ON |
| 06 | speedLimit | u12, 1–300 | km/h, integer |
| 07 | throttle | u12 | /4095; 0–1 |
| 08 | brake | u12 | /4095; 0–1 |
| 09 | steering | i12, −2047 to +2047 | /2047; −1 to +1; RAW −2048 is invalid |
| 0A | launch | u8, 0–2 | 0=OFF, 1=ARMED, 2=ACTIVE |
| 0B | powerKw | i16 | /10; −3276.8 to +3276.7 kW; negative is recovery |
| 0C | leftDoor | u1 | 0=closed, 1=open |
| 0D | rightDoor | u1 | 0=closed, 1=open |
| 0E | leftTurn | u1 | 0=OFF, 1=ON |
| 0F | rightTurn | u1 | 0=OFF, 1=ON |
| 11 | lowBeam | u1 | 0=OFF, 1=ON |
| 12 | highBeam | u1 | 0=OFF, 1=ON |
| 13 | FL battery | u8, 0–100 | Percent |
| 14 | FL temperature | i16 | /10; −3276.8 to +3276.7 °C |
| 15 | FR battery | u8, 0–100 | Percent |
| 16 | FR temperature | i16 | /10; −3276.8 to +3276.7 °C |
| 17 | RL battery | u8, 0–100 | Percent |
| 18 | RL temperature | i16 | /10; −3276.8 to +3276.7 °C |
| 19 | RR battery | u8, 0–100 | Percent |
| 1A | RR temperature | i16 | /10; −3276.8 to +3276.7 °C |
| 1B | recoveredKwh | u32 | /1000; 0–4294967.295 kWh |
| 1C | recoveredKm | u32 | /1000; 0–4294967.295 km |
| 1D | tripKm | u32 | /1000; 0–4294967.295 km |
| 1E | tripKwh | u32 | /1000; 0–4294967.295 kWh |
| 1F | averageKwh | u32 | /1000; 0–4294967.295 kWh/100 km |
| 20 | rangeKm | u32 | /1000; 0–4294967.295 km |
| 21 | remainingKwh | u32 | /1000; 0–4294967.295 kWh |
| 22 | outsideC | i16 | /10; −3276.8 to +3276.7 °C |
| 23 | clock | u12 | 0–1439 minutes after midnight; 4095 selects Android time; other values invalid |
| 2B | seq | u32 | 0–4294967295 |

FL, FR, RL and RR mean front left, front right, rear left and rear right. Turn-signal fields indicate whether a signal is enabled; Android generates synchronized blink timing. Clock values display as `HH:mm`, for example 765 → `12:45`.

## Music commands

Commands execute once per received cell, in order. Repeated commands are separate events. A command with no RAW value occupies only its six-bit ID.

| ID hex | Command | RAW | Meaning |
| --- | --- | --- | --- |
| 10 | music.previus | 0 bits | Previous track; spelling retained for compatibility |
| 24 | music.play | 0 bits | Start or resume |
| 25 | music.pause | 0 bits | Pause |
| 26 | music.next | 0 bits | Next track |
| 27 | music.select | u32 | Numeric track ID, 0–4294967295 |
| 28 | music.seek | u32 | Position in milliseconds, 0–4294967295 |
| 29 | music.volume | u12 | /4095; 0–1 |
| 2A | music.master | u12 | /4095; 0–1 |

Track IDs map to the Android catalog. The USB stream does not return a playlist or track metadata. Events in replaced packets are lost; sequence gaps do not authorize replaying or generating commands.

## Status packets

A status packet has `COUNT=1`, `ID=3F` and `RAW=u8`, without `seq`. Status is event-driven. A pending status packet can be replaced by newer telemetry or another status packet.

| RAW | Severity | Meaning |
| --- | --- | --- |
| 1 | WARNING | Invalid MFI packet |
| 2 | TERMINAL | Invalid MFI packets; connection disabled |
| 3 | TERMINAL | MFI timeout of 2 s; connection disabled |
| 4 | TERMINAL | Invalid format or local processing error; connection disabled |

A timeout packet is `01 FC 0C` in hex. Terminal errors require both microcontrollers to be reset. An idle stream is not evidence of a fault because the protocol has no heartbeat.

## Parsing

1. Append received bytes to a persistent buffer. A read may contain part of a packet or several packets.
2. Read `COUNT`, then exactly that many IDs and their specified RAW widths. Retain incomplete data until more bytes arrive.
3. Validate ranges, IDs, sequence placement and zero padding. Accept `3F` only as a standalone cell. Telemetry IDs cannot repeat within a packet; music commands can repeat.
4. Apply the complete packet and consume `ceil(packetBits / 8)` bytes. Continue parsing any remaining bytes.

IDs `2C`–`3E` are undefined. Unknown IDs cannot be skipped because their widths are unknown. Byte values such as `00`, `0A` and `0D`, and USB transfer sizes such as 64 bytes, have no framing significance.

### Example

Sequence 1234, speed 73.4 km/h, power 41.2 kW and throttle 1560/4095:

```text
04 AC 00 00 13 48 02 DE 2C 06 70 76 18
```

The packet contains 13 bytes and four cells: `2B/u32=1234`, `00/u12=734`, `0B/i16=412` and `07/u12=1560`.

## Delivery and recovery

BRIDGE maintains one packet submitted to USB and one replaceable packet waiting in the application. New arrivals replace only the waiting packet. A packet already submitted to USB completes before another packet begins, preserving stream boundaries. Host and driver buffers may contain additional received bytes.

For example, if A is submitted, B is waiting and C arrives, C replaces B. Android receives A followed by C. A sequence gap identifies the missing packet without implying damage to either received packet.

Replacement discards the entire packet, including unique field changes and music events. If a replaced packet contained the only door update, that field remains at its last received value until another update or full snapshot arrives. The protocol has no retransmission or snapshot request.

The MFI request/response timeout remains 2 s. Waiting for USB does not stop MFI processing. A USB disconnect can lose submitted data; there is no Android application acknowledgement.

USB protects its transfers, and BRIDGE checks the MFI CRC before forwarding. The Android stream itself has no CRC or synchronization marker. After byte loss or attachment mid-packet, automatic resynchronization is not guaranteed.

To establish a new session, start BRIDGE, open its USB connection, then start ECU. Resetting only the Android parser does not guarantee alignment or a full snapshot. Use binary or hexadecimal tools when inspecting the serial stream.
