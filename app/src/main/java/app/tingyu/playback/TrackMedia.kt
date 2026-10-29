package app.tingyu.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.tingyu.model.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TrackMedia {
    private const val KEY = "tingyu.track"
    private val json = Json { ignoreUnknownKeys = true }
    fun item(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(Uri.Builder().scheme("tingyu").authority("track").appendPath(track.bvid).appendPath(track.page.toString()).build())
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(track.title).setArtist(track.artist)
            .setArtworkUri(track.coverUrl.takeIf { it.startsWith("https://") }?.let(Uri::parse))
            .setIsPlayable(true)
            .setExtras(Bundle().apply { putString(KEY, json.encodeToString(track)) }).build())
        .build()

    fun track(item: MediaItem?): Track? = item?.mediaMetadata?.extras?.getString(KEY)?.let {
        runCatching { json.decodeFromString<Track>(it) }.getOrNull()
    }
}
