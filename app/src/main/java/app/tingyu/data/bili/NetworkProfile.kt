package app.tingyu.data.bili

import java.security.MessageDigest
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Public web identities used by the API and CDN, independently selectable for diagnostics. */
enum class UserAgentProfile(val label: String, val id: String, val value: String) {
    DESKTOP("桌面", "desktop", DESKTOP_UA),
    MOBILE("移动", "mobile", MOBILE_UA),
}

const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/** Immutable for a whole operation, including its one link refresh. */
data class NetworkProfile(
    val parseUa: UserAgentProfile = UserAgentProfile.DESKTOP,
    val audioUa: UserAgentProfile = UserAgentProfile.DESKTOP,
    val loggingEnabled: Boolean = false,
)

/** Only allowlisted, bounded values may appear in diagnostic output. */
object NetworkTrace {
    private val token = Regex("[A-Za-z0-9_.:-]{1,100}")
    private val keys = setOf("stage", "event", "request", "url_id", "host", "http", "code", "parse_ua", "ua", "offset", "redirects", "range", "profile", "bytes", "attempt", "family", "proxy", "network_stage", "failure", "sample", "result",
        "reason", "audio_total", "candidates", "candidate", "format", "codec", "mime", "urls")
    fun requestId(): String = UUID.randomUUID().toString().take(12)
    fun failureKind(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "dns"
        is javax.net.ssl.SSLException -> "tls"
        is java.net.SocketTimeoutException -> "timeout"
        is java.io.IOException -> "io"
        else -> "unexpected"
    }
    fun urlId(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun line(requestId: String, fields: Map<String, String>, url: String? = null): String {
        val safe = linkedMapOf("request" to requestId)
        safe.putAll(fields)
        url?.toHttpUrlOrNull()?.let { safe["host"] = it.host; safe["url_id"] = urlId(url) }
        return safe.filter { (key, value) -> key in keys && token.matches(value) }
            .entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}
