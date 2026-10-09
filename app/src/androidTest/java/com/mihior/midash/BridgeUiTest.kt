package com.mihior.midash

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Assert.*
import org.junit.Test

class BridgeUiTest {
    @Test(timeout = 25000)
    fun healthyAndPartialStatusIsHiddenButErrorsAreVisible() {
        assertTrue(android.os.Build.MODEL.contains("tablet", true))
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra("disable_bridge_relay", true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.connection.close()
                val status = activity.window.decorView.findViewsWithTextForTest()
                assertEquals(android.view.View.VISIBLE, status.visibility)
                activity.bridge.opened("TEST CDC")
                assertEquals("", activity.bridge.label)
                assertEquals(android.view.View.GONE, status.visibility)
                val bytes =
                    byteArrayOf(
                        4,
                        0xac.toByte(),
                        0,
                        0,
                        0x13,
                        0x48,
                        2,
                        0xde.toByte(),
                        0x2c,
                        6,
                        0x70,
                        0x76,
                        0x18,
                    )
                val packet = BridgeProtocol.decode(bytes, bytes.size).packet
                activity.bridge.receive(packet)
                assertEquals(3, activity.controller.state.known.size)
                assertEquals(android.view.View.GONE, status.visibility)
                activity.bridge.receive(packet.copy(sequence = 9999))
                assertEquals(android.view.View.GONE, status.visibility)
                activity.bridge.malformed("TEST bad padding")
                assertEquals(android.view.View.VISIBLE, status.visibility)
                assertTrue(status.text.contains("TEST bad padding"))
            }
        }
    }

    private fun android.view.View.findViewsWithTextForTest(): android.widget.TextView {
        val found = arrayListOf<android.view.View>()
        findViewsWithText(
            found,
            "BRIDGE status",
            android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION,
        )
        return found.single() as android.widget.TextView
    }

    @Test(timeout = 25000)
    fun noPortShowsUnknownsAndMultiplePortsShowMenu() {
        val avd = android.os.Build.MODEL
        assertTrue("Pixel Tablet only: $avd", avd.contains("tablet", true))
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra("disable_bridge_relay", true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.bridge.label.startsWith("BRIDGE not connected"))
                assertTrue(activity.controller.state.known.isEmpty())
                activity.connection.reconcile(
                    listOf(
                        BridgePort("test:COM3", "COM3 — test port"),
                        BridgePort("test:COM4", "COM4 — test port"),
                    )
                )
            }
            onView(withText("COM3 — test port")).check(matches(isDisplayed()))
            onView(withText("COM4 — test port")).check(matches(isDisplayed()))
            onView(withText("BRIDGE — COM / USB ports")).check(matches(isDisplayed()))
        }
    }
}
