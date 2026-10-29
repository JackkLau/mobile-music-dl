package app.tingyu.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import app.tingyu.data.auth.CookiePolicy
import app.tingyu.data.auth.LoginResponse
import app.tingyu.data.auth.LoginVerificationGate
import app.tingyu.data.auth.SessionStore
import app.tingyu.model.Account
import app.tingyu.ui.TingYuColors
import app.tingyu.R
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The app owns this isolated WebView; it never reads an external browser's cookies. */
class LoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var complete: Button
    private lateinit var reload: Button
    private lateinit var progress: ProgressBar
    private lateinit var successPanel: LinearLayout
    private val verification = LoginVerificationGate()
    private var pollingJob: Job? = null
    private var verificationJob: Job? = null
    private var activeAttempt: LoginVerificationGate.Attempt? = null
    private var resumed = false
    private var ready = false
    private var succeededMid: Long? = null
    private val checking: Boolean get() = activeAttempt != null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TingYuColors.Surface.toArgb())
            fitsSystemWindows = true
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        toolbar.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        toolbar.addView(TextView(this).apply { text = "登录 B 站"; textSize = 18f; setTextColor(TingYuColors.Ink.toArgb()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        complete = Button(this).apply { text = "完成登录"; setOnClickListener { verifyLogin(manual = true) } }
        toolbar.addView(complete)
        root.addView(toolbar)
        status = TextView(this).apply {
            text = "请在 B 站官方页面登录，成功后会自动提示；也可点击「完成登录」手动检查。"
            textSize = 13f
            setTextColor(TingYuColors.Muted.toArgb())
            minLines = 2
            setPadding(dp(16), dp(8), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true // Required by the first-party login and CAPTCHA UI.
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.safeBrowsingEnabled = true
            // No JavascriptInterface, password bridge, client certificate, or native file picker.
            setDownloadListener { _, _, _, _, _ -> showStatus("登录页不支持下载文件，请返回后重试。", true) }
        }
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (succeededMid != null) { view.stopLoading(); return }
                if (!CookiePolicy.isTrustedPage(url)) {
                    view.stopLoading()
                    showStatus("仅支持在 B 站官方页面完成登录。", true)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = CookiePolicy.isTrustedPage(request.url.toString())
                if (!allowed && request.isForMainFrame) showStatus("请在 B 站网页登录页面完成登录；暂不支持跳转到其他应用。", true)
                return !allowed
            }

            @Deprecated("Required for older WebView implementations")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !CookiePolicy.isTrustedPage(url)

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Permit HTTPS CAPTCHA/CDN subresources, but never local files, cleartext, or native schemes.
                val allowed = request.url.scheme == "https" &&
                    (!request.isForMainFrame || CookiePolicy.isTrustedPage(request.url.toString()))
                return if (allowed) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!checking) progress.visibility = View.GONE
                if (CookiePolicy.isTrustedPage(url)) verifyLogin()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    showStatus("登录页面未能加载，请检查网络后点击「重新加载」。", true)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showStatus("无法验证登录页面的安全连接，请检查网络和系统时间。", true)
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        successPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
            addView(TextView(this@LoginActivity).apply {
                text = "登录成功"
                textSize = 26f
                setTextColor(TingYuColors.Ink.toArgb())
                gravity = Gravity.CENTER
            })
            addView(Button(this@LoginActivity).apply {
                text = getString(R.string.return_to_app)
                minHeight = dp(48)
                setOnClickListener { finish() }
            })
        }
        root.addView(successPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        reload = Button(this).apply {
            text = "重新加载"
            setOnClickListener {
                if (!checking && succeededMid == null) {
                    progress.visibility = View.VISIBLE
                    webView.loadUrl(LOGIN_URL)
                }
            }
        }
        root.addView(reload)
        setContentView(root)
        // A completed login survives rotation without silently starting another login.
        val stored = SessionStore(applicationContext).state.value.account
        if (stored != null && savedInstanceState?.getLong("verified_mid", 0L) == stored.mid) {
            verification.close()
            showSuccess(stored)
            return
        }
        SessionStore.clearWebSession {
            if (!isFinishing && !isDestroyed) {
                ready = true
                webView.loadUrl(LOGIN_URL)
            }
        }
    }

    private fun currentCandidate(): String {
        val cookies = CookieManager.getInstance()
        return CookiePolicy.normalize(listOf(
            cookies.getCookie("https://www.bilibili.com").orEmpty(),
            cookies.getCookie("https://api.bilibili.com").orEmpty(),
        ).joinToString("; "))
    }

    private fun verifyLogin(manual: Boolean = false) {
        if (!resumed || !ready || isFinishing || isDestroyed || succeededMid != null || checking) return
        val candidate = currentCandidate()
        if (!CookiePolicy.hasSession(candidate)) {
            if (manual) showStatus("还没有检测到登录状态，请先在下方页面完成登录。", true)
            return
        }
        val attempt = verification.begin(candidate, manual) ?: return
        activeAttempt = attempt
        complete.isEnabled = false
        reload.isEnabled = false
        progress.visibility = View.VISIBLE
        showStatus("正在验证登录状态…")
        verificationJob = scope.launch {
            try {
                val account = fetchAccount(candidate)
                if (!resumed || isFinishing || isDestroyed) return@launch
                // Final guard and this small encrypted commit are serialized on the
                // Activity thread: a back/pause callback cannot occur between them.
                val committed = verification.commit(attempt, currentCandidate()) {
                    SessionStore(applicationContext).saveSession(candidate, account)
                }
                if (committed) showSuccess(account)
                else {
                    verification.cancelActive()
                    showStatus("登录状态已变化，正在重新检查。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never show exception text: it may contain request headers or a URL with session data.
                if (resumed && !isFinishing && !isDestroyed) {
                    showStatus("登录验证失败。请确认网页已完成登录，检查网络后点击「完成登录」重试。", true)
                }
            } finally {
                verification.finish(attempt)
                if (activeAttempt == attempt) {
                    activeAttempt = null
                    verificationJob = null
                    complete.isEnabled = true
                    reload.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun fetchAccount(candidate: String): Account = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("https://api.bilibili.com/x/web-interface/nav")
            .header("Cookie", candidate)
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", USER_AGENT).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("登录验证连接失败"))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val verified = response.use {
                        check(it.isSuccessful)
                        val body = it.body ?: error("Empty response")
                        check(body.contentLength() <= MAX_LOGIN_BYTES)
                        val source = body.source()
                        check(!source.request(MAX_LOGIN_BYTES + 1))
                        LoginResponse.parse(source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resume(verified)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("登录验证失败"))
                }
            }
        })
    }

    private fun showSuccess(account: Account) {
        succeededMid = account.mid
        pollingJob?.cancel()
        pollingJob = null
        webView.stopLoading()
        webView.visibility = View.GONE
        successPanel.visibility = View.VISIBLE
        reload.visibility = View.GONE
        progress.visibility = View.GONE
        complete.text = getString(R.string.return_to_app)
        complete.isEnabled = true
        complete.setOnClickListener { finish() }
        showStatus(getString(R.string.login_success_return, account.name))
        setResult(RESULT_OK)
        SessionStore.clearWebSession()
    }

    private fun showStatus(message: String, isError: Boolean = false) {
        if (succeededMid != null && isError) return // Ignore late WebView load errors after verified success.
        status.text = message
        status.setTextColor((if (isError) TingYuColors.Error else TingYuColors.Muted).toArgb())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        webView.onResume()
        pollingJob?.cancel()
        pollingJob = scope.launch {
            // Poll local cookies only. Network checks are deduplicated by session,
            // and happen only while the login page is visible.
            while (isActive && resumed && succeededMid == null) {
                verifyLogin()
                delay(1_000)
            }
        }
    }

    override fun onPause() {
        resumed = false
        pollingJob?.cancel()
        pollingJob = null
        verification.cancelActive()
        verificationJob?.cancel()
        verificationJob = null
        activeAttempt = null
        complete.isEnabled = true
        reload.isEnabled = true
        progress.visibility = View.GONE
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        succeededMid?.let { outState.putLong("verified_mid", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        verification.close()
        scope.cancel()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(true)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        SessionStore.clearWebSession()
        super.onDestroy()
    }

    companion object {
        private const val MAX_LOGIN_BYTES = 256_000L
        private const val LOGIN_URL = "https://passport.bilibili.com/login?gourl=https%3A%2F%2Fwww.bilibili.com%2F"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
