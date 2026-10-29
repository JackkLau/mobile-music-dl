package app.tingyu.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val bvid: String,
    val cid: Long,
    val title: String,
    val artist: String = "",
    val coverUrl: String = "",
    val durationMs: Long = 0,
    val page: Int = 1,
    val sourceUrl: String = "https://www.bilibili.com/video/$bvid?p=$page",
    val localOnly: Boolean = false,
) { val id: String get() = "$bvid:p$page" }

data class ParseResult(
    val title: String,
    val tracks: List<Track>,
    val favoriteId: Long? = null,
    val nextPage: Int? = null,
)

data class AudioStream(
    val url: String,
    val backupUrls: List<String> = emptyList(),
    val mimeType: String = "audio/mp4",
    val bitrate: Long = 0,
    val headers: Map<String, String> = emptyMap(),
    val expectedDurationMs: Long = 0,
)

@Serializable
data class Account(val mid: Long, val name: String, val avatarUrl: String = "")
enum class SessionMode { ANONYMOUS, ACCOUNT }
data class SessionState(val mode: SessionMode = SessionMode.ANONYMOUS, val account: Account? = null) {
    val isLoggedIn: Boolean get() = account != null && mode == SessionMode.ACCOUNT
}

@Serializable
data class LocalTrack(
    val track: Track,
    val uri: String,
    val savedAt: Long = System.currentTimeMillis(),
    val identityUri: String? = null,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, SAVING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
@Serializable
data class DownloadTask(
    val track: Track,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0,
    val totalBytes: Long = -1,
    val error: String? = null,
    val uri: String? = null,
)

enum class PlayMode { SEQUENTIAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE }
data class PlaybackState(
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val mode: PlayMode = PlayMode.SEQUENTIAL,
    val error: String? = null,
)
