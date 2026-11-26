package app.tingyu.data.music

import app.tingyu.data.bili.ConnectionMetadata
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.download.DownloadRules
import app.tingyu.model.MusicSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

internal object MusicMedia {
    fun permitted(url: HttpUrl, source: MusicSource): Boolean {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        return when (source) {
            MusicSource.NETEASE -> url.host.endsWith(".music.126.net")
            MusicSource.QQ -> url.host.endsWith(".stream.qqmusic.qq.com") || url.host == "aqqmusic.tc.qq.com"
            else -> false
        }
    }

    fun request(value: String, source: MusicSource, offset: Long, etag: String?): Request {
        val url = value.toHttpUrlOrNull()?.takeIf { permitted(it, source) } ?: throw IOException("平台返回了不受支持的音频地址")
        if (offset < 0 || offset > 0 && DownloadRules.strongEtag(etag) == null) throw IOException("续传信息已失效")
        return Request.Builder().url(url).header("User-Agent", UserAgentProfile.DESKTOP.value)
            .header("Referer", if (source == MusicSource.QQ) "https://y.qq.com/" else "https://music.163.com/")
            .header("Accept-Encoding", "identity").header("Range", "bytes=$offset-")
            .apply { if (offset > 0) header("If-Range", requireNotNull(etag)) }
            .tag(ConnectionMetadata::class.java, ConnectionMetadata()).build()
    }
}
