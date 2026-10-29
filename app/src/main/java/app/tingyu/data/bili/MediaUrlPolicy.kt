package app.tingyu.data.bili

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Shared API-to-media boundary. Never rewrite an accepted signed HTTPS address. */
object MediaUrlPolicy {
    private val trustedRoots = setOf("bilivideo.com", "bilivideo.cn", "bilivideo.net", "akamaized.net", "hdslb.com")
    private const val MCDN_ROOT = "mcdn.bilivideo.cn"

    fun normalize(value: String): String? {
        val checked = inspect(value)
        if (checked.reason != null) return null
        val url = checked.url ?: return null
        return if (url.scheme == "https") value else url.newBuilder().scheme("https").port(443).build().toString()
    }

    /** Requests themselves are HTTPS-only; HTTP API addresses must first be normalized. */
    fun permitted(value: String): Boolean {
        val checked = inspect(value)
        return checked.reason == null && checked.url?.scheme == "https"
    }

    /** Returns only fixed diagnostic categories, never caller-controlled text. */
    fun rejectionReason(value: String): String? = inspect(value).reason

    private fun inspect(value: String): Inspection {
        if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
            return Inspection(reason = "invalid_url")
        }
        val scheme = value.substringBefore(':', "").lowercase()
        if (scheme.isEmpty()) return Inspection(reason = "invalid_url")
        if (scheme !in setOf("http", "https")) return Inspection(reason = "scheme")
        if (!value.startsWith("$scheme://", ignoreCase = true)) return Inspection(reason = "invalid_url")
        // OkHttp normalizes away empty user-info, so reject its raw authority marker too.
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return Inspection(reason = "credentials")
        val parsed = value.toHttpUrlOrNull() ?: return Inspection(reason = "invalid_url")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return Inspection(reason = "credentials")
        if (parsed.fragment != null) return Inspection(reason = "fragment")
        val host = parsed.host
        if (trustedRoots.none { host == it || host.endsWith(".$it") }) return Inspection(reason = "host")
        val normalPort = if (parsed.scheme == "https") 443 else 80
        val mcdnTls = parsed.scheme == "https" && parsed.port == 4483 &&
            (host == MCDN_ROOT || host.endsWith(".$MCDN_ROOT"))
        if (parsed.port != normalPort && !mcdnTls) return Inspection(reason = "port")
        return Inspection(url = parsed)
    }

    private data class Inspection(val url: HttpUrl? = null, val reason: String? = null)
}
