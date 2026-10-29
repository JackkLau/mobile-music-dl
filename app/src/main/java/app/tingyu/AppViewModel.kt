package app.tingyu

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tingyu.auth.LoginActivity
import app.tingyu.catalog.ArtworkCandidate
import app.tingyu.catalog.LyricCandidate
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.data.local.DeleteResult
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@UnstableApi
class AppViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application), AppActions {
    private val container = (application as MusicApp).container
    private val mutable = MutableStateFlow(AppUiState(
        debugAvailable = container.debugSettings.available,
        debugSettings = container.debugSettings.state.value,
    ))
    val state = mutable.asStateFlow()
    private val localOperations = LocalLibraryOperations(savedStateHandle["pending_local_deletion"]) {
        savedStateHandle["pending_local_deletion"] = it
    }
    private val pendingDeleteConsent = MutableStateFlow<LocalDeleteConsent?>(null)
    val deleteConsent = pendingDeleteConsent.asStateFlow()
    // Restore a callback target only. A missing result must not lock the library or repeat deletion.
    val pendingDeleteResultId: String? get() = localOperations.pendingDeletion?.takeUnless { it.approved }?.operationId
    private var parseJob: Job? = null
    private var lyricsJob: Job? = null
    private var lyricsVersion = 0
    private var coverJob: Job? = null
    private var coverVersion = 0
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
        viewModelScope.launch {
            container.songDetails.load()
            container.songDetails.state.collect { refreshSongDetails() }
        }
        viewModelScope.launch { container.library.localTracks.collect { value ->
            mutable.update { it.copy(library = value.map { local -> local.copy(track = container.songDetails.apply(local.track)) }) }
        } }
        refreshLocalLibrary(automatic = true)
        viewModelScope.launch { container.downloads.jobs.collect { value ->
            mutable.update { it.copy(downloads = value.map { task -> task.copy(track = container.songDetails.apply(task.track)) }) }
        } }
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
                if (version == requestVersion) mutable.update { it.copy(result = result.copy(tracks = result.tracks.map(container.songDetails::apply))) }
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
                if (version == requestVersion) mutable.update { it.copy(result = current.copy(tracks = (current.tracks + next.tracks).distinctBy(Track::id).map(container.songDetails::apply), nextPage = next.nextPage)) }
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
        val items = (queue.ifEmpty { listOf(track) }).distinctBy(Track::id).map(container.songDetails::apply)
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
        val remote = tracks.filterNot { it.localOnly }.distinctBy(Track::id)
        if (remote.isEmpty()) { mutable.update { it.copy(message = "这是本地音频，无需再次下载") }; return }
        container.downloads.enqueue(remote.map(container.songDetails::apply))
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
    override fun showMusicEditor(show: Boolean) {
        if (!show && !mutable.value.musicSaving) cancelCoverSearch()
        if (!mutable.value.musicSaving) mutable.update { it.copy(musicEditorVisible = show,
            musicEditorTrack = if (show) it.playback.track else null, musicError = null,
            coverSource = if (show) it.playback.track?.let { track -> container.songDetails.details(track.id)?.coverSource }.orEmpty() else "") }
    }

    override fun saveMusicInfo(track: Track, title: String, artist: String) {
        if (mutable.value.musicSaving) return
        cancelLyrics()
        cancelCoverSearch()
        mutable.update { it.copy(musicSaving = true, musicError = null) }
        viewModelScope.launch {
            try {
                val oldCover = container.songDetails.details(track.id)?.coverUri
                container.songDetails.edit(track, title, artist)
                if (oldCover != container.songDetails.details(track.id)?.coverUri) withContext(Dispatchers.IO) { container.songFiles.deleteOwnedCover(oldCover) }
                mutable.update { it.copy(musicEditorVisible = false, musicEditorTrack = null, message = "歌曲信息已保存") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.songFailure(track, if (error is IllegalArgumentException) error.message ?: "请检查歌名和歌手" else "歌曲信息未能保存，请检查存储空间后重试") } }
            finally { mutable.update { it.copy(musicSaving = false) } }
        }
    }

