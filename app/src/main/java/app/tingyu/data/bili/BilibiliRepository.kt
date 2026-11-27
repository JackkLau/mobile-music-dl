package app.tingyu.data.bili

import app.tingyu.model.Account
import app.tingyu.model.AudioStream
import app.tingyu.model.ParseResult
import app.tingyu.model.Track
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
    private val logSink: (String) -> Unit = {},
) {
    constructor(cookies: () -> String, profileProvider: () -> NetworkProfile = { NetworkProfile() }, logSink: (String) -> Unit = {}) :
        this(cookies, OkHttpBiliTransport(), profileProvider = profileProvider, logSink = logSink)

    fun profileSnapshot(): NetworkProfile = profileProvider()

    /** Current logging gate is checked again so switching diagnostics off stops active jobs' output. */
    fun log(profile: NetworkProfile, requestId: String, fields: Map<String, String>, url: String? = null) {
        if (profile.loggingEnabled && profileProvider().loggingEnabled) logSink(NetworkTrace.line(requestId, fields, url))
    }

    private val keyMutex = Mutex()
    private var key: String? = null
    private var keyExpires: Long = 0

    suspend fun parse(
        input: String,
        profile: NetworkProfile = profileSnapshot(),
        requestId: String = NetworkTrace.requestId(),
    ): ParseResult {
        val cookie = cookies()
        val initial = try { BiliLinkParser.parse(input) }
        catch (error: BiliException) { throw error.atStage("link") }
        val link = expand(initial, profile, requestId)
        val result = when (link) {
            is BiliLink.Video -> {
                val data = videoDetails(link, cookie, profile, requestId)
                // The phone share sheet appends p=1 even when sharing a whole album.
                val page = if (initial is BiliLink.Short && link.page == 1) null else link.page
                val parts = BiliResponseParser.video(data, page)
                val collection = BiliResponseParser.seasonOf(data)
                when {
                    collection == null -> parts
                    page == null -> BiliResponseParser.embeddedSeason(data, collection)
                        ?: season(collection, data, cookie, profile, requestId)
                    // If only the season ID is present, the space page can still reveal more entries.
                    else -> parts.copy(collectionUrl = collection.url, hasMoreInCollection = parts.hasMoreInCollection ||
                        data.obj("ugc_season").int("ep_count") != 1)
                }
            }
            is BiliLink.Favorite -> favorites(link.id, 1, cookie, profile, requestId)
            is BiliLink.Season -> season(link, null, cookie, profile, requestId)
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
        if (track.localOnly) throw BiliException("这是本地音频，请重新导入可读取的文件", stage = "local")
        val cookie = cookies()
        // Refresh authoritative part metadata, even for favorites with first_cid.
        // This catches deleted/changed content and supplies an independent duration.
        val data = videoDetails(BiliLink.Video(bvid = track.bvid), cookie, profile, requestId)
        val current = BiliResponseParser.video(data, track.page).tracks.single()
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
        val playData = playInfo(parameters, cookie, profile, requestId)
        checkSession(cookie)
        val stream = BiliResponseParser.audio(playData, current.durationMs, current.sourceUrl) { fields ->
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
        val response = rawApi("/x/web-interface/nav", emptyMap(), cookie, profileSnapshot(), NetworkTrace.requestId())
        checkSession(cookie)
        if (response.root.int("code") == -101) return null
        val data = try { BiliResponseParser.data(response.root) }
        catch (error: BiliException) { throw error.atStage("api", response.status) }
        if (!data.bool("isLogin")) return null
        return Account(data.long("mid"), data.string("uname"), data.string("face"))
            .takeIf { it.mid > 0 }
    }

    private suspend fun videoDetails(link: BiliLink.Video, cookie: String, profile: NetworkProfile, requestId: String): JsonObject {
        val parameters = when {
            link.bvid != null && BiliLinkParser.bv.matches(link.bvid) -> mapOf("bvid" to link.bvid)
            link.aid != null && link.aid > 0 -> mapOf("aid" to link.aid.toString())
            else -> throw BiliException("视频编号不正确")
        }
        val data = api("/x/web-interface/view", parameters, cookie, profile, requestId)
        if (link.bvid != null && data.string("bvid") != link.bvid) throw BiliException("B 站返回的视频与链接不一致")
        return data
    }

    private suspend fun season(
        link: BiliLink.Season, seed: JsonObject?, cookie: String, profile: NetworkProfile, requestId: String,
    ): ParseResult {
        val seen = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        var title = seed?.obj("ugc_season")?.string("title").orEmpty()
        var page = 1
        var listed = 0L
        do {
            val data = api("/x/polymer/web-space/seasons_archives_list", mapOf(
                "mid" to link.mid.toString(), "season_id" to link.id.toString(), "page_num" to page.toString(), "page_size" to "30",
            ), cookie, profile, requestId)
            val meta = data.obj("meta")
            if (meta.long("mid").let { it > 0 && it != link.mid } ||
                meta.long("season_id").let { it > 0 && it != link.id }) throw BiliException("B 站返回的合集与链接不一致")
            if (title.isBlank()) title = meta.string("name")
            val archives = data["archives"] as? JsonArray
                ?: throw BiliException("未找到合集曲目")
            val pagination = data.obj("page")
            val total = (pagination["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                ?: throw BiliException("未找到合集分页信息")
            val returnedPage = pagination.int("page_num")
            if (returnedPage > 0 && returnedPage != page) throw BiliException("合集分页不完整，请重新解析")
            val before = seen.size
            for (item in archives) {
                val archive = item.asObject() ?: throw BiliException("合集曲目格式不正确")
                if (archive.int("state") < 0) continue
                val bvid = archive.string("bvid").takeIf(BiliLinkParser.bv::matches)
                    ?: throw BiliException("合集曲目缺少视频编号")
                if (!seen.add(bvid)) continue
                val details = if (seed != null && seed.string("bvid") == bvid) seed
                    else videoDetails(BiliLink.Video(bvid = bvid), cookie, profile, requestId)
                // A space link only needs its first archive if that view contains all episodes.
                if (before == 0 && seen.size == 1) BiliResponseParser.embeddedSeason(details, link)?.let { return it }
                tracks += BiliResponseParser.video(details, null).tracks
            }
            listed += archives.size
            if (archives.isNotEmpty() && before == seen.size && archives.any { it.asObject()?.int("state") == 0 }) {
                throw BiliException("合集返回了重复页，请重新解析")
            }
            if (listed >= total) break
            if (archives.isEmpty()) throw BiliException("合集分页不完整，请重新解析")
            page++
        } while (true)
        return ParseResult(title.ifBlank { "B 站合集" }, tracks, collectionUrl = link.url)
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
        val response = rawApi("/x/web-interface/nav", emptyMap(), cookie, profile, requestId)
        val code = response.root.int("code")
        if (code == -101 && cookie.isNotBlank()) throw BiliResponseParser.apiError(code).atStage("api", response.status)
        if (code != 0 && code != -101) throw BiliResponseParser.apiError(code).atStage("api", response.status)
        val images = response.root.obj("data").obj("wbi_img")
        WbiSigner.mixinKey(images.string("img_url"), images.string("sub_url")).also {
            key = it
            keyExpires = nowSeconds() + 1800
        }
    }

    private suspend fun api(path: String, query: Map<String, String>, cookie: String, profile: NetworkProfile, requestId: String): JsonObject {
        val response = rawApi(path, query, cookie, profile, requestId)
        return try { BiliResponseParser.data(response.root) }
        catch (error: BiliException) { throw error.atStage("api", response.status) }
    }

    private data class ApiResponse(val root: JsonObject, val status: Int)

    private suspend fun rawApi(path: String, query: Map<String, String>, cookie: String, profile: NetworkProfile, requestId: String): ApiResponse {
        checkSession(cookie)
        val url = API.toHttpUrl().newBuilder().encodedPath(path).apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val headers = commonHeaders(profile) + if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()
        val response = request(url, headers, profile, requestId, "api")
        if (response.status !in 200..299) throw httpError(response.status, "api")
        val root = try { BiliResponseParser.root(response.text) }
        catch (error: BiliException) {
            log(profile, requestId, mapOf("stage" to "api", "event" to "invalid_response", "http" to response.status.toString()))
            throw error.atStage("api", response.status)
        }
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
        log(profile, requestId, mapOf("stage" to "api", "event" to path.substringAfterLast('/'),
            "http" to response.status.toString()) + (code?.let { mapOf("code" to it.toString()) } ?: emptyMap()))
        return ApiResponse(root, response.status)
    }

    private suspend fun expand(initial: BiliLink, profile: NetworkProfile, requestId: String): BiliLink {
        if (initial !is BiliLink.Short) return initial
        var url = initial.url
        var lastStatus: Int? = null
        repeat(5) { redirects ->
            if (!BiliLinkParser.isSafeRedirect(url)) throw BiliException("短链接跳转到了不受支持的网站", stage = "short_link")
            // Short links never receive account cookies, even when logged in.
            val response = request(url, commonHeaders(profile), profile, requestId, "short_link", redirects)
            lastStatus = response.status
            if (response.status !in setOf(301, 302, 303, 307, 308)) {
                if (response.status !in 200..299) throw httpError(response.status, "short_link")
                // Some short-link errors arrive as HTTP 200 JSON. Preserve only the
                // numeric business code; server messages can contain private data.
                val code = try {
                    (BiliResponseParser.root(response.text)["code"] as? JsonPrimitive)?.intOrNull
                } catch (_: BiliException) { null }
                if (code != null && code != 0) {
                    log(profile, requestId, mapOf("stage" to "short_link", "event" to "business_error",
                        "http" to response.status.toString(), "code" to code.toString()))
                    throw BiliResponseParser.apiError(code).atStage("short_link", response.status)
                }
                throw BiliException("短链接未返回有效跳转，请复制视频页面的完整链接", stage = "short_link", httpStatus = response.status)
            }
            val target = response.location?.let(url::resolve)
                ?: throw BiliException("短链接缺少有效跳转地址，请复制视频页面的完整链接", stage = "short_link", httpStatus = response.status)
            if (!BiliLinkParser.isSafeRedirect(target)) {
                throw BiliException("短链接跳转到了不受支持的网站", stage = "short_link", httpStatus = response.status)
            }
            val parsed = try { BiliLinkParser.parse(target.toString()) }
            catch (error: BiliException) { throw error.atStage("short_link", response.status) }
            if (parsed !is BiliLink.Short) return parsed
            url = parsed.url
        }
        throw BiliException("短链接跳转次数过多，请使用视频页面的完整链接", stage = "short_link", httpStatus = lastStatus)
    }

    private suspend fun request(
        url: okhttp3.HttpUrl,
        headers: Map<String, String>,
        profile: NetworkProfile,
        requestId: String,
        stage: String,
        redirects: Int = 0,
    ): BiliResponse {
        val response = try { transport.get(url, headers) }
        catch (error: IOException) {
            val status = (error as? BiliException)?.httpStatus
            val connection = (error as? BiliException)?.connection
            log(profile, requestId, mapOf("stage" to stage, "event" to "request_failed",
                "failure" to NetworkTrace.failureKind(error.cause ?: error), "ua" to profile.parseUa.id,
                "family" to (connection?.family ?: "unknown"), "proxy" to (connection?.proxy ?: "unknown"),
                "network_stage" to (connection?.stage ?: "unknown"),
                "redirects" to redirects.toString()) + (status?.let { mapOf("http" to it.toString()) } ?: emptyMap()), url.toString())
            throw if (error is BiliException) error.atStage(stage) else
                BiliException("无法连接 B 站，请检查网络后重试", stage = stage).apply { initCause(error) }
        }
        log(profile, requestId, mapOf("stage" to stage, "event" to "response", "http" to response.status.toString(),
            "ua" to profile.parseUa.id, "parse_ua" to profile.parseUa.id, "redirects" to redirects.toString(),
            "family" to response.family, "proxy" to response.proxy), url.toString())
        return response
    }

    private fun BiliException.atStage(value: String, status: Int? = httpStatus): BiliException =
        BiliException(message ?: "B 站请求失败，请稍后重试", code, value, status, connection).also { it.initCause(cause ?: this) }

    private fun checkSession(snapshot: String) {
        if (cookies() != snapshot) throw BiliException("访问模式或账号已切换，请重新操作")
    }

    private fun commonHeaders(profile: NetworkProfile): Map<String, String> = mapOf(
        "User-Agent" to profile.parseUa.value,
        "Referer" to "https://www.bilibili.com/",
        "Accept" to "application/json, text/plain, */*",
    )

    private fun httpError(status: Int, stage: String): BiliException = BiliException(when (status) {
        401 -> "B 站授权失败（HTTP 401），请检查账号后重新登录"
        403 -> "B 站拒绝访问（HTTP 403），请检查权限或稍后重试"
        404 -> "链接不存在或已失效（HTTP 404），请重新复制链接"
        410 -> "链接已过期（HTTP 410），请重新复制链接"
        412, 429 -> "B 站暂时限制请求（HTTP $status），请稍后重试"
        in 500..599 -> "B 站服务暂时不可用（HTTP $status），请稍后重试"
        else -> "请求未完成（HTTP $status），请稍后重试"
    }, status, stage, status)

    companion object {
        private const val API = "https://api.bilibili.com"
        const val USER_AGENT = DESKTOP_UA
    }
}
