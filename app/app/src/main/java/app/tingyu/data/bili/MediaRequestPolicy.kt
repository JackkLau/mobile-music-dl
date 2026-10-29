package app.tingyu.data.bili

import app.tingyu.download.DownloadRules
import app.tingyu.model.AudioStream
import java.io.IOException
import java.net.Inet6Address
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** One public-header policy for downloads and the same-address UA comparison. */
object MediaRequestPolicy {
    fun request(
        url: String,
        stream: AudioStream,
        ua: UserAgentProfile,
        offset: Long = 0,
        etag: String? = null,
        sampleBytes: Int? = null,
    ): Request {
        if (!DownloadRules.permittedMediaUrl(url)) throw IOException("音频地址不受支持，请重新解析")
        require(offset >= 0)
        if (offset > 0 && DownloadRules.strongEtag(etag) == null) throw IOException("续传信息已失效，请重新下载")
        require(sampleBytes == null || sampleBytes in 1..1024 && offset == 0L)
        val source = stream.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val ref = source?.toHttpUrlOrNull()?.takeIf {
            it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                it.host in setOf("www.bilibili.com", "m.bilibili.com", "bilibili.com") &&
                it.pathSegments.firstOrNull() == "video"
        } ?: throw IOException("视频来源信息已失效，请重新解析")
        // Do not recompose the signed media URL or copy credentials from stream.headers.
        return Request.Builder().url(url)
            .header("User-Agent", ua.value)
            .header("Referer", ref.toString())
            .header("Origin", "https://${ref.host}")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Range", if (sampleBytes != null) "bytes=0-${sampleBytes - 1}" else "bytes=$offset-")
            .apply { if (offset > 0) header("If-Range", etag!!) }
            .tag(ConnectionMetadata::class.java, ConnectionMetadata())
            .build()
    }

    fun looksLikeMedia(contentType: String?): Boolean {
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        return type.isNotEmpty() && (type.startsWith("audio/") || type in setOf("video/mp4", "application/octet-stream", "application/mp4"))
    }

    fun containsMp4Header(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val type = String(bytes, 4, 4, Charsets.US_ASCII)
        if (type !in setOf("ftyp", "styp", "moov", "sidx", "moof", "mdat", "free")) return false
        fun unsignedLong(start: Int, count: Int): Long = (start until start + count)
            .fold(0L) { value, index -> (value shl 8) or (bytes[index].toLong() and 255L) }
        val size32 = unsignedLong(0, 4)
        val headerBytes = if (size32 == 1L) 16 else 8
        val minimum = headerBytes + if (type == "ftyp" || type == "styp") 8 else 0
        if (bytes.size < minimum) return false
        val declaredSize = if (size32 == 1L) unsignedLong(8, 8) else size32
        return size32 == 0L || declaredSize >= minimum
    }

    fun validSample(status: Int, contentType: String?, contentRange: String?, bytes: ByteArray): Boolean {
        val validRange = status != 206 || DownloadRules.parseContentRange(contentRange)?.let {
            it.start == 0L && it.end < 1024 && it.end + 1 == bytes.size.toLong()
        } == true
        return status in listOf(200, 206) && bytes.size in 8..1024 && validRange &&
            looksLikeMedia(contentType) && containsMp4Header(bytes)
    }
}

class ConnectionMetadata {
    @Volatile var family: String = "unknown"
    @Volatile var proxy: String = "unknown"
}

/** Report only protocol family and proxy type; never store addresses or credentials. */
class NetworkConnectionListener : EventListener() {
    override fun connectionAcquired(call: Call, connection: Connection) {
        val metadata = call.request().tag(ConnectionMetadata::class.java) ?: return
        val route = connection.route()
        metadata.family = if (route.socketAddress.address is Inet6Address) "ipv6" else if (route.socketAddress.address != null) "ipv4" else "unknown"
        metadata.proxy = when (route.proxy.type()) { Proxy.Type.DIRECT -> "direct"; Proxy.Type.HTTP -> "http"; Proxy.Type.SOCKS -> "socks" }
    }
}
