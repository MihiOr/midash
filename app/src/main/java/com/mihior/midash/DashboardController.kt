package com.mihior.midash

import android.os.SystemClock

/**
 * Central accepted state. Rendering can interpolate measurements, never invent accepted commands.
 */
class DashboardController(val audio: DashboardAudio) {
    var state = VehicleState()
        private set

    var previous = state
        private set

    var onState: ((VehicleState, VehicleState) -> Unit)? = null
    var connected = false
    var source = "NONE"
    var bridgeAttached = false
    private val speed = Measurement()
    private val gx = Measurement()
    private val gy = Measurement()

    fun beginBridge() {
        if (source != "BRIDGE") {
            val old = state
            state = VehicleState(timeNanos = SystemClock.elapsedRealtimeNanos())
            previous = state
            speed.target(0f, state.timeNanos)
            gx.target(0f, state.timeNanos)
            gy.target(0f, state.timeNanos)
            onState?.invoke(old, state)
        }
        source = "BRIDGE"
        bridgeAttached = true
        connected = false
    }

    private var active = false
    private var nextDoorCue = 0L
    private var indicatorEpoch = 0L
    private var previousBlink = false
    var blinkOn = false
        private set

    var displaySpeed = 0f
        private set

    var displayGx = 0f
        private set

    var displayGy = 0f
        private set

    fun start() {
        if (active) return
        active = true
        audio.resume()
    }

    fun stop() {
        if (!active) return
        active = false
        audio.pause()
    }

    internal fun accept(next: VehicleState) {
        if (next.timeNanos < state.timeNanos) return
        connected = true
        val old = state
        previous = old
        state = next
        speed.target(next.speedKmh, next.timeNanos)
        gx.target(next.lateralG, next.timeNanos)
        gy.target(next.longitudinalG, next.timeNanos)
        if (old.mode != next.mode)
            audio.cue(if (next.mode == DriveMode.COMFORT) "comfort" else "sport")
        if (old.gear != next.gear) audio.cue("gear")
        if (old.parking != next.parking) audio.cue(if (next.parking) "on" else "off")
        if (old.lowBeam != next.lowBeam || old.highBeam != next.highBeam) audio.cue("on")
        if (!old.overspeed && next.overspeed) audio.cue("overspeed")
        if (old.launch != next.launch)
            when (next.launch) {
                Launch.ARMED -> audio.cue("armed")
                Launch.ACTIVE -> audio.cue("launch")
                Launch.OFF -> if (old.launch != Launch.OFF) audio.cue("cancel")
            }
        val hadSignals = old.leftTurn || old.rightTurn || old.hazards
        val hasSignals = next.leftTurn || next.rightTurn || next.hazards
        if (hasSignals && !hadSignals) {
            indicatorEpoch = SystemClock.elapsedRealtimeNanos()
            previousBlink = false
        }
        if (!next.doorAlarm) nextDoorCue = 0L
        onState?.invoke(old, next)
    }

    /** Called once per display frame, even when no telemetry or user input changes. */
    fun frame(now: Long) {
        displaySpeed = speed.sample(now)
        displayGx = gx.sample(now)
        displayGy = gy.sample(now)
        val signals = state.leftTurn || state.rightTurn || state.hazards
        blinkOn = signals && ((now - indicatorEpoch).coerceAtLeast(0) / 375_000_000) % 2 == 0L
        if (connected && signals && blinkOn != previousBlink)
            audio.cue(if (blinkOn) "tick_on" else "tick_off")
        previousBlink = blinkOn
        if (connected && state.doorAlarm && now >= nextDoorCue) {
            audio.cue("door")
            nextDoorCue = now + 1_000_000_000
        }
    }

    fun close() {
        stop()
        audio.close()
    }
}

/** Unrelated delta packets never restart a measurement's 100 ms interpolation. */
class Measurement {
    private var from = 0f
    private var to = 0f
    private var start = 0L

    fun target(value: Float, now: Long) {
        if (value == to) return
        from = sample(now)
        to = value
        start = now
    }

    fun sample(now: Long): Float =
        from + (to - from) * ((now - start) / 100_000_000f).coerceIn(0f, 1f)
}
