package app.tingyu.data.music

import app.tingyu.data.bili.BiliResponse
import app.tingyu.data.bili.BiliTransport
import app.tingyu.data.bili.OkHttpBiliTransport
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.model.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.UUID

internal class MusicShareRepository(
    private val transport: BiliTransport = OkHttpBiliTransport(),
    private val session: suspend (MusicSource) -> String = { "" },
) {
    suspend fun parse(start: HttpUrl): ParseResult {
        val source = MusicShareLink.platform(start.host) ?: throw IOException("不支持此平台")
        var url = start
        repeat(6) {
            val song = MusicShareLink.direct(url, source)
            if (song != null) {
                val track = when (source) {
                    MusicSource.NETEASE -> {
                        val base = "https://music.163.com/api/song/detail/".toHttpUrl().newBuilder()
                        val data = json(base.addQueryParameter("ids", "[${song.id}]").addQueryParameter("id", song.id).build(), source)
                        if (data.number("code") != 200L) throw IOException("网易云音乐无法获取歌曲资料")
                        val info = data.rows("songs").firstOrNull { it.text("id") == song.id } ?: throw IOException("歌曲不存在或已下架")
                        track(song, info.text("name"), info.rows("artists"), info.number("duration") ?: 0)
                    }
                    MusicSource.QQ -> {
                        val info = qqDetail(song)
                        val mid = info.text("mid")
                        if (!MusicShareLink.qqMid.matches(mid) || (if (song.numericQqId) info.text("id") else mid) != song.id)
                            throw IOException("QQ音乐返回了不同的歌曲，请重新分享")
                        track(SongLink(source, mid), info.text("name"), info.rows("singer"), (info.number("interval") ?: 0) * 1000)
                    }
                    else -> throw IOException("不支持此平台")
                }
                return ParseResult(track.title, listOf(track))
            }
            if (!MusicShareLink.isShort(url)) throw IOException("请分享${source.label}的单曲链接")
            val response = get(url, source)
            val target = when (response.status) {
                in 300..399 -> response.location
                200 -> htmlRedirect.find(response.text)?.groupValues?.get(1)?.replace("&amp;", "&")
                else -> throw IOException("${source.label}分享链接无法打开（HTTP ${response.status}）")
            }
            url = target?.let(url::resolve)?.let(MusicShareLink::secureUrl)?.takeIf { MusicShareLink.permitted(it, source) }
                ?: throw IOException("分享短链未跳转到本站歌曲，请复制官方歌曲页链接")
        }
        throw IOException("分享短链跳转次数过多")
    }

    private fun track(song: SongLink, name: String, artists: List<JsonObject>, duration: Long): Track {
        if (name.isBlank() || duration <= 0) throw IOException("歌曲资料不完整，请在官方歌曲页核对")
        val net = song.source == MusicSource.NETEASE
        return Track((if (net) "netease-" else "qq-") + song.id, song.id.toLongOrNull() ?: 0L, name,
            artist = artists.map { it.text("name") }.filter(String::isNotBlank).joinToString(" / "),
            durationMs = duration, sourceUrl = if (net) "https://music.163.com/song?id=${song.id}" else "https://y.qq.com/n/ryqq/songDetail/${song.id}",
            sourceTitle = name, metadataVersion = 1, source = song.source)
    }

    suspend fun resolve(track: Track): AudioStream {
        val page = track.sourceUrl.toHttpUrlOrNull() ?: throw IOException("歌曲地址无效")
        val song = MusicShareLink.direct(page, track.source) ?: throw IOException("歌曲地址无效")
        val (url, size) = when (song.source) {
            MusicSource.NETEASE -> {
                val endpoint = "https://music.163.com/api/song/enhance/download/url".toHttpUrl().newBuilder()
                    .addQueryParameter("id", song.id).addQueryParameter("br", "128000").build()
                val response = json(endpoint, song.source, session(song.source))
                if (response.number("code") == 301L) throw IOException("网易云音乐要求登录后下载（301），请登录后重试")
                val data = response["data"] as? JsonObject
                if (response.number("code") != 200L || data == null || data.number("code") != 200L ||
                    data.text("id") != song.id || data["freeTrialInfo"]?.let { it !is JsonNull } == true)
                    throw IOException("网易云音乐未授权下载此歌曲，请在官方歌曲页查看权限")
                if (data.text("type").lowercase() != "mp3") throw IOException("平台未提供可保存的 MP3 音源")
                data.text("url") to (data.number("size") ?: -1)
            }
            MusicSource.QQ -> {
                val cookie = session(song.source)
                val info = qqDetail(song, cookie)
                val action = info.obj("action")
                val downloadable = action.number("down_lq")?.let { it == 1L }
                    ?: ((action.number("switch") ?: 0) and 16L != 0L)
                if (info.text("mid") != song.id || !downloadable || info.obj("pay").number("pay_down") != 0L)
                    throw IOException("QQ音乐未开放这首歌的普通音质下载，请在官方歌曲页查看权限")
                val file = info.obj("file")
                val mediaMid = file.text("media_mid").takeIf(MusicShareLink.qqMid::matches) ?: throw IOException("平台未提供完整音频文件")
                val filename = "M500$mediaMid.mp3"
                val uin = cookie.split(';').map(String::trim).firstNotNullOfOrNull {
                    if (it.substringBefore('=') in setOf("qqmusic_uin","uin","wxuin"))
                        it.substringAfter('=').removePrefix("o").toLongOrNull()?.takeIf { n -> n > 0 } else null
                }
                val param = buildJsonObject {
                    put("guid", UUID.randomUUID().leastSignificantBits.toUInt().toString())
                    putJsonArray("songmid") { add(song.id) }
                    putJsonArray("songtype") { add(0) }
                    putJsonArray("filename") { add(filename) }
                    put("uin", (uin ?: 0).toString())
                    put("loginflag", if (uin == null) 0 else 1)
                    put("platform", "20")
                }
                val data = qq("vkey.GetVkeyServer", "CgiGetVkey", param, cookie)
                val entry = data.rows("midurlinfo").singleOrNull()
                val path = entry?.text("purl").orEmpty()
                if (entry?.text("songmid") != song.id || entry.number("result") != 0L || path.isBlank() ||
                    !path.substringBefore('?').substringAfterLast('/').equals(filename, true))
                    throw IOException("QQ音乐未返回可下载的完整音源，请在官方歌曲页查看")
                val media = (data["sip"] as? JsonArray).orEmpty().asSequence().mapNotNull { it.jsonPrimitive.contentOrNull }
                    .mapNotNull { it.toHttpUrlOrNull()?.resolve(path)?.let(MusicShareLink::secureUrl) }.firstOrNull { MusicMedia.permitted(it, song.source) }
                    ?: throw IOException("平台未提供安全的音频地址")
                media.toString() to (file.number("size_128mp3") ?: -1)
            }
            else -> throw IOException("不支持的歌曲来源")
        }
        // Some official replies contain HTTP links. Only contact the HTTPS equivalent.
        val original = url.toHttpUrlOrNull() ?: throw IOException("平台未提供下载地址")
        val media = if (original.scheme == "http" && original.port == 80) original.newBuilder().scheme("https").port(443).build() else original
        if (!MusicMedia.permitted(media, song.source) || size <= 0) throw IOException("平台未提供完整、安全的下载地址")
        return AudioStream(media.toString(), mimeType = "audio/mpeg", expectedDurationMs = track.durationMs,
            expectedBytes = size, source = song.source)
    }

    private suspend fun qqDetail(song: SongLink, cookie: String = "") = qq("music.pf_song_detail_svr", "get_song_detail_yqq", buildJsonObject {
        if (song.numericQqId) put("song_id", song.id.toLong()) else put("song_mid", song.id)
        put("song_type", 0)
    }, cookie).obj("track_info")

    private suspend fun qq(module: String, method: String, param: JsonObject, cookie: String): JsonObject {
        val payload = buildJsonObject {
            putJsonObject("comm") { put("ct", 24); put("format", "json") }
            putJsonObject("song") { put("module",module); put("method",method); put("param",param) }
        }
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder().addQueryParameter("data",payload.toString()).build()
        val root = json(url, MusicSource.QQ, cookie)
        if (root.number("code") != 0L || root.obj("song").number("code") != 0L) throw IOException("QQ音乐暂未提供此歌曲的数据，请稍后重试")
        return root.obj("song").obj("data")
    }

    private suspend fun get(url: HttpUrl, source: MusicSource, cookie: String = ""): BiliResponse {
        val headers = mutableMapOf("User-Agent" to UserAgentProfile.DESKTOP.value,
            "Referer" to if (source == MusicSource.QQ) "https://y.qq.com/" else "https://music.163.com/")
        val accountEndpoint = when (source) {
            MusicSource.NETEASE -> url.host == "music.163.com" && url.encodedPath == "/api/song/enhance/download/url"
            MusicSource.QQ -> url.host == "u.y.qq.com" && url.encodedPath == "/cgi-bin/musicu.fcg"
            else -> false
        }
        if (accountEndpoint && cookie.isNotBlank()) headers["Cookie"] = cookie
        return try { transport.get(url, headers) } catch (e: IOException) { throw IOException("无法连接${source.label}，请检查网络后重试",e) }
    }

    private suspend fun json(url: HttpUrl, source: MusicSource, cookie: String = ""): JsonObject {
        val response = get(url, source, cookie)
        if (response.status !in 200..299) throw IOException("${source.label}请求失败（HTTP ${response.status}）")
        return try { Json.parseToJsonElement(response.text).jsonObject }
        catch (_: IllegalArgumentException) { throw IOException("${source.label}返回了无法识别的歌曲数据") }
    }

    companion object {
        private val htmlRedirect = Regex("""<link\s+[^>]*rel=["']canonical["'][^>]*href=["']([^"'<> ]+)["']""", RegexOption.IGNORE_CASE)
    }
}

private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.rows(key: String) = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
