package com.mihior.midash

import org.json.JSONObject

/** Firmware USB CDC bit stream. USB chunks are unrelated to packet boundaries. */
object BridgeProtocol {
    val expectedFields =
        (TelemetryProtocol.fields - setOf("wheels", "hazards")) +
            TelemetryProtocol.wheelNames.flatMap {
                listOf("wheels.$it.battery", "wheels.$it.temperature")
            }

    data class Music(val command: String, val value: Number? = null)

    data class Packet(
        val sequence: Long? = null,
        val values: JSONObject = JSONObject(),
        val commands: List<Music> = emptyList(),
        val status: String? = null,
        val error: String? = null,
        val disconnected: Boolean = false,
    ) {
        fun apply(old: VehicleState, now: Long): VehicleState =
            TelemetryProtocol.patch(old, values, now, bridge = true)
    }

    internal class NeedMore(val bytes: Int) : RuntimeException(null, null, false, false)

    internal data class Decoded(val packet: Packet, val bytes: Int)

    private class Bits(val data: ByteArray, val size: Int) {
        var position = 0

        fun read(width: Int): Long {
            if (position + width > size * 8) throw NeedMore((position + width + 7) / 8)
            var value = 0L
            repeat(width) {
                value =
                    (value shl 1) or
                        ((data[position / 8].toInt() ushr (7 - position % 8)) and 1).toLong()
                position++
            }
            return value
        }
    }

    internal fun decode(bytes: ByteArray, size: Int): Decoded {
        val bits = Bits(bytes, size)
        val count = bits.read(8).toInt()
        require(count in 1..63) { "Invalid COUNT $count" }
        val values = JSONObject()
        val commands = mutableListOf<Music>()
        val seen = mutableSetOf<Int>()
        var seq: Long? = null
        var status: Int? = null
        repeat(count) { index ->
            val id = bits.read(6).toInt()
            if (count == 1) require(id == 0x3f) { "Expected standalone status" }
            else if (index == 0) require(id == 0x2b) { "Expected seq first" }
            else require(id != 0x2b && id != 0x3f) { "Unexpected seq/status" }
            val width =
                when (id) {
                    0x03,
                    0x05,
                    in 0x0c..0x0f,
                    0x11,
                    0x12 -> 1
                    0x10,
                    in 0x24..0x26 -> 0
                    0x04,
                    0x0a,
                    0x13,
                    0x15,
                    0x17,
                    0x19,
                    0x3f -> 8
                    in 0x00..0x02,
                    in 0x06..0x09,
                    0x23,
                    0x29,
                    0x2a -> 12
                    0x0b,
                    0x14,
                    0x16,
                    0x18,
                    0x1a,
                    0x22 -> 16
                    in 0x1b..0x21,
                    0x27,
                    0x28,
                    0x2b -> 32
                    else -> error("Unknown ID %02X".format(id))
                }
            val music = id == 0x10 || id in 0x24..0x2a
            require(music || seen.add(id)) { "Duplicate telemetry ID %02X".format(id) }
            val raw = bits.read(width)
            fun signed(): Long =
                if (raw and (1L shl (width - 1)) != 0L) raw - (1L shl width) else raw
            fun range(min: Long, max: Long): Long {
                require(raw in min..max) { "Invalid RAW for ID %02X".format(id) }
                return raw
            }
            when (id) {
                0x00 -> values.put("speedKmh", range(0, 2550) / 10.0)
                0x01 -> values.put("longitudinalG", signed() / 1000.0)
                0x02 -> values.put("lateralG", signed() / 1000.0)
                0x03 -> values.put("mode", if (raw == 0L) "COMFORT" else "SPORT")
                0x04 -> values.put("gear", listOf("R", "N", "D")[range(0, 2).toInt()])
                0x05 -> values.put("parking", raw != 0L)
                0x06 -> values.put("speedLimit", range(1, 300))
                0x07 -> values.put("throttle", raw / 4095.0)
                0x08 -> values.put("brake", raw / 4095.0)
                0x09 -> {
                    require(signed() != -2048L) { "Invalid steering -2048" }
                    values.put("steering", signed() / 2047.0)
                }
                0x0a -> values.put("launch", listOf("OFF", "ARMED", "ACTIVE")[range(0, 2).toInt()])
                0x0b -> values.put("powerKw", signed() / 10.0)
                in 0x0c..0x0f ->
                    values.put(
                        listOf("leftDoor", "rightDoor", "leftTurn", "rightTurn")[id - 0x0c],
                        raw != 0L,
                    )
                0x10 -> commands.add(Music("music.previus"))
                0x11 -> values.put("lowBeam", raw != 0L)
                0x12 -> values.put("highBeam", raw != 0L)
                in 0x13..0x1a -> {
                    val wheels =
                        values.optJSONObject("wheels")
                            ?: JSONObject().also { values.put("wheels", it) }
                    val name = TelemetryProtocol.wheelNames[(id - 0x13) / 2]
                    val wheel =
                        wheels.optJSONObject(name) ?: JSONObject().also { wheels.put(name, it) }
                    if (id % 2 == 1) wheel.put("battery", range(0, 100))
                    else wheel.put("temperature", signed() / 10.0)
                }
                in 0x1b..0x21 ->
                    values.put(
                        listOf(
                            "recoveredKwh",
                            "recoveredKm",
                            "tripKm",
                            "tripKwh",
                            "averageKwh",
                            "rangeKm",
                            "remainingKwh",
                        )[id - 0x1b],
                        raw / 1000.0,
                    )
                0x22 -> values.put("outsideC", signed() / 10.0)
                0x23 -> {
                    require(raw in 0..1439 || raw == 4095L) { "Invalid clock" }
                    values.put(
                        "clock",
                        if (raw == 4095L) JSONObject.NULL
                        else "%02d:%02d".format(java.util.Locale.ROOT, raw / 60, raw % 60),
                    )
                }
                0x24 -> commands.add(Music("music.play"))
                0x25 -> commands.add(Music("music.pause"))
                0x26 -> commands.add(Music("music.next"))
                0x27 -> commands.add(Music("music.select", raw))
                0x28 -> commands.add(Music("music.seek", raw))
                0x29 -> commands.add(Music("music.volume", raw / 4095.0))
                0x2a -> commands.add(Music("music.master", raw / 4095.0))
                0x2b -> seq = raw
                0x3f -> status = range(1, 4).toInt()
            }
        }
        val padding = (8 - bits.position % 8) % 8
        require(bits.read(padding) == 0L) { "Nonzero padding" }
        val packet =
            if (status != null)
                Packet(
                    status = if (status == 1) "WARNING" else "TERMINAL",
                    error =
                        when (status) {
                            1,
                            2 -> "BAD_PACKET"
                            3 -> "MFI_TIMEOUT"
                            else -> "INVALID_PACKET"
                        },
                    disconnected = status != 1,
                )
            else Packet(seq, values, commands)
        return Decoded(packet, bits.position / 8)
    }
}

