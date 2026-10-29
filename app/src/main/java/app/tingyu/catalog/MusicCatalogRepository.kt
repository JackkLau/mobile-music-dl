package app.tingyu.catalog

import app.tingyu.lyrics.LrcParser
import app.tingyu.lyrics.LyricsDocument
import app.tingyu.model.Track
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ArtworkCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String,
    val source: String,
    val sourceUrl: String,
    val kind: String = "专辑封面",
)

data class LyricCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val source: String,
    val sourceUrl: String,
)

/** Optional, public catalog lookups. Never shares the audio client's cookies or credentials. */
class MusicCatalogRepository internal constructor(
    private val appVersion: String,
    client: OkHttpClient,
    private val neteaseBase: HttpUrl,
    private val qqBase: HttpUrl,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val requestSpacingMs: Long = 1000,
    private val imageUrlForTest: ((String) -> HttpUrl)? = null,
) {
    constructor(appVersion: String) : this(appVersion, publicClient(),
        "https://music.163.com/".toHttpUrl(), "https://c.y.qq.com/".toHttpUrl())

    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).build()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val cooldown = mutableMapOf<String, Long>()
    private var nextRequestAt = 0L
    // A caller cannot turn image() into an arbitrary URL fetcher, including by copying a candidate.
    private val offered = LinkedHashSet<ArtworkCandidate>()
    private val offeredLyrics = LinkedHashSet<LyricCandidate>()

    suspend fun artwork(track: Track): List<ArtworkCandidate> = mutex.withLock {
        requireIdentity(track)
        var firstFailure: IOException? = null
        val netease = try { neteaseArtwork(track) }
        catch (error: IOException) { firstFailure = error; emptyList() }
        val candidates = if (netease.isNotEmpty()) netease else try { qqArtwork(track) }
        catch (error: IOException) { throw firstFailure ?: error }
        if (candidates.isEmpty() && firstFailure != null) throw firstFailure
        val result = candidates.distinctBy { it.imageUrl }.take(MAX_CANDIDATES)
        offered.addAll(result)
        while (offered.size > 64) offered.remove(offered.first())
        result
    }

    suspend fun lyrics(track: Track): LyricsDocument? = mutex.withLock {
        requireIdentity(track)
        val durationKnown = track.durationMs in 1..3_600_000
        val songs = neteaseSongs(track).filter { song ->
            !durationKnown || song.long("duration")?.let { abs(it - track.durationMs) <= 2000 } == true
        }
        // Two releases/recordings can share a title and length. Let the user import instead of guessing.
        val song = songs.singleOrNull() ?: return@withLock null
        val id = song.long("id") ?: return@withLock null
        fetchLyrics(id, durationKnown)
    }

    suspend fun lyricCandidates(track: Track): List<LyricCandidate> = mutex.withLock {
        requireIdentity(track)
        val result = neteaseSongs(track).take(MAX_CANDIDATES).mapNotNull { song ->
            val id = song.long("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            LyricCandidate("netease:song:$id", track.title, track.artist, song.obj("album").string("name"),
                song.long("duration")?.coerceAtLeast(0) ?: 0, "网易云音乐", "https://music.163.com/song?id=$id")
        }
        offeredLyrics.addAll(result)
        while (offeredLyrics.size > 64) offeredLyrics.remove(offeredLyrics.first())
        result
    }

    /** Explicit version selection; callers explain/adjust any timing difference before saving. */
    suspend fun lyrics(candidate: LyricCandidate): LyricsDocument? = mutex.withLock {
        if (candidate !in offeredLyrics) throw IOException("歌词版本选择已失效，请重新查找")
        val id = candidate.id.removePrefix("netease:song:").toLongOrNull()?.takeIf { it > 0 }
            ?: throw IOException("歌词版本不可用，请重新选择")
        fetchLyrics(id, candidate.durationMs in 1..3_600_000)
    }

    private suspend fun fetchLyrics(id: Long, durationKnown: Boolean): LyricsDocument? {
        val root = getJson(neteaseBase.newBuilder().addPathSegments("api/song/lyric")
            .addQueryParameter("id", id.toString()).addQueryParameter("lv", "-1")
            .addQueryParameter("tv", "-1").build(), "网易云音乐") ?: return null
        businessCode(root, 200, "网易云音乐")
        val instrumental = root.boolean("nolyric") == true
        if (root.boolean("uncollected") == true && !instrumental) return null
        val original = root.obj("lrc").string("lyric")
        val translation = root.obj("tlyric").string("lyric")
        val combined = listOf(original, translation).filter(String::isNotBlank).joinToString("\n")
        if (combined.toByteArray().size > LrcParser.MAX_BYTES) throw IOException("歌词内容过大，请改用本地歌词文件")
        // Each language may carry its own offset tag. Parse separately before merging clocks.
        val lines = try {
            val separate = LrcParser.parse(original) + LrcParser.parse(translation)
            require(separate.size <= LrcParser.MAX_LINES)
            separate.sortedBy { it.timeMs }.groupBy { it.timeMs }.map { (time, values) ->
                app.tingyu.lyrics.LyricLine(time, values.map { it.text }.distinct().joinToString("\n"))
            }
        }
        catch (_: IllegalArgumentException) { throw IOException("歌词内容过多，请改用本地歌词文件") }
        val plain = if (lines.isNotEmpty()) lines.joinToString("\n") { it.text }
            else original.lineSequence().filterNot { it.trim().matches(Regex("^\\[[^]]*]$")) }.joinToString("\n").trim()
        if (plain.lineSequence().take(LrcParser.MAX_LINES + 1).count() > LrcParser.MAX_LINES) throw IOException("歌词行数过多，请改用本地歌词文件")
        if (plain.contains(Regex("(?i)<(?:!doctype|html|script|body)[\\s>]"))) throw IOException("歌词服务返回内容无法识别")
        if (!instrumental && plain.isBlank()) return null
        val synced = if (durationKnown && lines.isNotEmpty()) lines.joinToString("\n") { line ->
            if (line.timeMs > 59_999_999) throw IOException("歌词时间标记无法识别")
            val tag = String.format(Locale.ROOT, "[%02d:%02d.%03d]", line.timeMs / 60_000, line.timeMs / 1000 % 60, line.timeMs % 1000)
            line.text.lines().joinToString("\n") { "$tag$it" }
        } else ""
        if (synced.toByteArray().size > LrcParser.MAX_BYTES) throw IOException("歌词内容过大，请改用本地歌词文件")
        return LyricsDocument(plainText = plain, syncedText = synced,
            instrumental = instrumental, source = "网易云音乐", sourceUrl = "https://music.163.com/song?id=$id")
    }

    suspend fun image(candidate: ArtworkCandidate): ByteArray = mutex.withLock {
        if (candidate !in offered) throw IOException("封面选择已失效，请重新查找")
        val validated = officialImage(candidate.imageUrl) ?: throw IOException("封面地址不可用，请重新选择")
        val target = imageUrlForTest?.invoke(validated) ?: validated.toHttpUrl()
        val result = get(target, "封面", MAX_IMAGE_BYTES)
        if (result.status !in 200..299) throw statusError("封面", result.status)
        val type = result.contentType.substringBefore(';').lowercase(Locale.ROOT)
        if (type !in setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/avif")) throw IOException("封面返回内容不是图片")
        if (!hasImageSignature(result.bytes)) throw IOException("封面图片无法识别")
        result.bytes
    }

    private suspend fun neteaseSongs(track: Track): List<JsonObject> {
        val search = getJson(neteaseBase.newBuilder().addPathSegments("api/search/get/web")
            .addQueryParameter("s", "${track.title} ${track.artist}").addQueryParameter("type", "1")
            .addQueryParameter("limit", "20").addQueryParameter("offset", "0").build(), "网易云音乐") ?: return emptyList()
        businessCode(search, 200, "网易云音乐")
        val ids = search.obj("result").array("songs").mapNotNull { it as? JsonObject }
            .filter { matchesNetease(it, track) }.mapNotNull { it.long("id")?.takeIf { id -> id > 0 } }.distinct().take(20)
        if (ids.isEmpty()) return emptyList()
        val details = getJson(neteaseBase.newBuilder().addPathSegments("api/song/detail")
            .addQueryParameter("ids", ids.joinToString(",", "[", "]")).build(), "网易云音乐") ?: return emptyList()
        businessCode(details, 200, "网易云音乐")
        return details.array("songs").mapNotNull { it as? JsonObject }
            .filter { it.long("id") in ids && matchesNetease(it, track) }.distinctBy { it.long("id") }
    }

    private suspend fun neteaseArtwork(track: Track): List<ArtworkCandidate> {
        val songs = neteaseSongs(track)
        if (songs.isEmpty()) return emptyList()
        val albums = songs.mapNotNull { song ->
            val album = song.obj("album")
            val image = officialImage(album.string("picUrl")) ?: return@mapNotNull null
            val albumId = album.long("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            ArtworkCandidate("netease:album:$albumId", track.title, track.artist, album.string("name"), image,
                "网易云音乐", "https://music.163.com/album?id=$albumId")
        }.distinctBy { it.id }.take(MAX_CANDIDATES - 1)
        val artists = songs.flatMap { it.array("artists") }.mapNotNull { it as? JsonObject }
            .filter { identity(it.string("name")) == identity(track.artist) }
            .mapNotNull { it.long("id")?.takeIf { id -> id > 0 } }.distinct()
        val artistId = artists.singleOrNull() ?: return albums
        val artist = try {
            val root = getJson(neteaseBase.newBuilder().addPathSegments("api/artist/$artistId").build(), "网易云音乐")
                ?: return albums
            businessCode(root, 200, "网易云音乐")
            root.obj("artist")
        } catch (_: IOException) { return albums }
        if (artist.long("id") != artistId || identity(artist.string("name")) != identity(track.artist)) return albums
        val image = officialImage(artist.string("img1v1Url")) ?: return albums
        return albums + ArtworkCandidate("netease:artist:$artistId", track.title, track.artist, "", image,
            "网易云音乐", "https://music.163.com/artist?id=$artistId", "歌手照片")
    }

    private suspend fun qqArtwork(track: Track): List<ArtworkCandidate> {
        val root = getJson(qqBase.newBuilder().addPathSegments("soso/fcgi-bin/client_search_cp")
            .addQueryParameter("w", "${track.title} ${track.artist}").addQueryParameter("format", "json")
            .addQueryParameter("p", "1").addQueryParameter("n", "20").build(), "QQ 音乐") ?: return emptyList()
        businessCode(root, 0, "QQ 音乐")
        val songs = root.obj("data").obj("song").array("list").mapNotNull { it as? JsonObject }
            .filter { identity(it.string("songname")) == identity(track.title) && artistIdentity(it.array("singer")) == identity(track.artist) }
        val albums = songs.mapNotNull { song ->
            val mid = song.string("albummid").takeIf(::validMid) ?: return@mapNotNull null
            ArtworkCandidate("qq:album:$mid", track.title, track.artist, song.string("albumname"),
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg", "QQ 音乐",
                "https://y.qq.com/n/ryqq_v2/albumDetail/$mid")
        }.distinctBy { it.id }.take(MAX_CANDIDATES - 1)
        val artistMids = songs.flatMap { it.array("singer") }.mapNotNull { it as? JsonObject }
            .filter { identity(it.string("name")) == identity(track.artist) }.map { it.string("mid") }
            .filter(::validMid).distinct()
        val mid = artistMids.singleOrNull() ?: return albums
        return albums + ArtworkCandidate("qq:artist:$mid", track.title, track.artist, "",
            "https://y.gtimg.cn/music/photo_new/T001R500x500M000$mid.jpg", "QQ 音乐",
            "https://y.qq.com/n/ryqq_v2/singer/$mid", "歌手照片")
    }

    private suspend fun getJson(url: HttpUrl, source: String): JsonObject? {
        val result = get(url, source, MAX_JSON_BYTES)
        if (result.status == 404) return null
        if (result.status !in 200..299) throw statusError(source, result.status)
        // QQ's first-party JSON endpoint uses application/x-javascript; parse JSON only, never JSONP.
        if (result.contentType.substringBefore(';').lowercase(Locale.ROOT) !in setOf("application/json", "application/x-javascript", "text/javascript")) {
            throw IOException("${source}返回内容无法识别，请稍后重试")
        }
        return try { json.parseToJsonElement(result.bytes.toString(Charsets.UTF_8)).jsonObject }
        catch (_: Exception) { throw IOException("${source}返回内容无法识别，请稍后重试") }
    }

    private data class Result(val status: Int, val contentType: String, val retryAfter: String?, val bytes: ByteArray)

    private suspend fun get(url: HttpUrl, source: String, maxBytes: Long): Result {
        val now = nowMillis()
        if (now < (cooldown[url.host] ?: 0L)) throw IOException("${source}请求较多，请稍后重试")
        delay((nextRequestAt - now).coerceAtLeast(0))
        val result = try { request(url, source, maxBytes) }
        finally { nextRequestAt = nowMillis() + requestSpacingMs.coerceAtLeast(0) }
        if (result.status == 429 || result.status == 503) {
            cooldown[url.host] = safeDeadline(retryDelay(result.retryAfter))
            throw IOException("${source}暂时繁忙，请稍后重试")
        }
        return result
    }

    private suspend fun request(url: HttpUrl, source: String, maxBytes: Long): Result = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "Tingyu/$appVersion (https://github.com/JackkLau/mobile-music-dl)")
            .header("Accept", if (maxBytes == MAX_IMAGE_BYTES) "image/jpeg,image/png,image/webp,image/avif,image/gif" else "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("无法连接${source}，请检查网络后重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val bytes = if (it.isSuccessful) {
                            val body = it.body ?: throw IOException()
                            if (body.contentLength() > maxBytes) throw IOException()
                            val input = body.source()
                            if (input.request(maxBytes + 1)) throw IOException()
                            input.readByteArray()
                        } else byteArrayOf()
                        val result = Result(it.code, it.header("Content-Type").orEmpty(), it.header("Retry-After"), bytes)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("${source}内容过大或读取失败，请稍后重试"))
                }
            }
        })
    }

    private fun retryDelay(value: String?): Long {
        value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
        return try { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis()).coerceAtLeast(1000) }
        catch (_: Exception) { 60_000 }
    }
    private fun safeDeadline(delay: Long): Long = nowMillis().let { now -> if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay }
    private fun businessCode(root: JsonObject, success: Int, source: String) {
        if (root.long("code") != success.toLong()) throw IOException("${source}查询未完成，请稍后重试")
    }
    private fun statusError(source: String, status: Int) = IOException("${source}请求未完成（HTTP $status），请稍后重试")
    private fun requireIdentity(track: Track) {
        if (track.title.isBlank() || track.artist.isBlank()) throw IOException("请先补全歌名和歌手，再联网查找")
        if (track.title.length > 200 || track.artist.length > 200) throw IOException("歌名或歌手过长，请先修改歌曲信息")
    }
    private fun matchesNetease(song: JsonObject, track: Track) = identity(song.string("name")) == identity(track.title) && artistIdentity(song.array("artists")) == identity(track.artist)
    private fun artistIdentity(artists: JsonArray) = identity(artists.mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) }.joinToString(" / "))
    private fun identity(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Z}]+"), " ")
    private fun validMid(value: String) = value.matches(Regex("[A-Za-z0-9]{14}"))
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

    companion object {
        private const val MAX_CANDIDATES = 8
        private const val MAX_JSON_BYTES = 512L * 1024
        private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

        internal fun officialImage(value: String): String? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (!url.host.matches(Regex("p[1-9][0-9]?\\.music\\.126\\.net")) && url.host != "y.gtimg.cn") return null
            return when {
                url.scheme == "https" && url.port == 443 -> url.toString()
                url.scheme == "http" && url.port == 80 -> url.newBuilder().scheme("https").port(443).build().toString()
                else -> null
            }
        }

        internal fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            return if (bytes.size == 16) bytes[0] and 0xfe != 0xfc
            else bytes[0] != 0 && bytes[0] < 224 && !(bytes[0] == 100 && bytes[1] in 64..127)
        }

        private fun publicClient() = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("Non-public destination")
                }
            })
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()

        private fun hasImageSignature(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val unsigned = bytes.take(12).map { it.toInt() and 255 }
            return (unsigned[0] == 0xff && unsigned[1] == 0xd8 && unsigned[2] == 0xff) ||
                unsigned.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10) ||
                bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") ||
                (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") ||
                (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) in setOf("avif", "avis"))
        }
    }
}
