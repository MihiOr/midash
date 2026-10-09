package com.mihior.midash

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.IOException

data class Track(
    val numericId: Long,
    val title: String,
    val artist: String,
    val audio: String,
    val cover: String,
    val durationMs: Long,
)

object MusicCatalog {
    private var cached: List<Track>? = null

    @Synchronized
    fun tracks(context: Context): List<Track> =
        cached
            ?: listOf(
                    track(
                        context,
                        0,
                        "Seventh Heaven",
                        "INOHA",
                        "seventh-heaven.mp3",
                        "midnight-drive.png",
                    ),
                    track(context, 1, "misery.", "pupsies", "misery.mp3", "misery-cover.jpg"),
                )
                .also { cached = it }

    private fun track(
        context: Context,
        id: Long,
        title: String,
        artist: String,
        file: String,
        cover: String,
    ): Track {
        val metadata = MediaMetadataRetriever()
        val duration =
            try {
                context.assets.openFd("audio/$file").use {
                    metadata.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                    ?: 0L
            } catch (_: IOException) {
                0L
            } finally {
                metadata.release()
            }
        return Track(id, title, artist, file, cover, duration)
    }
}
