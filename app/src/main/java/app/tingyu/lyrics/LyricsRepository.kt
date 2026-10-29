package app.tingyu.lyrics

import app.tingyu.model.Track
import app.tingyu.catalog.LyricCandidate
import java.io.IOException
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Exact automatic lookup plus explicit version discovery, isolated from audio credentials. */
class LyricsRepository internal constructor(
    private val appVersion: String,
    client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitterMillis: (Long) -> Long = { delay -> Random.nextLong(1, (delay / 10).coerceAtLeast(2)) },
) {
    constructor(appVersion: String) : this(appVersion, OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
        "https://lrclib.net/".toHttpUrl())

    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).build()
    private val mutex = Mutex()
    private val offeredLyrics = LinkedHashSet<LyricCandidate>()
    private var nextRequestAt = 0L
    private var retryAfter = 0L
    private var retryStatus: Int? = null
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(track: Track): LyricsDocument? = mutex.withLock {
        if (track.title.isBlank() || track.artist.isBlank()) throw IOException("请先补全歌名和歌手，再查找歌词")
        val seconds = track.durationMs / 1000.0
        val durationKnown = seconds in 1.0..3600.0
        val url = baseUrl.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artist)
            .apply { if (durationKnown) addQueryParameter("duration", seconds.toString()) }.build()
        val value = requestJson(url) ?: return@withLock null
        val root = value as? JsonObject ?: throw malformed()
        if (identity(root.string("trackName")) != identity(track.title) || identity(root.string("artistName")) != identity(track.artist)) return@withLock null
        val returnedDuration = (root["duration"] as? JsonPrimitive)?.doubleOrNull
        if (durationKnown && (returnedDuration == null || !returnedDuration.isFinite() || abs(seconds - returnedDuration) > 2.0)) return@withLock null
        document(root, durationKnown)
    }

    /** Search is discovery: versions and duration differences stay visible for user confirmation. */
    suspend fun candidates(track: Track): List<LyricCandidate> = mutex.withLock {
        if (track.title.isBlank()) throw IOException("请先填写歌名，再查找歌词")
        if (track.title.length > 200 || track.artist.length > 200) throw IOException("歌名或歌手过长，请缩短搜索词")
        val url = baseUrl.newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", track.title.trim())
            .apply { if (track.artist.isNotBlank()) addQueryParameter("artist_name", track.artist.trim()) }.build()
        val value = requestJson(url) ?: return@withLock emptyList()
        val rows = value as? JsonArray ?: throw malformed()
        val result = rows.take(20).mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val id = row.id() ?: return@mapNotNull null
            val title = row.string("trackName").takeIf { it.isNotBlank() && it.length <= 200 } ?: return@mapNotNull null
            if (row.string("plainLyrics").isBlank() && row.string("syncedLyrics").isBlank() && (row["instrumental"] as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
            val duration = (row["duration"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it.isFinite() && it in 0.001..3600.0 }?.let { (it * 1000).toLong() } ?: 0
            LyricCandidate("lrclib:song:$id", title, row.string("artistName"), row.string("albumName"), duration,
                "LRCLIB", "https://lrclib.net/api/get/$id")
        }.distinctBy { it.id }.sortedWith(compareBy<LyricCandidate> { identity(it.title) != identity(track.title) }
            .thenBy { track.artist.isNotBlank() && identity(it.artist) != identity(track.artist) }
            .thenBy { if (track.durationMs > 0 && it.durationMs > 0) abs(it.durationMs - track.durationMs) else Long.MAX_VALUE })
        offeredLyrics.addAll(result)
        while (offeredLyrics.size > 64) offeredLyrics.remove(offeredLyrics.first())
        result
    }

    suspend fun lyrics(candidate: LyricCandidate): LyricsDocument? = mutex.withLock {
        if (candidate !in offeredLyrics) throw IOException("歌词版本选择已失效，请重新查找")
        val id = candidate.id.removePrefix("lrclib:song:").toLongOrNull()?.takeIf { it > 0 }
            ?: throw IOException("歌词版本不可用，请重新选择")
        val value = requestJson(baseUrl.newBuilder().addPathSegments("api/get/$id").build()) ?: return@withLock null
        val root = value as? JsonObject ?: throw malformed()
        if (root.id() != id || identity(root.string("trackName")) != identity(candidate.title) ||
            identity(root.string("artistName")) != identity(candidate.artist)) throw malformed()
        document(root, candidate.durationMs in 1..3_600_000)
    }

    private suspend fun requestJson(url: HttpUrl): JsonElement? {
        val now = nowMillis()
        if (now < retryAfter) throw LyricsSourceException("LRCLIB", "请求等待", "服务冷却中，请在 ${((retryAfter - now + 999) / 1000)} 秒后重试", httpStatus = retryStatus)
        delay((nextRequestAt - now).coerceAtLeast(0))
        val response = try { get(url) }
        finally { nextRequestAt = nowMillis() + 250 }
        when (response.status) {
            404 -> return null
            429, 503 -> {
                val pause = retryDelay(response.retryAfter).coerceAtLeast(1000)
                retryAfter = safeDeadline(pause, jitterMillis(pause).coerceAtLeast(0))
                retryStatus = response.status
                throw LyricsSourceException("LRCLIB", "请求", if (response.status == 429) "请求较多，请稍后重试" else "服务暂时繁忙，请稍后重试", httpStatus = response.status)
            }
            !in 200..299 -> throw LyricsSourceException("LRCLIB", "请求", "查询未完成，请稍后重试", httpStatus = response.status)
        }
        if (!response.contentType.substringBefore(';').trim().equals("application/json", true)) throw malformed()
        return try { json.parseToJsonElement(response.body) }
        catch (_: Exception) { throw malformed() }
    }

    private fun document(root: JsonObject, durationKnown: Boolean): LyricsDocument? {
        val instrumental = (root["instrumental"] as? JsonPrimitive)?.booleanOrNull == true
        val plain = root.string("plainLyrics")
        val synced = root.string("syncedLyrics")
        if (plain.toByteArray(Charsets.UTF_8).size > LrcParser.MAX_BYTES || synced.toByteArray(Charsets.UTF_8).size > LrcParser.MAX_BYTES) {
            throw LyricsSourceException("LRCLIB", "内容", "歌词内容过大，请改用较短的本地歌词文件")
        }
        if (plain.lineSequence().take(LrcParser.MAX_LINES + 1).count() > LrcParser.MAX_LINES) {
            throw LyricsSourceException("LRCLIB", "内容", "歌词行数过多，请改用较短的本地歌词文件")
        }
        if (listOf(plain, synced).any { it.contains(Regex("(?i)<(?:!doctype|html|script|body)[\\s>]")) }) throw malformed()
        val lines = try { LrcParser.parse(synced) }
        catch (_: IllegalArgumentException) { throw LyricsSourceException("LRCLIB", "内容", "歌词时间标记过多，请改用本地歌词文件") }
        if (!instrumental && plain.isBlank() && lines.none { it.text.isNotBlank() }) return null
        return LyricsDocument(
            plainText = plain.ifBlank { lines.joinToString("\n") { it.text } },
            syncedText = synced.takeIf { durationKnown && lines.isNotEmpty() }.orEmpty(),
            instrumental = instrumental,
            source = "LRCLIB",
            sourceUrl = root.id()?.let { "https://lrclib.net/api/get/$it" }.orEmpty(),
        )
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.id() = (this["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    private fun malformed() = LyricsSourceException("LRCLIB", "内容", "返回内容无法识别，请稍后重试")
    private fun safeDeadline(pause: Long, jitter: Long): Long {
        val delay = if (pause > Long.MAX_VALUE - jitter) Long.MAX_VALUE else pause + jitter
        val now = nowMillis()
        return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }

    private fun retryDelay(value: String?): Long {
        value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let {
            return it.coerceAtMost(Long.MAX_VALUE / 2000) * 1000
        }
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                .minus(nowMillis()).coerceAtLeast(1000)
        } catch (_: Exception) { 60_000 }
    }

    private data class HttpResult(val status: Int, val contentType: String, val retryAfter: String?, val body: String)

    private suspend fun get(url: HttpUrl): HttpResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("User-Agent", "Weile/$appVersion (https://github.com/JackkLau/mobile-music-dl)")
            .header("Accept", "application/json").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(LyricsSourceException("LRCLIB", "连接", "无法连接，请检查网络后重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        // Read bodies only on success; status and Retry-After suffice for failures.
                        val body = if (response.isSuccessful) {
                            val source = response.body?.source()
                            if (source != null && source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("歌词响应过大，请稍后重试")
                            source?.readUtf8().orEmpty()
                        } else ""
                        val result = HttpResult(response.code, response.header("Content-Type").orEmpty(), response.header("Retry-After"), body)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(LyricsSourceException("LRCLIB", "读取", "读取歌词失败，请稍后重试"))
                }
            }
        })
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 512L * 1024
        private fun identity(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Z}]+"), " ")
    }
}
