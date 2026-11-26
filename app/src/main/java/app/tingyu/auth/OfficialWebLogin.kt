package app.tingyu.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tingyu.data.auth.CookiePolicy
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OfficialWebLogin(
    status: WebStatus, onReady: (WebView) -> Unit, onRelease: (WebView) -> Unit,
    onPage: (WebView, WebStatus) -> Unit, onReload: () -> Unit, onComplete: () -> Unit, short: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        if (!short || status.error || status.verifying) {
            Text(status.message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
                color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status.loading || status.verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView.setWebContentsDebuggingEnabled(false)
                WebView(context).apply {
                    isSaveEnabled = false
                    settings.javaScriptEnabled = true // First-party login and CAPTCHA require JS.
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.safeBrowsingEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    setDownloadListener { _, _, _, _, _ -> onPage(this, WebStatus("登录页不支持下载文件。", error = true)) }
                    webViewClient = LoginWebClient(onPage)
                    onReady(this)
                }
            },
            onRelease = onRelease,
        )
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (short) 0.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReload, enabled = !status.verifying) { Text("重新加载") }
                Button(onClick = onComplete, enabled = !status.verifying) { Text("完成登录") }
            }
        }
    }
}

private class LoginWebClient(private val onPage: (WebView, WebStatus) -> Unit) : WebViewClient() {
    private var failed = false

    private fun error(view: WebView, message: String) {
        failed = true
        onPage(view, WebStatus(message, error = true))
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        failed = false
        if (!CookiePolicy.isTrustedPage(url)) {
            view.stopLoading()
            error(view, "请在哔哩哔哩官方网页完成登录。")
        } else onPage(view, WebStatus(loading = true))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val allowed = CookiePolicy.isTrustedPage(request.url.toString())
        if (!allowed && request.isForMainFrame) error(view, "请在哔哩哔哩官方网页完成登录。")
        return !allowed
    }

    @Deprecated("Required for older WebView implementations")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !CookiePolicy.isTrustedPage(url)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val allowed = request.url.scheme == "https" &&
            (!request.isForMainFrame || CookiePolicy.isTrustedPage(request.url.toString()))
        return if (allowed) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(),
            ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!failed && CookiePolicy.isTrustedPage(url)) onPage(view, WebStatus())
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, issue: WebResourceError) {
        if (request.isForMainFrame) error(view, "登录网页未能加载，请检查网络后重新加载。")
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, issue: SslError) {
        handler.cancel()
        error(view, "无法验证登录网页的安全连接，请检查网络和系统时间。")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) error(view, "登录网页暂时无法访问（HTTP ${response.statusCode}），请稍后重新加载。")
    }
}
