package app.tingyu.model

import app.tingyu.debug.AudioUaMode
import app.tingyu.debug.DebugSettings
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.lyrics.LyricsDocument

data class AppUiState(
    val input: String = "",
    val parsing: Boolean = false,
    val loadingMore: Boolean = false,
    val result: ParseResult? = null,
    val error: String? = null,
    val message: String? = null,
    val session: SessionState = SessionState(),
    val library: List<LocalTrack> = emptyList(),
    val libraryWorking: Boolean = false,
    val libraryStatus: String? = null,
    val libraryError: String? = null,
    val libraryNeedsPermission: Boolean = false,
    val deletingLocalId: String? = null,
    val localDeleteError: String? = null,
    val downloads: List<DownloadTask> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val tab: Int = 0,
    val playerVisible: Boolean = false,
    val queueVisible: Boolean = false,
    val lyrics: LyricsDocument? = null,
    val lyricsLoading: Boolean = false,
    val lyricsError: String? = null,
    val musicEditorVisible: Boolean = false,
    val musicEditorTrack: Track? = null,
    val musicSaving: Boolean = false,
    val musicError: String? = null,
    val debugAvailable: Boolean = false,
    val debugSettings: DebugSettings = DebugSettings(),
    val developerVisible: Boolean = false,
    val probeRunning: Boolean = false,
    val probeTrackId: String? = null,
    val probeLines: List<String> = emptyList(),
    val probeError: String? = null,
)

interface AppActions {
    fun input(value: String)
    fun parse()
    fun loadMore()
    fun tab(index: Int)
    fun play(track: Track, queue: List<Track>)
    fun download(tracks: List<Track>)
    fun togglePlayback()
    fun seek(positionMs: Long)
    fun next()
    fun previous()
    fun cycleMode()
    fun showPlayer(show: Boolean)
    fun showQueue(show: Boolean)
    fun showMusicEditor(show: Boolean)
    fun saveMusicInfo(track: Track, title: String, artist: String)
    fun findLyrics()
    fun cancelLyrics()
    fun importLyrics(track: Track)
    fun clearLyrics()
    fun selectSongCover(track: Track)
    fun resetSongCover(track: Track)
    fun cancelDownload(id: String)
    fun retryDownload(id: String)
    fun login()
    fun logout()
    fun sessionMode(mode: SessionMode)
    fun dismissMessage()
    fun deleteLocal(track: LocalTrack)
    fun scanLocalMusic()
    fun selectLocalFiles()
    fun dismissLocalDeleteError()
    fun versionTap()
    fun showDeveloperTools(show: Boolean)
    fun disableDebugMode()
    fun parseUa(value: UserAgentProfile)
    fun audioUa(value: AudioUaMode)
    fun debugLogging(enabled: Boolean)
    fun selectProbeTrack(id: String)
    fun startUaComparison()
    fun cancelUaComparison()
}
