package app.tingyu.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.tingyu.data.bili.DESKTOP_UA
import app.tingyu.ui.TingYuTheme
import app.tingyu.model.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The provider's own WebView profile is separate from Bilibili and the system browser. */
internal object MusicWebSession {
    fun profile(source: MusicSource) = when (source) {
        MusicSource.NETEASE -> "weile-netease"
        MusicSource.QQ -> "weile-qqmusic"
        else -> error("Unsupported login provider")
    }
    fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    suspend fun cookie(source: MusicSource): String = withContext(Dispatchers.Main.immediate) {
        if (!supported()) return@withContext ""
        val net = source == MusicSource.NETEASE
        val endpoint = if (net) "https://music.163.com/api/song/enhance/download/url" else "https://u.y.qq.com/cgi-bin/musicu.fcg"
        val cookies = ProfileStore.getInstance().getProfile(profile(source))?.cookieManager?.getCookie(endpoint).orEmpty()
        val names = if (net) setOf("MUSIC_U") else setOf("uin", "qqmusic_uin", "qqmusic_key", "qm_keyst", "wxuin", "login_type")
        cookies.split(';').map(String::trim).filter { it.substringBefore('=') in names && it.substringAfter('=', "").isNotEmpty() }
            .joinToString("; ")
    }
}

class MusicAccountActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = if (intent.getStringExtra("source") == MusicSource.QQ.name) MusicSource.QQ else MusicSource.NETEASE
        val net = source == MusicSource.NETEASE
        val profileName = MusicWebSession.profile(source)
        setContent {
            TingYuTheme {
                var closed by remember { mutableStateOf(false) }
                var cleared by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { finish() }) { Text("返回") }
                            Text("${source.label}账号", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (MusicWebSession.supported() && !closed) TextButton(onClick = { closed = true }) { Text("清除登录") }
                        }
                        if (!MusicWebSession.supported()) Text("当前系统 WebView 不支持独立登录，请更新 Android 系统 WebView，或前往官方歌曲页。", Modifier.padding(16.dp))
                        else if (closed) {
                            LaunchedEffect(Unit) {
                                val profile = ProfileStore.getInstance().getProfile(profileName)
                                if (profile == null) cleared = true else {
                                    profile.webStorage.deleteAllData()
                                    profile.cookieManager.removeAllCookies {
                                        profile.cookieManager.flush()
                                        cleared = true
                                    }
                                }
                            }
                            Text(if (cleared) "已清除本机的${source.label}登录" else "正在清除登录…", Modifier.padding(16.dp))
                        } else {
                            Text("在${source.label}官网登录后返回，重试下载。登录信息仅用于该平台的歌曲请求。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                                WebView.setWebContentsDebuggingEnabled(false)
                                WebView(context).apply {
                                    WebViewCompat.setProfile(this, profileName)
                                    isSaveEnabled = false
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.userAgentString = DESKTOP_UA
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            val url = request.url.toString().toHttpUrlOrNull() ?: return true
                                            val host = url.host
                                            val allowed = if (net) host == "music.163.com" || host.endsWith(".music.163.com") ||
                                                host == "reg.163.com" || host.endsWith(".reg.163.com")
                                            else host == "y.qq.com" || host.endsWith(".y.qq.com") || host.endsWith(".ptlogin2.qq.com") ||
                                                host in setOf("graph.qq.com", "open.weixin.qq.com")
                                            return !allowed || !url.isHttps || url.port != 443 ||
                                                url.username.isNotEmpty() || url.password.isNotEmpty()
                                        }
                                    }
                                    loadUrl(if (net) "https://music.163.com/#/login" else "https://y.qq.com/")
                                }
                            }, onRelease = { it.stopLoading(); it.destroy() })
                        }
                    }
                }
            }
        }
    }
}
