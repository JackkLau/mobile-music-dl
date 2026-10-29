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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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
                    // Only items created by this application are accepted; external content:// inputs are not opened.
                    val items = mediaItems.mapNotNull { TrackMedia.track(it) }.filter {
                        it.bvid.matches(Regex("BV[0-9A-Za-z]{10}")) && it.page > 0
                    }.map { track -> tracks[track.id] = track; TrackMedia.item(track) }
                    restored = true
                    return Futures.immediateFuture(items)
                }
            }).build()
        scope.launch {
            val restoreEpoch = sessionEpoch
            try {
                val snapshot = container.library.readPlayer()
                if (restoreEpoch == sessionEpoch && !restored && player.mediaItemCount == 0 && snapshot != null && snapshot.sessionKey == sessionKey()) {
                    restoring = true
                    val queue = container.library.decodeQueue(snapshot)
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
