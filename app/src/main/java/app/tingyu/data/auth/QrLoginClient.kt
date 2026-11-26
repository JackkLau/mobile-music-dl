package app.tingyu.data.auth

import app.tingyu.data.bili.DESKTOP_UA
import app.tingyu.model.Account
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// Avoid data classes: their default toString would include the challenge or session.
class QrLoginChallenge internal constructor(val url: String, internal val key: String)

sealed interface QrLoginStatus {
    object Waiting : QrLoginStatus
    object Scanned : QrLoginStatus
    object Expired : QrLoginStatus
    class Confirmed internal constructor(val cookie: String) : QrLoginStatus
}

class QrLoginException internal constructor(message: String) : IOException(message)

/** Passport requests are anonymous. Only the fixed account verification endpoint receives credentials. */
class QrLoginClient internal constructor(private val calls: Call.Factory) {
    constructor() : this(OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build())

    suspend fun generate(): QrLoginChallenge = request(GENERATE) { body, _ ->
        val data = passportData(body)
        val key = data.string("qrcode_key")
        val address = data.string("url")
        val scan = address.toHttpUrlOrNull()
        if (!KEY.matches(key) || address.length > 2048 || scan == null || !scan.isHttps ||
            scan.port != 443 || scan.username.isNotEmpty() || scan.password.isNotEmpty() ||
            scan.encodedPath != SCAN_PATHS[scan.host] || scan.queryParameterValues("qrcode_key") != listOf(key)
        ) throw QrLoginException("哔哩哔哩返回的二维码无效，请刷新后重试。")
        QrLoginChallenge(scan.toString(), key)
    }

    suspend fun poll(challenge: QrLoginChallenge): QrLoginStatus {
        val url = POLL.newBuilder().addQueryParameter("qrcode_key", challenge.key).build()
        return request(url) { body, response ->
            val data = passportData(body)
            when (val status = data.code()) {
                86101 -> QrLoginStatus.Waiting
                86090 -> QrLoginStatus.Scanned
                86038 -> QrLoginStatus.Expired
                0 -> {
                    // Match Set-Cookie's domain, path and lifetime to the eventual nav request.
                    // The cross-domain redirect URL and refresh token in the JSON are not needed.
                    val cookies = Cookie.parseAll(url, response.headers)
                        .filter { it.matches(NAV) && it.expiresAt > System.currentTimeMillis() }
                        .joinToString("; ") { "${it.name}=${it.value}" }
                    val candidate = CookiePolicy.normalize(cookies)
                    if (!CookiePolicy.hasSession(candidate)) throw QrLoginException("未收到有效登录凭据，请刷新二维码后重试。")
                    QrLoginStatus.Confirmed(candidate)
                }
                else -> throw QrLoginException("哔哩哔哩未完成扫码登录（状态码 $status）。请稍后重试。")
            }
        }
    }

    suspend fun verifyAccount(cookie: String): Account {
        val normalized = CookiePolicy.normalize(cookie)
        if (!CookiePolicy.hasSession(normalized)) throw QrLoginException("登录凭据无效，请重新登录。")
        return request(NAV, normalized) { body, _ ->
            try {
                LoginResponse.parse(body)
            } catch (_: Exception) {
                throw QrLoginException("未能验证哔哩哔哩账号，请重新登录。")
            }
        }
    }

    private fun passportData(body: String): JsonObject {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: throw invalidResponse()
        val code = root.code()
        if (code != 0) throw QrLoginException("哔哩哔哩登录请求失败（错误码 $code）。请稍后重试。")
        return root["data"] as? JsonObject ?: throw invalidResponse()
    }

    private fun JsonObject.code(): Int = (this["code"] as? JsonPrimitive)?.intOrNull ?: throw invalidResponse()
    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw invalidResponse()

    private fun invalidResponse() = QrLoginException("无法读取哔哩哔哩登录响应，请重新登录。")

    private suspend fun <T> request(url: HttpUrl, cookie: String? = null, parse: (String, Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", if (url == NAV) "https://www.bilibili.com/" else "https://passport.bilibili.com/login")
                .header("Cache-Control", "no-store")
                .apply {
                    if (cookie != null) {
                        check(url == NAV)
                        header("Cookie", cookie)
                    }
                }
                .build()
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        QrLoginException("连接哔哩哔哩失败，请检查网络后重试。"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw QrLoginException("哔哩哔哩登录请求失败（HTTP ${it.code}）。请稍后重试。")
                            val source = it.body?.source() ?: throw invalidResponse()
                            if (source.request(MAX_LOGIN_BYTES + 1)) throw invalidResponse()
                            parse(source.readUtf8(), it)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        // Parsing/network exceptions can embed a QR key or complete response.
                        if (continuation.isActive) continuation.resumeWithException(e as? QrLoginException ?: invalidResponse())
                    }
                }
            })
        }

    private companion object {
        val GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate".toHttpUrl()
        val POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll".toHttpUrl()
        val NAV = "https://api.bilibili.com/x/web-interface/nav".toHttpUrl()
        val KEY = Regex("[A-Za-z0-9_-]{16,128}")
        val SCAN_PATHS = mapOf(
            "account.bilibili.com" to "/h5/account-h5/auth/scan-web",
            "passport.bilibili.com" to "/h5-app/passport/login/scan",
        )
        const val MAX_LOGIN_BYTES = 256_000L
    }
}
