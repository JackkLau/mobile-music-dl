package app.tingyu

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tingyu.auth.LoginActivity
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.debug.AudioUaMode
import app.tingyu.debug.DebugModeGate
import app.tingyu.debug.DebugSettings
import app.tingyu.debug.UaComparison
import app.tingyu.model.*
import app.tingyu.playback.PlaybackService
import app.tingyu.playback.TrackMedia
import app.tingyu.util.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class AppViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val container = (application as MusicApp).container
    private val mutable = MutableStateFlow(AppUiState(
        debugAvailable = container.debugSettings.available,
        debugSettings = container.debugSettings.state.value,
    ))
    val state = mutable.asStateFlow()
    private var parseJob: Job? = null
    private var requestVersion = 0
    private val debugGate = DebugModeGate(container.debugSettings.available)
    private var probeJob: Job? = null
    @Volatile private var probeVersion = 0
    private var controller: MediaController? = null
    private var pendingPlay: Pair<Track, List<Track>>? = null
    private var notificationDownloads: List<Track>? = null
    private val controllerFuture = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncPlayback()
    }

    init {
        viewModelScope.launch {
            container.debugSettings.state.collect { settings ->
                mutable.update { it.copy(debugSettings = settings,
                    developerVisible = it.developerVisible && settings.enabled) }
                if (!settings.enabled) cancelUaComparison()
            }
        }
        viewModelScope.launch { container.library.localTracks.collect { value -> mutable.update { it.copy(library = value) } } }
        viewModelScope.launch { container.downloads.jobs.collect { value -> mutable.update { it.copy(downloads = value) } } }
        viewModelScope.launch {
            var initial = true
            container.sessions.state.collect { session ->
                if (!initial) {
                    parseJob?.cancel(); requestVersion++
                    cancelUaComparison()
                    container.downloads.interruptAll()
                    pendingPlay = null
                    mutable.update { it.copy(result = null, error = null, parsing = false, loadingMore = false,
                        probeTrackId = null, probeLines = emptyList(), probeError = null) }
                }
                initial = false
                mutable.update { it.copy(session = session) }
            }
        }
        controllerFuture.addListener({
            try {
                controller = controllerFuture.get().also { it.addListener(listener) }
                syncPlayback()
                pendingPlay?.let { (track, queue) -> pendingPlay = null; play(track, queue) }
            } catch (_: Exception) { mutable.update { it.copy(error = "播放器未能连接，请重新打开应用") } }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch { while (true) { delay(500); syncPlayback() } }
    }

    override fun input(value: String) { mutable.update { it.copy(input = value.take(8192)) } }
    fun receiveShare(value: String) { input(value); tab(0) }
    override fun parse() {
        val input = mutable.value.input.trim()
        if (input.isBlank()) { mutable.update { it.copy(error = "请先粘贴 B 站视频或收藏夹链接") }; return }
        parseJob?.cancel()
        cancelUaComparison()
        val version = ++requestVersion
        mutable.update { it.copy(parsing = true, loadingMore = false, result = null, error = null,
            probeTrackId = null, probeLines = emptyList(), probeError = null) }
        parseJob = viewModelScope.launch {
            try {
                val result = container.bilibili.parse(input)
                if (version == requestVersion) mutable.update { it.copy(result = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == requestVersion) mutable.update { it.copy(error = displayError(e)) } }
            finally { if (version == requestVersion) mutable.update { it.copy(parsing = false) } }
        }
    }
    override fun loadMore() {
        val current = mutable.value.result ?: return
        val id = current.favoriteId ?: return
        val page = current.nextPage ?: return
        if (mutable.value.loadingMore || mutable.value.parsing) return
        val version = requestVersion
        mutable.update { it.copy(loadingMore = true, error = null) }
        parseJob = viewModelScope.launch {
            try {
                val next = container.bilibili.loadFavorites(id, page)
                if (version == requestVersion) mutable.update { it.copy(result = current.copy(tracks = (current.tracks + next.tracks).distinctBy(Track::id), nextPage = next.nextPage)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == requestVersion) mutable.update { it.copy(error = displayError(e)) } }
            finally { if (version == requestVersion) mutable.update { it.copy(loadingMore = false) } }
        }
    }
    override fun tab(index: Int) {
        if (mutable.value.developerVisible) showDeveloperTools(false)
        debugGate.reset()
        mutable.update { it.copy(tab = index.coerceIn(0, 3)) }
    }
    override fun play(track: Track, queue: List<Track>) {
        val items = (queue.ifEmpty { listOf(track) }).distinctBy(Track::id)
        val fullQueue = if (items.none { it.id == track.id }) listOf(track) + items else items
        val player = controller
        if (player == null) { pendingPlay = track to fullQueue; mutable.update { it.copy(message = "正在连接播放器…") }; return }
        player.setMediaItems(fullQueue.map(TrackMedia::item), fullQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0)
        player.prepare()
        player.play()
        mutable.update { it.copy(playerVisible = true) }
        syncPlayback()
    }
    override fun download(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        container.downloads.enqueue(tracks.distinctBy(Track::id))
        mutable.update { it.copy(message = "已加入下载队列，可在“下载”查看进度") }
    }
    fun deferDownloadForPermission(tracks: List<Track>) { notificationDownloads = tracks }
    fun continueDownloadAfterPermission() {
        val tracks = notificationDownloads
        notificationDownloads = null
        tracks?.let(::download)
    }
    override fun cancelDownload(id: String) = container.downloads.cancel(id)
    override fun retryDownload(id: String) = container.downloads.retry(id)
    override fun togglePlayback() {
        controller?.let {
            if (it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playerError == null) it.pause()
            else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
        }
    }
    override fun seek(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)) }
    override fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    override fun previous() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
    override fun cycleMode() {
        val player = controller ?: return
        val next = PlayMode.entries[(mutable.value.playback.mode.ordinal + 1) % PlayMode.entries.size]
        player.shuffleModeEnabled = next == PlayMode.SHUFFLE
        player.repeatMode = when(next) { PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE; PlayMode.REPEAT_ALL, PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
        syncPlayback()
    }
    override fun showPlayer(show: Boolean) { mutable.update { it.copy(playerVisible = show) } }
    override fun showQueue(show: Boolean) { mutable.update { it.copy(queueVisible = show) } }
    override fun login() { getApplication<Application>().startActivity(Intent(getApplication(), LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    override fun logout() { container.sessions.logout() }
    override fun sessionMode(mode: SessionMode) {
        if (mode == SessionMode.ACCOUNT && container.sessions.state.value.account == null) login()
        else viewModelScope.launch {
            try { withContext(Dispatchers.IO) { container.sessions.setMode(mode) } }
            catch (_: Exception) { mutable.update { it.copy(message = "无法保存访问模式，请检查手机存储空间后重试") } }
        }
    }
    override fun dismissMessage() { mutable.update { it.copy(message = null) } }
    override fun versionTap() {
        if (mutable.value.debugSettings.enabled) {
            mutable.update { it.copy(message = "调试模式已开启，可进入开发者工具") }
            return
        }
        when (val remaining = debugGate.tap(android.os.SystemClock.elapsedRealtime())) {
            null -> return
            0 -> updateDebugSettings("调试模式已开启") { it.copy(enabled = true) }
            in 1..4 -> mutable.update { it.copy(message = "再点击 $remaining 次开启调试模式") }
            else -> Unit
        }
    }

    override fun showDeveloperTools(show: Boolean) {
        if (show && !container.debugSettings.available) return
        if (show && !container.debugSettings.state.value.enabled) {
            viewModelScope.launch {
                try {
                    container.debugSettings.update { it.copy(enabled = true, loggingEnabled = false) }
                    mutable.update { it.copy(developerVisible = true) }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { mutable.update { it.copy(message = "调试设置未保存，请检查手机存储空间后重试") } }
            }
            return
        }
        if (!show) cancelUaComparison()
        mutable.update { it.copy(developerVisible = show) }
    }

    override fun disableDebugMode() {
        cancelUaComparison()
        debugGate.reset()
        updateDebugSettings("调试模式已关闭，新请求恢复默认配置") { DebugSettings() }
    }

    override fun parseUa(value: UserAgentProfile) = changeEnabledDebugSettings { it.copy(parseUa = value) }
    override fun audioUa(value: AudioUaMode) = changeEnabledDebugSettings { it.copy(audioUa = value) }
    override fun debugLogging(enabled: Boolean) = changeEnabledDebugSettings { it.copy(loggingEnabled = enabled) }

    private fun changeEnabledDebugSettings(transform: (DebugSettings) -> DebugSettings) {
        if (container.debugSettings.state.value.enabled) updateDebugSettings(transform = transform)
    }

    private fun updateDebugSettings(message: String? = null, transform: (DebugSettings) -> DebugSettings) {
        viewModelScope.launch {
            try {
                container.debugSettings.update(transform)
                if (message != null) mutable.update { it.copy(message = message) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.copy(message = "调试设置未保存，请检查手机存储空间后重试") } }
        }
    }

    override fun selectProbeTrack(id: String) {
        if (mutable.value.probeRunning || mutable.value.result?.tracks?.none { it.id == id } != false) return
        mutable.update { it.copy(probeTrackId = id, probeLines = emptyList(), probeError = null) }
    }

    override fun startUaComparison() {
        val current = mutable.value
        if (!current.debugAvailable || !current.debugSettings.enabled || current.probeRunning) return
        val tracks = current.result?.tracks.orEmpty()
        val track = tracks.firstOrNull { it.id == current.probeTrackId } ?: tracks.firstOrNull() ?: return
        val profile = container.debugSettings.snapshot()
        val version = ++probeVersion
        mutable.update { it.copy(probeRunning = true, probeTrackId = track.id, probeLines = emptyList(), probeError = null) }
        probeJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UaComparison(container.bilibili).run(track, profile) { line ->
                        mutable.update {
                            if (probeVersion != version) it
                            else it.copy(probeLines = (it.probeLines + line).takeLast(40))
                        }
                    }
                }
                if (probeVersion == version) mutable.update { it.copy(probeLines = it.probeLines + "对照完成；样本可读不代表整首音频已保存。") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (probeVersion == version) mutable.update { it.copy(probeError = userMessage(error, "对照失败，请检查网络后重试")) }
            } finally {
                if (probeVersion == version) mutable.update { it.copy(probeRunning = false) }
            }
        }
    }

    override fun cancelUaComparison() {
        probeVersion++
        probeJob?.cancel()
        probeJob = null
        mutable.update { it.copy(probeRunning = false,
            probeLines = if (it.probeRunning) (it.probeLines + "对照已取消").takeLast(40) else it.probeLines) }
    }
    override fun deleteLocal(track: LocalTrack) {
        viewModelScope.launch {
            try {
                val player = controller
                if (player != null) {
                    for (index in player.mediaItemCount - 1 downTo 0) {
                        if (player.getMediaItemAt(index).mediaId == track.track.id) player.removeMediaItem(index)
                    }
                }
                withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.delete(Uri.parse(track.uri), null, null) }
                container.library.removeLocal(track.track.id)
                container.downloads.forgetCompleted(track.track.id)
                mutable.update { it.copy(message = "已删除本地音频") }
            } catch (_: Exception) { mutable.update { it.copy(message = "无法删除此文件，请在文件管理器中检查权限") } }
        }
    }

    private fun syncPlayback() {
        val player = controller ?: return
        val queue = (0 until player.mediaItemCount).mapNotNull { TrackMedia.track(player.getMediaItemAt(it)) }
        val current = TrackMedia.track(player.currentMediaItem)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: current?.durationMs ?: 0
        val mode = when {
            player.shuffleModeEnabled -> PlayMode.SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            player.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
            else -> PlayMode.SEQUENTIAL
        }
        val error = player.playerError?.let { userMessage(it, "音频加载失败，请检查网络或重新解析") }
        mutable.update { it.copy(playback = PlaybackState(current, queue, player.currentMediaItemIndex.coerceAtLeast(0), player.isPlaying, player.playbackState == Player.STATE_BUFFERING && player.playWhenReady, player.currentPosition.coerceAtLeast(0), duration, mode, error)) }
    }
    private fun displayError(e: Exception): String {
        return userMessage(e, "请求失败，请检查网络后重试")
    }
    override fun onCleared() { controller?.removeListener(listener); MediaController.releaseFuture(controllerFuture); super.onCleared() }
}
