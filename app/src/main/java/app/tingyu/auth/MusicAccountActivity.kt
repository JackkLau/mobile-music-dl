package app.tingyu.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.tingyu.data.bili.DESKTOP_UA
import app.tingyu.data.bili.MOBILE_UA
import app.tingyu.data.music.MusicLogin
import app.tingyu.model.MusicLoginMethod
import app.tingyu.model.MusicSource
import app.tingyu.ui.TingYuTheme
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object MusicWebSession {
    fun profile(source: MusicSource) = when (source) {
        MusicSource.NETEASE -> "weile-netease"
        MusicSource.QQ -> "weile-qqmusic"
        else -> error("Unsupported login provider")
    }
    fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun manager(source: MusicSource) = if (supported())
        ProfileStore.getInstance().getProfile(profile(source))?.cookieManager else null

    fun read(source: MusicSource): String {
        val endpoint = if (source == MusicSource.NETEASE) "https://music.163.com/api/song/enhance/download/url"
            else "https://u.y.qq.com/cgi-bin/musicu.fcg"
        return MusicLogin.sessionCookie(source, manager(source)?.getCookie(endpoint).orEmpty())
    }

    suspend fun cookie(source: MusicSource): String = withContext(Dispatchers.Main.immediate) { read(source) }

    fun fingerprint(source: MusicSource): String = read(source).takeIf(String::isNotEmpty)?.let {
        val keys = it.split("; ").filter { field -> field.substringBefore('=') in setOf("MUSIC_U", "qm_keyst", "qqmusic_key") }
        android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(keys.joinToString("; ").toByteArray()), android.util.Base64.NO_WRAP)
    }.orEmpty()

    fun flush(source: MusicSource) { manager(source)?.flush() }
}

class MusicAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = MusicSource.entries.firstOrNull { it.name == intent.getStringExtra("source") && it != MusicSource.BILIBILI }
            ?: run { finish(); return }
        val challenge = intent.getStringExtra("challenge")
        val initialMethod = MusicLoginMethod.entries.firstOrNull { it.name == intent.getStringExtra("method") } ?: MusicLoginMethod.QR
        setContent {
            TingYuTheme {
                var method by rememberSaveable { mutableStateOf(initialMethod) }
                var clearing by rememberSaveable { mutableStateOf(false) }
                var cleared by remember { mutableStateOf(false) }
                val supported = remember { MusicWebSession.supported() }
                // Persist only the fingerprint across recreation. A stale cookie must not immediately resume the task.
                val baseline = rememberSaveable { MusicWebSession.fingerprint(source) }
                var hasSession by remember { mutableStateOf(false) }
                val complete = {
                    if (!isFinishing && MusicWebSession.read(source).isNotEmpty()) {
                        MusicWebSession.flush(source)
                        setResult(Activity.RESULT_OK, Intent().putExtra("challenge", challenge))
                        finish()
                    }
                }
                if (supported && !clearing) {
                    LaunchedEffect(source, challenge) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            var last = ""
                            while (!isFinishing) {
                                val now = MusicWebSession.fingerprint(source)
                                hasSession = now.isNotEmpty()
                                if (hasSession && now != last) MusicWebSession.flush(source)
                                if (challenge != null && hasSession && now != baseline && now == last) complete()
                                last = now
                                delay(750)
                            }
                        }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { finish() }) { Text("返回") }
                            Text("${source.label}账号", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (supported && !clearing) TextButton(onClick = { clearing = true }) { Text("清除登录") }
                        }
                        if (!supported) Text("请更新 Android 系统 WebView 后重试，当前版本无法独立保存该平台登录。", Modifier.padding(16.dp))
                        else if (clearing) {
                            LaunchedEffect(Unit) {
                                val profile = ProfileStore.getInstance().getProfile(MusicWebSession.profile(source))
                                if (profile == null) cleared = true else {
                                    profile.webStorage.deleteAllData()
                                    profile.cookieManager.removeAllCookies {
                                        profile.cookieManager.flush()
                                        cleared = true
                                    }
                                }
                            }
                            Text(if (cleared) "已清除本机的${source.label}登录" else "正在清除登录…", Modifier.padding(16.dp))
                            if (cleared) TextButton(onClick = { cleared = false; clearing = false }) { Text("重新登录") }
                        } else {
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MusicLoginMethod.entries.forEach { option ->
                                    FilterChip(selected = method == option, onClick = { method = option }, label = { Text(option.label) })
                                }
                            }
                            val help = when {
                                method == MusicLoginMethod.QR -> "请用另一台设备扫描官网二维码并确认。"
                                source == MusicSource.QQ -> "请使用绑定 QQ 的手机号，按腾讯页面提示验证。"
                                else -> "请在网易云音乐官网填写手机号并完成验证。"
                            }
                            Text(help, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
                            if (hasSession && challenge != null) TextButton(onClick = complete) { Text("用当前登录继续") }
                            key(method) { OfficialMusicLoginPage(source, method) }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficialMusicLoginPage(source: MusicSource, method: MusicLoginMethod) {
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        Column(Modifier.padding(16.dp)) {
            Text("官方登录页未能打开，请检查网络或切换登录方式。")
            TextButton(onClick = { failed = false; loading = true; reload++ }) { Text("重新加载") }
        }
        return
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    key(reload) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView.setWebContentsDebuggingEnabled(false)
            WebView(context).apply {
                WebViewCompat.setProfile(this, MusicWebSession.profile(source))
                isSaveEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.userAgentString = if (method == MusicLoginMethod.QR) DESKTOP_UA else MOBILE_UA
                WebViewCompat.getProfile(this).cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame && request.url.toString() == "about:blank") return false
                        val url = request.url.toString().toHttpUrlOrNull() ?: return true
                        return !MusicLogin.permits(source, url)
                    }
                    override fun onPageFinished(view: WebView, url: String) { loading = false }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failed = true
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && response.statusCode >= 400) failed = true
                    }
                }
                loadUrl(MusicLogin.entry(source, method))
            }
        }, onRelease = { it.stopLoading(); it.destroy() })
    }
}