    override fun findLyrics() {
        if (mutable.value.lyricsLoading || mutable.value.musicSaving) return
        val current = mutable.value.playback
        val track = current.track ?: return
        val snapshot = track.copy(durationMs = current.durationMs.takeIf { it > 0 } ?: track.durationMs)
        cancelLyrics()
        val version = ++lyricsVersion
        mutable.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = viewModelScope.launch {
            try {
                // Exact matching remains conservative. Ambiguity or an unavailable provider
                // opens a searchable choice instead of treating existing lyrics as absent.
                val result = if (snapshot.artist.isBlank()) null else try { container.lyrics.find(snapshot) }
                    catch (_: java.io.IOException) { null }
                if (version != lyricsVersion || mutable.value.playback.track?.id != track.id) return@launch
                if (result == null) loadLyricCandidates(snapshot, snapshot, version)
                else if (!container.songDetails.saveLyrics(snapshot, result)) mutable.update {
                    if (version == lyricsVersion) it.copy(lyricsError = "歌曲信息已改变，请重新查找歌词") else it
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == lyricsVersion) mutable.update { it.copy(lyricsError = userMessage(error, "歌词未能加载，请检查网络或导入本地歌词")) }
            } finally { if (version == lyricsVersion) mutable.update { it.copy(lyricsLoading = false) } }
        }
    }

    override fun cancelLyrics() {
        lyricsVersion++
        lyricsJob?.cancel()
        lyricsJob = null
        mutable.update { it.copy(lyricsLoading = false, lyricsChoiceTrack = null, lyricsCandidates = emptyList(),
            lyricsQueryTitle = null, lyricsQueryArtist = null, lyricsWarnings = emptyList()) }
    }

    override fun findLyricVersions() {
        val track = mutable.value.playback.track ?: return
        searchLyricVersions(track.title, track.artist)
    }

    override fun searchLyricVersions(title: String, artist: String) {
        if (mutable.value.musicSaving || mutable.value.lyricsLoading) return
        val player = mutable.value.playback
        val track = player.track ?: return
        // Query corrections do not rename the audio or invalidate its saved details.
        val snapshot = track.copy(durationMs = player.durationMs.takeIf { it > 0 } ?: track.durationMs)
        cancelLyrics()
        val version = ++lyricsVersion
        mutable.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = viewModelScope.launch {
            try { loadLyricCandidates(snapshot, snapshot.copy(title = title.trim(), artist = artist.trim()), version) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (version == lyricsVersion) mutable.update { it.copy(lyricsError = userMessage(error, "歌词版本查询失败，请重试")) } }
            finally { if (version == lyricsVersion) mutable.update { it.copy(lyricsLoading = false) } }
        }
    }

    private suspend fun loadLyricCandidates(target: Track, query: Track, version: Int) {
        if (version != lyricsVersion) return
        mutable.update { it.copy(lyricsChoiceTrack = target, lyricsCandidates = emptyList(), lyricsWarnings = emptyList(),
            lyricsQueryTitle = query.title, lyricsQueryArtist = query.artist, lyricsError = null) }
        val result = container.lyrics.candidates(query)
        if (version == lyricsVersion && mutable.value.playback.track?.id == target.id) mutable.update {
            it.copy(lyricsCandidates = result.candidates, lyricsWarnings = result.warnings,
                lyricsError = if (result.candidates.isEmpty() && result.warnings.isEmpty())
                    "没有找到可选版本。可修改下方搜索词、仅用歌名搜索，或导入歌词。" else null)
        }
    }

