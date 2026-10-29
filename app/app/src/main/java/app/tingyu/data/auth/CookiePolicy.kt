package app.tingyu.data.auth

import java.net.URI

/** Only the login session created inside this app may enter the native request client. */
object CookiePolicy {
    private val acceptedNames = setOf(
        "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4", "b_nut",
    )

    fun normalize(header: String): String {
        if (header.length > 16_384 || header.any { it == '\r' || it == '\n' }) return ""
        val values = linkedMapOf<String, String>()
        header.split(';').forEach { item ->
            val name = item.substringBefore('=', "").trim()
            val value = item.substringAfter('=', "").trim()
            if (name in acceptedNames && value.isNotEmpty() &&
                value.all { it.code in 33..126 && it !in ";,\\\"" }
            ) values[name] = value
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun hasSession(header: String): Boolean = normalize(header).split("; ").any { it.startsWith("SESSDATA=") }

    fun isTrustedPage(rawUrl: String): Boolean = runCatching {
        val url = URI(rawUrl)
        val host = url.host?.lowercase().orEmpty()
        url.scheme.equals("https", true) && url.userInfo == null && (url.port == -1 || url.port == 443) &&
            (host == "bilibili.com" || host.endsWith(".bilibili.com"))
    }.getOrDefault(false)
}
