package app.tingyu.lyrics

import app.tingyu.model.Track
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
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Explicit, exact lookup only. This client never receives the video platform's session cookies. */
class LyricsRepository internal constructor(
    private val appVersion: String,
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val jitterMillis: (Long) -> Long = { delay -> Random.nextLong(1, (delay / 10).coerceAtLeast(2)) },
) {
    constructor(appVersion: String) : this(appVersion, OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
        "https://lrclib.net/".toHttpUrl())

    private val mutex = Mutex()
    private var nextRequestAt = 0L
    private var retryAfter = 0L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(track: Track): LyricsDocument? = mutex.withLock {
        if (track.title.isBlank() || track.artist.isBlank()) throw IOException("请先补全歌名和歌手，再查找歌词")
        val now = nowMillis()
        if (now < retryAfter) throw IOException("歌词服务请求较多，请在 ${((retryAfter - now + 999) / 1000)} 秒后重试")
        delay((nextRequestAt - now).coerceAtLeast(0))
        val seconds = track.durationMs / 1000.0
        val durationKnown = seconds in 1.0..3600.0
        val url = baseUrl.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artist)
            .apply { if (durationKnown) addQueryParameter("duration", seconds.toString()) }.build()
        val response = try { get(url) }
        finally { nextRequestAt = nowMillis() + 250 }
        when (response.status) {
            404 -> return@withLock null
            429, 503 -> {
                val pause = retryDelay(response.retryAfter).coerceAtLeast(1000)
                retryAfter = nowMillis() + pause + jitterMillis(pause).coerceAtLeast(0)
                throw IOException(if (response.status == 429) "歌词服务请求较多，请稍后重试" else "歌词服务暂时繁忙，请稍后重试")
            }
            !in 200..299 -> throw IOException("歌词查询未完成（HTTP ${response.status}），请稍后重试")
        }
        if (!response.contentType.substringBefore(';').trim().equals("application/json", true)) {
            throw IOException("歌词服务返回内容无法识别，请稍后重试")
        }
        val root = try { json.parseToJsonElement(response.body).jsonObject }
        catch (_: Exception) { throw IOException("歌词服务返回内容无法识别，请稍后重试") }
        fun string(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (identity(string("trackName")) != identity(track.title) || identity(string("artistName")) != identity(track.artist)) {
            return@withLock null
        }
        val returnedDuration = (root["duration"] as? JsonPrimitive)?.doubleOrNull
        if (durationKnown && (returnedDuration == null || !returnedDuration.isFinite() || abs(seconds - returnedDuration) > 2.0)) return@withLock null
        val instrumental = (root["instrumental"] as? JsonPrimitive)?.booleanOrNull == true
        val plain = string("plainLyrics")
        val synced = string("syncedLyrics")
        if (plain.toByteArray(Charsets.UTF_8).size > LrcParser.MAX_BYTES || synced.toByteArray(Charsets.UTF_8).size > LrcParser.MAX_BYTES) {
            throw IOException("歌词内容过大，请改用较短的本地歌词文件")
        }
        if (plain.lineSequence().take(LrcParser.MAX_LINES + 1).count() > LrcParser.MAX_LINES) {
            throw IOException("歌词行数过多，请改用较短的本地歌词文件")
        }
        val lines = try { LrcParser.parse(synced) }
        catch (_: IllegalArgumentException) { throw IOException("歌词时间标记过多，请改用本地歌词文件") }
        if (!instrumental && plain.isBlank() && lines.none { it.text.isNotBlank() }) return@withLock null
        val id = (root["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        LyricsDocument(
            plainText = plain.ifBlank { lines.joinToString("\n") { it.text } },
            syncedText = synced.takeIf { durationKnown && lines.isNotEmpty() }.orEmpty(),
            instrumental = instrumental,
            source = "LRCLIB",
            sourceUrl = id?.let { "https://lrclib.net/api/get/$it" }.orEmpty(),
        )
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
            .header("User-Agent", "Tingyu/$appVersion (https://github.com/JackkLau/mobile-music-dl)")
            .header("Accept", "application/json").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("无法连接歌词服务，请检查网络后重试"))
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
                    if (continuation.isActive) continuation.resumeWithException(IOException("读取歌词失败，请稍后重试"))
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
