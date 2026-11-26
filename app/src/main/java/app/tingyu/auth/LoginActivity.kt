package app.tingyu.auth

import android.os.Bundle
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import app.tingyu.data.auth.CookiePolicy
import app.tingyu.data.auth.LoginVerificationGate
import app.tingyu.data.auth.QrLoginChallenge
import app.tingyu.data.auth.QrLoginClient
import app.tingyu.data.auth.QrLoginException
import app.tingyu.data.auth.QrLoginStatus
import app.tingyu.data.auth.SessionStore
import app.tingyu.model.Account
import app.tingyu.ui.TingYuTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rotation retains the current challenge in memory. Credentials never enter a saved-state bundle. */
internal class LoginState : ViewModel() {
    var method by mutableStateOf(LoginMethod.Qr)
    var qr by mutableStateOf<QrPanel>(QrPanel.Loading)
    var account by mutableStateOf<Account?>(null)
    var challenge: QrLoginChallenge? = null
    var expiresAt = 0L
    var confirmedCookie = ""
    var webSessionInitialized = false

    fun resetQr() {
        challenge = null
        expiresAt = 0
        confirmedCookie = ""
        qr = QrPanel.Loading
    }
}

class LoginActivity : ComponentActivity() {
    private val state: LoginState by viewModels()
    private val client = QrLoginClient()
    private val store by lazy { SessionStore(applicationContext) }
    private var gate = LoginVerificationGate()
    private var polling: Job? = null
    private var verifying: Job? = null
    private var web: WebView? = null
    private var webStatus by mutableStateOf(WebStatus())
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val stored = store.state.value.account
        if (stored != null && savedInstanceState?.getLong("verified_mid") == stored.mid) state.account = stored
        if (state.account != null) setResult(RESULT_OK)
        setContent {
            TingYuTheme {
                LoginScreen(state.method, state.qr, state.account, webStatus,
                    onBack = ::finish, onMethod = ::selectMethod, onRefresh = ::refreshQr,
                    onRetryVerify = { verify(state.confirmedCookie, manual = true) },
                    onWebReady = ::attachWeb, onWebReleased = ::releaseWeb,
                    onWebPage = ::onWebPage, onWebReload = ::reloadWeb,
                    onWebComplete = { verify(webCandidate(), manual = true) })
            }
        }
    }

    private fun selectMethod(next: LoginMethod) {
        if (state.account != null || next == state.method) return
        cancelWork()
        gate = LoginVerificationGate()
        state.resetQr()
        state.method = next
        if (next == LoginMethod.Qr) {
            state.webSessionInitialized = false
            SessionStore.clearWebSession()
        } else webStatus = WebStatus()
        runVisibleFlow()
    }

    private fun refreshQr() {
        if (!visible(LoginMethod.Qr)) return
        cancelWork()
        gate = LoginVerificationGate()
        state.resetQr()
        runVisibleFlow()
    }

    private fun runVisibleFlow() {
        if (!resumed || state.account != null || isFinishing) return
        polling?.cancel()
        polling = lifecycleScope.launch {
            if (state.method == LoginMethod.Web) {
                while (isActive && visible(LoginMethod.Web)) {
                    if (state.webSessionInitialized && web != null) verify(webCandidate())
                    delay(1_000)
                }
            } else if (state.confirmedCookie.isNotEmpty()) {
                if (state.qr !is QrPanel.Failed) verify(state.confirmedCookie)
            } else if (state.qr !is QrPanel.Failed && state.qr != QrPanel.Expired) {
                pollQr()
            }
        }
    }

    private suspend fun pollQr() {
        try {
            val challenge = state.challenge ?: client.generate().also { generated ->
                val bitmap = withContext(Dispatchers.Default) { makeLoginQr(generated.url) }
                state.challenge = generated
                state.expiresAt = SystemClock.elapsedRealtime() + QR_VALIDITY_MS
                state.qr = QrPanel.Ready(bitmap)
            }
            while (visible(LoginMethod.Qr) && state.challenge === challenge) {
                if (SystemClock.elapsedRealtime() >= state.expiresAt) break
                when (val result = client.poll(challenge)) {
                    QrLoginStatus.Expired -> break
                    QrLoginStatus.Scanned -> (state.qr as? QrPanel.Ready)?.let { state.qr = it.copy(scanned = true) }
                    QrLoginStatus.Waiting -> Unit
                    is QrLoginStatus.Confirmed -> {
                        state.confirmedCookie = result.cookie
                        state.challenge = null
                        verify(result.cookie)
                        return
                    }
                }
                delay(3_000)
            }
            state.challenge = null
            state.qr = QrPanel.Expired
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.challenge = null
            state.qr = QrPanel.Failed(safeMessage(error, "二维码连接失败，请检查网络后重试。"))
        }
    }

    private fun visible(method: LoginMethod) = resumed && !isFinishing && !isDestroyed &&
        state.method == method && state.account == null

    private fun webCandidate(): String {
        val cookies = CookieManager.getInstance()
        return CookiePolicy.normalize(listOf("https://www.bilibili.com", "https://api.bilibili.com")
            .joinToString("; ") { cookies.getCookie(it).orEmpty() })
    }

    private fun verify(candidate: String, manual: Boolean = false) {
        val method = state.method
        if (!visible(method) || verifying?.isActive == true) return
        if (method == LoginMethod.Web && (!state.webSessionInitialized || web == null)) return
        if (!CookiePolicy.hasSession(candidate)) {
            if (manual) webStatus = webStatus.copy(message = "请先在官方网页完成登录。", error = true)
            return
        }
        val attempt = gate.begin(candidate, manual) ?: return
        if (method == LoginMethod.Qr) state.qr = QrPanel.Verifying
        else webStatus = WebStatus("正在验证登录状态…", verifying = true)
        verifying = lifecycleScope.launch {
            try {
                val account = client.verifyAccount(candidate)
                if (!visible(method)) return@launch
                val current = if (method == LoginMethod.Web) webCandidate() else state.confirmedCookie
                if (gate.commit(attempt, current) { store.saveSession(candidate, account) }) {
                    state.resetQr()
                    state.account = account
                    polling?.cancel()
                    web?.stopLoading()
                    SessionStore.clearWebSession()
                    setResult(RESULT_OK)
                } else if (method == LoginMethod.Web) {
                    webStatus = WebStatus("登录状态已变化，正在重新检查。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = safeMessage(error, "未能完成登录验证，请重试。")
                if (method == LoginMethod.Qr) state.qr = QrPanel.Failed(message, retryVerification = true)
                else webStatus = WebStatus(message, error = true)
            } finally {
                gate.finish(attempt)
            }
        }
    }

    private fun safeMessage(error: Exception, fallback: String): String =
        if (error is QrLoginException) error.message ?: fallback else fallback

    private fun attachWeb(view: WebView) {
        web = view
        val load = {
            if (web === view && state.method == LoginMethod.Web && state.account == null && !isFinishing && !isDestroyed) {
                state.webSessionInitialized = true
                view.loadUrl(WEB_LOGIN_URL)
            }
        }
        if (state.webSessionInitialized) load() else SessionStore.clearWebSession(load)
    }

    private fun releaseWeb(view: WebView) {
        if (web === view) web = null
        view.stopLoading()
        view.clearHistory()
        view.clearCache(true)
        view.destroy()
    }

    private fun reloadWeb() {
        if (webStatus.verifying || !state.webSessionInitialized) return
        webStatus = WebStatus(loading = true)
        web?.loadUrl(WEB_LOGIN_URL)
    }

    private fun onWebPage(view: WebView, status: WebStatus) {
        if (web === view && state.method == LoginMethod.Web && state.account == null && !webStatus.verifying) {
            webStatus = status
            if (!status.error && !status.loading && state.webSessionInitialized) verify(webCandidate())
        }
    }

    private fun cancelWork() {
        polling?.cancel()
        polling = null
        verifying?.cancel()
        verifying = null
        gate.cancelActive()
        if (webStatus.verifying) webStatus = WebStatus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        web?.onResume()
        runVisibleFlow()
    }

    override fun onPause() {
        resumed = false
        cancelWork()
        web?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        state.account?.let { outState.putLong("verified_mid", it.mid) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        gate.close()
        if (!isChangingConfigurations && state.webSessionInitialized) SessionStore.clearWebSession()
        super.onDestroy()
    }

    private companion object {
        const val QR_VALIDITY_MS = 180_000L
        const val WEB_LOGIN_URL = "https://passport.bilibili.com/login?gourl=https%3A%2F%2Fwww.bilibili.com%2F"
    }
}
