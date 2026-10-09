package com.mihior.midash

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Passive display. Vehicle and music commands arrive through BRIDGE or the development endpoint.
 */
class MainActivity : Activity() {
    lateinit var controller: DashboardController
        private set

    lateinit var dashboard: DashboardView
        private set

    private lateinit var endpoint: DashboardEndpoint
    lateinit var bridge: BridgeSession
        private set

    internal lateinit var connection: BridgeConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller = DashboardController(DashboardAudio(this))
        dashboard = DashboardView(this, controller)
        bridge = BridgeSession(controller)
        connection = BridgeConnection(this, bridge)
        val status =
            TextView(this).apply {
                contentDescription = "BRIDGE status"
                text = bridge.label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xffffc55a.toInt())
                setPadding(12, 8, 12, 8)
                setOnClickListener { connection.choose() }
            }
        val layout =
            FrameLayout(this).apply {
                addView(dashboard, FrameLayout.LayoutParams(-1, -1))
                addView(
                    status,
                    FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                        .apply { topMargin = (24 * resources.displayMetrics.density).toInt() },
                )
            }
        bridge.onStatus = {
            if (status.text.toString() != it) status.text = it
            status.visibility =
                if (it.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
        setContentView(layout)
        endpoint = DashboardEndpoint(controller, bridge)
        endpoint.start()
        connection.start()
        fullscreen()
    }

    private fun fullscreen() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        controller.start()
        dashboard.resume()
        connection.resume()
        fullscreen()
    }

    override fun onPause() {
        dashboard.pause()
        connection.pause()
        controller.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        if (focused) fullscreen()
    }

    override fun onDestroy() {
        connection.close()
        endpoint.close()
        controller.close()
        super.onDestroy()
    }
}
