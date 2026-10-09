package com.mihior.midash

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.SoundPool
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Dashboard client of the system media service; owns warning cues, never the music player. */
class DashboardAudio(context: Context) {
    private val app = context.applicationContext
    val tracks = MusicCatalog.tracks(app)
    private var controller: MediaController? = null
    private var state: PlaybackState? = null
    private var metadata: MediaMetadata? = null
    private var closed = false
    private var foreground = false
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var connectionError: String? = null
    var onStateChanged: (() -> Unit)? = null
    val error
        get() = connectionError ?: state?.extras?.getString("error")

    val trackIndex
        get() =
            tracks
                .indexOfFirst {
                    it.audio == metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                }
                .coerceAtLeast(0)

    val playing
        get() = state?.state == PlaybackState.STATE_PLAYING

    val positionMs
        get() = (state?.position ?: 0L).coerceAtLeast(0L)

    val durationMs
        get() = tracks[trackIndex].durationMs

    val playbackSpeed
        get() = state?.playbackSpeed ?: 0f

    val volume
        get() = state?.extras?.getFloat("volume", .15f) ?: .15f

    val master
        get() = state?.extras?.getFloat("master", 1f) ?: 1f

    val serviceConnected
        get() = controller != null

    private val callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(value: PlaybackState?) {
                state = value
                onStateChanged?.invoke()
            }

            override fun onMetadataChanged(value: MediaMetadata?) {
                metadata = value
                onStateChanged?.invoke()
            }

            override fun onSessionDestroyed() {
                controller?.unregisterCallback(this)
                controller = null
                state = null
                connectionError = "Music service disconnected"
            }
        }
    private val browser: MediaBrowser =
        MediaBrowser(
            app,
            ComponentName(app, MusicService::class.java),
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    if (closed) return
                    val connected = MediaController(app, browser.sessionToken)
                    controller = connected
                    connectionError = null
                    connected.registerCallback(callback, Handler(Looper.getMainLooper()))
                    state = connected.playbackState
                    metadata = connected.metadata
                    onStateChanged?.invoke()
                    while (pending.isNotEmpty()) pending.removeFirst()(connected)
                }

                override fun onConnectionSuspended() {
                    controller?.unregisterCallback(callback)
                    controller = null
                    state = null
                    connectionError = "Music service suspended"
                }

                override fun onConnectionFailed() {
                    connectionError = "Cannot connect to music service"
                }
            },
            null,
        )
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    private val pool = SoundPool.Builder().setAudioAttributes(attributes).setMaxStreams(4).build()
    private val sounds = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    val cueCounts = mutableMapOf<String, Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        for (name in
            listOf(
                "comfort",
                "sport",
                "gear",
                "on",
                "off",
                "warning",
                "overspeed",
                "door",
                "armed",
                "launch",
                "cancel",
                "tick_on",
                "tick_off",
            )) {
            app.assets.openFd("audio/cues/$name.wav").use { sounds[name] = pool.load(it, 1) }
        }
        browser.connect()
    }

    private fun command(action: (MediaController) -> Unit) {
        if (closed) return
        val current = controller
        if (current != null) action(current)
        else if (pending.size < 128) pending.addLast(action)
        else connectionError = "Music service command queue full"
    }

    fun play() = command { it.transportControls.play() }

    fun pauseMusic() = command { it.transportControls.pause() }

    fun next() = command { it.transportControls.skipToNext() }

    fun previous() = command { it.transportControls.skipToPrevious() }

    fun select(index: Int) {
        require(index in tracks.indices)
        command { it.transportControls.skipToQueueItem(tracks[index].numericId) }
    }

    fun seekMs(position: Long) = command { it.transportControls.seekTo(position.coerceAtLeast(0)) }

    fun volume(value: Float) = gain(MusicService.VOLUME, value)

    fun master(value: Float) = gain(MusicService.MASTER, value)

    private fun gain(action: String, value: Float) {
        require(value.isFinite() && value in 0f..1f)
        command {
            it.transportControls.sendCustomAction(
                action,
                Bundle().apply { putFloat("value", value) },
            )
        }
    }

    fun resume() {
        foreground = true
    }

    fun pause() {
        foreground = false
        pool.autoPause()
        // Activity visibility affects dashboard cues only. The media service keeps playing.
    }

    fun cue(name: String) {
        val id = sounds[name] ?: return
        if (!foreground || id !in loaded) return
        val stream = pool.play(id, master * .35f, master * .35f, 1, 0, 1f)
        if (stream != 0) cueCounts[name] = (cueCounts[name] ?: 0) + 1
    }

    fun playlist(): JSONArray =
        JSONArray().apply {
            tracks.forEach {
                put(
                    JSONObject()
                        .put("id", it.audio)
                        .put("numericId", it.numericId)
                        .put("title", it.title)
                        .put("artist", it.artist)
                        .put("durationMs", it.durationMs)
                )
            }
        }

    fun status(): JSONObject =
        JSONObject()
            .put("type", "media")
            .put("trackId", tracks[trackIndex].audio)
            .put("numericTrackId", tracks[trackIndex].numericId)
            .put("playing", playing)
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .put("volume", volume)
            .put("master", master)
            .put("speed", playbackSpeed)
            .put("serviceConnected", serviceConnected)
            .put("error", error ?: JSONObject.NULL)

    fun close() {
        if (closed) return
        closed = true
        pause()
        pending.clear()
        controller?.unregisterCallback(callback)
        controller = null
        browser.disconnect()
        pool.release()
    }
}
