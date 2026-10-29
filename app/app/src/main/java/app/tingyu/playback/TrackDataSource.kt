package app.tingyu.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.tingyu.AppContainer
import app.tingyu.model.Track
import java.io.IOException
import java.util.concurrent.ConcurrentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Resolve on the player's loading thread, never the UI thread. No signed URLs are persisted. */
@UnstableApi
class TrackDataSource(
    private val context: Context,
    private val container: AppContainer,
    private val tracks: ConcurrentMap<String, Track>,
    private val http: OkHttpClient,
) : DataSource {
    private var upstream: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    private var sourceChosen = false
    private var localUri: String? = null
    private var boundTrackId: String? = null
    private var boundBitrate: Long? = null
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; upstream?.addTransferListener(transferListener) }
    override fun open(dataSpec: DataSpec): Long {
        check(upstream == null)
        val uri = dataSpec.uri
        if (uri.scheme != "tingyu") throw IOException("不支持的音频来源")
        val bvid = uri.pathSegments.getOrNull(0) ?: throw IOException("音频信息缺失，请重新解析")
        val page = uri.pathSegments.getOrNull(1)?.toIntOrNull() ?: 1
        val track = tracks["$bvid:p$page"] ?: throw IOException("音频信息已过期，请重新解析")
        if (boundTrackId != null && boundTrackId != track.id) throw IOException("播放来源发生变化，请重新播放")
        boundTrackId = track.id
        val sessionAtStart = container.sessions.state.value
        val spec = try { runBlocking(Dispatchers.IO) {
            // A seek reuses byte positions from the original container. Never switch to a
            // newly remuxed download in the middle of this MediaSource's lifetime.
            if (!sourceChosen) { localUri = container.library.saved(track.id)?.uri; sourceChosen = true }
            val savedUri = localUri
            if (savedUri != null) {
                try {
                    context.contentResolver.openAssetFileDescriptor(Uri.parse(savedUri), "r")?.use { }
                        ?: throw IOException("本地音频不存在，请重新下载")
                } catch (_: Exception) { throw IOException("本地音频已移除或无法读取，请重新下载") }
                dataSpec.buildUpon().setUri(savedUri).setHttpRequestHeaders(emptyMap()).build()
            } else {
                val stream = container.bilibili.resolveAudio(track)
                if (container.sessions.state.value != sessionAtStart) throw IOException("访问模式已切换，请重新播放")
                if (boundBitrate != null && boundBitrate != stream.bitrate) throw IOException("音轨格式发生变化，请重新播放")
                boundBitrate = stream.bitrate
                dataSpec.buildUpon().setUri(stream.url)
                    .setHttpRequestHeaders(stream.headers.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) })
                    .build()
            }
        } } catch (e: Exception) { if (e is IOException) throw e else throw IOException("音频解析失败，请重试", e) }
        val source = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http)).createDataSource()
        upstream = source
        listeners.forEach(source::addTransferListener)
        return try { source.open(spec) } catch (e: Exception) { close(); throw IOException(e.message ?: "音频加载失败", e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream?.read(buffer, offset, length) ?: throw IOException("音频尚未打开")
    override fun getUri(): Uri? = upstream?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()
    override fun close() { val source = upstream; upstream = null; source?.close() }
}