/** Bounded, incremental, fail-closed parser; never guesses synchronization after byte loss. */
class BridgeBinaryStream(
    private val packet: (BridgeProtocol.Packet) -> Unit,
    private val error: (String) -> Unit,
) {
    private val buffer = ByteArray(301)
    private var size = 0
    private var needed = 1
    var failed = false
        private set

    fun accept(data: ByteArray, count: Int = data.size) {
        require(count in 0..data.size)
        var offset = 0
        while (!failed && offset < count) {
            val take = minOf(needed - size, count - offset)
            data.copyInto(buffer, size, offset, offset + take)
            size += take
            offset += take
            if (size < needed) continue
            val decoded =
                try {
                    BridgeProtocol.decode(buffer, size)
                } catch (e: BridgeProtocol.NeedMore) {
                    needed = e.bytes
                    if (needed > buffer.size) fail("Packet exceeds 301 bytes")
                    continue
                } catch (e: IllegalArgumentException) {
                    fail(e.message ?: "Invalid binary packet")
                    continue
                } catch (e: IllegalStateException) {
                    fail(e.message ?: "Invalid binary packet")
                    continue
                }
            check(decoded.bytes == size)
            size = 0
            needed = 1
            packet(decoded.packet)
        }
    }

    private fun fail(reason: String) {
        if (!failed) {
            failed = true
            error(reason)
        }
    }

    fun finish() {
        if (size != 0) fail("Truncated binary packet")
    }
}
