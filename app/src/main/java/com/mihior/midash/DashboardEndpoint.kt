package com.mihior.midash

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.InetSocketAddress
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject

/** Standard WebSocket server; protocol processing and MediaPlayer calls stay on the main thread. */
class DashboardEndpoint(
    private val controller: DashboardController,
    private val bridge: BridgeSession? = null,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var client: WebSocket? = null
    private var sequence = -1L
    private var lastTelemetry = 0L
    private var closed = false
    private val server =
        object : WebSocketServer(InetSocketAddress("0.0.0.0", 8765), 1) {
                override fun onStart() {
                    connectionLostTimeout = 5
                    Log.i("MidashSocket", "Listening on ws://0.0.0.0:8765/dashboard")
                }

                override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                    main.post {
                        if (closed || handshake.resourceDescriptor != "/dashboard") {
                            conn.close(1008, "Use /dashboard")
                            return@post
                        }
                        if (client?.isOpen == true) {
                            conn.close(1008, "One telemetry controller at a time")
                            return@post
                        }
                        client = conn
                        sequence = -1
                        conn.reply(
                            JSONObject()
                                .put("type", "hello")
                                .put("protocol", 1)
                                .put("playlist", controller.audio.playlist())
                                .put(
                                    "telemetryFields",
                                    JSONArray(TelemetryProtocol.fields.toList()),
                                )
                                .toString()
                        )
                        conn.reply(controller.audio.status().toString())
                    }
                }

                override fun onMessage(conn: WebSocket, message: String) {
                    if (message.length > 65536) {
                        conn.close(1009, "Packet too large")
                        return
                    }
                    main.post {
                        if (!closed && conn === client && conn.isOpen) receive(conn, message)
                    }
                }

                override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                    main.post {
                        if (conn === client) {
                            client = null
                            if (controller.source == "SIMULATOR") controller.connected = false
                        }
                    }
                }

                override fun onError(conn: WebSocket?, ex: Exception) {
                    Log.e("MidashSocket", "WebSocket error", ex)
                }
            }
            .apply { isReuseAddr = true }
    private val status =
        object : Runnable {
            override fun run() {
                if (closed) return
                if (lastTelemetry > 0 && SystemClock.elapsedRealtime() - lastTelemetry > 3000)
                    if (controller.source == "SIMULATOR") controller.connected = false
                client?.takeIf { it.isOpen }?.reply(controller.audio.status().toString())
                main.postDelayed(this, 250)
            }
        }

    private fun WebSocket.reply(message: String) {
        try {
            if (isOpen) send(message)
        } catch (_: org.java_websocket.exceptions.WebsocketNotConnectedException) {
            /* Peer closed during an in-flight reply. */
        }
    }

    fun start() {
        controller.audio.onStateChanged = {
            client?.takeIf { it.isOpen }?.reply(controller.audio.status().toString())
        }
        server.start()
        main.post(status)
    }

    private fun receive(conn: WebSocket, text: String) {
        var id: Any = JSONObject.NULL
        try {
            val j = JSONObject(text)
            id = j.opt("id") ?: JSONObject.NULL
            when (j.getString("type")) {
                "telemetry" -> {
                    require(!controller.bridgeAttached) {
                        "BRIDGE owns telemetry while its port is connected"
                    }
                    val raw = j.get("seq")
                    require(raw is Number && raw.toDouble() % 1 == 0.0) { "seq must be an integer" }
                    val seq = j.getLong("seq")
                    require(seq > sequence) { "Stale sequence" }
                    val next =
                        TelemetryProtocol.patch(
                            controller.state,
                            j.getJSONObject("values"),
                            SystemClock.elapsedRealtimeNanos(),
                        )
                    sequence = seq
                    lastTelemetry = SystemClock.elapsedRealtime()
                    controller.source = "SIMULATOR"
                    controller.accept(next)
                    conn.reply(
                        JSONObject().put("type", "ack").put("id", id).put("seq", seq).toString()
                    )
                }
                "music" -> {
                    val a = controller.audio
                    when (j.getString("action")) {
                        "play" -> a.play()
                        "pause" -> a.pauseMusic()
                        "next" -> a.next()
                        "select" -> {
                            val index = a.tracks.indexOfFirst { it.audio == j.getString("trackId") }
                            require(index >= 0) { "Unknown trackId" }
                            a.select(index)
                        }
                        "seek" -> {
                            val ms = j.getDouble("positionMs")
                            require(ms.isFinite() && ms >= 0)
                            a.seekMs(ms.toLong())
                        }
                        "volume" -> a.volume(j.getDouble("value").toFloat())
                        "master" -> a.master(j.getDouble("value").toFloat())
                        else -> error("Unknown music action")
                    }
                    conn.reply(JSONObject().put("type", "ack").put("id", id).toString())
                    conn.reply(a.status().toString())
                }
                "playlist" ->
                    conn.reply(
                        JSONObject()
                            .put("type", "playlist")
                            .put("playlist", controller.audio.playlist())
                            .toString()
                    )
                "state" ->
                    conn.reply(
                        JSONObject()
                            .put("type", "state")
                            .put("id", id)
                            .put("connected", controller.connected)
                            .put("values", TelemetryProtocol.encode(controller.state))
                            .toString()
                    )
                "bridge_status" ->
                    conn.reply(
                        (bridge?.status() ?: JSONObject().put("type", "bridge_status"))
                            .put("id", id)
                            .toString()
                    )
                "ping" -> conn.reply(JSONObject().put("type", "pong").put("id", id).toString())
                else -> error("Unknown message type")
            }
        } catch (e: Exception) {
            conn.reply(
                JSONObject()
                    .put("type", "error")
                    .put("id", id)
                    .put("message", e.message ?: "Invalid packet")
                    .toString()
            )
        }
    }

    override fun close() {
        controller.audio.onStateChanged = null
        closed = true
        main.removeCallbacksAndMessages(null)
        client = null
        if (controller.source == "SIMULATOR") controller.connected = false
        server.stop(200)
    }
}
