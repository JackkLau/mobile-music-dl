package app.tingyu.data.bili

import android.util.Log
import app.tingyu.data.auth.SessionStore
import app.tingyu.model.Account
import app.tingyu.model.AudioStream
import app.tingyu.model.ParseResult
import app.tingyu.model.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Bilibili web adapter. Requests use the user's selected mode; no service proxy,
 * stored media URL, fabricated successful response, or access-control fallback.
 */
class BilibiliRepository internal constructor(
    private val cookies: () -> String,
    private val transport: BiliTransport,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val profileProvider: () -> NetworkProfile = { NetworkProfile() },
    private val logSink: (String) -> Unit = { Log.i("TingYuDownload", it) },
) {
    constructor(sessions: SessionStore, profileProvider: () -> NetworkProfile = { NetworkProfile() }) :
        this(sessions::cookieHeader, OkHttpBiliTransport(), profileProvider = profileProvider)

    fun profileSnapshot(): NetworkProfile = profileProvider()

    /** Current logging gate is checked again so switching diagnostics off stops active jobs' output. */
    fun log(profile: NetworkProfile, requestId: String, fields: Map<String, String>, url: String? = null) {
        if (profile.loggingEnabled && profileProvider().loggingEnabled) logSink(NetworkTrace.line(requestId, fields, url))
    }

    private val keyMutex = Mutex()
    private var key: String? = null
    private var keyExpires: Long = 0

    suspend fun parse(input: String): ParseResult {
        val cookie = cookies()
        val profile = profileSnapshot()
        val requestId = NetworkTrace.requestId()
        val link = expand(BiliLinkParser.parse(input), profile, requestId)
        val result = when (link) {
            is BiliLink.Video -> video(link, cookie, profile, requestId)
            is BiliLink.Favorite -> favorites(link.id, 1, cookie, profile, requestId)
            is BiliLink.Short -> throw BiliException("短链接未能转换为视频或收藏夹链接")
        }
        checkSession(cookie)
        return result
    }

    suspend fun loadFavorites(id: Long, page: Int): ParseResult {
        if (id <= 0 || page < 1) throw BiliException("收藏夹分页参数不正确")
        val cookie = cookies()
        return favorites(id, page, cookie, profileSnapshot(), NetworkTrace.requestId()).also { checkSession(cookie) }
    }

    suspend fun resolveAudio(track: Track, profile: NetworkProfile = profileSnapshot(), requestId: String = NetworkTrace.requestId()): AudioStream {
        val cookie = cookies()
        // Refresh authoritative part metadata, even for favorites with first_cid.
        // This catches deleted/changed content and supplies an independent duration.
        val current = video(BiliLink.Video(bvid = track.bvid, page = track.page), cookie, profile, requestId).tracks.first()
        checkSession(cookie)
        val parameters = mapOf(
            "bvid" to current.bvid,
            "cid" to current.cid.toString(),
            "qn" to "80",
            "fnval" to "4048",
            "fnver" to "0",
            "fourk" to "1",
            "platform" to "pc",
        )
        val data = playInfo(parameters, cookie, profile, requestId)
        checkSession(cookie)
        val stream = BiliResponseParser.audio(data, current.durationMs, current.sourceUrl) { fields ->
            log(profile, requestId, fields + mapOf("stage" to "audio_select", "parse_ua" to profile.parseUa.id))
        }.let {
            it.copy(headers = it.headers + ("User-Agent" to profile.audioUa.value))
        }
        log(profile, requestId, mapOf("stage" to "resolve", "event" to "audio_ready", "parse_ua" to profile.parseUa.id, "ua" to profile.audioUa.id), stream.url)
        return stream
    }

    suspend fun fetchAccount(): Account? {
        val cookie = cookies()
        if (cookie.isBlank()) return null
        val root = rawApi("/x/web-interface/nav", emptyMap(), cookie, profileSnapshot(), NetworkTrace.requestId())
        checkSession(cookie)
        if (root.int("code") == -101) return null
        val data = BiliResponseParser.data(root)
        if (!data.bool("isLogin")) return null
        return Account(data.long("mid"), data.string("uname"), data.string("face"))
            .takeIf { it.mid > 0 }
    }

    private suspend fun video(link: BiliLink.Video, cookie: String, profile: NetworkProfile, requestId: String): ParseResult {
        val parameters = when {
            link.bvid != null && Regex("BV[0-9A-Za-z]{10}").matches(link.bvid) -> mapOf("bvid" to link.bvid)
            link.aid != null && link.aid > 0 -> mapOf("aid" to link.aid.toString())
            else -> throw BiliException("视频编号不正确")
        }
        return BiliResponseParser.video(api("/x/web-interface/view", parameters, cookie, profile, requestId), link.page)
    }

    private suspend fun favorites(id: Long, page: Int, cookie: String, profile: NetworkProfile, requestId: String): ParseResult {
        val data = api("/x/v3/fav/resource/list", mapOf(
            "media_id" to id.toString(), "pn" to page.toString(), "ps" to "20", "order" to "mtime", "type" to "0",
        ), cookie, profile, requestId)
        return BiliResponseParser.favorites(data, id, page)
    }

    private suspend fun playInfo(parameters: Map<String, String>, cookie: String, profile: NetworkProfile, requestId: String): JsonObject {
        suspend fun request(key: String): JsonObject = api(
            "/x/player/wbi/playurl", WbiSigner.sign(parameters, key, nowSeconds()), cookie, profile, requestId,
        )
        val cachedKey = wbiKey(cookie, profile, requestId)
        return try {
            request(cachedKey)
        } catch (error: BiliException) {
            // -400 is request-parameter validation failure; a stale cached signing key is
            // one possible cause. Refresh at most once. -403 is ambiguous/permission-related
            // and is intentionally NOT treated as an expired-key signal, nor are rate limits.
            if (error.code != -400) throw error
            checkSession(cookie)
            request(wbiKey(cookie, profile, requestId, forceRefresh = true))
        }
    }

    private suspend fun wbiKey(cookie: String, profile: NetworkProfile, requestId: String, forceRefresh: Boolean = false): String = keyMutex.withLock {
        if (!forceRefresh && nowSeconds() < keyExpires) key?.let { return@withLock it }
        // Anonymous nav may use code -101 while still supplying wbi_img.
        val root = rawApi("/x/web-interface/nav", emptyMap(), cookie, profile, requestId)
        val code = root.int("code")
        if (code == -101 && cookie.isNotBlank()) throw BiliResponseParser.apiError(code)
        if (code != 0 && code != -101) throw BiliResponseParser.apiError(code)
        val images = root.obj("data").obj("wbi_img")
        WbiSigner.mixinKey(images.string("img_url"), images.string("sub_url")).also {
            key = it
            keyExpires = nowSeconds() + 1800
        }
    }

    private suspend fun api(path: String, query: Map<String, String>, cookie: String, profile: NetworkProfile, requestId: String): JsonObject =
        BiliResponseParser.data(rawApi(path, query, cookie, profile, requestId))

    private suspend fun rawApi(path: String, query: Map<String, String>, cookie: String, profile: NetworkProfile, requestId: String): JsonObject {
        checkSession(cookie)
        val url = API.toHttpUrl().newBuilder().encodedPath(path).apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val headers = commonHeaders(profile) + if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()
        val response = try { transport.get(url, headers) }
        catch (error: java.io.IOException) {
            log(profile, requestId, mapOf("stage" to "api", "event" to "request_failed", "failure" to NetworkTrace.failureKind(error.cause ?: error), "ua" to profile.parseUa.id))
            throw error
        }
        log(profile, requestId, mapOf("stage" to "api", "event" to path.substringAfterLast('/'), "http" to response.status.toString(),
            "ua" to profile.parseUa.id, "family" to response.family, "proxy" to response.proxy))
        if (response.status !in 200..299) throw httpError(response.status)
        return BiliResponseParser.root(response.text)
    }

    private suspend fun expand(initial: BiliLink, profile: NetworkProfile, requestId: String): BiliLink {
        if (initial !is BiliLink.Short) return initial
        var url = initial.url
        repeat(5) {
            if (!BiliLinkParser.isSafeRedirect(url)) throw BiliException("短链接跳转到了不受支持的网站")
            // Short links never receive account cookies, even when logged in.
            val response = transport.get(url, commonHeaders(profile))
            log(profile, requestId, mapOf("stage" to "short_link", "http" to response.status.toString(), "ua" to profile.parseUa.id))
            if (response.status !in 300..399) throw BiliException("短链接未返回有效跳转，请复制视频页面的完整链接")
            val target = response.location?.let(url::resolve) ?: throw BiliException("短链接已失效或缺少跳转地址")
            if (!BiliLinkParser.isSafeRedirect(target)) throw BiliException("短链接跳转到了不受支持的网站")
            val parsed = BiliLinkParser.parse(target.toString())
            if (parsed !is BiliLink.Short) return parsed
            url = parsed.url
        }
        throw BiliException("短链接跳转次数过多，请使用视频页面的完整链接")
    }

    private fun checkSession(snapshot: String) {
        if (cookies() != snapshot) throw BiliException("访问模式或账号已切换，请重新操作")
    }

    private fun commonHeaders(profile: NetworkProfile): Map<String, String> = mapOf(
        "User-Agent" to profile.parseUa.value,
        "Referer" to "https://www.bilibili.com/",
        "Accept" to "application/json, text/plain, */*",
    )

    private fun httpError(status: Int): BiliException = when (status) {
        401 -> BiliException("登录已失效，请重新登录", status)
        403 -> BiliException("B 站拒绝了访问，请检查权限或稍后重试", status)
        404 -> BiliException("链接已失效或内容不存在", status)
        412, 429 -> BiliException("B 站暂时限制了请求，请稍后重试", status)
        in 500..599 -> BiliException("B 站服务暂时不可用，请稍后重试", status)
        else -> BiliException("请求未完成（HTTP $status），请稍后重试", status)
    }

    companion object {
        private const val API = "https://api.bilibili.com"
        const val USER_AGENT = DESKTOP_UA
    }
}
