package app.tingyu.data.music

import app.tingyu.data.bili.BiliLinkParser
import app.tingyu.model.MusicSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

internal data class SongLink(val source: MusicSource, val id: String, val numericQqId: Boolean = false)

internal object MusicShareLink {
    private val netHosts = setOf("music.163.com", "y.music.163.com", "m.music.163.com", "163cn.tv")
    private val qqHosts = setOf("y.qq.com", "i.y.qq.com", "i2.y.qq.com", "c.y.qq.com", "c6.y.qq.com", "c7.y.qq.com", "c8.y.qq.com", "c9.y.qq.com", "c6y.qq.com", "url.cn")
    private val digits = Regex("[1-9][0-9]{0,17}")
    val qqMid = Regex("[0-9A-Za-z]{14}")

    fun platform(host: String): MusicSource? = when (host) {
        in netHosts -> MusicSource.NETEASE
        in qqHosts -> MusicSource.QQ
        else -> null
    }

    fun first(text: String): HttpUrl? = BiliLinkParser.shareUrls(text).asSequence()
        .mapNotNull { it.toHttpUrlOrNull() }.firstOrNull { platform(it.host) != null }?.let(::secureUrl)

    fun secureUrl(url: HttpUrl): HttpUrl = if (url.scheme == "http" && url.port == 80)
        url.newBuilder().scheme("https").port(443).build() else url

    fun permitted(url: HttpUrl, source: MusicSource): Boolean = url.isHttps && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && platform(url.host) == source

    fun direct(url: HttpUrl, source: MusicSource): SongLink? {
        if (!permitted(url, source)) throw IOException("分享链接的域名、协议或端口不受支持")
        if (source == MusicSource.NETEASE) {
            val fragment = url.encodedFragment.orEmpty()
            val page = if (fragment.startsWith("/song?")) ("https://music.163.com" + fragment).toHttpUrl() else url
            if (page.encodedPath.trimEnd('/') in setOf("/song", "/m/song")) {
                val id = page.queryParameter("id").orEmpty()
                if (digits.matches(id)) return SongLink(source, id)
            }
        } else {
            val parts = url.pathSegments.filter { it.isNotEmpty() }
            val id = when {
                parts.size >= 2 && parts[parts.lastIndex - 1].lowercase() in setOf("song", "songdetail") ->
                    parts.last().removeSuffix(".html")
                parts.takeLast(2) == listOf("playsong", "index.html") ||
                    parts.lastOrNull() in setOf("playsong.html", "playsong", "songDetail", "singleSong.html") ->
                    url.queryParameter("songmid") ?: url.queryParameter("songMid") ?: url.queryParameter("mid")
                    ?: url.queryParameter("songid") ?: url.queryParameter("songId") ?: url.queryParameter("id")
                else -> null
            }
            if (id != null && (qqMid.matches(id) || digits.matches(id))) return SongLink(source, id, !qqMid.matches(id))
        }
        return null
    }

    fun isShort(url: HttpUrl): Boolean = when (platform(url.host)) {
        MusicSource.NETEASE -> url.host == "163cn.tv" || url.encodedPath.startsWith("/m/")
            && url.pathSegments.filter(String::isNotEmpty).size == 2 && url.pathSegments[1] != "song"
        MusicSource.QQ -> url.host == "url.cn" || url.host == "c6y.qq.com" ||
            url.encodedPath.startsWith("/base/fcgi-bin/u") || url.encodedPath.startsWith("/cgi-bin/music_get_jump_url")
        else -> false
    }
}
