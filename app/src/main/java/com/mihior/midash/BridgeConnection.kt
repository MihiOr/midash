package com.mihior.midash

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

data class BridgePort(val id: String, val label: String, val usb: UsbSerialPort? = null)

/** USB driver enumeration + one read-only stream. TCP is only the debug emulator's COM relay. */
class BridgeConnection(private val activity: Activity, private val session: BridgeSession) :
    AutoCloseable {
    private val usb = activity.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val generation = AtomicInteger()
    private val permissionAction = activity.packageName + ".USB_PERMISSION"
    private val denied = mutableSetOf<String>()
    private var ports = emptyList<BridgePort>()
    private var selected: BridgePort? = null
    private var opening = false
    private var scanning = false
    private var visible = false
    private var closed = false
    private var prompted = emptySet<String>()
    private var dialog: AlertDialog? = null
    @Volatile private var serial: UsbSerialPort? = null
    @Volatile private var socket: Socket? = null
    private val relayEnabled =
        BuildConfig.DEBUG &&
            (Build.FINGERPRINT.contains("generic") ||
                Build.MODEL.contains("sdk") ||
                Build.HARDWARE.contains("ranchu")) &&
            !activity.intent.getBooleanExtra("disable_bridge_relay", false)
    private val relayPort =
        if (BuildConfig.DEBUG) activity.intent.getIntExtra("bridge_relay_port", 8766) else 8766

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (closed) return
                if (intent.action == permissionAction) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val candidate = selected
                    if (candidate?.usb?.device?.deviceId != device?.deviceId) return
                    opening = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        candidate?.let { connect(it) }
                    else {
                        candidate?.let { denied.add(it.id) }
                        selected = null
                        session.disconnected("USB permission denied · tap to retry")
                    }
                } else {
                    if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (selected?.usb?.device?.deviceId == device?.deviceId) disconnect()
                    }
                    main.removeCallbacks(poll)
                    main.post(poll)
                }
            }
        }
    private val poll =
        object : Runnable {
            override fun run() {
                if (closed) return
                if (!scanning) scan()
                main.postDelayed(this, 1000)
            }
        }

    fun start() {
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter().apply {
                addAction(permissionAction)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        main.post(poll)
    }

    fun resume() {
        visible = true
        reconcile(ports)
    }

    fun pause() {
        visible = false
        dialog?.dismiss()
        dialog = null
        prompted = emptySet()
    }

    fun choose() {
        denied.clear()
        prompted = emptySet()
        if (ports.isNotEmpty()) showMenu(ports)
        else {
            main.removeCallbacks(poll)
            main.post(poll)
        }
    }

    private fun relay(): Pair<Socket, String> {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("10.0.2.2", relayPort), 350)
            s.soTimeout = 700
            s.tcpNoDelay = true
            val input = s.getInputStream()
            val header = StringBuilder()
            while (header.length < 512) {
                val b = input.read()
                check(b >= 0) { "Relay closed" }
                if (b == 10) break
                header.append(b.toChar())
            }
            val info = JSONObject(header.toString())
            check(info.optString("protocol") == "minini-binary-v1") {
                "Update PC relay for binary protocol"
            }
            check(info.getBoolean("connected")) { "Host COM unavailable" }
            val name = info.getString("relayPort")
            s.soTimeout = 0 // A motionless ECU is allowed to be silent indefinitely.
            return s to name
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    private fun scan() {
        scanning = true
        val selectedRelay = selected?.takeIf { it.usb == null && (opening || session.attached) }
        workers.execute {
            val found = mutableListOf<BridgePort>()
            try {
                UsbSerialProber.getDefaultProber()
                    .findAllDrivers(usb)
                    .sortedBy { it.device.deviceName }
                    .forEach { driver ->
                        driver.ports.forEach { port ->
                            val d = port.device
                            found.add(
                                BridgePort(
                                    "${d.deviceName}:${port.portNumber}",
                                    "USB serial / CDC · ${d.productName ?: driver.javaClass.simpleName} · ${"%04X:%04X".format(d.vendorId,d.productId)} · port ${port.portNumber + 1} · device ${d.deviceId}",
                                    port,
                                )
                            )
                        }
                    }
                if (selectedRelay != null) found.add(selectedRelay)
                else if (relayEnabled)
                    try {
                        val (s, name) = relay()
                        s.close()
                        found.add(BridgePort("relay:$relayPort", "$name · PC relay"))
                    } catch (_: Exception) {
                        /* No relay is a normal disconnected state. */
                    }
            } catch (e: Exception) {
                android.util.Log.w("MidashBridge", "Port enumeration failed", e)
            } finally {
                main.post {
                    scanning = false
                    if (!closed) reconcile(found)
                }
            }
        }
    }

    internal fun reconcile(found: List<BridgePort>) {
        ports = found
        denied.retainAll(found.map { it.id }.toSet())
        if (selected != null && found.none { it.id == selected!!.id }) disconnect()
        if (selected != null || opening || !visible) return
        when (found.size) {
            0 -> {
                dialog?.dismiss()
                dialog = null
                prompted = emptySet()
                session.disconnected()
            }
            1 -> {
                dialog?.dismiss()
                dialog = null
                prompted = emptySet()
                if (found[0].id !in denied) connect(found[0])
            }
            else -> if (prompted != found.map { it.id }.toSet()) showMenu(found)
        }
    }

    internal fun showMenu(choices: List<BridgePort>) {
        if (!visible || closed || activity.isFinishing) return
        dialog?.dismiss()
        prompted = choices.map { it.id }.toSet()
        session.message("")
        dialog =
            AlertDialog.Builder(activity)
                .setTitle("BRIDGE — COM / USB ports")
                .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                    val port = choices[index]
                    if (ports.any { it.id == port.id }) {
                        disconnect()
                        connect(port)
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> session.disconnected("tap to select port") }
                .setOnCancelListener { session.disconnected("tap to select port") }
                .show()
    }

    private fun connect(port: BridgePort) {
        if (closed) return
        selected = port
        opening = true
        if (port.usb != null && !usb.hasPermission(port.usb.device)) {
            session.message("BRIDGE · USB permission required")
            val intent =
                PendingIntent.getBroadcast(
                    activity,
                    0,
                    Intent(permissionAction).setPackage(activity.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            usb.requestPermission(port.usb.device, intent)
            return
        }
        val token = generation.incrementAndGet()
        session.message("")
        workers.execute {
            var localPort: UsbSerialPort? = null
            var localSocket: Socket? = null
            val decoder =
                BridgeBinaryStream(
                    { packet ->
                        main.post {
                            if (!closed && token == generation.get()) session.receive(packet)
                        }
                    },
                    { error ->
                        main.post {
                            if (!closed && token == generation.get()) session.malformed(error)
                        }
                    },
                )
            try {
                if (port.usb != null) {
                    localPort = port.usb
                    val connection =
                        usb.openDevice(localPort.device) ?: error("Cannot open USB device")
                    try {
                        localPort.open(connection)
                    } catch (e: Exception) {
                        connection.close()
                        throw e
                    }
                    localPort.setParameters(
                        921600,
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE,
                    )
                    localPort.setFlowControl(UsbSerialPort.FlowControl.NONE)
                    serial = localPort
                } else {
                    localSocket = relay().first
                    // Subscribe only to the PC relay. This byte is NEVER sent to USB/ECU.
                    localSocket.getOutputStream().write(1)
                    socket = localSocket
                }
                if (closed || token != generation.get()) return@execute
                main.post {
                    if (!closed && token == generation.get()) {
                        opening = false
                        session.opened(port.label)
                    }
                }
                val buffer = ByteArray(8192)
                while (!closed && token == generation.get()) {
                    val n =
                        if (localPort != null) localPort.read(buffer, 250)
                        else localSocket!!.getInputStream().read(buffer)
                    if (n < 0) break
                    if (n > 0) decoder.accept(buffer, n)
                }
            } catch (e: Exception) {
                main.post {
                    if (!closed && token == generation.get())
                        session.disconnected(e.message?.take(100))
                }
            } finally {
                decoder.finish()
                runCatching { localPort?.close() }
                runCatching { localSocket?.close() }
                main.post {
                    if (!closed && token == generation.get()) {
                        serial = null
                        socket = null
                        selected = null
                        opening = false
                        session.disconnected()
                    }
                }
            }
        }
    }

    private fun disconnect() {
        generation.incrementAndGet()
        opening = false
        selected = null
        runCatching { serial?.close() }
        runCatching { socket?.close() }
        serial = null
        socket = null
        session.disconnected()
    }

    override fun close() {
        closed = true
        dialog?.dismiss()
        main.removeCallbacksAndMessages(null)
        runCatching { activity.unregisterReceiver(receiver) }
        disconnect()
        workers.shutdownNow()
    }
}
