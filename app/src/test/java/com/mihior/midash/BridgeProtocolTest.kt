package com.mihior.midash

import org.junit.Assert.*
import org.junit.Test

class BridgeProtocolTest {
    // Independent test encoder: every cell explicitly provides its wire width.
    private fun wire(vararg cells: Triple<Int, Int, Long>): ByteArray {
        val bits =
            cells.size.toString(2).padStart(8, '0') +
                cells.joinToString("") { (id, width, raw) ->
                    id.toString(2).padStart(6, '0') +
                        if (width == 0) ""
                        else (raw and ((1L shl width) - 1)).toString(2).padStart(width, '0')
                }
        return bits
            .padEnd((bits.length + 7) / 8 * 8, '0')
            .chunked(8)
            .map { it.toInt(2).toByte() }
            .toByteArray()
    }

    private fun c(id: Int, width: Int, raw: Long = 0) = Triple(id, width, raw)

    private fun packet(vararg cells: Triple<Int, Int, Long>) = wire(c(0x2b, 32, 1), *cells)

    private fun decode(data: ByteArray) = BridgeProtocol.decode(data, data.size).packet

    private val golden =
        "04 AC 00 00 13 48 02 DE 2C 06 70 76 18"
            .split(" ")
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun firmwareGoldenBytes() {
        val p = decode(golden)
        assertEquals(1234L, p.sequence)
        val s = p.apply(VehicleState(), 1)
        assertEquals(73.4f, s.speedKmh)
        assertEquals(41.2f, s.powerKw)
        assertEquals(1560f / 4095, s.throttle)
        val timeout = decode(byteArrayOf(0x01, 0xfc.toByte(), 0x0c))
        assertEquals("MFI_TIMEOUT", timeout.error)
        assertEquals("TERMINAL", timeout.status)
        assertTrue(timeout.disconnected)
        for (i in 1L..4L) {
            val status = decode(wire(c(0x3f, 8, i)))
            assertEquals(i != 1L, status.disconnected)
            assertEquals(0, status.values.length())
        }
    }

    @Test
    fun everyFieldAndSignedUnsignedLimits() {
        val all =
            packet(
                c(0, 12, 2550),
                c(1, 12, -2048),
                c(2, 12, 2047),
                c(3, 1, 1),
                c(4, 8, 2),
                c(5, 1, 1),
                c(6, 12, 300),
                c(7, 12, 4095),
                c(8, 12, 0),
                c(9, 12, -2047),
                c(10, 8, 2),
                c(11, 16, -32768),
                c(12, 1, 1),
                c(13, 1, 0),
                c(14, 1, 1),
                c(15, 1, 1),
                c(17, 1, 1),
                c(18, 1, 0),
                c(19, 8, 100),
                c(20, 16, -32768),
                c(21, 8, 25),
                c(22, 16, 32767),
                c(23, 8, 5),
                c(24, 16, 0),
                c(25, 8, 0),
                c(26, 16, 874),
                c(27, 32, 4294967295),
                c(28, 32, 1),
                c(29, 32, 4294967295),
                c(30, 32, 2),
                c(31, 32, 16800),
                c(32, 32, 312000),
                c(33, 32, 18580),
                c(34, 16, -32768),
                c(35, 12, 765),
            )
        val s = decode(all).apply(VehicleState(), 1)
        assertEquals(BridgeProtocol.expectedFields, s.known)
        assertEquals(35, s.known.size)
        assertEquals(255f, s.speedKmh)
        assertEquals(-2.048f, s.longitudinalG)
        assertEquals(2.047f, s.lateralG)
        assertEquals(DriveMode.SPORT, s.mode)
        assertEquals(Gear.D, s.gear)
        assertTrue(s.parking && s.leftTurn && s.rightTurn && s.lowBeam && s.leftDoor)
        assertFalse(s.rightDoor || s.highBeam)
        assertEquals(-1f, s.steering)
        assertEquals(Launch.ACTIVE, s.launch)
        assertEquals(-3276.8f, s.powerKw)
        assertEquals(-3276.8f, s.outsideC)
        assertEquals(listOf(100f, 25f, 5f, 0f), s.wheels.map { it.battery })
        assertEquals(listOf(-3276.8f, 3276.7f, 0f, 87.4f), s.wheels.map { it.temperature })
        assertEquals(4294967.295, s.tripKm, 0.0000001)
        assertEquals(4294967.295, s.recoveredKwh, 0.0000001)
        assertEquals(0.001, s.recoveredKm, 0.0000001)
        assertEquals("12:45", s.clock)
        // Full snapshots exceed a USB transfer; all possible boundaries must work.
        for (split in 0..all.size) {
            val got = mutableListOf<BridgeProtocol.Packet>()
            val stream = BridgeBinaryStream({ got.add(it) }, { fail(it) })
            stream.accept(all.copyOfRange(0, split))
            stream.accept(all.copyOfRange(split, all.size) + golden)
            stream.finish()
            assertEquals(2, got.size)
            assertEquals(35, got[0].apply(VehicleState(), 1).known.size)
        }
    }

