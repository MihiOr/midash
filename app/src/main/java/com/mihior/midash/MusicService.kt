package com.mihior.midash

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.service.media.MediaBrowserService
import androidx.core.content.ContextCompat
import java.io.IOException

/** Sole owner of music playback. Bound controllers may come and go without releasing the player. */
class MusicService : MediaBrowserService() {
    private lateinit var player: MediaPlayer
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var focus: AudioFocusRequest
    private val handler = Handler(Looper.getMainLooper())
    private val tracks by lazy { MusicCatalog.tracks(this) }
    private val artwork by lazy {
        tracks.map { track ->
            assets.open(track.cover).use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = 4 },
                )
            }
        }
    }
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    private var index = 0
    private var prepared = false
    private var wantPlay = false
    private var focused = false
    private var ducked = false
    private var promoted = false
    private var started = false
    private var pendingSeek = 0L
    private var volume = .15f
    private var master = 1f
    private var error: String? = null
    private val playing
        get() = prepared && player.isPlaying

    private val position
        get() = if (prepared) player.currentPosition.toLong() else pendingSeek

    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                pauseMusic()
            }
        }
    private val progress =
        object : Runnable {
            override fun run() {
                publishState()
                if (wantPlay) handler.postDelayed(this, 250)
            }
        }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW)
        )
        player =
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setWakeMode(this@MusicService, PowerManager.PARTIAL_WAKE_LOCK)
            }
        session = MediaSession(this, "MiNini music")
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = play()

                override fun onPause() = pauseMusic()

                override fun onStop() {
                    pauseMusic()
                    notifications.cancel(NOTIFICATION)
                    stopSelf()
                }

                override fun onSkipToNext() = select((index + 1) % tracks.size)

                override fun onSkipToPrevious() = select((index + tracks.size - 1) % tracks.size)

                override fun onSkipToQueueItem(id: Long) {
                    tracks
                        .indexOfFirst { it.numericId == id }
                        .takeIf { it >= 0 }
                        ?.let { select(it) }
                }

                override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
                    tracks
                        .indexOfFirst { it.audio == mediaId }
                        .takeIf { it >= 0 }
                        ?.let {
                            select(it)
                            play()
                        }
                }

                override fun onSeekTo(pos: Long) {
                    pendingSeek = pos.coerceIn(0, tracks[index].durationMs)
                    if (prepared) player.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST)
                    publishState()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    val value = extras?.getFloat("value") ?: return
                    if (!value.isFinite() || value !in 0f..1f) return
                    when (action) {
                        VOLUME -> volume = value
                        MASTER -> master = value
                        else -> return
                    }
                    applyVolume()
                    publishState()
                }
            },
            handler,
        )
        session.setSessionActivity(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        session.setQueue(tracks.map { MediaSession.QueueItem(description(it), it.numericId) })
        session.isActive = true
        sessionToken = session.sessionToken
        focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(
                    { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                focused = true
                                ducked = false
                                applyVolume()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                ducked = true
                                applyVolume()
                            }
                            else -> pauseMusic()
                        }
                    },
                    handler,
                )
                .build()
        player.setOnPreparedListener {
            prepared = true
            if (pendingSeek > 0) player.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST)
            if (wantPlay && focused) player.start()
            publishState()
            refreshNotification()
        }
        player.setOnSeekCompleteListener { publishState() }
        player.setOnCompletionListener {
            if (wantPlay) select((index + 1) % tracks.size)
        }
        player.setOnErrorListener { _, what, extra ->
            error = "MediaPlayer error $what / $extra"
            prepared = false
            pauseMusic()
            true
        }
        ContextCompat.registerReceiver(
            this,
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        select(0)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? =
        if (clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID) BrowserRoot(ROOT, null)
        else null

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>,
    ) {
        result.sendResult(
            if (parentId == ROOT)
                tracks
                    .map {
                        MediaBrowser.MediaItem(
                            description(it),
                            MediaBrowser.MediaItem.FLAG_PLAYABLE,
                        )
                    }
                    .toMutableList()
            else mutableListOf()
        )
    }

    private fun description(track: Track) =
        MediaDescription.Builder()
            .setMediaId(track.audio)
            .setTitle(track.title)
            .setSubtitle(track.artist)
            .build()

    private fun play() {
        if (wantPlay && (playing || !prepared)) return
        if (error != null) {
            select(index)
            if (error != null) return
        }
        // Become a started foreground media service before requesting audio focus (Android 15+).
        if (!started) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MusicService::class.java).setAction(KEEP_ALIVE),
            )
            started = true
        }
        wantPlay = true
        startForeground(NOTIFICATION, notification())
        promoted = true
        if (!focused)
            focused =
                audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focused) {
            pauseMusic()
            return
        }
        if (prepared && !player.isPlaying) player.start()
        handler.removeCallbacks(progress)
        handler.post(progress)
        refreshNotification()
    }

    private fun pauseMusic() {
        wantPlay = false
        if (playing) player.pause()
        handler.removeCallbacks(progress)
        audioManager.abandonAudioFocusRequest(focus)
        focused = false
        ducked = false
        applyVolume()
        publishState()
        if (promoted) {
            postMediaNotification()
            stopForeground(STOP_FOREGROUND_DETACH)
            promoted = false
        }
        started = false
        stopSelf()
    }

    private fun select(next: Int) {
        index = next
        prepared = false
        pendingSeek = 0
        error = null
        player.reset()
        player.setAudioAttributes(attributes)
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        val track = tracks[index]
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.audio)
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork[index])
                .build()
        )
        try {
            assets.openFd("audio/${track.audio}").use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            applyVolume()
            player.prepareAsync()
        } catch (_: IOException) {
            error = "Audio file unavailable: ${track.audio}"
            pauseMusic()
            return
        }
        publishState()
        refreshNotification()
    }

    private fun applyVolume() {
        val gain = volume * master * if (ducked) .2f else 1f
        player.setVolume(gain, gain)
    }

    private fun publishState() {
        val state =
            when {
                error != null -> PlaybackState.STATE_ERROR
                !prepared -> PlaybackState.STATE_BUFFERING
                playing -> PlaybackState.STATE_PLAYING
                else -> PlaybackState.STATE_PAUSED
            }
        val extras =
            Bundle().apply {
                putFloat("volume", volume)
                putFloat("master", master)
                putString("error", error)
            }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(state, position, if (playing) player.playbackParams.speed else 0f)
                .setActiveQueueItemId(tracks[index].numericId)
                .setExtras(extras)
                .apply { error?.let { setErrorMessage(it) } }
                .build()
        )
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(tracks[index].title)
            .setContentText(tracks[index].artist)
            .setLargeIcon(artwork[index])
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(wantPlay)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        "Previous",
                        actionIntent(PREVIOUS),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        if (wantPlay) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play,
                        if (wantPlay) "Pause" else "Play",
                        actionIntent(if (wantPlay) PAUSE else PLAY),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        "Next",
                        actionIntent(NEXT),
                    )
                    .build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

    // Media-session notifications are exempt from POST_NOTIFICATIONS on Android 13+.
    @SuppressLint("NotificationPermission")
    private fun postMediaNotification() {
        notifications.notify(NOTIFICATION, notification())
    }

    private fun refreshNotification() {
        if (promoted) postMediaNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> play()
            PAUSE -> pauseMusic()
            NEXT -> select((index + 1) % tracks.size)
            PREVIOUS -> select((index + tracks.size - 1) % tracks.size)
        }
        if (!wantPlay) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!wantPlay) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(noisy)
        audioManager.abandonAudioFocusRequest(focus)
        session.isActive = false
        session.release()
        player.release()
        notifications.cancel(NOTIFICATION)
        super.onDestroy()
    }

    companion object {
        const val VOLUME = "com.mihior.midash.MUSIC_VOLUME"
        const val MASTER = "com.mihior.midash.MASTER_VOLUME"
        private const val CHANNEL = "music"
        private const val ROOT = "music"
        private const val NOTIFICATION = 41
        private const val KEEP_ALIVE = "com.mihior.midash.KEEP_MUSIC"
        private const val PLAY = "com.mihior.midash.PLAY"
        private const val PAUSE = "com.mihior.midash.PAUSE"
        private const val NEXT = "com.mihior.midash.NEXT"
        private const val PREVIOUS = "com.mihior.midash.PREVIOUS"
    }
}
