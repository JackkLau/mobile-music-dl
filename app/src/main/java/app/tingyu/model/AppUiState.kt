package app.tingyu.model

import app.tingyu.debug.AudioUaMode
import app.tingyu.debug.DebugSettings
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.lyrics.LyricsDocument
import app.tingyu.catalog.ArtworkCandidate
import app.tingyu.catalog.LyricCandidate

enum class MusicLoginMethod(val label: String) { QR("二维码登录"), PHONE("手机号快捷登录") }

@kotlinx.serialization.Serializable
data class MusicLoginChallenge(val source: MusicSource, val id: String, val title: String? = null, val parseUrl: String? = null)

enum class AppTab(val label: String) {
    PLAYER("播放"), PARSE("解析"), LIBRARY("音乐库"), DOWNLOADS("下载"), MINE("我的"),
}

data class AppUiState(
    val input: String = "",
    val linkSource: MusicSource = MusicSource.BILIBILI,
    val parseLogin: MusicLoginChallenge? = null,
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
    val downloadFolder: app.tingyu.download.DownloadFolder = app.tingyu.download.DownloadFolder(),
    val folderBusy: Boolean = false,
    val folderError: String? = null,
    val downloads: List<DownloadTask> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val tab: AppTab = AppTab.PLAYER,
    val playerVisible: Boolean = false,
    val queueVisible: Boolean = false,
    val lyrics: LyricsDocument? = null,
    val lyricsLoading: Boolean = false,
    val lyricsError: String? = null,
    val lyricsChoiceTrack: Track? = null,
    val lyricsCandidates: List<LyricCandidate> = emptyList(),
    val lyricsQueryTitle: String? = null,
    val lyricsQueryArtist: String? = null,
    val lyricsWarnings: List<String> = emptyList(),
    val musicEditorVisible: Boolean = false,
    val musicEditorTrack: Track? = null,
    val musicSaving: Boolean = false,
    val musicError: String? = null,
    val coverSource: String = "",
    val coverSearchTrack: Track? = null,
    val coverCandidates: List<ArtworkCandidate> = emptyList(),
    val coverLoading: Boolean = false,
    val coverError: String? = null,
    val debugAvailable: Boolean = false,
    val debugSettings: DebugSettings = DebugSettings(),
    val developerVisible: Boolean = false,
    val probeRunning: Boolean = false,
    val probeTrackId: String? = null,
    val probeLines: List<String> = emptyList(),
    val probeError: String? = null,
) {
    val musicLogin: MusicLoginChallenge? get() = parseLogin ?: downloads.firstNotNullOfOrNull { task ->
        task.loginRequestId?.takeIf { task.status == DownloadStatus.FAILED }?.let {
            MusicLoginChallenge(task.track.source, it, title = task.track.title)
        }
    }
}

interface AppActions {
    fun selectDownloadFolder() {}
    fun resetDownloadFolder() {}
    fun startMusicLogin(challenge: MusicLoginChallenge, method: MusicLoginMethod) {}
    fun dismissMusicLogin(id: String) {}
    fun loginForDownload(id: String) {}
    fun input(value: String)
    fun chooseLinkSource(source: MusicSource)
    fun openMusicPage(track: Track) {}
    fun shareMusic(track: Track) {}
    fun clearParse()
    fun parse()
    fun loadMore()
    fun tab(destination: AppTab)
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
    fun findSongCover(track: Track) {}
    fun cancelCoverSearch() {}
    fun useSongCover(candidate: ArtworkCandidate) {}
    fun adjustLyricsOffset(track: Track, deltaMs: Long) {}
    fun resetLyricsOffset(track: Track) {}
    fun findLyricVersions() {}
    fun searchLyricVersions(title: String, artist: String) {}
    fun useLyricVersion(candidate: LyricCandidate) {}
    fun cancelDownload(id: String)
    fun retryDownload(id: String)
    fun removeDownload(id: String)
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