    override fun useLyricVersion(candidate: LyricCandidate) {
        val state = mutable.value
        val target = state.lyricsChoiceTrack ?: return
        if (state.musicSaving || state.lyricsLoading || candidate !in state.lyricsCandidates) return
        val version = ++lyricsVersion
        mutable.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = viewModelScope.launch {
            try {
                val document = container.lyrics.select(candidate)
                if (version == lyricsVersion && mutable.value.playback.track?.id == target.id) {
                    if (document == null) mutable.update { it.copy(lyricsError = "这个版本暂无歌词，可选其他版本或导入本地歌词。") }
                    else {
                        val saved = container.songDetails.saveLyrics(target, document)
                        if (version == lyricsVersion) mutable.update {
                            if (saved) it.copy(lyricsChoiceTrack = null, lyricsCandidates = emptyList(), lyricsWarnings = emptyList(),
                                lyricsQueryTitle = null, lyricsQueryArtist = null, message = "歌词已保存，请试听核对时间")
                            else it.copy(lyricsError = "歌曲信息已改变，请重新查找歌词")
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (version == lyricsVersion) mutable.update { it.copy(lyricsError = userMessage(error, "这个版本的歌词暂时无法加载")) } }
            finally { if (version == lyricsVersion) mutable.update { it.copy(lyricsLoading = false) } }
        }
    }

    override fun adjustLyricsOffset(track: Track, deltaMs: Long) = updateLyricsTiming(track, deltaMs)
    override fun resetLyricsOffset(track: Track) = updateLyricsTiming(track, null)

    private fun updateLyricsTiming(track: Track, deltaMs: Long?) {
        if (mutable.value.musicSaving || mutable.value.lyricsLoading) return
        val playback = mutable.value.playback
        val snapshot = if (playback.track?.id == track.id && playback.durationMs > 0) track.copy(durationMs = playback.durationMs) else track
        mutable.update { it.copy(musicSaving = true, lyricsError = null) }
        viewModelScope.launch {
            try {
                val saved = if (deltaMs == null) container.songDetails.resetLyricsOffset(snapshot)
                else container.songDetails.adjustLyricsOffset(snapshot, deltaMs)
                if (!saved) mutable.update { it.songFailure(track, "歌词或歌曲信息已改变，请重新打开歌词", lyrics = true) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.songFailure(track, "歌词时间未能保存，请重试", lyrics = true) } }
            finally { mutable.update { it.copy(musicSaving = false) } }
        }
    }

    override fun findSongCover(track: Track) {
        if (mutable.value.musicSaving || mutable.value.coverLoading) return
        cancelCoverSearch()
        val snapshot = container.songDetails.apply(track)
        val version = ++coverVersion
        mutable.update { it.copy(coverSearchTrack = snapshot, coverLoading = true, coverCandidates = emptyList(), coverError = null) }
        coverJob = viewModelScope.launch {
            try {
                val candidates = container.catalog.artwork(snapshot)
                if (version == coverVersion) mutable.update { it.copy(coverCandidates = candidates,
                    coverError = if (candidates.isEmpty()) "没有找到匹配封面。可修正歌名、歌手，或选择自己的图片。" else null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (version == coverVersion) mutable.update { it.copy(coverError = userMessage(error, "封面查找失败，请稍后重试")) } }
            finally { if (version == coverVersion) mutable.update { it.copy(coverLoading = false) } }
        }
    }

    override fun cancelCoverSearch() {
        if (mutable.value.musicSaving) return
        coverVersion++
        coverJob?.cancel()
        coverJob = null
        mutable.update { it.copy(coverSearchTrack = null, coverCandidates = emptyList(), coverLoading = false, coverError = null) }
    }

    override fun useSongCover(candidate: ArtworkCandidate) {
        val state = mutable.value
        val target = state.coverSearchTrack ?: return
        if (state.musicSaving || state.coverLoading || candidate !in state.coverCandidates) return
        mutable.update { it.copy(musicSaving = true, coverError = null) }
        coverJob = viewModelScope.launch {
            var savedUri: String? = null
            try {
                val bytes = container.catalog.image(candidate)
                val old = container.songDetails.details(target.id)?.coverUri
                withContext(Dispatchers.IO) {
                    savedUri = Uri.fromFile(container.songFiles.cover(bytes)).toString()
                    if (!container.songDetails.saveMatchedCover(target, savedUri!!, candidate.source, candidate.sourceUrl))
                        throw java.io.IOException("歌曲信息已改变，请重新查找封面")
                    container.songFiles.deleteOwnedCover(old)
                }
                mutable.update { it.copy(coverSearchTrack = null, coverCandidates = emptyList(), coverError = null,
                    message = "封面已保存：${target.title}") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(coverError = userMessage(error, "封面未能保存，请重试")) } }
            finally {
                // A cancellation may arrive after the atomic store commit; keep that committed file.
                if (savedUri != null && container.songDetails.details(target.id)?.coverUri != savedUri)
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { container.songFiles.deleteOwnedCover(savedUri) }
                mutable.update { it.copy(musicSaving = false) }
            }
        }
    }

    override fun clearLyrics() {
        val track = mutable.value.playback.track ?: return
        cancelLyrics()
        viewModelScope.launch {
            try { container.songDetails.clearLyrics(track.id); mutable.update { if (it.playback.track?.id == track.id) it.copy(lyricsError = null) else it } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.songFailure(track, "歌词未能移除，请重试", lyrics = true) } }
        }
    }

    // MainActivity owns the document picker. Store the exact target across recreation and track changes.
    override fun importLyrics(track: Track) = Unit
    override fun selectSongCover(track: Track) = Unit
    fun beginSongFile(kind: String, track: Track): Boolean {
        if (mutable.value.musicSaving || mutable.value.lyricsLoading || savedStateHandle.get<String>("song_file_$kind") != null) return false
        val playback = mutable.value.playback
        val snapshot = container.songDetails.apply(track).let {
            if (kind == "lyrics" && playback.track?.id == track.id && playback.durationMs > 0) it.copy(durationMs = playback.durationMs) else it
        }
        savedStateHandle["song_file_$kind"] = Json.encodeToString(snapshot)
        return true
    }
    fun finishSongFile(kind: String, uri: Uri?) {
        val target = savedStateHandle.remove<String>("song_file_$kind") ?: return
        if (uri == null) return
        val track = runCatching { Json.decodeFromString<Track>(target) }.getOrNull() ?: return
        if (kind == "lyrics") cancelLyrics()
        mutable.update { it.copy(musicSaving = true,
            musicError = if (it.musicEditorTrack?.id == track.id) null else it.musicError,
            lyricsError = if (kind == "lyrics" && it.playback.track?.id == track.id) null else it.lyricsError) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (kind == "lyrics") {
                        if (!container.songDetails.saveLyrics(track, container.songFiles.lyrics(uri)))
                            throw java.io.IOException("歌曲信息已改变，请重新导入歌词")
                    } else {
                        val old = container.songDetails.details(track.id)?.coverUri
                        val file = container.songFiles.cover(uri)
                        val savedUri = Uri.fromFile(file).toString()
                        try { container.songDetails.setCover(track.id, savedUri) }
                        catch (error: Exception) {
                            // Cancellation can be delivered after the atomic commit completed.
                            if (container.songDetails.details(track.id)?.coverUri != savedUri) file.delete()
                            throw error
                        }
                        container.songFiles.deleteOwnedCover(old)
                    }
                }
                mutable.update { it.copy(message = if (kind == "lyrics") "歌词已导入：${track.title}" else "封面已保存：${track.title}") }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                val message = if (kind == "lyrics") "歌词导入失败。请选择 UTF-8 的 LRC／TXT 文件（不超过 256 KB）。" else "封面导入失败。请选择可读取的图片（不超过 12 MB）。"
                mutable.update { it.songFailure(track, message, lyrics = kind == "lyrics") }
            } finally { mutable.update { it.copy(musicSaving = false) } }
        }
    }

    override fun resetSongCover(track: Track) {
        if (mutable.value.musicSaving) return
        mutable.update { it.copy(musicSaving = true, musicError = null) }
        viewModelScope.launch {
            try {
                val old = container.songDetails.details(track.id)?.coverUri
                container.songDetails.setCover(track.id, "")
                withContext(Dispatchers.IO) { container.songFiles.deleteOwnedCover(old) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.songFailure(track, "默认封面未能保存，请重试") } }
            finally { mutable.update { it.copy(musicSaving = false) } }
        }
    }

    private fun refreshSongDetails() {
        mutable.update { state ->
            val playback = state.playback
            val current = playback.track?.let(container.songDetails::apply)
            state.copy(
                result = state.result?.let { it.copy(tracks = it.tracks.map(container.songDetails::apply)) },
                library = state.library.map { it.copy(track = container.songDetails.apply(it.track)) },
                downloads = state.downloads.map { it.copy(track = container.songDetails.apply(it.track)) },
                playback = playback.copy(track = current, queue = playback.queue.map(container.songDetails::apply)),
                musicEditorTrack = state.musicEditorTrack?.let(container.songDetails::apply),
                lyrics = current?.let { container.songDetails.lyricsFor(it.copy(durationMs = playback.durationMs.takeIf { value -> value > 0 } ?: it.durationMs)) },
                coverSource = state.musicEditorTrack?.let { container.songDetails.details(it.id)?.coverSource }.orEmpty(),
            )
        }
    }
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
    override fun scanLocalMusic() = refreshLocalLibrary(automatic = false)

    // MainActivity owns the system document picker and passes only its granted URI flags.
    override fun selectLocalFiles() = Unit

    private fun refreshLocalLibrary(automatic: Boolean) {
        viewModelScope.launch {
            localOperations.serialized {
                mutable.update { it.copy(libraryWorking = true, libraryStatus = "正在查找已下载音乐…", libraryError = null) }
                try {
                    val result = withContext(Dispatchers.IO) { container.localMusic.scan() }
                    mutable.update { it.copy(libraryNeedsPermission = result.permissionRequired,
                        libraryStatus = if (automatic && result.added == 0) null else "扫描完成：新增 ${result.added} 首，已有 ${result.existing} 首",
                        libraryError = if (result.failed > 0) "${result.failed} 个文件暂时无法读取，已有音乐记录已保留。可重试扫描或手动导入。" else null) }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { mutable.update { it.copy(libraryStatus = null, libraryError = "扫描未完成，已有记录已保留。请重试或手动导入音频。") } }
                finally { mutable.update { it.copy(libraryWorking = false) } }
            }
        }
    }

    fun importLocalFiles(uris: List<Uri>, grantedFlags: Int) {
        if (uris.isEmpty()) return
        // System picker results must wait for startup scans, never be discarded as "busy".
        viewModelScope.launch {
            localOperations.serialized {
                mutable.update { it.copy(libraryWorking = true, libraryStatus = "正在导入本地音频…", libraryError = null) }
                try {
                    val result = withContext(Dispatchers.IO) { container.localMusic.importUris(uris.distinct(), grantedFlags) }
                    mutable.update { it.copy(libraryStatus = "导入完成：新增 ${result.added} 首，已有 ${result.existing} 首",
                        libraryError = if (result.failed > 0) "${result.failed} 个文件未能导入。请选择可读取的音频，并保留文件访问授权。" else null) }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { mutable.update { it.copy(libraryStatus = null, libraryError = "导入未完成，请重新选择可读取的音频。已有记录已保留。") } }
                finally { mutable.update { it.copy(libraryWorking = false) } }
            }
        }
    }

    fun localPickerUnavailable() {
        mutable.update { it.copy(libraryError = "无法打开文件选择器，请使用系统文件应用后重试。") }
    }

    override fun dismissLocalDeleteError() { mutable.update { it.copy(localDeleteError = null) } }

    override fun deleteLocal(track: LocalTrack) {
        if (mutable.value.deletingLocalId != null) return
        performLocalDelete(localOperations.beginDeletion(track))
    }

    fun consumeDeleteConsent(operationId: String) {
        if (pendingDeleteConsent.value?.operationId == operationId) pendingDeleteConsent.value = null
    }

    fun completeDeleteConsent(operationId: String, approved: Boolean) {
        val pending = localOperations.pendingDeletion ?: return
        if (pending.operationId != operationId || pending.approved) return
        consumeDeleteConsent(operationId)
        if (approved) localOperations.approveDeletion(operationId)?.let(::performLocalDelete)
        else if (localOperations.clearDeletion(operationId)) {
            mutable.update { it.copy(deletingLocalId = null, localDeleteError = "已取消系统授权，文件和音乐记录均保留。") }
        }
    }

    private fun performLocalDelete(pending: PendingLocalDeletion) {
        val local = pending.local
        mutable.update { it.copy(deletingLocalId = local.track.id, localDeleteError = null) }
        viewModelScope.launch {
            localOperations.serialized {
                if (localOperations.pendingDeletion?.operationId != pending.operationId) return@serialized
                var awaitingConsent = false
                try {
                    when (val result = withContext(Dispatchers.IO) { container.localMusic.delete(local, pending.approved) }) {
                        is DeleteResult.Deleted -> {
                            // Only remove playback/download records after file deletion is confirmed.
                            controller?.let { player ->
                                for (index in player.mediaItemCount - 1 downTo 0) {
                                    if (player.getMediaItemAt(index).mediaId == local.track.id) player.removeMediaItem(index)
                                }
                            }
                            container.downloads.forgetCompleted(local.track.id)
                            mutable.update { it.copy(message = if (result.alreadyMissing) "文件已不存在，已移除音乐记录" else "已删除本地音频") }
                        }
                        is DeleteResult.RequiresConfirmation -> {
                            awaitingConsent = true
                            pendingDeleteConsent.value = LocalDeleteConsent(pending.operationId, result.request)
                        }
                        is DeleteResult.Failed -> mutable.update { it.copy(localDeleteError = result.message, message = result.message) }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { mutable.update { it.copy(localDeleteError = "删除未完成，请检查文件权限后重试。", message = "删除未完成，请检查文件权限后重试。") } }
                finally {
                    if (!awaitingConsent && localOperations.clearDeletion(pending.operationId)) {
                        mutable.update { it.copy(deletingLocalId = null) }
                    }
                }
            }
        }
    }

    private fun syncPlayback() {
        val player = controller ?: return
        val queue = (0 until player.mediaItemCount).mapNotNull { TrackMedia.track(player.getMediaItemAt(it)) }.map(container.songDetails::apply)
        val current = TrackMedia.track(player.currentMediaItem)?.let(container.songDetails::apply)
        val changed = current?.id != mutable.value.playback.track?.id
        if (changed) cancelLyrics()
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: current?.durationMs ?: 0
        val mode = when {
            player.shuffleModeEnabled -> PlayMode.SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            player.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
            else -> PlayMode.SEQUENTIAL
        }
        val error = player.playerError?.let { userMessage(it, "音频加载失败，请检查网络或重新解析") }
        mutable.update { it.copy(playback = PlaybackState(current, queue, player.currentMediaItemIndex.coerceAtLeast(0), player.isPlaying, player.playbackState == Player.STATE_BUFFERING && player.playWhenReady, player.currentPosition.coerceAtLeast(0), duration, mode, error),
            lyrics = current?.let { track -> container.songDetails.lyricsFor(track.copy(durationMs = duration)) },
            lyricsError = if (changed) null else it.lyricsError,
            musicError = if (changed && !it.musicEditorVisible) null else it.musicError) }
    }
    private fun displayError(e: Exception): String {
        return userMessage(e, "请求失败，请检查网络后重试")
    }
    override fun onCleared() { controller?.removeListener(listener); MediaController.releaseFuture(controllerFuture); super.onCleared() }
}

data class LocalDeleteConsent(val operationId: String, val request: PendingIntent)
