package app.tingyu.data.music

import app.tingyu.model.MusicLoginMethod
import app.tingyu.model.MusicSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import java.io.IOException

internal class MusicLoginRequired(val source: MusicSource, reason: String, val songUrl: HttpUrl? = null) :
    IOException("${source.label}要求登录（$reason），登录后可继续下载")

internal object MusicLogin {
    fun entry(source: MusicSource, method: MusicLoginMethod): String = when (source) {
        MusicSource.NETEASE -> if (method == MusicLoginMethod.QR) "https://music.163.com/#/login"
            else "https://music.163.com/m/login?needThirdlogin=false"
        MusicSource.QQ -> qqEntry(method)
        else -> error("Unsupported login provider")
    }

    // Both Tencent forms return to QQ Music's own HTTPS callback to establish its first-party session.
    private fun qqEntry(method: MusicLoginMethod): String {
        val qr = method == MusicLoginMethod.QR
        val callback = (if (qr) "https://y.qq.com/portal/wx_redirect.html" else "https://y.qq.com/m/login/redirect.html")
            .toHttpUrl().newBuilder().addQueryParameter("login_type", "1")
            .addQueryParameter("surl", "https://y.qq.com/").build().toString()
        return if (qr) "https://graph.qq.com/oauth2.0/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code").addQueryParameter("client_id", "100497308")
            .addQueryParameter("scope", "get_user_info").addQueryParameter("state", UUID.randomUUID().toString())
            .addQueryParameter("redirect_uri", callback).build().toString()
        else "https://ui.ptlogin2.qq.com/cgi-bin/login".toHttpUrl().newBuilder()
            .addQueryParameter("appid", "83886593").addQueryParameter("daid", "384")
            .addQueryParameter("style", "9").addQueryParameter("s_url", callback).build().toString()
    }

    fun permits(source: MusicSource, url: HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host
        return when (source) {
            MusicSource.NETEASE -> host == "music.163.com" || host.endsWith(".music.163.com") ||
                host == "reg.163.com" || host.endsWith(".reg.163.com")
            MusicSource.QQ -> host == "y.qq.com" || host.endsWith(".y.qq.com") || host.endsWith(".ptlogin2.qq.com") ||
                host in setOf("graph.qq.com", "open.weixin.qq.com", "connect.qq.com", "ssl.captcha.qq.com")
            else -> false
        }
    }

    fun isSignInPage(source: MusicSource, url: HttpUrl): Boolean = permits(source, url) && when (source) {
        MusicSource.NETEASE -> url.encodedPath in setOf("/login", "/m/login") || url.fragment?.substringBefore('?') == "/login"
        MusicSource.QQ -> url.encodedPath in setOf("/m/client/login/index.html", "/oauth2.0/authorize", "/cgi-bin/login", "/cgi-bin/xlogin")
        else -> false
    }

    fun checkRedirect(source: MusicSource, destination: HttpUrl?, reason: String) {
        if (destination == null || !isSignInPage(source, destination)) return
        // A shortener can send everyone to the same login page. Resume a validated song on that platform
        // when its login page supplies one, without sending any account cookies to the shortener.
        val song = listOf("redirect_url", "redirect_uri", "s_url", "surl", "target").asSequence()
            .mapNotNull(destination::queryParameter).mapNotNull(destination::resolve)
            .firstOrNull { MusicShareLink.permitted(it, source) && MusicShareLink.direct(it, source) != null }
        throw MusicLoginRequired(source, reason, song)
    }

    fun checkCode(source: MusicSource, code: Long?) {
        val login = when (source) {
            MusicSource.NETEASE -> code == 301L || code == 401L
            MusicSource.QQ -> code == 1000L || code == -1000L || code == 401L
            else -> false
        }
        if (login) throw MusicLoginRequired(source, "业务码 $code")
    }

    // An account number or login_type can survive sign-out. Require a provider-issued session key.
    fun sessionCookie(source: MusicSource, raw: String): String {
        val names = when (source) {
            MusicSource.NETEASE -> listOf("MUSIC_U")
            MusicSource.QQ -> listOf("uin", "qqmusic_uin", "wxuin", "qqmusic_key", "qm_keyst", "login_type")
            else -> return ""
        }
        val fields = raw.split(';').map(String::trim).filter { '=' in it }.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }.filter { (key, value) ->
            value.isNotBlank() && value != "null" && value != "deleted" && (value != "0" || key == "login_type")
        }
        if (source == MusicSource.QQ) {
            val account = listOf("qqmusic_uin", "uin", "wxuin").any { (fields[it]?.removePrefix("o")?.toLongOrNull() ?: 0) > 0 }
            if (!account || ("qqmusic_key" !in fields && "qm_keyst" !in fields)) return ""
        }
        return names.mapNotNull { name -> fields[name]?.let { "$name=$it" } }.joinToString("; ")
    }
}