    @Test
    fun deltasPreserveUnknownsAndClockNull() {
        val first = decode(packet(c(0, 12, 734), c(19, 8, 92))).apply(VehicleState(), 1)
        val second = decode(packet(c(26, 16, 874))).apply(first, 2)
        assertEquals(73.4f, second.speedKmh)
        assertEquals(92f, second.wheels[0].battery)
        assertEquals(87.4f, second.wheels[3].temperature)
        assertTrue(TelemetryProtocol.encode(second).isNull("gear"))
        assertFalse(second.has("clock"))
        val clock = decode(packet(c(35, 12, 4095))).apply(second, 3)
        assertTrue(clock.has("clock"))
        assertNull(clock.clock)
    }

    @Test
    fun orderedEventsNoByteAlignmentAndNoDelimiterSplitting() {
        val bytes =
            wire(
                c(43, 32, 4294967295),
                c(38, 0),
                c(38, 0),
                c(16, 0),
                c(39, 32, 0x000a0d00),
                c(40, 32, 4294967295),
                c(41, 12, 1024),
                c(42, 12, 4095),
                c(36, 0),
                c(37, 0),
            )
        val got = mutableListOf<BridgeProtocol.Packet>()
        val stream = BridgeBinaryStream({ got.add(it) }, { fail(it) })
        for (b in
            bytes + wire(c(43, 32, 0), c(0, 12, 1)) + wire(c(43, 32, 100), c(0, 12, 2))) stream
            .accept(byteArrayOf(b))
        assertEquals(listOf(4294967295L, 0L, 100L), got.map { it.sequence })
        val p = got[0]
        assertEquals(
            listOf(
                "music.next",
                "music.next",
                "music.previus",
                "music.select",
                "music.seek",
                "music.volume",
                "music.master",
                "music.play",
                "music.pause",
            ),
            p.commands.map { it.command },
        )
        assertEquals(0x000a0d00L, p.commands[3].value)
        assertEquals(4294967295L, p.commands[4].value)
        assertEquals(1024.0 / 4095, p.commands[5].value!!.toDouble(), 0.0)
        val max = wire(c(43, 32, 1), *Array(62) { c(40, 32, 4294967295) })
        assertEquals(301, max.size)
        stream.accept(max)
        stream.finish()
        assertEquals(62, got.last().commands.size)
    }

    @Test
    fun invalidPacketsNeverPartiallyApplyOrResynchronize() {
        val bad =
            mutableListOf(
                byteArrayOf(0),
                byteArrayOf(64),
                wire(c(0, 12, 1)),
                wire(c(43, 32, 1)),
                wire(c(0, 12, 1), c(43, 32, 1)),
                packet(c(43, 32, 1)),
                packet(c(63, 8, 1)),
                packet(c(44, 0)),
                wire(c(63, 8, 0)),
                wire(c(63, 8, 5)),
                packet(c(0, 12, 1), c(0, 12, 2)),
            )
        for ((id, width, raw) in
            listOf(
                c(0, 12, 2551),
                c(4, 8, 3),
                c(6, 12, 0),
                c(6, 12, 301),
                c(9, 12, -2048),
                c(10, 8, 3),
                c(19, 8, 101),
                c(35, 12, 1440),
                c(35, 12, 4094),
            )) bad.add(packet(c(36, 0), c(0x22, 16, 200), c(id, width, raw)))
        bad.add(packet(c(3, 1, 1)).also { it[it.lastIndex] = (it.last().toInt() or 1).toByte() })
        for (bytes in bad) {
            val got = mutableListOf<BridgeProtocol.Packet>()
            val errors = mutableListOf<String>()
            val stream = BridgeBinaryStream({ got.add(it) }, { errors.add(it) })
            stream.accept(bytes + golden)
            stream.finish()
            stream.accept(golden)
            assertTrue("No partial telemetry/music", got.isEmpty())
            assertTrue(stream.failed)
            assertEquals(1, errors.size)
        }
    }

    @Test
    fun truncatedPacketFailsOnlyAtEnd() {
        for (length in 1 until golden.size) {
            var errors = 0
            val stream = BridgeBinaryStream({ fail("Partial packet applied") }, { errors++ })
            stream.accept(golden.copyOf(length))
            assertEquals(0, errors)
            stream.finish()
            stream.finish()
            stream.accept(golden)
            assertEquals(1, errors)
        }
    }

    @Test
    fun unrelatedUpdatesDoNotRestartInterpolation() {
        val m = Measurement()
        m.target(100f, 1_000_000_000)
        assertEquals(50f, m.sample(1_050_000_000))
        m.target(100f, 1_050_000_000)
        assertEquals(100f, m.sample(1_100_000_000))
    }
}
