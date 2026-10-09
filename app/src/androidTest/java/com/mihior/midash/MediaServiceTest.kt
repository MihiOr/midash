package com.mihior.midash

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MediaServiceTest {
    private fun awaitState(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 3000
        while (!check() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(40)
        assertTrue("Media service state did not arrive", check())
    }

    @Test(timeout = 25000)
    fun serviceOwnsPlaybackAfterActivityDestructionAndReconnection() {
        assertTrue(android.os.Build.MODEL.contains("tablet", true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, MainActivity::class.java).putExtra("disable_bridge_relay", true)
        var scenario: ActivityScenario<MainActivity>? = ActivityScenario.launch(intent)
        lateinit var browser: MediaBrowser
        lateinit var control: MediaController
        val connected = CountDownLatch(1)
        instrumentation.runOnMainSync {
            browser =
                MediaBrowser(
                    context,
                    ComponentName(context, MusicService::class.java),
                    object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            control = MediaController(context, browser.sessionToken)
                            connected.countDown()
                        }
                    },
                    null,
                )
            browser.connect()
        }
        assertTrue(connected.await(3, TimeUnit.SECONDS))
        // A MediaController alone does not bind the service; release this test browser now.
        instrumentation.runOnMainSync { browser.disconnect() }
        try {
            control.transportControls.pause()
            control.transportControls.skipToQueueItem(1)
            awaitState {
                control.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == "misery.mp3" &&
                    control.playbackState?.state == PlaybackState.STATE_PAUSED
            }
            assertNotNull(control.metadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
            control.transportControls.seekTo(30000)
            awaitState { control.playbackState!!.position in 29500..30500 }
            control.transportControls.play()
            awaitState { control.playbackState!!.state == PlaybackState.STATE_PLAYING }
            SystemClock.sleep(400)
            val token = control.sessionToken
            val before = control.playbackState!!.position
            scenario!!.close()
            scenario = null
            SystemClock.sleep(1400)
            assertEquals(PlaybackState.STATE_PLAYING, control.playbackState!!.state)
            assertTrue(control.playbackState!!.position > before + 800)
            // Nothing in Activity owns the surviving service/player.
            scenario = ActivityScenario.launch(intent)
            var attached = false
            awaitState {
                scenario!!.onActivity {
                    attached =
                        it.controller.audio.serviceConnected &&
                            it.controller.audio.playing &&
                            it.controller.audio.trackIndex == 1
                }
                attached
            }
            assertEquals(token, control.sessionToken)
            assertTrue(control.playbackState!!.position >= before)
            for (i in 0 until 10) {
                control.transportControls.pause()
                awaitState { control.playbackState!!.state == PlaybackState.STATE_PAUSED }
                control.transportControls.play()
                awaitState { control.playbackState!!.state == PlaybackState.STATE_PLAYING }
            }
            val start = control.playbackState!!.position
            SystemClock.sleep(1500)
            val advance = control.playbackState!!.position - start
            assertTrue("Unexpected music advance: $advance", advance in 1100..1900)
            assertEquals(1f, control.playbackState!!.playbackSpeed, .001f)
            control.transportControls.pause()
            awaitState { control.playbackState!!.state == PlaybackState.STATE_PAUSED }
        } finally {
            control.transportControls.pause()
            scenario?.close()
        }
    }
}
