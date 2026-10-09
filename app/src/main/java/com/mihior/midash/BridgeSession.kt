package com.mihior.midash

import android.os.SystemClock
import org.json.JSONObject

/** Main-thread owner of bridge health and diagnostic sequence tracking. Silence is healthy. */
class BridgeSession(private val controller: DashboardController) {
    var onStatus: (String) -> Unit = {}
    var label = "BRIDGE not connected"
        private set

    private var port = ""
    var attached = false
        private set

    private var terminal: String? = null
    private var warning: String? = null
    private var protocolError: String? = null
    private var sequence: Long? = null
    private var fresh = emptySet<String>()
    private var received = 0L
    private var lost = 0L
    private var invalid = 0L
    private var commands = 0L

    fun opened(name: String) {
        port = name
        attached = true
        terminal = null
        warning = null
        protocolError = null
        sequence = null
        fresh = emptySet()
        controller.beginBridge()
        update()
    }

    fun disconnected(reason: String? = null) {
        attached = false
        controller.bridgeAttached = false
        if (controller.source == "BRIDGE") controller.connected = false
        label =
            "BRIDGE not connected" +
                if (controller.state.known.isNotEmpty() && controller.source == "BRIDGE") " · STALE"
                else ""
        if (reason != null) label += " · $reason"
        onStatus(label)
    }

    fun message(text: String) {
        label = text
        onStatus(label)
    }

    fun malformed(reason: String) {
        invalid++
        protocolError = reason
        controller.connected = false
        update()
    }

    fun receive(packet: BridgeProtocol.Packet) {
        if (!attached || protocolError != null) return
        try {
            if (packet.status != null) {
                if (packet.disconnected || packet.status == "TERMINAL") {
                    terminal = packet.error
                    controller.connected = false
                } else warning = packet.error
                update()
                return
            }
            val seq = packet.sequence!!
            val last = sequence
            if (last != null) {
                val distance = (seq - last) and 0xffffffffL
                if (distance > 0x7fffffffL) {
                    // ECU restarted. New deltas are valid; old values remain visibly last-known.
                    fresh = emptySet()
                    terminal = null
                    warning = null
                } else if (distance > 1) lost += distance - 1
            }
            sequence = seq
            received++
            val delta =
                packet.apply(
                    controller.state.copy(known = emptySet()),
                    SystemClock.elapsedRealtimeNanos(),
                )
            fresh = fresh + delta.known
            if (delta.known.isNotEmpty())
                controller.accept(delta.copy(known = controller.state.known + delta.known))
            if (terminal != null) controller.connected = false
            for (command in packet.commands) {
                commands++
                when (command.command) {
                    "music.play" -> controller.audio.play()
                    "music.pause" -> controller.audio.pauseMusic()
                    "music.next" -> controller.audio.next()
                    "music.previus" -> controller.audio.previous()
                    "music.select" -> {
                        val index =
                            controller.audio.tracks.indexOfFirst {
                                it.numericId == command.value!!.toLong()
                            }
                        if (index >= 0) controller.audio.select(index)
                        else warning = "Unknown trackId ${command.value}"
                    }
                    "music.seek" -> controller.audio.seekMs(command.value!!.toLong())
                    "music.volume" -> controller.audio.volume(command.value!!.toFloat())
                    "music.master" -> controller.audio.master(command.value!!.toFloat())
                }
            }
            update()
        } catch (e: Exception) {
            malformed(e.message ?: "Invalid binary packet")
        }
    }

    private fun update() {
        label =
            when {
                protocolError != null ->
                    "BRIDGE · $protocolError · restart BRIDGE, reconnect USB, then restart ECU"
                !attached -> "BRIDGE not connected"
                terminal != null -> "BRIDGE · $terminal · reset both MCUs"
                warning != null -> "BRIDGE · $warning"
                else -> ""
            }
        onStatus(label)
    }

    fun status(): JSONObject =
        JSONObject()
            .put("type", "bridge_status")
            .put("label", label)
            .put("attached", attached)
            .put("terminal", terminal ?: JSONObject.NULL)
            .put("warning", warning ?: JSONObject.NULL)
            .put("port", port)
            .put("seq", sequence ?: JSONObject.NULL)
            .put("received", received)
            .put("missingPackets", lost)
            .put("invalidPackets", invalid)
            .put("protocolError", protocolError ?: JSONObject.NULL)
            .put("musicCommands", commands)
            .put("freshFields", fresh.size)
            .put(
                "stale",
                !attached ||
                    terminal != null ||
                    protocolError != null ||
                    controller.state.known.any { it !in fresh },
            )
            .put("expectedFields", BridgeProtocol.expectedFields.size)
            .put("knownFields", org.json.JSONArray(controller.state.known.toList()))
}
