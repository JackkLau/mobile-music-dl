package app.tingyu.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tingyu.MainActivity
import app.tingyu.MusicApp
import app.tingyu.data.bili.BiliResponseParser
import app.tingyu.model.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracks = ConcurrentHashMap<String, Track>()
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val container get() = (application as MusicApp).container
    private var restored = false
    private var restoring = false
    private var sessionEpoch = 0L
    private val snapshotMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != "https" || BiliResponseParser.safeMediaUrl(url.toString()) == null) throw java.io.IOException("音频地址不受支持，请重新解析")
                chain.proceed(chain.request())
            }.build()
        val factory = androidx.media3.datasource.DataSource.Factory { TrackDataSource(this, container, tracks, http) }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { if (restored && !restoring) saveSnapshot() }
            })
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return if (controller.packageName == packageName || controller.isTrusted) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.reject()
                }
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    // URI access comes only from our persisted library, never a controller-supplied URI.
                    restored = true
                    val epoch = sessionEpoch
                    val result = SettableFuture.create<List<MediaItem>>()
                    val job = scope.launch {
                        try {
                            val accepted = withContext(Dispatchers.IO) {
                                container.songDetails.load()
                                mediaItems.mapNotNull { TrackMedia.track(it) }.mapNotNull { track ->
                                    if (track.page <= 0) null
                                    else if (track.localOnly) container.library.saved(track.id)?.track?.takeIf { it.localOnly }
                                    else track.takeIf { it.bvid.matches(Regex("BV[0-9A-Za-z]{10}")) }
                                }.map(container.songDetails::apply)
                            }
                            if (epoch != sessionEpoch) result.set(emptyList())
                            else result.set(accepted.map { track -> tracks[track.id] = track; TrackMedia.item(track) })
                        } catch (error: CancellationException) { result.cancel(false); throw error }
                        catch (_: Exception) { result.setException(java.io.IOException("无法读取音乐记录，请重新打开音乐库")) }
                    }
                    result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
                    return result
                }
            }).build()
        scope.launch {
            val restoreEpoch = sessionEpoch
            try {
                container.songDetails.load()
                val snapshot = container.library.readPlayer()
                if (restoreEpoch == sessionEpoch && !restored && player.mediaItemCount == 0 && snapshot != null && snapshot.sessionKey == sessionKey()) {
                    restoring = true
                    val queue = container.library.decodeQueue(snapshot).map(container.songDetails::apply)
                    queue.forEach { tracks[it.id] = it }
                    if (queue.isNotEmpty()) {
                        player.setMediaItems(queue.map(TrackMedia::item), snapshot.index.coerceIn(queue.indices), snapshot.position.coerceAtLeast(0))
                        player.repeatMode = when (snapshot.mode) { "REPEAT_ONE" -> Player.REPEAT_MODE_ONE; "REPEAT_ALL", "SHUFFLE" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
                        player.shuffleModeEnabled = snapshot.mode == "SHUFFLE"
                    }
                }
            } catch (_: Exception) { /* A corrupt snapshot must not prevent a new playback request. */ }
            finally { restoring = false; restored = true }
            while (true) { delay(5000); if (player.mediaItemCount > 0) saveSnapshot() }
        }
        scope.launch {
            container.songDetails.load()
            container.songDetails.state.collect {
                for (index in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(index)
                    val old = TrackMedia.track(item) ?: continue
                    val updated = container.songDetails.apply(old)
                    if (updated != old) {
                        tracks[updated.id] = updated
                        // Keep the exact media URI/configuration so edits do not reload the audio.
                        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(TrackMedia.item(updated).mediaMetadata).build())
                    }
                }
            }
        }
        scope.launch {
            container.sessions.state.drop(1).collect {
                sessionEpoch++
                restored = true
                player.stop()
                player.clearMediaItems()
                tracks.clear()
                snapshotMutex.withLock { runCatching { container.library.clearPlayer() } }
            }
        }
    }

    private fun saveSnapshot() {
        val queue = (0 until player.mediaItemCount).mapNotNull { TrackMedia.track(player.getMediaItemAt(it)) }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val position = player.currentPosition.coerceAtLeast(0)
        val mode = when {
            player.shuffleModeEnabled -> "SHUFFLE"
            player.repeatMode == Player.REPEAT_MODE_ONE -> "REPEAT_ONE"
            player.repeatMode == Player.REPEAT_MODE_ALL -> "REPEAT_ALL"
            else -> "SEQUENTIAL"
        }
        val epoch = sessionEpoch
        val owner = sessionKey()
        scope.launch {
            snapshotMutex.withLock {
                if (epoch == sessionEpoch) runCatching { container.library.savePlayer(queue, index, position, mode, owner) }
            }
        }
    }
    private fun sessionKey(): String {
        val state = container.sessions.state.value
        return if (state.isLoggedIn) "account:${state.account?.mid}" else "anonymous"
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { scope.cancel(); session?.release(); player.release(); super.onDestroy() }
}
